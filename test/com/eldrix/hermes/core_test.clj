(ns com.eldrix.hermes.core-test
  (:require [clojure.test :refer :all]
            [com.eldrix.hermes.core :as hermes]
            [clojure.set :as set]))

(defonce svc (atom {}))


(defn live-test-fixture [f]
  (reset! svc (hermes/open "snomed.db" {:quiet? true}))
  (f))

(use-fixtures :once live-test-fixture)

(deftest ^:live basic-gets
  (is (= 24700007 (.id (hermes/get-concept @svc 24700007))))
  (let [multiple-sclerosis (hermes/get-extended-concept @svc 24700007)]
      (is ((get-in multiple-sclerosis [:parentRelationships 116680003]) 6118003) "Multiple sclerosis is a type of CNS demyelinating disorder")))

(deftest ^:live test-reverse-map-range
  (let [synonyms (->> (hermes/reverse-map-range @svc 447562003 "I30")
                      (map :referencedComponentId)
                      (map #(:term (hermes/get-preferred-synonym @svc % "en"))))]
       (some #{"Viral pericarditis"} synonyms)))

(deftest ^:live test-map-into
  (let [mapped (hermes/map-into @svc [24700007 763794005 95883001] "118940003 OR 50043002 OR 40733004")]
    (is (= '(#{118940003} #{118940003} #{40733004 118940003}) mapped) "Mapping concepts to a subset defined by an ECL")))

(deftest ^:live test-ecl-contains
  (is (hermes/ecl-contains? @svc [24700007] "<<24700007") "Descendant or self expression should include self")
  (is (hermes/ecl-contains? @svc [816984002] "<<24700007") "Primary progressive multiple sclerosis is a type of MS"))

(deftest ^:live test-expand-historic
  (is (every? true? (->> (hermes/expand-ecl @svc "<<24700007")
                         (map #(hermes/get-concept @svc (:conceptId %)))
                         (map :active))) "ECL expansion should return only active concepts by default")
  (is (not (every? true? (->> (hermes/expand-ecl-historic @svc "<24700007")
                              (map #(hermes/get-concept @svc (:conceptId %)))
                              (map :active)))) "ECL with historic expansion should include inactive concepts"))

(deftest ^:live test-transitive-synonyms
  (let [synonyms (set (hermes/all-transitive-synonyms @svc "<<24700007"))
        all-children (hermes/get-all-children @svc 24700007)
        all-descriptions (set (mapcat #(hermes/get-descriptions @svc %) all-children))]
    (is (empty? (set/difference synonyms all-descriptions)))
    (is #{"Secondary progressive multiple sclerosis"} (map :term synonyms))))

(deftest ^:live test-search
  (is (contains? (set (map :conceptId (hermes/search @svc {:s "MND"}))) 37340000) "Search for MND should find motor neurone disease"))

