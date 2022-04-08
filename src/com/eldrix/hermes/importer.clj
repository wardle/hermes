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
(ns com.eldrix.hermes.importer
  "Provides import functionality for processing directories of files"
  (:require
    [cheshire.core :as json]
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging.readable :as log]
    [com.eldrix.hermes.snomed :as snomed])
  (:import (java.io File)
           (com.fasterxml.jackson.core JsonParseException)))

(defn is-snomed-file? [f]
  (snomed/parse-snomed-filename (.getName (clojure.java.io/file f))))

(defn snomed-file-seq
  "A tree sequence for SNOMED CT data files, returning a sequence of maps.

  Each result is a map of SNOMED information from the filename as per
  the [release file documentation](https://confluence.ihtsdotools.org/display/DOCRELFMT/3.3.2+Release+File+Naming+Convention),
  with
  * :path : the path of the file,
  * :component the canonical name of the SNOMED component (e.g. 'Concept', 'SimpleRefset')"
  [dir]
  (->> dir
       clojure.java.io/file
       file-seq
       (map #(snomed/parse-snomed-filename (.getPath ^File %)))
       (filter :component)))

(defn importable-files
  "Return a list of importable files from the directory specified."
  [dir]
  (->> (snomed-file-seq dir)
       (filter #(= (:release-type %) "Snapshot"))
       (filter :parser)))

(defn read-metadata
  "Reads the metadata from the file specified.

  Unfortunately, some UK releases have invalid JSON in their metadata, so
  we log an error and avoid throwing an exception.
  Raised as issue #34057 with NHS Digital.

  Unfortunately the *name* of the release is not included currently, but as the
  metadata file exists at the root of the release, we can guess the name from
  the parent directory and use that if a 'name' isn't in the metadata.
  Raised as issue #32991 with Snomed International."
  [^File f]
  (let [default {:name (.getName (.getParentFile f))}]
    (try (merge default (json/parse-string (slurp f) true))
         (catch JsonParseException _e
           (log/warn "invalid metadata in distribution file" (:name default))
           (assoc default :error "invalid metadata: invalid json in file")))))

(defn metadata-files
  "Returns a list of release package information files from the directory.
  Each entry returned in the list will be a `java.io.File`.
  These files have been issued since the July 2020 International edition release."
  [dir]
  (->> (io/file dir)
       (file-seq)
       (filter #(= (.getName ^File %) "release_package_information.json"))))

(defn all-metadata
  "Returns all release metadata from the directory specified."
  [dir]
  (doall (->> (metadata-files dir)
              (map read-metadata))))

(defn csv-data->maps
  "Turn CSV data into maps, assuming first row is the header."
  [csv-data]
  (map zipmap
       (->> (first csv-data)
            (map keyword)
            repeat)
       (rest csv-data)))

(defn- process-file
  "Process the specified file, streaming batched results to the channel
  specified, blocking if channel not being drained.

  Each batch is a map with keys
   - :type      : a type of SNOMED component
   - :parser    : a parser that can take each row and give you data
   - :headings  : a sequence of headings from the original file
   - :data      : a sequence of vectors representing each column."
  [filename out-c & {:keys [batch-size] :or {batch-size 1000}}]
  (with-open [reader (io/reader filename)]
    (let [snofile (snomed/parse-snomed-filename filename)
          parser (:parser snofile)]
      (when parser
        (let [csv-data (map #(str/split % #"\t") (line-seq reader))
              headings (first csv-data)
              data (rest csv-data)
              batches (->> data
                           (partition-all batch-size)
                           (map #(hash-map :type (:identifier snofile)
                                           :parser parser
                                           :headings headings
                                           :data %)))]
          (when-not quiet? (log/info "Processing: " filename " type: " (:component snofile)))
          (doseq [batch batches]
            (when-not (async/>!! out-c batch)
              (throw (InterruptedException. "process cancelled")))))))))

(defn test-csv [filename]
  (with-open [rdr (clojure.java.io/reader filename)]
    (if-let [parser (:parser (snomed/parse-snomed-filename filename))]
      (loop [i 0
             n 0
             data (map #(str/split % #"\t") (line-seq rdr))]

        (when-let [line (first data)]
          (when (= i 0)
            (println "Processing " filename "\n" line))
          (try
            (when (> i 0) (parser line))
            (catch Throwable e (throw (Exception. (str "Error parsing " filename " line:" i "\nline : " line "\nError: " e)))))
          (when (and (not= 0 n) (not= n (count line)))
            (println "incorrect number of columns; expected" n " got:" (count line)) {})
          (recur (inc i)
                 (if (= n 0) (long (count line)) n)
                 (next data))))
      (println "no parser for file: " filename))))

(defn test-all-csv [dir]
  (doseq [filename (map :path (importable-files dir))]
    (test-csv filename)))

(defn load-snomed
  "Imports a SNOMED-CT distribution from the specified directory, returning
  results on the returned channel which will be closed once all files have been
  sent through."
  [dir & {:keys [nthreads batch-size quiet?] :or {nthreads 4 batch-size 5000 quiet? false}}]
  (let [raw-c (async/chan)                                  ;; CSV data in batches with :type, :headings and :data, :data as a vector of raw strings
        processed-c (async/chan)                            ;; CSV data in batches with :type, :headings and :data, :data as a vector of SNOMED entities
        files (importable-files dir)]
    (when-not quiet? (log/info "importing files from " dir))
    (async/pipeline nthreads
                    processed-c
                    (map snomed/parse-batch)
                    raw-c
                    true
                    (fn [err] (log/error "failed to import:" err)))
    (async/thread
      (when-not (seq files) (log/warn "no files found to import in " dir))
      (doseq [file files]
        (process-file (:path file) raw-c :batch-size batch-size :quiet? quiet?))
      (async/close! raw-c))
    processed-c))


(defn examine-distribution-files
  [dir]
  (let [results-c (load-snomed dir :batch-size 5000)]
    (loop [counts {}
           batch (async/<!! results-c)]
      (if-not batch
        (print "Total statistics: \n" counts)
        (do (print counts "\r")
            (recur
              (merge-with + counts {(:type batch) (count (:data batch))})
              (async/<!! results-c)))))))

(comment
  (snomed/parse-snomed-filename "sct2_Concept_Full_INT_20190731.txt")
  (def filename "/Users/mark/Downloads/uk_sct2cl_30.0.0_20200805000001/SnomedCT_InternationalRF2_PRODUCTION_20190731T120000Z/Snapshot/Refset/Map/der2_iisssccRefset_ExtendedMapSnapshot_INT_20190731.txt")
  (test-csv filename))

