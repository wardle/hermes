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
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.expression.ecl :as ecl]
            [com.eldrix.hermes.expression.scg :as scg]
            [com.eldrix.hermes.impl.language :as lang]
            [com.eldrix.hermes.impl.search :as search]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.importer :as importer])
  (:import (com.eldrix.hermes.impl.store MapDBStore)
           (org.apache.lucene.search IndexSearcher)
           (org.apache.lucene.index IndexReader)
           (java.nio.file Paths Files LinkOption)
           (java.nio.file.attribute FileAttribute)
           (java.util Locale)
           (java.time.format DateTimeFormatter)
           (java.time LocalDateTime)
           (java.io Closeable)))

(set! *warn-on-reflection* true)

(deftype Service [^MapDBStore store
                  ^IndexReader index-reader
                  ^IndexSearcher searcher
                  locale-match-fn]
  Closeable
  (close [_] (.close store) (.close index-reader)))

(defn get-concept [^Service svc concept-id]
  (store/get-concept (.-store svc) concept-id))

(defn get-extended-concept [^Service svc concept-id]
  (when-let [concept (store/get-concept (.-store svc) concept-id)]
    (store/make-extended-concept (.-store svc) concept)))

(defn get-descriptions [^Service svc concept-id]
  (store/get-concept-descriptions (.-store svc) concept-id))

(defn get-reference-sets [^Service svc component-id]
  (store/get-component-refsets (.-store svc) component-id))

(defn get-component-refset-items [^Service svc component-id refset-id]
  (store/get-component-refset-items (.-store svc) component-id refset-id))

(defn get-installed-reference-sets [^Service svc]
  (store/get-installed-reference-sets (.-store svc)))

(defn reverse-map [^Service svc refset-id code]
  (store/get-reverse-map (.-store svc) refset-id code))

(defn get-preferred-synonym [^Service svc concept-id langs]
  (let [locale-match-fn (.-locale_match_fn svc)]
    (store/get-preferred-synonym (.-store svc) concept-id (locale-match-fn langs))))

(defn get-release-information [^Service svc]
  (store/get-release-information (.-store svc)))

(defn subsumed-by? [^Service svc concept-id subsumer-concept-id]
  (store/is-a? (.-store svc) concept-id subsumer-concept-id))

(defn parse-expression [^Service svc s]
  (scg/parse s))

(defn search [^Service svc params]
  (if-let [constraint (:constraint params)]
    (search/do-search (.-searcher svc) (assoc params :query (ecl/parse (.-store svc) (.-searcher svc) constraint)))
    (search/do-search (.-searcher svc) params)))

(defn synonyms [^Service svc params]
  (mapcat (partial store/all-transitive-synonyms (.-store svc)) (map :conceptId (search/do-search (.-searcher svc) params))))

(def ^:private expected-manifest
  "Defines the current expected manifest."
  {:version 0.4
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
        data-c (importer/load-snomed dir)
        done (importer/create-workers nthreads store/write-batch-worker store data-c)]
    (async/<!! done)
    (store/close store)))

(defn log-metadata [dir]
  (let [metadata (importer/all-metadata dir)]
    (log/info "importing " (count metadata) " distributions from " dir)
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
                                (get-absolute-filename root (:search manifest)) language-priority-list)
     (log/info "Building search index... complete."))))

(defn get-status [root]
  (let [manifest (open-manifest root)]
    (with-open [st (store/open-store (get-absolute-filename root (:store manifest)))]
      (log/info "Status information for database at '" root "'...")
      (merge
        {:installed-releases (map :term (store/get-release-information st))}
        (store/status st)))))

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
  (def svc (open "snomed.db"))
  (search svc {:s "mult scl" :constraint "<< 24700007"})
  (search svc {:constraint "<900000000000455006 {{ term = \"emerg\"}}"})
  (search svc {:constraint "<900000000000455006 {{ term = \"household\", type = syn, dialect = (en-GB)  }}"})

  (search svc {:constraint "<  64572001 |Disease|  {{ term = wild:\"cardi*opathy\"}}"})
  (search svc {:constraint "<24700007" :inactive-concepts? false})
  (search svc {:constraint "<24700007" :inactive-concepts? true})
  (def ecl-q (ecl/parse (.-store svc) (.-searcher svc) "<24700007"))
  ecl-q
  (def q1 (search/q-and [ecl-q (#'search/make-search-query {:inactive-concepts? true} )]))
  (def q2 (search/q-and [ecl-q (#'search/make-search-query {:inactive-concepts? false} )]))
  q1
  q2
  (count (#'search/do-query-for-concepts (.-searcher svc) q1))
  (count (#'search/do-query-for-concepts (.-searcher svc) q2))
  q2
  )