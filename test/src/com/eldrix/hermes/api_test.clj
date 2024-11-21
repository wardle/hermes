(ns com.eldrix.hermes.api-test
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [is deftest use-fixtures]])
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
  (is (empty? (.intersectEcl *hermes* [24700007] "<24700007")))
  (is (.isValidEcl *hermes* "<24700007"))
  (let [en-gb (map :term (.expandEclPreferred *hermes* "<<24700007" "en-GB"))
        expected-gb #{"Paediatric multiple sclerosis"}
        en-us (map :term (.expandEclPreferred *hermes* "<<24700007" "en-US"))
        expected-us #{"Pediatric multiple sclerosis"}]
    (is (some expected-gb en-gb))
    (is (some expected-us en-us))
    (is (not (some expected-gb en-us)))
    (is (not (some expected-us en-gb)))))

(deftest ^:live synonyms
  (let [lang-refset-ids (.matchLocale *hermes* "en-US")
        en-us (.synonyms *hermes* 80146002 lang-refset-ids)]
    (is (some #{"Appendectomy"} en-us))
    (is (empty? (filter #{"Appendicectomy"} en-us)))))


(deftest ^:live concrete-values
  (is (= #{"#62.5" "#1" "#250" "#2"} (set (map #(.value %) (.concreteValues (.extendedConcept *hermes* 1197141004)))))
      "Co-amoxiclav 62.5/250"))

(comment
  (def *hermes* (Hermes/openLocal "snomed.db")))

