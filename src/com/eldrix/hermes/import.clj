(ns com.eldrix.hermes.import
  "Provides import functionality for processing directories of files"
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [>! <! >!! <!! go chan buffer close! thread
                                                  alts! alts!! take! put! timeout]]))

(def ^:private snomed-files
  "Pattern matched SNOMED distribution files and their 'type'"
  {#"sct2_Concept_Full_\S+_\S+.txt"      :snomed/concept
   #"sct2_Description_Full-\S+_\S+.txt"  :snomed/description
   #"sct2_Relationship_Full_\S+_\S+.txt" :snomed/relationship})

(defn is-snomed-file? [filename]
  (first (filter #(re-find % filename) (keys snomed-files))))

(defn get-snomed-type
  "Returns the SNOMED 'type' :snomed/concept, :snomed/description, :snomed/relationship or :snomed/reference-set"
  [filename]
  (get snomed-files (is-snomed-file? filename)))

(defn snomed-file-seq
  "A tree sequence for SNOMED CT data files"
  [dir]
  (->> dir
       clojure.java.io/file
       file-seq
       (filter #(is-snomed-file? (.getName %)))))

(defn csv-data->maps
  "Turn CSV data into maps, assuming first row is the header"
  [csv-data]
  (map zipmap
       (->> (first csv-data)
            (map keyword)
            repeat)
       (rest csv-data)))

(defn- process-file
  "Process the specified file, streaming batched results to the channel specified, blocking if channel not being
  drained. Each batch is a map with keys :type and :data."
  [filename out-c batchSize]
  (with-open [reader (io/reader filename)]
    (log/info "Processing file " filename)
    (let [snomed-type (get-snomed-type filename)
          batches (->> (csv/read-csv reader :separator \tab :quote \tab)
                       csv-data->maps
                       (partition-all batchSize)
                       (map #(hash-map :type snomed-type :data %))
                       )]
      (doseq [batch batches] (>!! out-c batch)))))

(defn file-worker
  [files-c out-c batchSize]
  (loop [f (<!! files-c)]
    (when f
      (log/info "Queuing file for import: " (.getPath f))
      (process-file (.getPath f) out-c (or batchSize 1000))
      (recur (<!! files-c)))))

(defn create-workers
  "Creates a number of workers threads each running the function specified. Returns a channel
  to signal when the workers have completed."
  [n f & args]
  (loop [i 1 chans []]
    (if (= i n)
      (async/merge chans)
      (recur (inc i) (conj chans (thread (apply f args)))))))

(defn- counting-worker
  "A worker that loops on a channel containing batches of work and simply counts the types of data.
  Useful in testing and debugging"
  [batch-c totals-atom]
  (loop [batch (<!! batch-c)]
    (when batch
      (swap! totals-atom #(update % (:type batch) (fnil + 0) (count (:data batch))))
      (log/info "Processed... " @totals-atom)
      (recur (<!! batch-c)))))


(defn import-snomed
  "Imports a SNOMED-CT distribution from the specified directory, returning results on the returned channel
  which will be closed once all files have been sent through."
  [dir & {:keys [nthreads batch-size]}]
  (let [files-c (chan)
        batches-c (chan)
        files (snomed-file-seq dir)
        done (create-workers (or nthreads 2) file-worker files-c batches-c (or batch-size 50000))]
    (log/info "importing files from " dir)
    (when-not (seq files) (log/warn "no files found to import in " dir))
    (async/onto-chan!! files-c files true)                  ;; stream list of files into work channel
    (thread (<!! done) (close! batches-c))                  ;; watch for completion and close output channel
    batches-c))



(defn -main [x]
  (let [ff (snomed-file-seq x)]
    (println "Found" (count ff) "files in" x)))

(comment
  (require '[clojure.reflect :as reflect])
  (clojure.pprint/pprint (reflect/reflect (java.util.Date.)))
  (def f (first (file-seq (clojure.java.io/file "."))))
  (reflect/reflect f)
  (def patterns (vals snomed-files))

  (is-snomed-file? "sct2_Concept_Full_INT_20190731.txt")
  (is-snomed-file? "sct2_Concept_Wibble_INT_20190731.txt")

  -- Let's open and read a CSV file into a lazy sequence of maps
  (def filename "/Users/mark/Downloads/uk_sct2cl_30.0.0_20200805000001/SnomedCT_InternationalRF2_PRODUCTION_20190731T120000Z/Full/Terminology/sct2_Concept_Full_INT_20190731.txt")
  (is-snomed-file? filename)
  (is-snomed-file? "wibble")
  (get-snomed-type filename)
  (snomed-file-seq "/Users/mark/Downloads/uk_sct2cl_30.0.0_20200805000001")
  (def c (chan))
  (thread (process-snomed-file filename c 5))
  (<! c)

  ;; manually configure
  (def dir "/Users/mark/Downloads/uk_sct2cl_30.0.0_20200805000001")
  (snomed-file-seq dir)
  ;; try asynchronously now...
  (def batches-c (chan))
  (def files-c (chan))
  ;; put file sequence on a file processing channel
  (async/onto-chan!! files-c (snomed-file-seq dir) true)
  (def complete (create-workers 4 file-worker files-c batches-c 5))
  (thread (<!! complete) (close! batches-c))
  (<!! batches-c)                                           ;; run this a few times as a test
  (close! files-c)
  (close! batches-c)

  ;; this does all of that in one step
  (def results-c (import-snomed dir))
  (def totals (atom {}))
  (<!! (create-workers 4 counting-worker results-c totals))
  (println "Totals: " @totals)
  )