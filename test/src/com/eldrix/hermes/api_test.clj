(ns com.eldrix.hermes.api-test
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [is deftest use-fixtures]])
  (:import [com.eldrix.hermes.client Hermes Hermes$SubsumptionResult Hermes$SubsumptionMode]
           [com.eldrix.hermes.sct IExpression]))


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

(deftest ^:live refset-items
  (let [refset-ids (.componentRefsetIds *hermes* 24700007)]
    (is (seq refset-ids) "Multiple sclerosis should be a member of at least one refset"))
  (let [items (.componentRefsetItems *hermes* 24700007)]
    (is (seq items) "Should return refset items for multiple sclerosis")
    (is (every? #(.active %) (filter #(.active %) items))))
  (let [installed (.installedReferenceSets *hermes*)]
    (is (seq installed) "Should have installed reference sets")
    (is (every? pos? installed)))
  (let [refset-id (first (.componentRefsetIds *hermes* 24700007))]
    (when refset-id
      (let [members (.refsetMembers *hermes* refset-id)]
        (is (seq members) "Refset should have members")
        (is (contains? members 24700007) "MS should be a member"))
      (let [items (.componentRefsetItems *hermes* 24700007 refset-id)]
        (is (seq items) "Should return items for specific refset"))
      (let [extended (.componentRefsetItemsExtended *hermes* 24700007 refset-id)]
        (is (seq extended) "Should return extended items for specific refset")))))

(deftest ^:live parse-and-render-expression
  (let [expr (.parseExpression *hermes* "24700007 |Multiple sclerosis|")]
    (is (instance? IExpression expr))
    (is (.contains (.toString expr) "24700007")
        "toString should render the expression with concept id"))
  (let [expr (.parseExpression *hermes* "=== 24700007")]
    (is (instance? IExpression expr))
    (is (.contains (.toString expr) "24700007")))
  (let [rendered (.renderExpression *hermes* 24700007)]
    (is (string? rendered))
    (is (.contains rendered "24700007")))
  (let [rendered (.renderExpression *hermes* 24700007 "en-GB")]
    (is (.contains rendered "Multiple sclerosis"))))

(deftest ^:live validate-expression
  (is (.validateExpression *hermes* (.parseExpression *hermes* "24700007"))
      "Valid concept should pass validation")
  (is (.validateExpression *hermes* 24700007)
      "Valid concept id should pass validation")
  (is (not (.validateExpression *hermes* (.parseExpression *hermes* "64572001 |Disease| : 24700007 |Multiple sclerosis| = 24700007")))
      "Expression with non-attribute concept used as attribute should fail validation"))

(deftest ^:live subsumes
  (is (= Hermes$SubsumptionResult/EQUIVALENT
         (.subsumes *hermes* 24700007 24700007))
      "A concept should be equivalent to itself")
  (is (= Hermes$SubsumptionResult/SUBSUMED_BY
         (.subsumes *hermes* 24700007 6118003))
      "Multiple sclerosis should be subsumed by demyelinating disease of CNS")
  (let [ms (.parseExpression *hermes* "24700007")
        ms2 (.parseExpression *hermes* "24700007")]
    (is (= Hermes$SubsumptionResult/EQUIVALENT
           (.subsumes *hermes* ms ms2))
        "Same expression should be equivalent")
    (is (= Hermes$SubsumptionResult/EQUIVALENT
           (.subsumes *hermes* ms ms2 Hermes$SubsumptionMode/STRUCTURAL))
        "Same expression should be equivalent in structural mode")))

(comment
  (def *hermes* (Hermes/openLocal "snomed.db")))

