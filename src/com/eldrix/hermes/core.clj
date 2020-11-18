(ns com.eldrix.hermes.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [com.eldrix.hermes.import :as import]
            [com.eldrix.hermes.store :as store]
            [com.eldrix.hermes.search :as search]
            [clojure.core.async :as async]))

(defn import-snomed
  "Import a SNOMED distribution from the specified directory `dir` into a local
   file-based database `db-filename`.
   Blocking; will return when done. "
  [dir db-filename]
  (let [nthreads (.availableProcessors (Runtime/getRuntime))
        store (store/open-store db-filename {:read-only? false})
        data-c (import/load-snomed dir)
        done (import/create-workers nthreads store/write-batch-worker store data-c)]
    (async/<!! done)
    (store/close store)))

(defn -main [& args]
  (log/info "Hello, World"))

(comment
  (def filename "/Users/mark/Downloads/uk_sct2cl_30.0.0_20200805000001/SnomedCT_InternationalRF2_PRODUCTION_20190731T120000Z")
  (def filename "C:\\Users\\mark\\Dev\\downloads\\uk_sct2cl_30.0.0_20200805000001")
  (do
    (def filename "/Users/mark/Downloads/uk_sct2cl_30.0.0_20200805000001")
    (import-snomed filename "snomed.db")                    ;; 15 minutes
    (log/info "Starting indexing")
    (log/info "building description index")
    (with-open [st (store/open-store "snomed.db" {:read-only? false :skip-check? false})]
      (store/build-description-index st))
    (log/info "building relationship indices")
    (with-open [st (store/open-store "snomed.db" {:read-only? false :skip-check? false})]
      (store/build-relationship-indices st))
    (log/info "building refset indices")
    (with-open [st (store/open-store "snomed.db" {:read-only? false :skip-check? false})]
      (store/build-refset-indices st))
    (log/info "compacting database")
    (with-open [st (store/open-store "snomed.db" {:read-only? false :skip-check? false})]
      (store/compact st))
    (log/info "finished: getting status")
    (with-open [st (store/open-store "snomed.db" {:read-only? false :skip-check? false})]
      (store/status st)))

  (def st (store/open-store "snomed.db"))
  (store/get-concept st 24700007)
  (store/get-description-refsets st 41398015)

  (search/build-search-index "snomed.db" "search.db" "en-GB")
  )