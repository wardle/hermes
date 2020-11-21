(ns com.eldrix.hermes.service
  (:require
    [integrant.core :as ig]
    [com.eldrix.hermes.store :as store]
    [com.eldrix.hermes.search :as search]
    [com.eldrix.hermes.expression :as expression]
    [clojure.walk :as walk]
    [clojure.tools.logging :as log])
  (:import (com.eldrix.hermes.store MapDBStore)
           (java.io Closeable)
           (org.apache.lucene.search IndexSearcher)))


(defprotocol SnomedService
  (getConcept [_ concept-id])
  (getExtendedConcept [_ concept-id])
  (getDescriptions [_ concept-id])
  (getReferenceSets [_ component-id])
  (getPreferredSynonym [_ concept-id langs])
  (subsumedBy [_ concept-id subsumer-concept-id])
  (parseExpression [_ s])
  (search [_ params]))

(deftype Service [^MapDBStore store
                  ^org.apache.lucene.index.IndexReader index-reader
                  ^org.apache.lucene.search.IndexSearcher searcher]
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
  (subsumedBy [_ concept-id subsumer-concept-id]
    (contains? (store/get-all-parents store concept-id) subsumer-concept-id))
  (parseExpression [_ s]
    (expression/parse s))
  (search [_ params]
    (search/do-search searcher params)))

(defn ^SnomedService open-service [store-filename search-filename]
  (let [st (store/open-store store-filename)
        index-reader (search/open-index-reader search-filename)
        searcher (IndexSearcher. index-reader)]
    (->Service st index-reader searcher)))

(def config
  {:com.eldrix.hermes.snomed/Store  {:filename "snomed.db"}
   :com.eldrix.hermes.snomed/Search {:filename "search.db"}})

(defmethod ig/init-key :com.eldrix.hermes.snomed/Store [_ {:keys [filename]}]
  (store/open-store filename))

(defmethod ig/halt-key! :com.eldrix.hermes.snomed/Store [_ store]
  (.close store))

(defmethod ig/init-key :com.eldrix.hermes.snomed/Search [_ {:keys [filename]}]
  (search/open-index-reader filename))

(defmethod ig/halt-key! :com.eldrix.hermes.snomed/Search [_ search]
  (.close search))


(comment
  (def service (ig/init config))
  (ig/halt! service)
  (store/get-concept (:com.eldrix.hermes.snomed/Store service) 24700007)

  (def svc (open-service "snomed.db" "search.db"))
  (getConcept svc 24700007)
  (search svc {"s" "multiple sclerosis" "max-hits" 1})
  (.close svc)
  )