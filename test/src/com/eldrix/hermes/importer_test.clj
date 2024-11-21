(ns com.eldrix.hermes.importer-test
  (:require
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.spec.gen.alpha :as gen]
   [clojure.test :refer [deftest is testing]]
   [com.eldrix.hermes.importer :as importer]
   [com.eldrix.hermes.rf2 :as rf2])
  (:import (java.time LocalDate)))

(deftest parse-filename
  (testing "nil filename"
    (is (nil? (importer/parse-filename nil))))
  (testing "concept filename as string"
    (let [{:keys [format version-date content-subtype type country-code identifier component]} (importer/parse-filename "sct2_Concept_Snapshot_INT_20230131.txt")]
      (is (= "Concept" component))
      (is (= "INT" country-code))
      (is (= :info.snomed/Concept identifier))
      (is (= "2" format))
      (is (= "sct" type))
      (is (= (LocalDate/of 2023 1 31) version-date))))
  (testing "description filename as URL"
    (let [{:keys [identifier]} (importer/parse-filename (java.net.URL. "file://Terminology/sct2_Description_Snapshot-en_INT_20230131.txt"))]
      (is (= :info.snomed/Description identifier))))
  (testing "relationship concrete values filename as file"
    (let [{:keys [identifier]} (importer/parse-filename (io/file "./Terminology/sct2_RelationshipConcreteValues_Snapshot_INT_20230131.txt"))]
      (is (= :info.snomed/RelationshipConcreteValues identifier)))))

(defn import-file
  "Import a SNOMED file"
  [f]
  (let [ch (async/chan)]
    (async/thread
      (importer/process-file f ch)
      (async/close! ch))
    (async/<!! ch)))

(deftest import-concepts
  (let [{:keys [type parser headings data]} (import-file (io/resource "example-snapshot/Terminology/sct2_Concept_Snapshot_INT_20230131.txt"))]
    (is (= :info.snomed/Concept type))))

(deftest import-refset
  (let [{:keys [type parser headings data] :as f} (import-file (io/resource "example-snapshot/Refset/Map/der2_iisssccRefset_ExtendedMapSnapshot_INT_20230131.txt"))]
    (is (= :info.snomed/ExtendedMapRefset type))
    (is (= ["id" "effectiveTime" "active" "moduleId" "refsetId" "referencedComponentId"
            "mapGroup" "mapPriority" "mapRule" "mapAdvice" "mapTarget"
            "correlationId" "mapCategoryId"] headings))))

(deftest import-custom-refset-nil-values
  (let [{:keys [type parser headings data] :as f} (import-file  (io/resource "example-snapshot/Refset/Map/der2_ssRefset_SimpleMapWithDescriptionSnapshot_12345_20241021.txt"))]
    (is (= :info.snomed/SimpleMapRefset type))
    (is (= ["id" "effectiveTime" "active" "moduleId" "refsetId" "referencedComponentId"
            "mapTarget" "mapTargetDescription"] headings))
    (is (= ["000d91ce-aae4-4f9e-ad06-51be576becd6" "20241021"
            "1" "195941000112101" "22671000001102"
            "1434181000001106" "ABC01256Q" ""] (first data))
        "Empty last column should be returned as empty string")))

(comment
  (require '[clojure.data.csv :as csv])
  (csv/read-csv "hi\tthere\tand\thow\tare\tyou?\t" :separator \tab)
  (def f (io/resource "example-snapshot/Terminology/sct2_Concept_Snapshot_INT_20230131.txt"))
  (type f)
  (io/as-file f)
  (importer/parse-filename "sct2_Concept.txt")
  (importer/parse-filename (java.net.URL. "https://wibble.com/sct_Concept_Snapshot_INT_20230131.txt"))
  (importer/parse-filename f)
  (importer/parse-filename nil)
  (def ch (async/chan))
  (async/thread
    (importer/process-file f ch)
    (async/close! ch))
  (def ch (importer/load-snomed (io/resource "example-snapshot/")))
  (async/<!! ch)

  (gen/sample (rf2/gen-simple-map-refset {:fields [""]})))

