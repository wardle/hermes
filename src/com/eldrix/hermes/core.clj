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
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.expression.ecl :as ecl]
            [com.eldrix.hermes.expression.scg :as scg]
            [com.eldrix.hermes.impl.language :as lang]
            [com.eldrix.hermes.impl.members :as members]
            [com.eldrix.hermes.impl.search :as search]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.importer :as importer]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.verhoeff :as verhoeff])
  (:import (com.eldrix.hermes.snomed Result)
           (org.apache.lucene.search IndexSearcher Query)
           (org.apache.lucene.index IndexReader)
           (java.nio.file Paths Files LinkOption)
           (java.nio.file.attribute FileAttribute)
           (java.util Locale UUID)
           (java.time.format DateTimeFormatter)
           (java.time LocalDateTime)
           (java.io Closeable)))

(set! *warn-on-reflection* true)

(s/def ::svc any?)
(s/def ::non-blank-string (s/and string? (complement str/blank?)))
(s/def ::component-id (s/and pos-int? verhoeff/valid?))
(s/def ::search-params (s/keys :req-un [(or ::s ::constraint)]
                               :opt-un [::max-hits ::fuzzy ::fallback-fuzzy ::query
                                        ::show-fsn? ::inactive-concepts? ::inactive-descriptions?
                                        ::properties ::concept-refsets]))
(s/def ::s string?)
(s/def ::constraint string?)
(s/def ::max-hits pos-int?)
(s/def ::fuzzy (s/int-in 0 2))
(s/def ::fallback-fuzzy (s/int-in 0 2))
(s/def ::query #(instance? Query %))
(s/def ::show-fsn? boolean?)
(s/def ::inactive-concepts? boolean?)
(s/def ::inactive-descriptions? boolean?)
(s/def ::properties (s/map-of int? int?))
(s/def ::concept-refsets (s/coll-of :info.snomed.Concept/id))

(deftype Service [^Closeable store
                  ^IndexReader indexReader
                  ^IndexSearcher searcher
                  ^IndexReader memberReader
                  ^IndexSearcher memberSearcher
                  locale-match-fn]
  Closeable
  (close [_]
    (.close store)
    (.close indexReader)
    (.close memberReader)))

(s/fdef get-concept
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))
(defn get-concept
  "Return the concept with the specified identifier."
  [^Service svc concept-id]
  (store/get-concept (.-store svc) concept-id))

(s/fdef get-extended-concept
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))
(defn get-extended-concept
  "Return an extended concept that includes the concept, its descriptions,
  its relationships and its refset memberships. See
  [[com.eldrix.hermes.snomed/ExtendedConcept]]"
  [^Service svc concept-id]
  (when-let [concept (store/get-concept (.-store svc) concept-id)]
    (store/make-extended-concept (.-store svc) concept)))

(s/fdef get-descriptions
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))
(defn get-descriptions
  "Return a sequence of descriptions for the given concept."
  [^Service svc concept-id]
  (store/get-concept-descriptions (.-store svc) concept-id))

(s/fdef get-synonyms
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))
(defn get-synonyms
  "Returns a sequence of synonyms for the given concept."
  [^Service svc concept-id]
  (->> (get-descriptions svc concept-id)
       (filter #(= snomed/Synonym (:typeId %)))))

(s/fdef get-all-parents
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :type-id (s/? :info.snomed.Concept/id)))
(defn get-all-parents
  "Returns all parents of the specified concept. By design, this includes the
  concept itself."
  ([^Service svc concept-id]
   (get-all-parents svc concept-id snomed/IsA))
  ([^Service svc concept-id type-id]
   (store/get-all-parents (.-store svc) concept-id type-id)))

(s/fdef get-all-children
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :type-id (s/? :info.snomed.Concept/id)))
(defn get-all-children
  "Return all children of the specified concept. By design, this includes the
  concept itself."
  ([^Service svc concept-id]
   (store/get-all-children (.-store svc) concept-id))
  ([^Service svc concept-id type-id]
   (store/get-all-children (.-store svc) concept-id type-id)))

(s/fdef get-parent-relationships-of-type
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :type-concent-id :info.snomed.Concept/id))
(defn get-parent-relationships-of-type
  "Returns a collection of identifiers representing the parent relationships of
  the specified type of the specified concept."
  [^Service svc concept-id type-concept-id]
  (store/get-parent-relationships-of-type (.-store svc) concept-id type-concept-id))

