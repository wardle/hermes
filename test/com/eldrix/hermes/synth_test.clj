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
            [com.eldrix.hermes.importer :as importer]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.rf2 :as rf2]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.core :as hermes])

  (:import (java.nio.file Paths Files FileVisitOption Path)
           (java.nio.file.attribute FileAttribute)
           (com.eldrix.hermes.snomed SimpleRefsetItem AssociationRefsetItem)))

(stest/instrument)

(defn delete-all
  "Delete all files from the `path` specified, where `path` can be a
  `java.nio.file.Path` or anything coercible to a `file` using
  [[clojure.java.io/as-file]]."
  [path]
  (let [path' (if (instance? Path path) path (.toPath (io/as-file path)))]
    (dorun (->> (iterator-seq (.iterator (Files/walk path' (make-array FileVisitOption 0))))
                sort reverse
                (map #(Files/delete %))))))


(def ^:dynamic *paths*)

(defn temporary-paths-fixture
  "Fixture to create a temporary directory and associated subpaths."
  [f]
  (let [root-path (Files/createTempDirectory "hermes-" (make-array FileAttribute 0))
        release-path (.toAbsolutePath (.resolve root-path "synth"))
        db-path (.toAbsolutePath (.resolve root-path "snomed.db"))
        store-path (.toAbsolutePath (.resolve db-path "store.db"))]
    (Files/createDirectory release-path (make-array FileAttribute 0))
    (binding [*paths* {:root-path root-path
                       :release-path release-path
                       :db-path  db-path
                       :store-path store-path}]
      (f)
      (delete-all root-path))))

(use-fixtures :each temporary-paths-fixture)


(defn write-components
  [dir filename components]
  (with-open [writer (io/writer (.toFile (.resolve dir filename)))]
    (.write writer (str \newline))
    (doall (->> components (map snomed/unparse) (map #(.write writer (str (str/join "\t" %) \newline)))))))

(deftest test-bad-import
  (let [{:keys [release-path]} *paths*
        refsets (gen/sample (rf2/gen-language-refset))
        different-extended-fields? (not (apply = (->> refsets (map :fields) (map count))))]
    (testing "Generated malformed reference set"
      (is different-extended-fields? "Generated reference sets must have same number of extension fields"))
    (write-components release-path "der2_cRefset_LanguageSnapshot-en-GB_GB1000000_20180401.txt" refsets)
    (testing "Import of malformed reference set should throw an exception"
      (is (thrown? Throwable (let [ch (importer/load-snomed (str release-path))]
                               (loop [o (a/<!! ch)]
                                 (when (instance? Throwable o)
                                   (throw (ex-info "SNOMED CT loading error:" (ex-data o))))
                                 (when o
                                   (recur (a/<!! ch))))))))))

(deftest test-reify-refset
  (let [{:keys [release-path db-path store-path]} *paths*
        refset-concept (gen/generate (rf2/gen-concept {:id 1322291000000109 :active true}))
        rd1 (gen/generate (rf2/gen-refset-descriptor-refset {:refsetId 900000000000456007 :referencedComponentId 1322291000000109 :active true :attributeOrder 0 :attributeDescriptionId 449608002}))
        rd2 (gen/generate (rf2/gen-refset-descriptor-refset {:refsetId 900000000000456007 :referencedComponentId 1322291000000109 :active true :attributeOrder 1 :attributeDescriptionId 900000000000533001}))
        refset-items (gen/sample (rf2/gen-simple-refset {:refsetId 1322291000000109 :active true :fields [24700007]}))]
    (write-components release-path "sct2_Concept_Snapshot_GB1000000_20180401.txt" [refset-concept])
    (write-components release-path "der2_cciRefset_RefsetDescriptorUKEDSnapshot_GB_20220316.txt" [rd1 rd2])
    (write-components release-path "der2_cRefset_CareRecordElementUKCLSnapshot_GB_20220216.txt" refset-items)
    (hermes/import-snomed (str db-path) [(str release-path)])
    (hermes/compact (str db-path))
    (with-open [store (store/open-store (str store-path))]
      (is (= refset-concept (store/get-concept store (:id refset-concept))))
      (is (every? #(instance? SimpleRefsetItem % ) refset-items))
      (doseq [item refset-items]  ;; has every instance been reified to the correct type of reference set?
        (is (instance? AssociationRefsetItem (store/get-refset-item store (:id item))))))))

(deftest test-components
  (let [{:keys [release-path db-path]} *paths*
        n 2000
        concepts (gen/sample (rf2/gen-concept) n)
        descriptions (gen/sample (rf2/gen-description) n)
        relationships (gen/sample (rf2/gen-relationship) n)
        lang-refsets (gen/sample (rf2/gen-language-refset {:fields []}) n)
        refset-descriptors (gen/sample (rf2/gen-refset-descriptor-refset) n)]
    (log/debug "Creating temporary components in " release-path)
    (write-components release-path "sct2_Concept_Snapshot_GB1000000_20180401.txt" concepts)
    (write-components release-path "sct2_Description_Snapshot_GB1000000_20180401.txt" descriptions)
    (write-components release-path "sct2_Relationship_Snapshot_GB1000000_20180401.txt" relationships)
    (write-components release-path "der2_cRefset_LanguageSnapshot-en-GB_GB1000000_20180401.txt" lang-refsets)
    (write-components release-path "der2_cciRefset_RefsetDescriptorUKEDSnapshot_GB_20220316.txt" refset-descriptors)
    (hermes/import-snomed (str db-path) [(str release-path)])
    (hermes/compact (str db-path))
    (let [status (hermes/get-status (str db-path) :counts? true)]
      (is (= n (:concepts status)))
      (is (= n (:descriptions status)))
      (is (= n (:relationships status)))
      (is (= (* n 2) (:refsets status))))))

(deftest test-localisation
  (let [{:keys [release-path db-path store-path]} *paths*
        concepts (gen/sample (rf2/gen-concept) 2000)
        en-GB-refset (gen/generate (rf2/gen-concept {:id 999001261000000100 :active true}))
        en-US-refset (gen/generate (rf2/gen-concept {:id 900000000000509007 :active true}))
        descriptions (mapcat #(gen/sample (rf2/gen-description {:conceptId (:id %) :typeId snomed/Synonym :active true})) (conj concepts en-US-refset en-GB-refset))
        en-GB (hgen/make-language-refset-items descriptions {:refsetId (:id en-GB-refset) :active true :acceptabilityId snomed/Preferred :typeId snomed/Synonym})
        en-US (hgen/make-language-refset-items descriptions {:refsetId (:id en-US-refset) :active true :acceptabilityId snomed/Preferred :typeId snomed/Synonym})]
    (write-components release-path "sct2_Concept_Snapshot_GB1000000_20180401.txt" (conj concepts en-GB-refset en-US-refset))
    (write-components release-path "sct2_Description_Snapshot_GB1000000_20180401.txt" descriptions)
    (write-components release-path "der2_cRefset_LanguageSnapshot-en-GB_GB1000000_20180401.txt" (concat en-GB en-US))
    (hermes/import-snomed (str db-path) [(str release-path)])
    (hermes/compact (str db-path))
    (with-open [store (store/open-store (str store-path))]
      (log/info "installed reference sets:" (store/get-installed-reference-sets store)))
    (hermes/build-search-index (str db-path))
    (with-open [svc (hermes/open (str db-path))]
      (let [en-GB-description-ids (set (map :referencedComponentId en-GB))
            en-US-description-ids (set (map :referencedComponentId en-US))]
        (is (= en-GB-refset (hermes/get-concept svc 999001261000000100)))
        (is (every? true? (map #(contains? en-GB-description-ids (:id (hermes/get-preferred-synonym svc (:id %) "en-GB"))) concepts)))
        (is (every? true? (map #(contains? en-US-description-ids (:id (hermes/get-preferred-synonym svc (:id %) "en-US"))) concepts)))))))

(comment
  (run-tests))