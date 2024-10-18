(ns com.eldrix.hermes.search-test
  (:require [clojure.core.async :as async]
            [clojure.spec.gen.alpha :as gen]
            [clojure.test :refer [deftest is]]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.impl.lucene :as lucene]
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
             (into #{} (map :conceptId) (search/do-query-for-results (:searcher svc) q nil)))))))

(defn ch->set
  "Drain the clojure.core.async channel `ch` and return results as a set."
  [ch]
  (loop [results (transient #{})]
    (if-let [result (async/<!! ch)]
      (recur (conj! results result))
      (persistent! results))))

(defn test-query [svc q]
  (let [searcher (.-searcher svc)
        ch1 (async/chan)
        ch2 (async/chan)]
    (async/thread (lucene/stream-all searcher q ch1))
    (async/thread (lucene/stream-all* searcher q ch2))
    (is (= (set (lucene/search-all searcher q))
           (set (lucene/search-all* searcher q))
           (ch->set ch1)
           (ch->set ch2)) (str "Query returned different results" q))))

(deftest ^:live search-parallel
  (with-open [svc (hermes/open "snomed.db")]
    (let [concept-ids (take 5000 (shuffle (#'hermes/get-n-concept-ids svc 1000000)))]
      (doseq [concept-id concept-ids]
        (test-query svc (search/q-descendantOf concept-id))))))

(deftest ^:live test-query-and-constraint ;; if there is a query AND a constraint, they should be AND'ed together
  (with-open [svc (hermes/open "snomed.db")]
    (is (empty? (hermes/search svc {:constraint "<24700007" :query (search/q-concept-id 24700007)})))))

(comment
  (def svc (hermes/open "snomed.db"))
  (def searcher (.-searcher svc))
  (def q (search/q-descendantOf 24700007))   ;138875005
  (def q (search/q-descendantOrSelfOf 24700007))
  (hermes/expand-ecl svc "<24700007")
  (hermes/search svc {:constraint "<<24700007" :query (search/q-concept-id 24700007)})
  (require '[criterium.core :as crit])
  (crit/bench (lucene/search-all searcher q))
  (crit/bench (lucene/search-all* searcher q)))

