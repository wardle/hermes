(ns com.eldrix.hermes.snomed-test
  (:require
    [clojure.test :refer :all]
    [com.eldrix.hermes.verhoeff :as verhoeff]
    [com.eldrix.hermes.snomed :as snomed]
    [clojure.java.io :as io])
  (:import [java.time LocalDate]))


(deftest test-filenames
  (let [examples (slurp (io/resource "com/eldrix/hermes/example-snomed-file-list.txt"))
        parsed (map #(hash-map :filename % :data (snomed/parse-snomed-filename %)) (clojure.string/split examples #"\n"))]
    (doseq [f parsed]
      (is (not (nil? (:data f))) (str "couldn't parse filename" f)))))

(deftest test-filenames2
  (let [p (snomed/parse-snomed-filename "der2_cRefset_AttributeValueSnapshot_INT_20180131.txt")]
    (is (= "AttributeValue" (:summary p)))
    (is (= "Snapshot" (:release-type p)))
    (is (nil? (:language-code p))))
  (let [p (snomed/parse-snomed-filename "sct2_Description_Snapshot-en_INT_20180131.txt")]
    (is (= "Snapshot" (:release-type p)))
    (is (= "en" (:language-code p)))))


(deftest test-partition
  (is (= :info.snomed/Concept (snomed/identifier->type 247000007)))
  (is (= :info.snomed/Concept (snomed/identifier->type " 247000007")))
  (is (= :info.snomed/Description (snomed/identifier->type 110017)))
  (is (= :info.snomed/Relationship (snomed/identifier->type 100022))))


(def core-examples
  [{:filename "sct2_Concept_Full_INT_20180131.txt"
    :type     :info.snomed/Concept
    :data     [["100005" "20020131" "0" "900000000000207008" "900000000000074008"]]}
   {:filename "sct2_Description_Full-en_INT_20180131.txt"
    :type     :info.snomed/Description
    :data     [["101013" "20020131" "1" "900000000000207008" "126813005" "en" "900000000000013009"
                "Neoplasm of anterior aspect of epiglottis" "900000000000020002"]]}
   {:filename "sct2_Relationship_Full_INT_20180131.txt"
    :type     :info.snomed/Relationship
    :data     [["100022" "20020131" "1" "900000000000207008" "100000000" "102272007" "0" "116680003" "900000000000011006" "900000000000451002"]]}
   ])

(deftest test-valid?
  (let [ms (snomed/->Concept 24700007 (LocalDate/now) true 0 0)]
    (is (verhoeff/valid? (:id ms)))
    (is (= :info.snomed/Concept (snomed/identifier->type (:id ms))))))

(defn test-example [m]
  (let [t (:type m)
        r (snomed/parse-batch m)
        v (doall (map #(= t (snomed/identifier->type (:id %))) r))]
    (is (every? true? v))))

(deftest test-parsing
  (doall (map test-example core-examples)))


(def refset-examples
  [{:filename "der2_Refset_SimpleFull_INT_20180131.txt"
    :type     :info.snomed/SimpleRefset
    :data     [["800aa109-431f-4407-a431-6fe65e9db160" "20170731" "1" "900000000000207008" "723264001" "731819006"]]}])

(comment
  (run-tests)
  (def examples-list
    (slurp (clojure.java.io/resource "com/eldrix/hermes/example-snomed-file-list.txt")))
  (def examples (clojure.string/split examples-list #"\n"))
  (clojure.pprint/print-table
    (doall (map snomed/parse-snomed-filename examples)))

  (snomed/parse-snomed-filename "der2_iisssccRefset_ExtendedMapFull_INT_20180131.txt")
  (snomed/parse-simple-refset-item (first (:data (first refset-examples))))
  )
