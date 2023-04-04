(ns com.eldrix.hermes.api-test
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [is deftest use-fixtures]]
            [com.eldrix.hermes.core :as hermes])
  (:import [com.eldrix.hermes.client Hermes]))


(stest/instrument)
(def ^:dynamic *hermes* nil)

(defn live-test-fixture [f]
  (binding [*hermes* (Hermes/openLocal "snomed.db")]
    (f)
    (.close *hermes*)))

(use-fixtures :once live-test-fixture)

(deftest ^:live basic-gets
  (is (= 24700007 (:id (.concept *hermes* 24700007))))
  (is (= [24700007] (mapv :id (.concepts *hermes* [24700007]))))
  (is (= 24700007 (get-in (.extendedConcept *hermes* 24700007) [:concept :id])))
  (is ((set (map :term (:descriptions (.extendedConcept *hermes* 24700007)))) "Multiple sclerosis")))

(deftest ^:live ecl
  (is (= #{24700007} (set (map :conceptId (.expandEcl *hermes* "24700007" false)))))
  (is (seq (.intersectEcl *hermes* [24700007] "<<24700007")))
  (is (empty? (.intersectEcl *hermes* [24700007] "<24700007"))))

(deftest ^:live concrete-values
  (is (= #{"#62.5" "#1" "#250" "#2"} (set (map #(.value %) (.concreteValues (.extendedConcept *hermes* 1197141004)))))
      "Co-amoxiclav 62.5/250"))

(comment
  (def *hermes* (Hermes/openLocal "snomed.db")))

