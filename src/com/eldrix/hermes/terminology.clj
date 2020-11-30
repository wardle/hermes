(ns com.eldrix.hermes.terminology
  "Provides a terminology service, wrapping the SNOMED store and
  search implementations as a single unified service."
  (:require
    [integrant.core :as ig]
    [com.eldrix.hermes.store :as store]
    [com.eldrix.hermes.search :as search]
    [com.eldrix.hermes.expression :as expression]
    [clojure.walk :as walk]
    [clojure.tools.logging :as log])
  (:import (com.eldrix.hermes.store MapDBStore)
           (java.io Closeable)
           (org.apache.lucene.search IndexSearcher)
           (org.apache.lucene.index IndexReader)
           (java.nio.file LinkOption Paths Files)))

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
    (contains? (store/get-all-parents store concept-id) subsumer-concept-id))
  (parseExpression [_ s]
    (expression/parse s))
  (search [_ params]
    (search/do-search searcher params)))

(defn- ^SnomedService open-service [store-filename search-filename]
  (let [st (store/open-store store-filename)
        index-reader (search/open-index-reader search-filename)
        searcher (IndexSearcher. index-reader)]
    (->Service st index-reader searcher)))

(def manifest
  {:store  {:path    "store.db"
            :version 0.1}
   :search {:path    "search.db"
            :version 0.1}})

(defn  create-or-open-service [path]
  (let [path (Paths/get path (into-array String []))
        exists? (Files/exists path (into-array LinkOption []))
        is-directory? (Files/isDirectory path (into-array LinkOption []))]
    {:path path
     :exists       exists?
     :is-directory is-directory?}))

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

  (def parents #{138875005
                 21483005
                 442083009
                 123037004
                 25087005
                 91689009
                 91723000})
  (def st (store/open-store "snomed.db"))

  ;; for each parent, get all of the parents, ensuring each combination of parents is itself unique and non-empty
  (def parents (disj (store/get-all-parents st 95883001) 95883001))

  (def unique-parents (filter seq (into #{} (map #(disj (store/get-all-parents st %) %) parents))))
  )