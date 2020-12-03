(ns com.eldrix.hermes.terminology
  "Provides a terminology service, wrapping the SNOMED store and
  search implementations as a single unified service."
  (:require
    [integrant.core :as ig]
    [com.eldrix.hermes.store :as store]
    [com.eldrix.hermes.search :as search]
    [com.eldrix.hermes.expression :as expression]
    [clojure.tools.logging.readable :as log]
    [com.eldrix.hermes.import :as import]
    [clojure.core.async :as async])
  (:import (com.eldrix.hermes.store MapDBStore)
           (java.io Closeable File)
           (org.apache.lucene.search IndexSearcher)
           (org.apache.lucene.index IndexReader)
           (java.nio.file Paths Files LinkOption)
           (java.nio.file.attribute FileAttribute)
           (java.util Locale)))

(defprotocol SnomedService
  (getConcept [_ concept-id])
  (getExtendedConcept [_ concept-id])
  (getDescriptions [_ concept-id])
  (getReferenceSets [_ component-id])
  (getPreferredSynonym [_ concept-id langs])
  (subsumedBy? [_ concept-id subsumer-concept-id])
  (parseExpression [_ s])
  (search [_ params]))

(deftype Service [^MapDBStore store
                  ^IndexReader index-reader
                  ^IndexSearcher searcher]
  Closeable
  (close [_] (.close store) (.close index-reader))

  SnomedService
  (getConcept [_ concept-id]
    (store/get-concept store concept-id))
  (getExtendedConcept [_ concept-id]
    (when-let [concept (store/get-concept store concept-id)]
      (store/make-extended-concept store concept)))
  (getDescriptions [_ concept-id]
    (store/get-concept-descriptions store concept-id))
  (getReferenceSets [_ component-id]
    (store/get-component-refsets store component-id))
  (getPreferredSynonym [_ concept-id langs]
    (let [lang-refsets (store/ordered-language-refsets-from-locale langs (store/get-installed-reference-sets store))]
      (store/get-preferred-synonym store concept-id lang-refsets)))
  (subsumedBy? [_ concept-id subsumer-concept-id]
    (store/is-a? store concept-id subsumer-concept-id))
  (parseExpression [_ s]
    (expression/parse s))
  (search [_ params]
    (search/do-search searcher params)))

(def expected-manifest
  "Defines the current expected manifest."
  {:version 0.1
   :store   "store.db"
   :search  "search.db"})

(defn open-manifest
  "Open or, if it doesn't exist, optionally create a manifest at the location specified."
  ([root] (open-manifest root false))
  ([root create?]
   (let [root-path (Paths/get root (into-array String []))
         manifest-path (.resolve root-path "manifest.edn")
         exists? (Files/exists manifest-path (into-array LinkOption []))]
     (cond
       exists?
       (if-let [manifest (clojure.edn/read-string (slurp (.toFile manifest-path)))]
         (if (= (:version manifest) (:version expected-manifest))
           manifest
           (throw (Exception. (str "error: incompatible database version. expected:'" (:version expected-manifest) "' got:'" (:version manifest) "'"))))
         (throw (Exception. (str "error: unable to read manifest from " root))))
       create?
       (let [manifest (assoc expected-manifest
                        :created (.format (java.time.format.DateTimeFormatter/ISO_DATE_TIME) (java.time.LocalDateTime/now)))]
         (Files/createDirectory root-path (into-array FileAttribute []))
         (spit (.toFile manifest-path) (pr-str manifest))
         manifest)
       :else
       (throw (ex-info "no database found at path and operating read-only" {:path root}))))))

(defn get-absolute-filename
  [root filename]
  (let [root-path (Paths/get root (into-array String []))]
    (.toString (.normalize (.toAbsolutePath (.resolve root-path filename))))))

(defn ^SnomedService open-service
  "Open a (read-only) SNOMED service from the path `root`."
  [root]
  (let [manifest (open-manifest root)
        st (store/open-store (get-absolute-filename root (:store manifest)))
        index-reader (search/open-index-reader (get-absolute-filename root (:search manifest)))
        searcher (IndexSearcher. index-reader)]
    (->Service st index-reader searcher)))

(defn do-import-snomed
  "Import a SNOMED distribution from the specified directory `dir` into a local
   file-based database `store-filename`.
   Blocking; will return when done. "
  [store-filename dir]
  (let [nthreads (.availableProcessors (Runtime/getRuntime))
        store (store/open-store store-filename {:read-only? false})
        data-c (import/load-snomed dir)
        done (import/create-workers nthreads store/write-batch-worker store data-c)]
    (async/<!! done)
    (store/close store)))