(s/fdef get-child-relationships-of-type
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :type-concent-id :info.snomed.Concept/id))
(defn get-child-relationships-of-type
  "Returns a collection of identifiers representing the child relationships of
  the specified type of the specified concept."
  [^Service svc concept-id type-concept-id]
  (store/get-child-relationships-of-type (.-store svc) concept-id type-concept-id))

(s/fdef get-component-refset-items
  :args (s/cat :svc ::svc :component-id ::component-id :refset-id (s/? :info.snomed.Concept/id)))
(defn get-component-refset-items
  "Returns a sequence of refset items for the given component."
  ([^Service svc component-id]
   (store/get-component-refset-items (.-store svc) component-id))
  ([^Service svc component-id refset-id]
   (store/get-component-refset-items (.-store svc) component-id refset-id)))

(defn ^:deprecated get-reference-sets
  "DEPRECATED: use [[get-component-refset-items]] instead."
  [^Service svc component-id]
  (get-component-refset-items svc component-id))

(s/fdef get-component-refset-ids
  :args (s/cat :svc ::svc :component-id ::component-id))
(defn get-component-refset-ids
  "Returns a collection of refset identifiers to which this concept is a member."
  [^Service svc component-id]
  (store/get-component-refset-ids (.-store svc) component-id))

(s/fdef get-refset-item
  :args (s/cat :svc ::svc :uuid uuid?))
(defn get-refset-item
  "Return a specific refset item by UUID."
  [^Service svc ^UUID uuid]
  (store/get-refset-item (.-store svc) uuid))

(s/fdef get-refset-descriptor-attribute-ids
  :args (s/cat :svc ::svc :refset-id :info.snomed.Concept/id))
(defn get-refset-descriptor-attribute-ids
  "Return a vector of attribute description concept ids for the given reference
  set."
  [^Service svc refset-id]
  (store/get-refset-descriptor-attribute-ids (.-store svc) refset-id))

(s/fdef extended-refset-item
  :args (s/cat :svc ::svc :item :info.snomed/Refset))
(defn extended-refset-item
  "Merges a map of extended attributes to the specified reference set item.
  The attributes will be keyed based on information from the reference set
  descriptor information and known field names."
  [^Service svc item]
  (store/extended-refset-item (.-store svc) item))

