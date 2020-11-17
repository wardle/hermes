(ns com.eldrix.hermes.import
  "Provides import functionality for processing directories of files"
  (:require
    [clojure.java.io :as io]
    [clojure.tools.logging :as log]
    [clojure.core.async :as async]
    [com.eldrix.hermes.snomed :as snomed]
    [clojure.string :as str]))


(defn is-snomed-file? [f]
  (snomed/parse-snomed-filename (.getName (clojure.java.io/file f))))

(defn snomed-file-seq
  "A tree sequence for SNOMED CT data files, returning a sequence of maps.
  Each result is a map of SNOMED information from the filename as per
  the release file documentation.
  https://confluence.ihtsdotools.org/display/DOCRELFMT/3.3.2+Release+File+Naming+Convention
  with :path the path of the file, and :component the canonical name of the
  SNOMED component (e.g. 'Concept', 'SimpleRefset')"
  [dir]
  (->> dir
       clojure.java.io/file
       file-seq
       (map #(snomed/parse-snomed-filename (.getPath %)))
       (filter :component)))

(defn importable-files
  "Return a list of importable files from the directory specified."
  [dir]
  (->> (snomed-file-seq dir)
       (filter #(= (:release-type %) "Snapshot"))
       (filter :parser)))

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
  drained. Each batch is a map with keys :type, :headings and :data. :data is a sequence of vectors representing each
  column."
  [filename out-c batchSize]
  (with-open [reader (io/reader filename)]
    (let [snofile (snomed/parse-snomed-filename filename)
          parser (:parser snofile)]
      (when parser
        (let [csv-data (map #(str/split % #"\t") (line-seq reader))
              headings (first csv-data)
              data (rest csv-data)
              batches (->> data
                           (partition-all batchSize)
                           (map #(hash-map :type (:identifier snofile)
                                           :headings headings
                                           :data %)))]
          (log/info "Processing: " filename " type: " (:component snofile))
          (doseq [batch batches] (async/>!! out-c batch)))))))

(defn file-worker
  [files-c out-c batchSize]
  (loop [f (async/<!! files-c)]
    (when f
      (log/debug "Queuing   : " (.getPath f))
      (process-file f out-c (or batchSize 1000))
      (recur (async/<!! files-c)))))

(defn test-csv [filename]
  (with-open [rdr (clojure.java.io/reader filename)]
    (if-let [parser (:parser (snomed/parse-snomed-filename filename))]
      (loop [i 0
             n 0
             data (map #(str/split % #"\t") (line-seq rdr))
             ]
        (when-let [line (first data)]
          (when (= i 0)
            (println "Processing " filename "\n" line))
          (try
            (when (> i 0) (parser line))
            (catch Throwable e (throw (Exception. (str "Error parsing " filename " line:" i "\nline : " line "\nError: " e)))))
          (when (and (not= 0 n) (not= n (count line)))
            (println "incorrect number of columns; expected" n " got:" (count line)) {})
          (recur (inc i)
                 (if (= n 0) (count line) n)
                 (next data))))
      (println "no parser for file: " filename))))

(defn test-all-csv [dir]
  (doseq [filename (map :path (importable-files dir))]
    (test-csv filename)))

(defn create-workers
  "Creates a number of workers threads each running the function specified. Returns a channel
  to signal when the workers have completed."
  [n f & args]
  (loop [i 0 chans []]
    (if (= i n)
      (async/merge chans)
      (recur (inc i) (conj chans (async/thread (apply f args)))))))

(defn- counting-worker
  "A worker that loops on a channel containing batches of work and simply counts the types of data.
  Useful in testing and debugging"
  [batch-c totals-atom]
  (loop [batch (async/<!! batch-c)]
    (when batch
      (swap! totals-atom #(update % (:type batch) (fnil + 0) (count (:data batch))))
      (snomed/parse-batch batch)
      (log/debug "processed... " @totals-atom)
      (recur (async/<!! batch-c)))))

(defn load-snomed
  "Imports a SNOMED-CT distribution from the specified directory, returning results on the returned channel
  which will be closed once all files have been sent through."
  [dir & {:keys [nthreads batch-size]}]
  (let [files-c (async/chan)                                      ;; list of files
        raw-c (async/chan)                                        ;; CSV data in batches with :type, :headings and :data, :data as a vector of raw strings
        processed-c (async/chan)                                  ;; CSV data in batches with :type, :headings and :data, :data as a vector of SNOMED entities
        files (importable-files dir)
        done (create-workers (or nthreads 4) file-worker files-c raw-c (or batch-size 5000))]
    (log/info "importing files from " dir)
    (when-not (seq files) (log/warn "no files found to import in " dir))
    (async/onto-chan!! files-c (map :path files) true)      ;; stream list of files into work channel
    (async/thread (async/<!! done) (async/close! raw-c))                      ;; watch for completion and close output channel
    (async/pipeline (or nthreads 4) processed-c (map snomed/parse-batch) raw-c true)
    processed-c))


(defn examine-distribution-files
  [dir]
  (let [results-c (load-snomed dir :batch-size 5000)]
    (loop [counts {}
           batch (async/<!! results-c)]
      (when batch
        (print counts "\r")
        (recur
          (merge-with + counts {(:type batch) (count (:data batch))})
          (async/<!! results-c))))))

(defn -main [x]
  (log/info "starting hermes...")
  (examine-distribution-files x))

(comment
  (require '[clojure.reflect :as reflect])

  (snomed/parse-snomed-filename "sct2_Concept_Full_INT_20190731.txt")
  (def filename "/Users/mark/Downloads/uk_sct2cl_30.0.0_20200805000001/SnomedCT_InternationalRF2_PRODUCTION_20190731T120000Z/Snapshot/Refset/Map/der2_iisssccRefset_ExtendedMapSnapshot_INT_20190731.txt")
  (test-csv filename)
  )
