(ns com.eldrix.hermes.synth-test
  (:require [clojure.core.async :as a]
            [clojure.tools.logging.readable :as log]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [com.eldrix.hermes.gen :as hgen]
            [com.eldrix.hermes.rf2 :as rf2]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.core :as hermes])

  (:import (java.nio.file Paths Files FileVisitOption Path)
           (java.nio.file.attribute FileAttribute)))

(stest/instrument)

(defn delete-all
  "Delete all files from the `path` specified, where `path` can be a
  `java.nio.file.Path` or anything coercible to a `file` using
  [[clojure.java.io/as-file]]."
  [path]
  (let [path' (if (instance? Path path) path (.toPath (io/as-file path)))]
    (dorun (->> (iterator-seq (.iterator (Files/walk path' (make-array FileVisitOption 0))))
                sort
                reverse
                (map #(Files/delete %))))))

(defn write-components
  [dir filename components]
  (with-open [writer (io/writer (.toFile (.resolve dir filename)))]
    (.write writer (str \newline))
    (doall (->> components (map snomed/unparse) (map #(.write writer (str (str/join "\t" %) \newline)))))))

(deftest test-bad-import
  (let [temp-dir (Files/createTempDirectory "hermes-" (make-array FileAttribute 0))
        db-path (str (.toAbsolutePath (.resolve temp-dir "snomed.db")))
        refsets (gen/sample (rf2/gen-language-refset))
        different-extended-fields? (not (apply = (->> refsets ( map :fields) (map count))))]
    (testing "Generated malformed reference set"
      (is different-extended-fields? "Generated reference sets all have same number of extension fields"))
    (write-components temp-dir "der2_cRefset_LanguageSnapshot-en-GB_GB1000000_20180401.txt" refsets)
    (testing "Import of malformed reference set should throw an exception"
      (is (thrown? Throwable (let [ch (com.eldrix.hermes.importer/load-snomed (str (.toAbsolutePath temp-dir)))]
                               (loop [o (a/<!! ch)]
                                 (when (instance? Throwable o)
                                   (throw (ex-info "SNOMED CT loading error:" (ex-data o))))
                                 (when o
                                   (recur (a/<!! ch))))))))))


(deftest test-components
  (let [temp-dir (Files/createTempDirectory "hermes-" (make-array FileAttribute 0))
        db-path (str (.toAbsolutePath (.resolve temp-dir "snomed.db")))
        n 5000
        concepts (gen/sample (rf2/gen-concept) n)
        descriptions (gen/sample (rf2/gen-description) n)
        relationships (gen/sample (rf2/gen-relationship) n)
        lang-refsets (gen/sample (rf2/gen-language-refset {:fields []}) n)]
    (log/debug "Creating temporary components in " temp-dir)
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
      (is (= n (:refsets status))))
    #_(delete-all temp-dir)))

(deftest test-localisation
  (let [temp-dir (Files/createTempDirectory "hermes-" (make-array FileAttribute 0))
        concepts (gen/sample (rf2/gen-concept) 2000)
        en-GB-refset (gen/generate (rf2/gen-concept {:id 999001261000000100 :active true}))
        en-US-refset (gen/generate (rf2/gen-concept {:id 900000000000509007 :active true}))
        descriptions (mapcat #(gen/sample (rf2/gen-description {:conceptId (:id %) :typeId snomed/Synonym :active true})) (conj concepts en-US-refset en-GB-refset))
        en-GB (hgen/make-language-refset-items descriptions {:refsetId (:id en-GB-refset) :active true :acceptabilityId snomed/Preferred :typeId snomed/Synonym})
        en-US (hgen/make-language-refset-items descriptions {:refsetId (:id en-US-refset) :active true :acceptabilityId snomed/Preferred :typeId snomed/Synonym})]
    (write-components temp-dir "sct2_Concept_Snapshot_GB1000000_20180401.txt" (conj concepts en-GB-refset en-US-refset))
    (write-components temp-dir "sct2_Description_Snapshot_GB1000000_20180401.txt" descriptions)
    (write-components temp-dir "der2_cRefset_LanguageSnapshot-en-GB_GB1000000_20180401.txt" (concat en-GB en-US))
    (let [db-path (.toAbsolutePath (.resolve temp-dir "snomed.db"))]
      (hermes/import-snomed (str db-path) [(str (.toAbsolutePath temp-dir))])
      (hermes/compact (str db-path))
      (with-open [store (com.eldrix.hermes.impl.store/open-store (str (.resolve db-path "store.db")))]
        (log/info "installed reference sets:" (com.eldrix.hermes.impl.store/get-installed-reference-sets store)))
      (hermes/build-search-index (str db-path))
      (with-open [svc (hermes/open (str db-path))]
        (let [en-GB-description-ids (set (map :referencedComponentId en-GB))
              en-US-description-ids (set (map :referencedComponentId en-US))]
          (is (= en-GB-refset (hermes/get-concept svc 999001261000000100)))
          (is (every? true? (map #(contains? en-GB-description-ids (:id (hermes/get-preferred-synonym svc (:id %) "en-GB"))) concepts)))
          (is (every? true? (map #(contains? en-US-description-ids (:id (hermes/get-preferred-synonym svc (:id %) "en-US"))) concepts))))))
    (delete-all temp-dir)))

(comment
  (run-tests))