(defn get-component-refset-items-extended
  "Returns a sequence of refset items for the given component, supplemented
  with a map of extended attributes as defined by the refset descriptor"
  ([^Service svc component-id]
   (->> (store/get-component-refset-items (.-store svc) component-id)
        (map #(extended-refset-item svc %))))
  ([^Service svc component-id refset-id]
   (->> (store/get-component-refset-items (.-store svc) component-id refset-id)
        (map #(extended-refset-item svc %)))))

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
  the target. For example, searching for `24700007` will result in a map keyed
  by refset-id (e.g. `SAME-AS` reference set) and a set of concept identifiers."
  [^Service svc component-id]
  (let [refset-ids (store/get-all-children (.-store svc) snomed/HistoricalAssociationReferenceSet)]
    (apply merge (map #(when-let [result (seq (store/get-source-association-referenced-components (.-store svc) component-id %))]
                         (hash-map % (set result))) refset-ids))))

(defn source-historical
  "Return the requested historical associations for the component of types as
  defined by refset-ids, or all association refsets if omitted."
  ([^Service svc component-id]
   (store/source-historical (.-store svc) component-id))
  ([^Service svc component-id refset-ids]
   (store/source-historical (.-store svc) component-id refset-ids)))

(s/fdef with-historical
  :args (s/cat :svc ::svc
               :concept-ids (s/coll-of :info.snomed.Concept/id)
               :refset-ids (s/? (s/coll-of :info.snomed.Concept/id))))
(defn with-historical
  "For a given sequence of concept identifiers, expand to include historical
  associations both backwards and forwards in time.

  For a currently active concept, this will return historic inactivated concepts
  in which it is the target. For a now inactive concept, this will return the
  active associations and their historic associations.

  By default, all types of historical associations except MoveTo and MovedFrom
  are included, but this is configurable. "
  ([^Service svc concept-ids]
   (store/with-historical (.-store svc) concept-ids))
  ([^Service svc concept-ids refset-ids]
   (store/with-historical (.-store svc) concept-ids refset-ids)))

(s/fdef history-profile
  :args (s/cat :svc ::svc :profile #{:HISTORY-MIN :HISTORY-MOD :HISTORY-MAX})
  :ret (s/coll-of :info.snomed.Concept/id))
(defn history-profile
  "Returns a set of reference sets matching the named profile. Use in
  conjunction with [[with-historical]]:
  ```
  (with-historical svc [24700007] (history-profile :HISTORY-MIN)
  ```
  See https://confluence.ihtsdotools.org/display/DOCECL/6.11+History+Supplements"
  [^Service svc profile]
  (store/history-profile (.-store svc) profile))

(defn get-installed-reference-sets
  "Return a set of identifiers representing installed reference sets."
  [^Service svc]
  (store/get-installed-reference-sets (.-store svc)))

(defn member-field
  "Returns a set of referenced component identifiers that are members of the
  given reference set with a matching 'value' for the 'field' specified.
  For example, to perform a reverse map from ICD-10:
  ```
  (member-field svc 447562003 \"mapTarget\" \"G35\")
  ```"
  [^Service svc refset-id field s]
  (members/search (.-memberSearcher svc)
                  (members/q-and
                    [(members/q-refset-id refset-id) (members/q-term field s)])))

(defn member-field-prefix
  [^Service svc refset-id field prefix]
  (members/search (.-memberSearcher svc)
                  (members/q-and
                    [(members/q-refset-id refset-id) (members/q-prefix field prefix)])))

(defn member-field-wildcard
  "Perform a member field wildcard search.
  Supported wildcards are *, which matches any character sequence (including
  the empty one), and ?, which matches any single character. '\\' is the escape
  character.
  Example:
  ```
      (member-field-wildcard svc 447562003 \"mapTarget\" \"G3?\")
  ```"
  [^Service svc refset-id field s]
  (members/search (.-memberSearcher svc)
                  (members/q-and [(members/q-refset-id refset-id)
                                  (members/q-wildcard field s)])))

(s/fdef reverse-map
  :args (s/cat :svc ::svc :refset-id :info.snomed.Concept/id :code ::non-blank-string))
(defn ^:deprecated reverse-map
  "DEPRECATED: Use [[member-field]] instead.

  Returns a sequence of reference set items representing the reverse mapping
  from the reference set and mapTarget specified. It's almost always better to
  use [[member-field]] or [[member-field-prefix]] directly."
  [^Service svc refset-id code]
  (->> (member-field svc refset-id "mapTarget" code)
       (mapcat #(store/get-component-refset-items (.-store svc) % refset-id))
       (filter #(= (:mapTarget %) code))))

(s/fdef reverse-map-prefix
  :args (s/cat :svc ::svc :refset-id :info.snomed.Concept/id :prefix ::non-blank-string))
(defn ^:deprecated reverse-map-prefix
  "DEPRECATED: Use [[member-field-prefix]] instead.

  Returns a sequence of reference set items representing the reverse mapping
  from the reference set and mapTarget. It is almost always better to use
  [[member-field]] or [[member-field-prefix]] directly."
  [^Service svc refset-id prefix]
  (->> (member-field-prefix svc refset-id "mapTarget" prefix)
       (mapcat #(store/get-component-refset-items (.-store svc) % refset-id))
       (filter #(.startsWith ^String (:mapTarget %) prefix))))

(s/fdef reverse-map-range
  :args (s/cat :svc ::svc
               :refset-id :info.snomed.Concept/id
               :code (s/alt :prefix ::non-blank-string
                            :range (s/cat :lower-bound ::non-blank-string :upper-bound ::non-blank-string))))

(s/fdef get-preferred-synonym
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :language-range (s/? ::non-blank-string)))
(defn get-preferred-synonym
  "Return the preferred synonym for the concept based on the language
  preferences specified.

  Parameters:
  - svc            : hermes service
  - concept-id     : concept identifier
  - language-range : a single string containing a list of comma-separated
                     language ranges or a list of language ranges in the form of
                     the \"Accept-Language \" header defined in RFC3066."
  ([^Service svc concept-id]
   (get-preferred-synonym svc concept-id (.toLanguageTag (Locale/getDefault))))
  ([^Service svc concept-id language-range]
   (let [locale-match-fn (.-locale_match_fn svc)]
     (store/get-preferred-synonym (.-store svc) concept-id (locale-match-fn language-range)))))

(s/fdef get-fully-specified-name
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))
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

(s/fdef search
  :args (s/cat :svc ::svc :params ::search-params)
  :ret (s/coll-of ::result))
(defn search [^Service svc params]
  (if-let [constraint (:constraint params)]
    (search/do-search (.-searcher svc) (assoc params :query (ecl/parse {:store           (.-store svc)
                                                                        :searcher        (.-searcher svc)
                                                                        :member-searcher (.-memberSearcher svc)} constraint)))
    (search/do-search (.-searcher svc) params)))

(defn- make-svc-map
  [^Service svc]
  {:store           (.-store svc)
   :searcher        (.-searcher svc)
   :member-searcher (.-memberSearcher svc)})

(s/fdef expand-ecl
  :args (s/cat :svc ::svc :ecl ::non-blank-string)
  :ret (s/coll-of ::result))
(defn expand-ecl
  "Expand an ECL expression."
  [^Service svc ecl]
  (let [q1 (ecl/parse (make-svc-map svc) ecl)
        q2 (search/q-not q1 (search/q-fsn))]
    (search/do-query-for-results (.-searcher svc) q2)))

(s/fdef expand-ecl-historic
  :args (s/cat :svc ::svc :ecl ::non-blank-string)
  :ret (s/coll-of ::result))
(defn expand-ecl-historic
  "Expand an ECL expression and include historic associations of the results,
  so that the results will include now inactive/deprecated concept identifiers."
  [^Service svc ^String ecl]
  (let [q1 (ecl/parse (make-svc-map svc) ecl)
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


(s/def ::transitive-synonym-params (s/or :by-search map? :by-ecl string? :by-concept-ids coll?))

(s/fdef all-transitive-synonyms
  :args (s/cat :svc ::svc
               :params (s/alt :by-ecl string? :by-search ::search-params ::by-concept-ids (s/coll-of :info.snomed.Concept/id))))
(defn all-transitive-synonyms
  "Returns all of the synonyms of the specified concepts, including those
  of its descendants.

  Parameters:
  - svc    : hermes service
  - params : search parameters to select concepts; one of:

          - a map        : search parameters as per [[search]]
          - a string     : a string containing an ECL expression
          - a collection : a collection of concept identifiers"
  [^Service svc params]
  (let [[op v] (s/conform ::transitive-synonym-params params)]
    (if-not op
      (throw (ex-info "invalid parameters:" (s/explain-data ::transitive-synonym-params params)))
      (let [concept-ids (case op
                          :by-search (map :conceptId (search svc v))
                          :by-ecl (map #(.-conceptId ^Result %) (expand-ecl svc v))
                          :by-concept-ids v)]
        (mapcat (partial store/transitive-synonyms (.-store svc)) concept-ids)))))

(s/fdef ecl-contains?
  :args (s/cat :svc ::svc
               :concept-ids (s/coll-of :info.snomed.Concept/id)
               :ecl ::non-blank-string))
(defn ecl-contains?
  "Do any of the concept-ids satisfy the constraint expression specified?
  This is an alternative to expanding the valueset and then checking membership."
  [^Service svc concept-ids ^String ecl]
  (let [q1 (ecl/parse {:store (.-store svc) :searcher (.-searcher svc) :member-searcher (.-memberSearcher svc)} ecl)
        q2 (search/q-concept-ids concept-ids)]
    (seq (search/do-query-for-concepts (.-searcher svc) (search/q-and [q1 q2])))))

(s/fdef get-refset-members
  :args (s/cat :svc ::svc :refset-ids (s/+ :info.snomed.Concept/id)))
(defn get-refset-members
  "Return a set of identifiers for the members of the given refset(s).

  Parameters:
  - refset-id  - SNOMED identifier representing the reference set."
  [^Service svc refset-id & more]
  (let [refset-ids (if more (into #{refset-id} more) #{refset-id})]
    (into #{} (map :conceptId (search svc {:concept-refsets refset-ids})))))

(s/fdef map-into
  :args (s/cat :svc ::svc
               :source-concept-ids (s/coll-of :info.snomed.Concept/id)
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

(s/fdef get-example-historical-associations
  :args (s/cat :svc ::svc :type-id :info.snomed.Concept/id :n pos-int?))
(defn- get-example-historical-associations
  "Returns 'n' examples of the type of historical association specified."
  [^Service svc type-id n]
  (let [ch (async/chan 100 (remove :active))]
    (store/stream-all-concepts (.-store svc) ch)
    (loop [i 0
           result []]
      (let [c (async/<!! ch)]
        (if-not (and c (< i n))
          result
          (let [assocs (historical-associations svc (:id c))
                append? (contains? assocs type-id)]
            (recur (if append? (inc i) i)
                   (if append? (conj result {(:id c) assocs}) result))))))))


(s/fdef paths-to-root
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))
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
  {:version 12
   :store   "store.db"
   :search  "search.db"
   :members "members.db"})

(defn- open-manifest
  "Open or, if it doesn't exist, optionally create a manifest at the location specified."
  ([root] (open-manifest root false))
  ([root create?]
   (let [root-path (Paths/get (str root) (into-array String []))
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

(defn open
  "Open a (read-only) SNOMED service from the path `root`."
  ^Service [^String root]
  (let [manifest (open-manifest root)
        st (store/open-store (get-absolute-filename root (:store manifest)))
        index-reader (search/open-index-reader (get-absolute-filename root (:search manifest)))
        searcher (IndexSearcher. index-reader)
        member-reader (com.eldrix.hermes.impl.members/open-index-reader (get-absolute-filename root (:members manifest)))
        member-searcher (IndexSearcher. member-reader)
        locale-match-fn (lang/match-fn st)]
    (log/info "hermes terminology service opened " root (assoc manifest :releases (map :term (store/get-release-information st))))
    (->Service st
               index-reader
               searcher
               member-reader
               member-searcher
               locale-match-fn)))

(defn close [^Service svc]
  (.close svc))

(s/fdef do-import-snomed
  :args (s/cat :store-filename string?
               :files (s/coll-of :info.snomed/ReleaseFile)))
(defn- do-import-snomed
  "Import a SNOMED distribution from the specified files into a local
   file-based database `store-filename`.
   Blocking; will return when done. Throws an exception on the calling thread if
   there are any import problems."
  [store-filename files]
  (with-open [store (store/open-store store-filename {:read-only? false})]
    (let [nthreads (.availableProcessors (Runtime/getRuntime))
          result-c (async/chan)
          data-c (importer/load-snomed-files files :nthreads nthreads)]
      (async/pipeline-blocking
        nthreads
        result-c
        (map #(if (instance? Throwable %)                   ;; if channel contains an exception, throw it on
                (throw %)
                (do (store/write-batch-with-fallback % store)
                    ;; important to return true
                    true)))
        data-c true (fn ex-handler [err] err))              ;; and the exception handler then passes the exception through to results channel
      (loop []
        (when-let [v (async/<!! result-c)]
          (if (instance? Throwable v) (throw v) (recur)))))))

(defn log-metadata [dir]
  (let [metadata (importer/all-metadata dir)]
    (when (seq metadata)
      (log/info "importing " (count metadata) " distributions from " dir))
    (doseq [dist metadata]
      (log/info "distribution: " (:name dist))
      (log/info "license: " (if (:licenceStatement dist) (:licenceStatement dist) (str "error : " (:error dist)))))))

(def ^:private core-components
  #{"Concept" "Description" "Relationship" "RefsetDescriptorRefset"})

(defn import-snomed
  "Import SNOMED distribution files from the directories `dirs` specified into
  the database directory `root` specified. Import is performed in two phases
  for each directory - firstly core components and essential metadata, and
  secondly non-core and extension files."
  [root dirs]
  (let [manifest (open-manifest root true)
        store-filename (get-absolute-filename root (:store manifest))]
    (doseq [dir dirs]
      (log-metadata dir)
      (let [files (importer/importable-files dir)]
        (do-import-snomed store-filename (->> files (filter #(core-components (:component %)))))
        (do-import-snomed store-filename (->> files (remove #(core-components (:component %)))))))))

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

(defn build-search-indices
  ([root] (build-search-indices root (.toLanguageTag (Locale/getDefault))))
  ([root language-priority-list]
   (let [manifest (open-manifest root false)]
     (log/info "Building indices" {:root root :languages language-priority-list})
     (log/info "Building search index")
     (search/build-search-index (get-absolute-filename root (:store manifest))
                                (get-absolute-filename root (:search manifest))
                                language-priority-list)
     (log/info "Building members index")
     (members/build-members-index (get-absolute-filename root (:store manifest))
                                  (get-absolute-filename root (:members manifest)))
     (log/info "Building indices... complete."))))

(def ^:deprecated build-search-index
  "DEPRECATED: Use [[build-search-indices]] instead"
  build-search-indices)

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
  (add-tap #'p/submit)                                      ; Add portal as a tap> target
  (def svc (open "snomed.db"))
  (get-concept svc 24700007)
  (get-all-children svc 24700007)
  (time (get-all-parents svc 24700007))
  (time (get-extended-concept svc 24700007))
  (s/valid? :info.snomed/Concept (get-concept svc 24700007))

  (tap> (get-concept svc 24700007))
  (tap> (get-extended-concept svc 24700007))
  (get-extended-concept svc 24700007)
  (search svc {:s "mult scl"})
  (tap> (search svc {:s "mult scl"}))
  (search svc {:s "mult scl" :constraint "<< 24700007"})

  (search svc {:s "ICD-10 complex map"})
  (->> (reverse-map-prefix svc 447562003 "I30")
       (map :referencedComponentId)
       (map #(:term (get-preferred-synonym svc % "en"))))

  (search svc {:constraint "<900000000000455006 {{ term = \"emerg\"}}"})
  (search svc {:constraint "<900000000000455006 {{ term = \"household\", type = syn, dialect = (en-GB)  }}"})

  (reverse-map-prefix svc 447562003 "I")
  (get-component-refset-items svc 24700007 447562003)
  (map :mapTarget (get-component-refset-items svc 24700007 447562003))

  (get-extended-concept svc 24700007)
  (subsumed-by? svc 24700007 6118003)                       ;; demyelinating disease of the CNS

  (are-any? svc [24700007] [45454])


  (search svc {:constraint "<  64572001 |Disease|  {{ term = wild:\"cardi*opathy\"}}"})
  (search svc {:constraint "<24700007" :inactive-concepts? false})
  (search svc {:constraint "<24700007" :inactive-concepts? true})
  (def ecl-q (ecl/parse {:store           (.-store svc)
                         :searcher        (.-searcher svc)
                         :member-searcher (.-memberSearcher svc)} "<24700007"))
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
  (get-descriptions svc 24700007)


  (require '[criterium.core :as crit])
  (crit/bench (get-extended-concept svc 24700007))
  (crit/bench (search svc {:s "multiple sclerosis"})))

