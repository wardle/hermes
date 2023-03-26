(ns com.eldrix.hermes.search-test
  (:require [clojure.spec.gen.alpha :as gen]
            [clojure.test :refer [deftest is]]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.impl.search :as search]))

(def example-results-1
  [{:id            464271012
    :conceptId     322236009
    :term          "Paracetamol 500mg tablet"
    :preferredTerm "Paracetamol 500 mg oral tablet"}
   {:id            1236200010
    :conceptId     322236009
    :term          "Paracetamol 500mg tablet"
    :preferredTerm "Paracetamol 500 mg oral tablet"}
   {:id            464300011
    :conceptId     322280009
    :term          "Paracetamol 500mg capsule"
    :preferredTerm "Paracetamol 500 mg oral capsule"}
   {:id            1236213016
    :conceptId     322280009
    :term          "Paracetamol 500mg capsule"
    :preferredTerm "Paracetamol 500 mg oral capsule"}
   {:id            2154521017
    :conceptId     322280009
    :term          "Paracetamol 500mg capsule"
    :preferredTerm "Paracetamol 500 mg oral capsule"}])

(deftest dup-1
  (let [results (gen/sample (search/gen-result))]   ;; this will ALWAYS provide a set of unique results
    (is (= (count results) (count (search/remove-duplicates search/duplicate-result? results)))
        "Non-duplicate results removed"))
  (is (= 2 (count (search/remove-duplicates search/duplicate-result? example-results-1)))
      "Duplicate results not removed"))

(deftest ^:live search
  (with-open [svc (hermes/open "snomed.db")]
    (let [q (search/q-descendantOrSelfOf 24700007)]
      (is (= (search/do-query-for-concept-ids (:searcher svc) q)
             (into #{} (map :conceptId) (search/do-query-for-results (:searcher svc) q)))))))