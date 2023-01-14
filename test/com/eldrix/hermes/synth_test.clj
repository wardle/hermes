(ns com.eldrix.hermes.synth-test
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing use-fixtures]]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.gen :as hgen]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.importer :as importer]
            [com.eldrix.hermes.rf2 :as rf2]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (java.nio.file Files FileVisitOption Path)
           (java.nio.file.attribute FileAttribute)
           (com.eldrix.hermes.snomed SimpleRefsetItem AssociationRefsetItem)
           (java.time LocalDate)))

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
  [f & {:keys [delete? print?] :or {delete? true print? false}}]
  (let [root-path (Files/createTempDirectory "hermes-" (make-array FileAttribute 0))
        release-path (.toAbsolutePath (.resolve root-path "synth"))
        db-path (.toAbsolutePath (.resolve root-path "snomed.db"))
        store-path (.toAbsolutePath (.resolve db-path "store.db"))]
    (Files/createDirectory release-path (make-array FileAttribute 0))
    (binding [*paths* {:root-path    root-path
                       :release-path release-path
                       :db-path      db-path
                       :store-path   store-path}]
      (when print? (println *paths*))
      (f)
      (when delete? (delete-all root-path)))))

(use-fixtures :each temporary-paths-fixture)

(defn write-components
  [dir filename components & {:keys [field-headings]}]
  (with-open [writer (io/writer (.toFile (.resolve dir filename)))]
    (let [component (first components)
          headings (concat (map name (keys (dissoc component :fields)))
                           (or field-headings (gen/generate (s/gen (s/coll-of ::hermes/non-blank-string :count (count (:fields component)))))))]
      (.write writer (str (str/join "\t" headings) \newline))
      (doall (->> components (map snomed/unparse) (map #(.write writer (str (str/join "\t" %) \newline))))))))

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
    (write-components release-path "der2_cRefset_CareRecordElementUKCLSnapshot_GB_20220216.txt" refset-items :field-headings ["targetComponentId"])
    (hermes/import-snomed (str db-path) [(str release-path)])
    (hermes/compact (str db-path))
    (with-open [store (store/open-store (str store-path))]
      (is (= refset-concept (store/get-concept store (:id refset-concept))))
      (is (every? #(instance? SimpleRefsetItem %) refset-items))
      (doseq [item refset-items]                            ;; has every instance been reified to the correct type of reference set?
        (is (instance? AssociationRefsetItem (store/get-refset-item store (:id item))))))))

(deftest test-components
  (let [{:keys [release-path db-path]} *paths*
        n 2000
        en-GB-refset (gen/generate (rf2/gen-concept {:id 999001261000000100 :active true}))
        concepts (conj (gen/sample (rf2/gen-concept) (dec n)) en-GB-refset)
        descriptions (mapcat #(gen/sample (rf2/gen-description {:conceptId (:id %) :typeId snomed/Synonym :active true})) concepts)
        en-GB (hgen/make-language-refset-items descriptions {:refsetId (:id en-GB-refset) :active true :acceptabilityId snomed/Preferred :typeId snomed/Synonym})
        relationships (gen/sample (rf2/gen-relationship) n)
        refset-descriptors (gen/sample (rf2/gen-refset-descriptor-refset) n)]
    (log/debug "Creating temporary components in " release-path)
    (write-components release-path "sct2_Concept_Snapshot_GB1000000_20180401.txt" (conj concepts en-GB-refset))
    (write-components release-path "sct2_Description_Snapshot_GB1000000_20180401.txt" descriptions)
    (write-components release-path "sct2_Relationship_Snapshot_GB1000000_20180401.txt" relationships)
    (write-components release-path "der2_cRefset_LanguageSnapshot-en-GB_GB1000000_20180401.txt" en-GB)
    (write-components release-path "der2_cciRefset_RefsetDescriptorUKEDSnapshot_GB_20220316.txt" refset-descriptors)
    (hermes/import-snomed (str db-path) [(str release-path)])
    (hermes/compact (str db-path))
    (hermes/index (str db-path) "en-GB")
    (let [status (hermes/get-status (str db-path) :counts? true)]
      (is (= (count concepts) (get-in status [:components :concepts])))
      (is (= (count descriptions) (get-in status [:components :descriptions])))
      (is (= n (get-in status [:components :relationships])))
      (is (= (count (set (map :refsetId (concat en-GB-refset refset-descriptors)))) (get-in status [:components :refsets]))))))

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
    (hermes/index (str db-path))
    (with-open [svc (hermes/open (str db-path))]
      (let [en-GB-description-ids (set (map :referencedComponentId en-GB))
            en-US-description-ids (set (map :referencedComponentId en-US))]
        (is (= en-GB-refset (hermes/get-concept svc 999001261000000100)))
        (dorun (map #(is (contains? en-GB-description-ids (:id (hermes/get-preferred-synonym svc (:id %) "en-GB")))) concepts))
        (dorun (map #(is (contains? en-US-description-ids (:id (hermes/get-preferred-synonym svc (:id %) "en-US")))) concepts))))))


(deftest test-module-dependencies
  (let [{:keys [release-path db-path store-path]} *paths*
        template {:active true :sourceEffectiveTime (LocalDate/of 2014 1 31) :targetEffectiveTime (LocalDate/of 2014 1 31) :fields []}
        module-1 (gen/generate (rf2/gen-module-dependency-refset (assoc template :moduleId 900000000000207008 :referencedComponentId 900000000000012004)))
        module-2 (gen/generate (rf2/gen-module-dependency-refset (assoc template :moduleId 449080006 :referencedComponentId 900000000000012004)))
        module-3 (gen/generate (rf2/gen-module-dependency-refset (assoc template :moduleId 449080006 :referencedComponentId 900000000000207008)))
        en-US-refset (gen/generate (rf2/gen-concept {:id 900000000000509007 :active true}))
        descriptions (mapcat #(gen/sample (rf2/gen-description {:conceptId (:moduleId %) :typeId snomed/Synonym :active true})) [module-1 module-2 module-3])
        en-US (hgen/make-language-refset-items descriptions {:refsetId (:id en-US-refset) :active true :acceptabilityId snomed/Preferred :typeId snomed/Synonym})]
    (write-components release-path "sct2_Description_Snapshot_GB1000000_20180401.txt" descriptions)
    (write-components release-path "der2_cRefset_LanguageSnapshot-en-GB_GB1000000_20180401.txt" en-US)
    (write-components release-path "der2_ssRefset_ModuleDependencySnapshot_INT_20220731.txt" [module-1 module-2 module-3])
    (hermes/import-snomed (str db-path) [(str release-path)])
    (hermes/index (str db-path) "en-US")
    (with-open [svc (hermes/open (str db-path))]
      (is (every? :valid (hermes/module-dependencies svc)) "Synthetically generated module dependencies should be valid"))
    (let [module-4 (gen/generate (rf2/gen-module-dependency-refset (assoc template :moduleId 449080006 :referencedComponentId 999000011000000103)))]
      (write-components release-path "der2_ssRefset_ModuleDependencySnapshot_INT_20220731.txt" [module-4])
      (hermes/import-snomed (str db-path) [(str release-path)])
      (hermes/index (str db-path) "en-US")
      (with-open [svc (hermes/open (str db-path))]
        (is (not-every? :valid (hermes/module-dependencies svc))) "Dependencies should be invalid, as module 449080006 depends on a module that does not exist"))))

(comment
  (run-tests)
  (temporary-paths-fixture test-localisation :delete? false)
  (temporary-paths-fixture test-reify-refset))