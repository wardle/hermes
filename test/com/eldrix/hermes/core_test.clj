(ns com.eldrix.hermes.core-test
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.eldrix.hermes.core :as hermes]))

(stest/instrument)

(def ^:dynamic *svc* nil)

(defn live-test-fixture [f]
  (binding [*svc* (hermes/open "snomed.db")]
    (f)
    (hermes/close *svc*)))

(use-fixtures :once live-test-fixture)

(deftest ^:live basic-gets
  (is (= 24700007 (.id (hermes/concept *svc* 24700007))))
  (let [multiple-sclerosis (hermes/extended-concept *svc* 24700007)]
    (is ((get-in multiple-sclerosis [:parentRelationships 116680003]) 6118003) "Multiple sclerosis is a type of CNS demyelinating disorder")
    (is (s/valid? :info.snomed/Concept (:concept multiple-sclerosis)))
    (is (every? true? (map #(s/valid? :info.snomed/Description %) (:descriptions multiple-sclerosis))))))

(deftest ^:live test-reverse-map-prefix
  (let [synonyms (->> (hermes/member-field-prefix *svc* 447562003 "mapTarget" "I30")
                      (map #(:term (hermes/preferred-synonym *svc* % "en"))))]
    (is (some #{"Viral pericarditis"} synonyms)))
  (is (->> (hermes/reverse-map *svc* 447562003 "G35")
           (map :mapTarget)
           (every? #(.startsWith % "G35")))
      "Reverse map prefix returns items with a map target not fulfulling original request ")
  (is (->> (hermes/reverse-map-prefix *svc* 447562003 "I30")
           (map :mapTarget)
           (every? #(.startsWith % "I30")))
      "Reverse map prefix returns items with a map target not fulfulling original request "))

(deftest ^:live test-cross-map
  (is (contains? (set (map :mapTarget (hermes/component-refset-items *svc* 24700007 447562003))) "G35") "Multiple sclerosis should map to ICD code G35"))

(deftest ^:live test-map-into
  (let [mapped (hermes/map-into *svc* [24700007 763794005 95883001] "118940003 OR 50043002 OR 40733004")]
    (is (= '(#{118940003} #{118940003} #{40733004 118940003}) mapped) "Mapping concepts to a subset defined by an ECL")))

(deftest ^:live test-ecl-contains
  (is (hermes/ecl-contains? *svc* [24700007] "<<24700007") "Descendant or self expression should include self")
  (is (hermes/ecl-contains? *svc* [816984002] "<<24700007") "Primary progressive multiple sclerosis is a type of MS")
  (is (hermes/ecl-contains? *svc* [24700007] "^447562003")) "Multiple sclerosis should be in the ICD-10 complex map reference set")

(deftest ^:live test-intersect-ecl
  (is (= #{24700007} (hermes/intersect-ecl *svc* [24700007] "<<24700007")) "Descendant or self expression should include self")
  (is (= #{816984002} (hermes/intersect-ecl *svc* [816984002] "<<24700007")) "Primary progressive multiple sclerosis is a type of MS")
  (is (= #{24700007} (hermes/intersect-ecl *svc* [24700007] "^447562003")) "Multiple sclerosis should be in the ICD-10 complex map reference set")
  (is (= #{24700007} (hermes/intersect-ecl *svc* #{315560000 24700007} "<64572001")) "Born in Wales is not a type of disease")
  (let [concept-ids-1 (set (map :conceptId (hermes/search *svc* {:s "m"})))
        concept-ids-2 (hermes/intersect-ecl *svc* concept-ids-1 "<<138875005")]
    (is (empty? (set/difference concept-ids-1 concept-ids-2)) "All concepts should be children of root SNOMED CT")))

(deftest ^:live test-expand-historic
  (is (every? true? (->> (hermes/expand-ecl *svc* "<<24700007")
                         (map #(hermes/concept *svc* (:conceptId %)))
                         (map :active))) "ECL expansion should return only active concepts by default")
  (is (not (every? true? (->> (hermes/expand-ecl-historic *svc* "<24700007")
                              (map #(hermes/concept *svc* (:conceptId %)))
                              (map :active)))) "ECL with historic expansion should include inactive concepts"))

(deftest ^:live test-transitive-synonyms
  (let [synonyms (set (hermes/transitive-synonyms *svc* "<<24700007"))
        all-children (hermes/all-children *svc* 24700007)
        all-descriptions (set (mapcat #(hermes/descriptions *svc* %) all-children))]
    (is (empty? (set/difference synonyms all-descriptions)))
    (is #{"Secondary progressive multiple sclerosis"} (map :term synonyms))))

(deftest ^:live test-search
  (is (contains? (set (map :conceptId (hermes/search *svc* {:s "MND"}))) 37340000) "Search for MND should find motor neurone disease")
  (is (= 5 (count (map :conceptId (hermes/search *svc* {:s "multiple sclerosis" :max-hits 5})))))
  (is (thrown? Exception (hermes/search *svc* {:s "huntington" :max-hits "abc"}))))

(deftest ^:live test-with-historical
  (is (:active (hermes/concept *svc* 24700007)))
  (is (not (:active (hermes/concept *svc* 586591000000100))))
  (is (some false? (map #(:active (hermes/concept *svc* %)) (hermes/with-historical *svc* [24700007]))))
  (is (contains? (hermes/with-historical *svc* [586591000000100]) 586591000000100))
  (is (= (hermes/with-historical *svc* [24700007]) (hermes/with-historical *svc* [586591000000100]))))

(deftest ^:live test-refset-members
  (is (seq (hermes/refset-members *svc* 723264001))
      "Lateralizable body structure reference set should have at least one member"))

(deftest ^:live test-module-dependencies
  (is (every? :valid (hermes/module-dependencies *svc*))))

#_(deftest ^:live test-historical-assumptions
    (let [counts (#'hermes/historical-association-counts *svc*)]
      (is (= 1 (get counts snomed/ReplacedByReferenceSet)))))

