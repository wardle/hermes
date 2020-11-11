(ns com.eldrix.hermes.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [com.eldrix.hermes.import :as import]
            [com.eldrix.hermes.store :as store]
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
  (def filename "/Users/mark/Downloads/uk_sct2cl_30.0.0_20200805000001/SnomedCT_InternationalRF2_PRODUCTION_20190731T120000Z/Snapshot/Terminology")
  (import-snomed filename "snomed.db")
  (println "Done")
  (def st (store/open-store "snomed.db" true))
  (store/concept st 24700007)
  (store/description st 41398015)
  )