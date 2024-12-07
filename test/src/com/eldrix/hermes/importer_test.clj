(ns com.eldrix.hermes.importer-test
  (:require
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [com.eldrix.hermes.importer :as imp])
  (:import (java.time LocalDate)))

(deftest parse-filenames
  (testing "nil filename"
    (is (nil? (imp/parse-filename nil))))
  (testing "concept filename as string"
    (let [{fmt :format t :type :keys [version-date country-code identifier component]} (imp/parse-filename "sct2_Concept_Snapshot_INT_20230131.txt")]
      (is (= "Concept" component))
      (is (= "INT" country-code))
      (is (= :info.snomed/Concept identifier))
      (is (= "2" fmt))
      (is (= "sct" t))
      (is (= (LocalDate/of 2023 1 31) version-date))))
  (testing "description filename as URL"
    (let [{:keys [identifier]} (imp/parse-filename (java.net.URL. "file://Terminology/sct2_Description_Snapshot-en_INT_20230131.txt"))]
      (is (= :info.snomed/Description identifier))))
  (testing "relationship concrete values filename as file"
    (let [{:keys [identifier]} (imp/parse-filename (io/file "./Terminology/sct2_RelationshipConcreteValues_Snapshot_INT_20230131.txt"))]
      (is (= :info.snomed/RelationshipConcreteValues identifier)))))

(deftest import-terminology
  (testing "concepts"
    (let [{t :type} (imp/import-file (io/resource "example-snapshot/Terminology/sct2_Concept_Snapshot_INT_20230131.txt"))]
      (is (= :info.snomed/Concept t))))
  (testing "descriptions"
    (let [{t :type} (imp/import-file (io/resource "example-snapshot/Terminology/sct2_Description_Snapshot-en_INT_20230131.txt"))]
      (is (= :info.snomed/Description t)))))

(deftest import-refset
  (let [{t :type, headings :headings} (imp/import-file (io/resource "example-snapshot/Refset/Map/der2_iisssccRefset_ExtendedMapSnapshot_INT_20230131.txt"))]
    (is (= :info.snomed/ExtendedMapRefset t))
    (is (= ["id" "effectiveTime" "active" "moduleId" "refsetId" "referencedComponentId"
            "mapGroup" "mapPriority" "mapRule" "mapAdvice" "mapTarget"
            "correlationId" "mapCategoryId"] headings))))

(deftest import-custom-refset-nil-values
  (let [{t :type :keys [headings data]} (imp/import-file (io/resource "example-snapshot/Refset/Map/der2_ssRefset_SimpleMapWithDescriptionSnapshot_12345_20241021.txt"))]
    (is (= :info.snomed/SimpleMapRefset t))
    (is (= ["id" "effectiveTime" "active" "moduleId" "refsetId" "referencedComponentId"
            "mapTarget" "mapTargetDescription"] headings))
    (is (= ["000d91ce-aae4-4f9e-ad06-51be576becd6" "20241021"
            "1" "195941000112101" "22671000001102"
            "1434181000001106" "ABC01256Q" ""] (first data))
        "Empty last column should be returned as empty string")))



