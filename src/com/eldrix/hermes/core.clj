(ns com.eldrix.hermes.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [com.eldrix.hermes.import :as import]
            [com.eldrix.hermes.store :as store]
            [clojure.core.async :as async]))


(defn import-worker
  "Pull a batch from the channel 'c' specified into the backing store, or send an individual item to the duplicates channel 'dup-c' if already exists"
  [bucket c dup-c]
  (loop [batch (async/<!! c)]
    (when-not (nil? batch)
      (doseq [e batch]
        (when-not (.putIfAbsentBoolean bucket (:id e) e)
          (async/>!! dup-c e)))
      (recur (async/<!! c)))))

(defn duplicates-worker
  [bucket dup-c]
  (loop [count 0
         e (async/<!! dup-c)]
    (if (nil? e) count
     (let [old (.get bucket (:id e))]
        (when (nil? old)
          (throw (ex-info "Entity for processing in duplicates channel not already in KV store!" {:entity e})))
        (when (.isAfter (:effectiveTime e) (:effectiveTime old))
          (.put bucket (:id e) e))
        (recur (inc count))))))

(defn import-snomed
  [dir db-filename]
  (let [nthreads (.availableProcessors (Runtime/getRuntime))
        results-c (import/load-snomed dir :nthreads nthreads :batch-size 5000)
        duplicates-c (async/chan)
        db (store/open-database db-filename)
        bt (store/open-btreemap db "core" org.mapdb.Serializer/LONG org.mapdb.Serializer/JAVA)
        workers (import/create-workers nthreads (partial import-worker bt results-c duplicates-c))]
    (async/thread
      ;; single thread to process duplicates one-by-one, not in parallel
      (duplicates-worker bt duplicates-c)
      (log/info "finished processing duplicates from duplicates channel"))
    (async/<!! workers)
    (async/close! duplicates-c)
    (.close db)
    (log/info "import complete from '" dir "'")))


(defn -main [& args]
  (log/info "Hello, World"))




(comment



  ;; Demonstrate usage of import to a key-value store
  (def filename "/Users/mark/Downloads/uk_sct2cl_30.0.0_20200805000001")
  (def filename "C:\\Users\\mark\\Dev\\downloads\\uk_sct2cl_30.0.0_20200805000001")

  (import-snomed filename "snomed.db")

  (def results-c (import/load-snomed filename :nthreads 8 :batch-size 5000))
  (def duplicates-c (async/chan))
  (def db (store/open-database "snomed.db" :read-only? true))
  (def bt (store/open-btreemap db "core" org.mapdb.Serializer/LONG org.mapdb.Serializer/JAVA))

  (.get bt 107012)
  (time (.get bt 345122012))

  ;; this thread simply processes an entity from the duplicates channel one-by-one
  (async/thread
    (loop []
      (when-let [e (async/<!! duplicates-c)]
        (let [old (.get bt (:id e))]
          (when (nil? old)
            (throw (ex-info "Entity for processing in duplicates channel not already in KV store!" {:entity e})))
          (when (.isAfter (:effectiveTime e) (:effectiveTime old))
            (.put bt (:id e) e))
          (recur))))
    (log/info "finished processing duplicates from duplicates channel"))

  (def done (import/create-workers
              8
              (fn [c dup-c] (loop [batch (async/<!! c)]
                              (when-not (nil? batch)
                                (doseq [e batch]
                                  (when-not (.putIfAbsentBoolean bt (:id e) e)
                                    (async/>!! dup-c e)))
                                (recur (async/<!! c)))))
              results-c duplicates-c))
  (async/thread (<!! done) (close! duplicates-c))

  )