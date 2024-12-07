; Copyright (c) 2020-2023 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns com.eldrix.hermes.importer
  "Provides import functionality for processing directories of files"
  (:require [clojure.core.async :as a]
            [clojure.data.csv :as csv]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (java.io File)))

(defn snomed-file? [f]
  (snomed/parse-snomed-filename (.getName (io/file f))))

(def ^:deprecated is-snomed-file? snomed-file?)

(defn snomed-file-seq
  "A tree sequence for SNOMED CT data files, returning a sequence of maps.

  Each result is a map of SNOMED information from the filename as per
  the [release file documentation](https://confluence.ihtsdotools.org/display/DOCRELFMT/3.3.2+Release+File+Naming+Convention),
  with additional keys:

  path            : the path of the file,
  component       : the canonical name of the SNOMED component (e.g. 'Concept', 'SimpleRefset')
  component-order : the sort order as defined by component type"
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

(def ^:private metadata-parsers
  {:effectiveTime snomed/parse-date
   :deltaToDate   snomed/parse-date
   :deltaFromDate snomed/parse-date})

(defn read-metadata-value [k v]
  ((or (k metadata-parsers) identity) v))

(defn read-metadata
  "Reads the metadata from the file specified.

  Unfortunately, some UK releases have invalid JSON in their metadata, so
  we log an error and avoid throwing an exception.
  Raised as issue #34057 with NHS Digital.

  Unfortunately the *name* of the release is not included currently, but as the
  metadata file exists at the root of the release, we can guess the name from
  the parent directory and use that if a 'name' isn't in the metadata.
  Raised as issue #32991 with Snomed International."
  [f]
  (let [parent (when (instance? File f) (.getParentFile ^File f))
        default (when parent {:name (.getName parent)})]
    (try
      (-> default                                           ;; start with sane default
          (merge (json/read-str (slurp f) :key-fn keyword :value-fn read-metadata-value)) ;; read in metadaa
          (update :modules update-keys (fn [x] (-> x name parse-long)))) ;; return all module identifiers as longs
      (catch Throwable e (log/warn e "Invalid metadata in distribution file" (:name default))
             (assoc default :error "Invalid metadata in distribution file")))))

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

(defprotocol SnomedFile
  (parse-filename [this] "Returns structured data about a SNOMED file"))

(extend-protocol SnomedFile
  String
  (parse-filename [s] (snomed/parse-snomed-filename s))
  File
  (parse-filename [f] (snomed/parse-snomed-filename (.getName f)))
  java.net.URL
  (parse-filename [url] (snomed/parse-snomed-filename (.getPath url)))
  nil
  (parse-filename [_] nil))

(defn process-file
  "Process the specified file, streaming batched results to the channel
  specified, blocking if channel not being drained. 
  Parameters:
  - f     : anything coercible using clojure.java.io/reader

  Each batch is a map with keys
   - :type      : a type of SNOMED component
   - :parser    : a parser that can take each row and give you data
   - :headings  : a sequence of headings from the original file
   - :data      : a sequence of vectors representing each column."
  [f out-c & {:keys [batch-size] :or {batch-size 1000}}]
  (let [{:keys [identifier parser filename component]} (parse-filename f)]
    (when parser
      (with-open [reader (io/reader f)]
        (let [csv-data (csv/read-csv reader :separator \tab :quote \u0000)
              headings (first csv-data)
              data (rest csv-data)
              batches (->> data
                           (partition-all batch-size)
                           (map #(hash-map :type identifier
                                           :parser parser
                                           :headings headings
                                           :data %)))]
          (log/info "Processing: " filename " type: " component)
          (log/debug "Processing " (count batches) " batches")
          (doseq [batch batches]
            (log/debug "Processing batch " {:batch (dissoc batch :data) :first-data (-> batch :data first)})
            (when-not (a/>!! out-c batch)
              (log/debug "Processing cancelled (output channel closed)")
              (throw (InterruptedException. "process cancelled")))))))))

(defn import-file
  "Import a SNOMED file, returning a map containing :type :headings :parser 
  and :data as per [[process-file]]. This is designed only for testing and
  development purposes."
  [f]
  (let [ch (a/chan)]
    (a/thread
      (process-file f ch)
      (a/close! ch))
    (a/<!! ch)))

(s/fdef load-snomed-files
  :args (s/cat :files (s/coll-of :info.snomed/ReleaseFile)
               :opts (s/keys* :opt-un [::nthreads ::batch-size])))

(defn load-snomed-files
  "Imports a SNOMED-CT distribution from the specified files, returning
  results on the returned channel which will be closed once all files have been
  sent through. Any exceptions will be passed on the channel."
  [files & {:keys [nthreads batch-size] :or {nthreads 4 batch-size 5000}}]
  (let [raw-c (a/chan)                                  ;; CSV data in batches with :type, :headings and :data, :data as a vector of raw strings
        processed-c (a/chan)]                           ;; CSV data in batches with :type, :headings and :data, :data as a vector of SNOMED entities
    (a/thread
      (log/debug "Processing " (count files) " files")
      (try
        (doseq [file files]
          (process-file (:path file) raw-c :batch-size batch-size))
        (catch Throwable e
          (log/debug "Error during raw SNOMED file import: " e)
          (a/>!! processed-c e)))
      (a/close! raw-c))
    (a/pipeline
     nthreads
     processed-c
     (map snomed/parse-batch)
     raw-c
     true
     (fn ex-handler [err] (log/debug "Error during import pipeline: " (ex-data err)) err))
    processed-c))

(defn load-snomed
  "Imports a SNOMED-CT distribution from the specified directory, returning
  results on the returned channel which will be closed once all files have been
  sent through. Any exceptions will be passed on the channel.

  This streams data in a single pass; in generally usage you will usually want
  to stream data in multiple passes."
  [dir & opts]
  (let [files (snomed-file-seq dir)]
    (load-snomed-files files opts)))

(comment
  (snomed/parse-snomed-filename "sct2_Concept_Full_INT_20190731.txt")
  (def filename "/Users/mark/Downloads/uk_sct2cl_30.0.0_20200805000001/SnomedCT_InternationalRF2_PRODUCTION_20190731T120000Z/Snapshot/Refset/Map/der2_iisssccRefset_ExtendedMapSnapshot_INT_20190731.txt"))