(defn import-snomed
  "Import SNOMED distribution files from the directories `dirs` specified into
  the database directory `root` specified."
  [root dirs]
  (let [manifest (open-manifest root true)
        store-filename (get-absolute-filename root (:store manifest))]
    (doseq [dir dirs]
      (do-import-snomed store-filename dir))))

(defn build-indices
  [root]
  (let [manifest (open-manifest root false)]
    (log/info "Building indices for database at '" root "'...")
    (with-open [st (store/open-store (get-absolute-filename root (:store manifest)) {:read-only? false})]
      (store/build-indices st))))

(defn compact
  [root]
  (let [manifest (open-manifest root false)]
    (log/info "Compacting database at '" root "'...")
    (with-open [st (store/open-store (get-absolute-filename root (:store manifest)) {:read-only? false})]
      (store/compact st))))

(defn build-search-index
  ([root] (build-search-index root (.toLanguageTag (Locale/getDefault))))
  ([root locale-preference-string]
   (let [manifest (open-manifest root false)]
     (log/info "Building search index at '" root "' using languages " locale-preference-string "...")
     (search/build-search-index (get-absolute-filename root (:store manifest))
                                (get-absolute-filename root (:seach manifest)) locale-preference-string))))

(defn get-status [root]
  (let [manifest (open-manifest root)]
    (with-open [st (store/open-store (get-absolute-filename root (:store manifest)))]
      (log/info "Status information for database at '" root "'...")
      (store/status st))))


(defn create-service
  "Create a monolithic terminology service combining both store and search functionality."
  ([root import-from] (create-service root import-from))
  ([root import-from locale-preference-string]
   (let [path (Files/createDirectory (Paths/get root (into-array String [])) (into-array FileAttribute []))
         manifest-path (.resolve path "manifest.edn")
         store-filename (.toString (.toAbsolutePath (.resolve path (:store expected-manifest))))
         search-filename (.toString (.toAbsolutePath (.resolve path (:search expected-manifest))))]
     (log/info "creating SNOMED database at " path)
     (spit (.toFile manifest-path) (pr-str (assoc expected-manifest
                                             :locale locale-preference-string
                                             :created (java.time.LocalDateTime/now)))) ;; write manifest file
     (log/info "importing distribution from '" import-from "' to " store-filename)
     (import-snomed store-filename import-from)             ;; 15 minutes
     (log/info "starting indexing")
     (with-open [st (store/open-store store-filename {:read-only? false})]
       (store/build-indices st))
     (log/info "compacting database")
     (with-open [st (store/open-store store-filename {:read-only? false})]
       (store/compact st))
     (log/info "finished import and indexing: getting status")
     (with-open [st (store/open-store store-filename {:read-only? false :skip-check? false})]
       (store/status st))
     (log/info "building search / autocompletion")
     (search/build-search-index store-filename search-filename locale-preference-string))))









;;;;
;;;; Experiments with integrant
;;;;
;;;;



(def config
  {:com.eldrix.hermes/Service       {:store ig/ref :com.eldrix.hermes.snomed/Store
                                            :search ig/ref :com.eldrix.hermes.snomed/Search}
   :com.eldrix.hermes.snomed/Store  {:filename "snomed.db"}
   :com.eldrix.hermes.snomed/Search {:filename "search.db"}})

(defmethod ig/init-key :com.eldrix.hermes/Terminology [_ {:keys [path]}]
  (open-service))

(defmethod ig/init-key :com.eldrix.hermes.snomed/Store [_ {:keys [filename]}]
  (store/open-store filename))

(defmethod ig/halt-key! :com.eldrix.hermes.snomed/Store [_ store]
  (.close store))

(defmethod ig/init-key :com.eldrix.hermes.snomed/Search [_ {:keys [filename]}]
  (let [reader (search/open-index-reader filename)
        searcher (IndexSearcher. reader)]
    {:reader   reader
     :searcher searcher}))

(defmethod ig/halt-key! :com.eldrix.hermes.snomed/Search [_ search]
  (.close (:searcher search)))





(comment
  (def service (ig/init config))
  (ig/halt! service)
  (store/get-concept (:com.eldrix.hermes.snomed/Store service) 24700007)

  (def svc (open-service "snomed.db" "search.db"))
  (getConcept svc 24700007)
  (search svc {"s" "multiple sclerosis" "max-hits" 1})
  (.close svc)
  )


(comment

  )