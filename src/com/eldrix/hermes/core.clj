; Copyright 2020 Mark Wardle and Eldrix Ltd
;
;   Licensed under the Apache License, Version 2.0 (the "License");
;   you may not use this file except in compliance with the License.
;   You may obtain a copy of the License at
;
;       http://www.apache.org/licenses/LICENSE-2.0
;
;   Unless required by applicable law or agreed to in writing, software
;   distributed under the License is distributed on an "AS IS" BASIS,
;   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;   See the License for the specific language governing permissions and
;   limitations under the License.
;;;;
(ns com.eldrix.hermes.core
  "Provides a terminology service, wrapping the SNOMED store and
  search implementations as a single unified service."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.expression.ecl :as ecl]
            [com.eldrix.hermes.expression.scg :as scg]
            [com.eldrix.hermes.impl.language :as lang]
            [com.eldrix.hermes.impl.search :as search]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.importer :as importer]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (com.eldrix.hermes.impl.store MapDBStore)
           (org.apache.lucene.search IndexSearcher)
           (org.apache.lucene.index IndexReader)
           (java.nio.file Paths Files LinkOption)
           (java.nio.file.attribute FileAttribute)
           (java.util Locale UUID)
           (java.time.format DateTimeFormatter)
           (java.time LocalDateTime)
           (java.io Closeable)
           (java.util.concurrent Executors Future)))

(set! *warn-on-reflection* true)

(deftype Service [^MapDBStore store
                  ^IndexReader index-reader
                  ^IndexSearcher searcher
                  locale-match-fn]
  Closeable
  (close [_] (.close store) (.close index-reader)))

(defn get-concept
  "Return the concept with the specified identifier."
  [^Service svc concept-id]
  (store/get-concept (.-store svc) concept-id))

(defn get-extended-concept [^Service svc concept-id]
  (when-let [concept (store/get-concept (.-store svc) concept-id)]
    (store/make-extended-concept (.-store svc) concept)))

(defn get-descriptions [^Service svc concept-id]
  (store/get-concept-descriptions (.-store svc) concept-id))

