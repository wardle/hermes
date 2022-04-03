(ns com.eldrix.hermes.synth-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.test :refer :all]
            [com.eldrix.hermes.gen :as hgen]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.impl.store :as store])
  (:import (java.nio.file Paths Files FileVisitOption)
           (java.nio.file.attribute FileAttribute)))

(defn delete-all
  "Delete all files from the `path` specified, where `path` can be a
  `java.nio.file.Path` or anything coercible to a `file` using
  [[clojure.java.io/as-file]]."
  [path]
  (let [path' (if (instance? java.nio.file.Path path) path (.toPath (io/as-file path)))]
    (dorun (->> (iterator-seq (.iterator (Files/walk path' (make-array FileVisitOption 0))))
                sort
                reverse
                (map #(Files/delete %))))))

(defn write-components
  [dir filename components]
  (with-open [writer (io/writer (.toFile (.resolve dir filename)))]
    (.write writer (str \newline))
    (doall (->> components (map snomed/unparse) (map #(.write writer (str (str/join "\t" %) \newline)))))))

(deftest test-components
  (let [temp-dir (Files/createTempDirectory "hermes" (make-array FileAttribute 0))
        db-path (str (.toAbsolutePath (.resolve temp-dir "snomed.db")))
        n 5000
        concepts (map snomed/map->Concept (gen/sample (s/gen :info.snomed/Concept) n))
        descriptions (map snomed/map->Description (gen/sample (s/gen :info.snomed/Description) n))
        relationships (map snomed/map->Relationship (gen/sample (s/gen :info.snomed/Relationship) n))
        lang-refsets (map snomed/map->LanguageRefsetItem (gen/sample (s/gen :info.snomed/LanguageRefset) n))]
    (write-components temp-dir "sct2_Concept_Snapshot_GB1000000_20180401.txt" concepts)
    (write-components temp-dir "sct2_Description_Snapshot_GB1000000_20180401.txt" descriptions)
    (write-components temp-dir "sct2_Relationship_Snapshot_GB1000000_20180401.txt" relationships)
    (write-components temp-dir "der2_cRefset_LanguageSnapshot-en-GB_GB1000000_20180401.txt" lang-refsets)
    (hermes/import-snomed db-path [(.toString (.toAbsolutePath temp-dir))])
    (hermes/compact db-path)
    (let [status (hermes/get-status db-path :counts? true)]
      (is (= n (:concepts status)))
      (is (= n (:descriptions status)))
      (is (= n (:relationships status)))
      (is (= n (:refsets status))))))

(comment
  (run-tests))