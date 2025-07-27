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
            [com.eldrix.hermes.impl.search :as search]
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
          headings (into (mapv name (keys (dissoc component :fields)))
                           (or field-headings (gen/generate (s/gen (s/coll-of ::hermes/non-blank-string :count (count (:fields component)))))))]
      (.write writer (str (str/join "\t" headings) \newline))
      (doall (->> components (map snomed/unparse) (map #(.write writer (str (str/join "\t" %) \newline))))))))

(deftest test-bad-import
  (let [{:keys [release-path]} *paths*
        ;; generate reference set items with fields not in the specification
        ;; if this were named der2_ciiiRefset_LanguageSnapshot... then it would work
        refsets (gen/sample (rf2/gen-language-refset {:fields [1 2 3]}))]
    (write-components release-path "der2_cRefset_LanguageSnapshot-en-GB_GB1000000_20180401.txt" refsets)
    (testing "Import of malformed reference set should throw an exception"
      (is (thrown? Throwable (let [ch (importer/load-snomed (str release-path))]
                               (loop [o (a/<!! ch)]
                                 (when (instance? Throwable o)
                                   (throw (ex-info "SNOMED CT loading error:" (ex-data o))))
                                 (when o
                                   (recur (a/<!! ch))))))))))



(deftest test-null-refset-field-import
  (let [{:keys [release-path db-path store-path]} *paths*
        items1 (gen/sample (rf2/gen-simple-refset {:fields [1 2 "a"]}))
        items2 (gen/sample (rf2/gen-simple-refset {:fields [nil 2 "a"]}))
        items3 (gen/sample (rf2/gen-simple-refset {:fields [1 nil "a"]}))
        items4 (gen/sample (rf2/gen-simple-refset {:fields [1 nil ""]}))
        items5 (gen/sample (rf2/gen-simple-refset {:fields [nil nil ""]}))
        items# (concat items1 items2 items3 items4 items5)]
    (write-components release-path "der2_iisRefset_SimpleSnapshot_INT_20230131.txt" items#)
    (hermes/import-snomed (str db-path) [(str release-path)])
    (hermes/compact (str db-path))
    (hermes/index (str db-path))
    (with-open [store (store/open-store (str store-path))]
      (doseq [item items#]
        (is (= item (store/refset-item store (:id item))))))))

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
      (is (= refset-concept (store/concept store (:id refset-concept))))
      (is (every? #(instance? SimpleRefsetItem %) refset-items))
      (doseq [item refset-items]                            ;; has every instance been reified to the correct type of reference set?
        (is (instance? AssociationRefsetItem (store/refset-item store (:id item))))))))

(deftest test-components
  (let [{:keys [release-path db-path]} *paths*
        n 2000
        en-GB-refset (gen/generate (rf2/gen-concept {:id 999001261000000100 :active true}))
        concepts (conj (gen/sample (rf2/gen-concept) (dec n)) en-GB-refset)
        descriptions (mapcat #(gen/sample (rf2/gen-description {:conceptId (:id %) :typeId snomed/Synonym :active true}) 2) concepts)
        descriptions-by-id (group-by :conceptId descriptions)
        en-GB (hgen/make-language-refset-items descriptions {:refsetId (:id en-GB-refset) :active true :acceptabilityId snomed/Preferred :typeId snomed/Synonym})
        relationships (gen/sample (rf2/gen-relationship) n)
        refset-descriptors (gen/sample (rf2/gen-refset-descriptor-refset) n)]
    (log/debug "Creating temporary components in " release-path)
    (is (some #(< 1024 (count (:term %))) descriptions) "There must be synthetic descriptions with very long terms (up to 4096 characters)")
    (write-components release-path "sct2_Concept_Snapshot_GB1000000_20180401.txt" (conj concepts en-GB-refset))
    (write-components release-path "sct2_Description_Snapshot_GB1000000_20180401.txt" descriptions)
    (write-components release-path "sct2_Relationship_Snapshot_GB1000000_20180401.txt" relationships)
    (write-components release-path "der2_cRefset_LanguageSnapshot-en-GB_GB1000000_20180401.txt" en-GB)
    (write-components release-path "der2_cciRefset_RefsetDescriptorUKEDSnapshot_GB_20220316.txt" refset-descriptors)
    (hermes/import-snomed (str db-path) [(str release-path)])
    (hermes/compact (str db-path))
    (hermes/index (str db-path))
    (with-open [svc (hermes/open (str db-path) {:default-locale "en-GB"})]
      (let [status (hermes/status* svc {:counts? true})
            ch (a/chan 1 (partition-all 500))]
        (a/thread (hermes/stream-all-concepts svc ch))
        (is (= (count concepts) (get-in status [:components :concepts])))
        (is (= (count descriptions) (get-in status [:components :descriptions])))
        (is (= n (get-in status [:components :relationships])))
        (is (= (count (set (map :refsetId (concat en-GB-refset refset-descriptors)))) (get-in status [:components :refsets])))
        (is (= (count concepts) (a/<!! (a/reduce (fn [acc v] (+ acc (count v))) 0 ch)))
            "Number of concepts streamed does not match total number of concepts")
        (doseq [{:keys [id] :as concept} concepts]
          (is (= concept (hermes/concept svc id)) "")
          (is (= (set (descriptions-by-id id)) (set (hermes/descriptions svc id)))))
        (doseq [{:keys [id term] :as description} descriptions]
          (is (= description (hermes/description svc id)))
          (let [results-by-id (search/do-query-for-results (.-searcher svc) (search/q-description-id id) nil)
                results-by-s (search/do-query-for-results (.-searcher svc) (search/q-term term) nil)
                result-by-id (first results-by-id)]
            (is (= 1 (count results-by-id)) (str "Search for description by id did not return a single result" {:d description :results results-by-id}))
            (is (= id (:id result-by-id)) (str "Failed to search for description" description))
            (is (= term (:term result-by-id)) (str "Did not get back same term for description" {:d description :result result-by-id}))
            (is (contains? (set (map :term results-by-s)) term))
            (is (contains? (set (map :id results-by-s)) id))))
        (doseq [{:keys [id] :as relationship} relationships]
          (is (= relationship (hermes/relationship svc id))))))))

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
      (log/info "installed reference sets:" (store/installed-reference-sets store)))
    (hermes/index (str db-path))
    (with-open [svc (hermes/open (str db-path))]
      (let [en-GB-description-ids (set (map :referencedComponentId en-GB))
            en-US-description-ids (set (map :referencedComponentId en-US))]
        (is (= en-GB-refset (hermes/concept svc 999001261000000100)))
        (run! #(is (contains? en-GB-description-ids (:id (hermes/preferred-synonym svc (:id %) "en-GB")))) concepts)
        (run! #(is (contains? en-US-description-ids (:id (hermes/preferred-synonym svc (:id %) "en-US")))) concepts)))))

(deftest test-module-dependencies
  (let [template {:active true :sourceEffectiveTime (LocalDate/of 2014 1 31) :targetEffectiveTime (LocalDate/of 2014 1 31) :fields []}
        module-1 (gen/generate (rf2/gen-module-dependency-refset (assoc template :moduleId 900000000000207008 :referencedComponentId 900000000000012004)))
        module-2 (gen/generate (rf2/gen-module-dependency-refset (assoc template :moduleId 449080006 :referencedComponentId 900000000000012004)))
        module-3 (gen/generate (rf2/gen-module-dependency-refset (assoc template :moduleId 449080006 :referencedComponentId 900000000000207008)))
        modules [module-1 module-2 module-3]]
    (is (every? :valid (hermes/module-dependencies* [module-1 module-2 module-3])) "Synthetically generated module dependencies should be valid")
    (let [module-4 (gen/generate (rf2/gen-module-dependency-refset (assoc template :moduleId 449080006 :referencedComponentId 999000011000000103)))]
      (is (not-every? :valid (hermes/module-dependencies* (conj modules module-4)))) "Dependencies should be invalid, as module 449080006 depends on a module that does not exist")))

(comment
  (run-tests)
  (temporary-paths-fixture test-localisation :delete? false)
  (temporary-paths-fixture test-reify-refset))