(defn get-synonyms
  "Returns a collection of synonyms for the given concept."
  [^Service svc concept-id]
  (->> (get-descriptions svc concept-id)
       (filter #(= snomed/Synonym (:typeId %)))))

(defn get-all-parents
  "Returns all parents of the specified concept. By design, this includes the
  concept itself."
  ([^Service svc concept-id]
   (get-all-parents svc concept-id snomed/IsA))
  ([^Service svc concept-id type-id]
   (store/get-all-parents (.-store svc) concept-id type-id)))

(defn get-all-children
  "Return all children of the specified concept. By design, this includes the
  concept itself."
  ([^Service svc concept-id]
   (store/get-all-children (.-store svc) concept-id))
  ([^Service svc concept-id type-id]
   (store/get-all-children (.-store svc) concept-id type-id)))

(defn get-parent-relationships-of-type
  "Returns a collection of identifiers representing the parent relationships of
  the specified type of the specified concept."
  [^Service svc concept-id type-concept-id]
  (store/get-parent-relationships-of-type (.-store svc) concept-id type-concept-id))

(defn get-child-relationships-of-type
  "Returns a collection of identifiers representing the child relationships of
  the specified type of the specified concept."
  [^Service svc concept-id type-concept-id]
  (store/get-child-relationships-of-type (.-store svc) concept-id type-concept-id))

(defn get-component-refset-items
  ([^Service svc component-id]
   (store/get-component-refset-items (.-store svc) component-id))
  ([^Service svc component-id refset-id]
   (store/get-component-refset-items (.-store svc) component-id refset-id)))

(defn ^:deprecated get-reference-sets
  "DEPRECATED: use [[get-component-refset-items]] instead."
  [^Service svc component-id]
  (get-component-refset-items svc component-id))

(defn get-refset-item [^Service svc ^UUID uuid]
  (store/get-refset-item (.-store svc) uuid))

(defn active-association-targets
  "Return the active association targets for a given component."
  [^Service svc component-id refset-id]
  (->> (get-component-refset-items svc component-id refset-id)
       (filter :active)
       (map :targetComponentId)))

(defn historical-associations
  "Returns all historical-type associations for the specified component.
  Result is a map, keyed by the type of association (e.g. SAME-AS) and
  a sequence of reference set items for that association. Some concepts
  may be ambiguous and therefore map to multiple targets. Annoyingly, but
  understandably, the 'moved-to' reference set does not actually reference
  the new component - but the namespace to which it has moved - so for example
  an ancient International release may have accidentally including UK specific
  concepts - so they will have been removed. It is hoped that most MOVED-TO
  concepts will also have a SAME-AS or POSSIBLY-EQUIVALENT-TO historical
  association.
  See https://confluence.ihtsdotools.org/display/DOCRELFMT/5.2.5.1+Historical+Association+Reference+Sets
  and https://confluence.ihtsdotools.org/display/editorialag/Component+Moved+Elsewhere"
  [^Service svc component-id]
  (select-keys (group-by :refsetId (get-component-refset-items svc component-id))
               (store/get-all-children (.-store svc) snomed/HistoricalAssociationReferenceSet)))

(defn source-historical-associations
  "Returns all historical-type associations in which the specified component is
  the target. For example, searching for 24700007 will result in a map keyed
  by refset-id (e.g. SAME-AS reference set) and a set of concept identifiers."
  [^Service svc component-id]
  (let [refset-ids (store/get-all-children (.-store svc) snomed/HistoricalAssociationReferenceSet)]
    (apply merge (map #(when-let [result (seq (store/get-source-association-referenced-components (.-store svc) component-id %))]
                         (hash-map % (set result))) refset-ids))))

(defn source-historical
  "Return the requested historical associations for the component of types as
  defined by assoc-refset-ids, or all association refsets if omitted."
  ([^Service svc component-id]
   (source-historical svc component-id (store/get-all-children (.-store svc) snomed/HistoricalAssociationReferenceSet)))
  ([^Service svc component-id refset-ids]
   (mapcat #(store/get-source-association-referenced-components (.-store svc) component-id %) refset-ids)))

(defn with-historical
  "For a given sequence of concept identifiers, expand to include historical
  associations both backwards and forwards in time.

  For a currently active concept, this will return historic inactivated concepts
  in which it is the target. For a now inactive concept, this will return the
  active associations and their historic associations.

  By default, all types of historical associations except MoveTo and MovedFrom
  are included, but this is configurable. "
  ([^Service svc concept-ids]
   (with-historical svc concept-ids
                    (disj (store/get-all-children (.-store svc) snomed/HistoricalAssociationReferenceSet) snomed/MovedToReferenceSet snomed/MovedFromReferenceSet)))
  ([^Service svc concept-ids assoc-refset-ids]
   (let [historical-refsets assoc-refset-ids
         future (map :targetComponentId (filter #(historical-refsets (:refsetId %)) (mapcat #(get-component-refset-items svc %) concept-ids)))
         modern (set/union (set concept-ids) (set future))
         historic (set (mapcat #(source-historical svc % assoc-refset-ids) modern))]
     (set/union modern historic))))

(defn get-installed-reference-sets [^Service svc]
  (store/get-installed-reference-sets (.-store svc)))

(defn reverse-map
  "Returns the reverse mapping from the reference set and mapTarget specified."
  [^Service svc refset-id code]
  (store/get-reverse-map (.-store svc) refset-id code))

(defn reverse-map-range
  "Returns the reverse mapping from the reference set specified, performing
  what is essentially a prefix search using the parameters."
  ([^Service svc refset-id prefix]
   (store/get-reverse-map-range (.-store svc) refset-id prefix))
  ([^Service svc refset-id lower-bound upper-bound]
   (store/get-reverse-map-range (.-store svc) refset-id lower-bound upper-bound)))

(defn get-preferred-synonym [^Service svc concept-id langs]
  (let [locale-match-fn (.-locale_match_fn svc)]
    (store/get-preferred-synonym (.-store svc) concept-id (locale-match-fn langs))))

(defn get-fully-specified-name [^Service svc concept-id]
  (store/get-fully-specified-name (.-store svc) concept-id))

(defn get-release-information [^Service svc]
  (store/get-release-information (.-store svc)))

(defn subsumed-by? [^Service svc concept-id subsumer-concept-id]
  (store/is-a? (.-store svc) concept-id subsumer-concept-id))

(defn are-any?
  "Are any of the concept-ids subsumed by any of the parent-ids?

  Checks the is-a relationships of the concepts in question against the set of
  parent identifiers."
  [^Service svc concept-ids parent-ids]
  (let [parents (set parent-ids)]
    (->> (set concept-ids)
         (mapcat #(set/intersection (set (conj (get-in (get-extended-concept svc %) [:parentRelationships 116680003]) %)) parents))
         (some identity))))

(defn parse-expression [^Service svc s]
  (scg/parse s))

(defn search [^Service svc params]
  (if-let [constraint (:constraint params)]
    (search/do-search (.-searcher svc) (assoc params :query (ecl/parse (.-store svc) (.-searcher svc) constraint)))
    (search/do-search (.-searcher svc) params)))

(defn all-transitive-synonyms [^Service svc params]
  (mapcat (partial store/all-transitive-synonyms (.-store svc)) (map :conceptId (search/do-search (.-searcher svc) params))))

(defn expand-ecl
  "Expand an ECL expression."
  [^Service svc ecl]
  (let [q1 (ecl/parse (.-store svc) (.-searcher svc) ecl)
        q2 (search/q-not q1 (search/q-fsn))]
    (search/do-query-for-results (.-searcher svc) q2)))

(defn expand-ecl-historic
  "Expand an ECL expression and include historic associations of the results,
  so that the results will include now inactive/deprecated concept identifiers."
  [^Service svc ^String ecl]
  (let [q1 (ecl/parse (.-store svc) (.-searcher svc) ecl)
        q2 (search/q-not q1 (search/q-fsn))
        base-concept-ids (search/do-query-for-concepts (.-searcher svc) q2)
        historic-concept-ids (apply set/union (->> base-concept-ids
                                                   (map #(source-historical-associations svc %))
                                                   (filter some?)
                                                   (map vals)
                                                   flatten))
        historic-query (search/q-concept-ids historic-concept-ids)
        query (search/q-not (search/q-or [q1 historic-query]) (search/q-fsn))]
    (search/do-query-for-results (.-searcher svc) query)))

(defn ecl-contains?
  "Do any of the concept-ids satisfy the constraint expression specified?
  This is an alternative to expanding the valueset and then checking membership."
  [^Service svc concept-ids ^String ecl]
  (let [q1 (ecl/parse (.-store svc) (.-searcher svc) ecl)
        q2 (search/q-concept-ids concept-ids)]
    (seq (search/do-query-for-concepts (.-searcher svc) (search/q-and [q1 q2])))))


(defn get-refset-members
  "Return a set of identifiers for the members of the given refset(s).
  Parameters:
  - refset-id  - SNOMED identifier representing the reference set."
  [^Service svc refset-id & more]
  (let [refset-ids (if more (into #{refset-id} more) #{refset-id})]
    (into #{} (map :conceptId (search svc {:concept-refsets refset-ids})))))

(s/def ::svc any?)
(s/fdef map-into
  :args (s/cat :svc ::svc :source-concept-ids (s/coll-of :info.snomed.Concept/id)
               :target (s/alt :ecl string?
                              :refset-id :info.snomed.Concept/id
                              :concepts (s/coll-of :info.snomed.Concept/id))))
(defn map-into
  "Map the source-concept-ids into the target, usually in order to reduce the
  dimensionality of the dataset.

  Parameters:
  - svc                : hermes service
  - source-concept-ids : a collection of concept identifiers
  - target             : one of:
                           - a collection of concept identifiers
                           - an ECL expression
                           - a refset identifier

  If a source concept id resolves to multiple concepts in the target collection,
  then a collection will be returned such that no member of the subset is
  subsumed by another member.

  It would be usual to map any source concept identifiers into their modern
  active replacements, if they are now inactive.

  The use of 'map-into' is in reducing the granularity of user-entered
  data to aid analytics. For example, rather than limiting data entry to the UK
  emergency reference set, a set of commonly seen diagnoses in emergency
  departments in the UK, we can allow clinicians to enter highly specific,
  granular terms, and map to the contents of that reference set as required
  for analytics and reporting.

  For example, '991411000000109' is the UK emergency unit diagnosis refset:
  ```
  (map-into svc [24700007 763794005] 991411000000109)
       =>  (#{24700007} #{45170000})
  ```
  As multiple sclerosis (24700007) is in the reference set, it is returned.
  However, LGI1-associated limbic encephalitis (763794005) is not in the
  reference set, the best terms are returned (\"Encephalitis\" - 45170000).

  This can be used to do simple classification tasks - such as determining
  the broad types of illness. For example, here we use ECL to define a set to
  include 'neurological disease', 'respiratory disease' and
  'infectious disease':

  ```
  (map-into svc [24700007 763794005 95883001] \"118940003 OR 50043002 OR 40733004\")
      => (#{118940003} #{118940003} #{40733004 118940003})
  ```
  Both multiple sclerosis and LGI-1 encephalitis are types of neurological
  disease (118940003). However, 'Bacterial meningitis' (95883001) is mapped to
  both 'neurological disease' (118940003) AND 'infectious disease' (40733004)."
  [^Service svc source-concept-ids target]
  (let [target-concept-ids
        (cond (string? target)
              (into #{} (map :conceptId (expand-ecl svc target)))
              (coll? target)
              (set target)
              (number? target) (get-refset-members svc target))]
    (->> source-concept-ids
         (map #(set/intersection (conj (get-all-parents svc %) %) target-concept-ids))
         (map #(store/get-leaves (.-store svc) %)))))


(defn ^:deprecated map-features
  "DEPRECATED: Use [[map-into]] instead."
  [^Service svc source-concept-ids target]
  (map-into svc source-concept-ids target))

;;
(defn- historical-association-counts
  "Returns counts of all historical association counts.

  Example result:
  ```
    {900000000000526001 #{1},              ;; replaced by - always one
     900000000000527005 #{1 4 6 3 2},       ;; same as - multiple!
     900000000000524003 #{1},               ;; moved to - always 1
     900000000000523009 #{7 1 4 6 3 2 11 9 5 10 8},
     900000000000528000 #{7 1 4 3 2 5},
     900000000000530003 #{1 2},
     900000000000525002 #{1 3 2}}
  ```"
  [^Service svc]
  (let [ch (async/chan 100 (remove :active))]
    (store/stream-all-concepts (.-store svc) ch)
    (loop [result {}]
      (let [c (async/<!! ch)]
        (if-not c
          result
          (recur (reduce-kv (fn [m k v]
                              (update m k (fnil conj #{}) (count v)))
                            result
                            (historical-associations svc (:id c)))))))))

(defn- get-example-historical-associations
  [^Service svc type n]
  (let [ch (async/chan 100 (remove :active))]
    (store/stream-all-concepts (.-store svc) ch)
    (loop [i 0
           result []]
      (let [c (async/<!! ch)]
        (if-not (and c (< i n))
          result
          (let [assocs (historical-associations svc (:id c))
                append? (contains? assocs type)]
            (recur (if append? (inc i) i)
                   (if append? (conj result {(:id c) assocs}) result))))))))


(defn paths-to-root [^Service svc concept-id]
  (store/paths-to-root (.-store svc) concept-id))

(defn some-indexed
  "Returns index and first logical true value of (pred x) in coll, or nil.
  e.g.
  ```
  (some-indexed #{64572001} '(385093006 233604007 205237003 363169009 363170005 123946008 64572001 404684003 138875005))
  ```
  returns: `[6 664572001]`"
  [pred coll]
  (first (keep-indexed (fn [idx v] (when (pred v) [idx v])) coll)))

;;;;
;;;;
;;;;

(def ^:private expected-manifest
  "Defines the current expected manifest."
  {:version 0.6
   :store   "store.db"
   :search  "search.db"})

(defn- open-manifest
  "Open or, if it doesn't exist, optionally create a manifest at the location specified."
  ([root] (open-manifest root false))
  ([root create?]
   (let [root-path (Paths/get root (into-array String []))
         manifest-path (.resolve root-path "manifest.edn")
         exists? (Files/exists manifest-path (into-array LinkOption []))]
     (cond
       exists?
       (if-let [manifest (edn/read-string (slurp (.toFile manifest-path)))]
         (if (= (:version manifest) (:version expected-manifest))
           manifest
           (throw (Exception. (str "error: incompatible database version. expected:'" (:version expected-manifest) "' got:'" (:version manifest) "'"))))
         (throw (Exception. (str "error: unable to read manifest from " root))))
       create?
       (let [manifest (assoc expected-manifest
                        :created (.format (DateTimeFormatter/ISO_DATE_TIME) (LocalDateTime/now)))]
         (Files/createDirectory root-path (into-array FileAttribute []))
         (spit (.toFile manifest-path) (pr-str manifest))
         manifest)
       :else
       (throw (ex-info "no database found at path and operating read-only" {:path root}))))))

(defn- get-absolute-filename
  [^String root ^String filename]
  (let [root-path (Paths/get root (into-array String []))]
    (.toString (.normalize (.toAbsolutePath (.resolve root-path filename))))))

(defn ^Service open
  "Open a (read-only) SNOMED service from the path `root`."
  [^String root]
  (let [manifest (open-manifest root)
        st (store/open-store (get-absolute-filename root (:store manifest)))
        index-reader (search/open-index-reader (get-absolute-filename root (:search manifest)))
        searcher (IndexSearcher. index-reader)
        locale-match-fn (lang/match-fn st)]
    (log/info "hermes terminology service opened " root (assoc manifest :releases (map :term (store/get-release-information st))))
    (->Service st index-reader searcher locale-match-fn)))

(defn close [^Service svc]
  (.close svc))

(defn- do-import-snomed
  "Import a SNOMED distribution from the specified directory `dir` into a local
   file-based database `store-filename`.
   Blocking; will return when done. "
  [store-filename dir]
  (let [nthreads (.availableProcessors (Runtime/getRuntime))
        store (store/open-store store-filename {:read-only? false})
        cancel-c (async/chan)
        data-c (importer/load-snomed dir)
        pool (Executors/newFixedThreadPool nthreads)
        tasks (repeat nthreads
                      (fn [] (try (loop [batch (async/alts!! [cancel-c data-c])]
                                    (when batch
                                      (store/write-batch-with-fallback batch store)
                                      (recur (async/<!! data-c))))
                                  (catch Throwable e
                                    (async/close! cancel-c) e))))]
    (doseq [future (.invokeAll pool tasks)]
      (when-let [e (.get ^Future future)]
        (when-not (instance? InterruptedException e)        ;; only show the original cause
          (throw (ex-info (str "Error during import: " (.getMessage ^Throwable e)) (Throwable->map e))))))
    (.shutdown pool)
    (store/close store)))

(defn log-metadata [dir]
  (let [metadata (importer/all-metadata dir)]
    (when (seq metadata)
      (log/info "importing " (count metadata) " distributions from " dir))
    (doseq [dist metadata]
      (log/info "distribution: " (:name dist))
      (log/info "license: " (if (:licenceStatement dist) (:licenceStatement dist) (str "error : " (:error dist)))))))

(defn import-snomed
  "Import SNOMED distribution files from the directories `dirs` specified into
  the database directory `root` specified."
  [root dirs]
  (let [manifest (open-manifest root true)
        store-filename (get-absolute-filename root (:store manifest))]
    (doseq [dir dirs]
      (log-metadata dir)
      (do-import-snomed store-filename dir))))

(defn compact
  [root]
  (let [manifest (open-manifest root false)]
    (log/info "Compacting database at " root "...")
    (let [root-path (Paths/get root (into-array String []))
          file-size (Files/size (.resolve root-path ^String (:store manifest)))
          heap-size (.maxMemory (Runtime/getRuntime))]
      (when (> file-size heap-size)
        (log/warn "warning: compaction will likely need additional heap; consider using flag -Xmx - e.g. -Xmx8g"
                  {:file-size (str (int (/ file-size (* 1024 1024))) "Mb")
                   :heap-size (str (int (/ heap-size (* 1024 1024))) "Mb")}))
      (with-open [st (store/open-store (get-absolute-filename root (:store manifest)) {:read-only? false})]
        (store/compact st))
      (log/info "Compacting database... complete."))))

(defn build-search-index
  ([root] (build-search-index root (.toLanguageTag (Locale/getDefault))))
  ([root language-priority-list]
   (let [manifest (open-manifest root false)]
     (log/info "Building search index" {:root root :languages language-priority-list})
     (search/build-search-index (get-absolute-filename root (:store manifest))
                                (get-absolute-filename root (:search manifest))
                                language-priority-list)
     (log/info "Building search index... complete."))))

(defn get-status [root & {:keys [counts? installed-refsets?] :or {counts? false installed-refsets? true}}]
  (let [manifest (open-manifest root)]
    (with-open [st (store/open-store (get-absolute-filename root (:store manifest)))]
      (log/info "Status information for database at '" root "'...")
      (merge
        {:installed-releases (map :term (store/get-release-information st))}
        (when installed-refsets? {:installed-refsets (->> (store/get-installed-reference-sets st)
                                                          (map #(store/get-fully-specified-name st %))
                                                          (sort-by :term)
                                                          (map #(vector (:id %) (:term %)))
                                                          doall)})
        (when counts? (store/status st))))))

(defn create-service
  "Create a terminology service combining both store and search functionality
  in a single step. It would be unusual to use this; usually each step would be
  performed interactively by an end-user."
  ([root import-from] (create-service root import-from))
  ([root import-from locale-preference-string]              ;; There are four steps:
   (import-snomed root import-from)                         ;; import the files
   (compact root)                                           ;; compact the store
   (build-search-index root locale-preference-string)))     ;; build the search index



(comment
  (require '[portal.api :as p])
  (def p (p/open))
  (add-tap #'p/submit) ; Add portal as a tap> target
  (def svc (open "snomed.db"))
  (get-concept svc 24700007)
  (get-all-children svc 24700007)
  (require '[clojure.spec.alpha :as s])
  (s/valid? :info.snomed/Concept (get-concept svc 24700007))

  (tap> (get-concept svc 24700007))
  (tap> (get-extended-concept svc 24700007))
  (search svc {:s "mult scl"})
  (tap> (search svc {:s "mult scl"}))
  (search svc {:s "mult scl" :constraint "<< 24700007"})

  (search svc {:s "ICD-10 complex map"})
  (->> (reverse-map-range svc 447562003 "I30")
       (map :referencedComponentId)
       (map #(:term (get-preferred-synonym svc % "en"))))

  (search svc {:constraint "<900000000000455006 {{ term = \"emerg\"}}"})
  (search svc {:constraint "<900000000000455006 {{ term = \"household\", type = syn, dialect = (en-GB)  }}"})

  (reverse-map-range svc 447562003 "I")
  (get-component-refset-items svc 24700007 447562003)
  (map :mapTarget (get-component-refset-items svc 24700007 447562003))

  (get-extended-concept svc 24700007)
  (subsumed-by? svc 24700007 6118003)   ;; demyelinating disease of the CNS

  (are-any? svc [24700007] [45454])


  (search svc {:constraint "<  64572001 |Disease|  {{ term = wild:\"cardi*opathy\"}}"})
  (search svc {:constraint "<24700007" :inactive-concepts? false})
  (search svc {:constraint "<24700007" :inactive-concepts? true})
  (def ecl-q (ecl/parse (.-store svc) (.-searcher svc) "<24700007"))
  ecl-q
  (def q1 (search/q-and [ecl-q (#'search/make-search-query {:inactive-concepts? true})]))
  (def q2 (search/q-and [ecl-q (#'search/make-search-query {:inactive-concepts? false})]))
  q1
  q2
  (count (#'search/do-query-for-concepts (.-searcher svc) q1))
  (count (#'search/do-query-for-concepts (.-searcher svc) q2))
  q2

  (search svc {:constraint "<  404684003 |Clinical finding| :\n   [0..0] { [2..*]  363698007 |Finding site|  = <  91723000 |Anatomical structure| }"})

  ;; explore SNOMED - get counts of historical association types / frequencies
  (def counts (historical-association-counts svc))
  (reduce-kv (fn [m k v] (assoc m (:term (get-fully-specified-name svc k)) (apply max v))) {} counts)

  (historical-associations svc 5171008)
  (get-fully-specified-name svc 900000000000526001)
  (get-example-historical-associations svc snomed/PossiblyEquivalentToReferenceSet 2)
  (filter :active (get-component-refset-items svc 203004 snomed/PossiblyEquivalentToReferenceSet))
  (get-preferred-synonym svc 24700007 "en-GB")
  (get-parent-relationships-of-type svc 24700007 snomed/IsA)
  (get-child-relationships-of-type svc 24700007 snomed/IsA)
  (set (map :conceptId (expand-ecl-historic svc "<<24700007")))
  (let [parents (set (map :referencedComponentId (reverse-map-range svc 447562003 "I30")))
        historic (set (mapcat #(source-historical svc %) parents))]
    (are-any? svc [1949008] (set/union parents historic)))
  (map #(vector (:conceptId %) (:term %)) (search svc {:s "complex map"}))
  (set/difference
    (set (map :referencedComponentId (reverse-map-range svc 999002271000000101 "G35")))
    (set (map :referencedComponentId (reverse-map-range svc 447562003 "G35"))))

  (contains? (set (map :referencedComponentId (reverse-map-range svc 447562003 "I30"))) 233886008)
  ;; G35 will contain MS, but not outdated deprecated SNOMED concepts such as 192928003
  (are-any? svc [24700007] (map :referencedComponentId (reverse-map-range svc 447562003 "G35")))
  (are-any? svc [192928003] (map :referencedComponentId (reverse-map-range svc 447562003 "G35")))
  (are-any? svc [192928003] (with-historical svc (map :referencedComponentId (reverse-map-range svc 447562003 "G35"))))
  (get-descriptions svc 24700007))
