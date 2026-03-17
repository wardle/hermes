(ns com.eldrix.hermes.core-test
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.impl.reasoner :as reasoner]))

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

(deftest ^:live test-icd10-map-installed
  (let [installed (hermes/installed-reference-sets *svc*)]
    (is (or (contains? installed 447562003)
            (contains? installed 999002271000000101))
        "At least one ICD-10 complex map reference set must be installed (international: 447562003 or UK: 999002271000000101)")))

(deftest ^:live test-reverse-map-prefix
  (when (contains? (hermes/installed-reference-sets *svc*) 447562003)
    (let [synonyms (->> (hermes/member-field-prefix *svc* 447562003 "mapTarget" "I30")
                        (map #(:term (hermes/preferred-synonym *svc* % "en"))))]
      (is (some #{"Viral pericarditis"} synonyms)))
    (is (->> (hermes/reverse-map *svc* 447562003 "G35")
             (map :mapTarget)
             (every? #(.startsWith % "G35")))
        "Reverse map prefix returns items with a map target not fulfilling original request ")
    (is (->> (hermes/reverse-map-prefix *svc* 447562003 "I30")
             (map :mapTarget)
             (every? #(.startsWith % "I30")))
        "Reverse map prefix returns items with a map target not fulfulling original request ")))

(deftest ^:live test-cross-map
  (when (contains? (hermes/installed-reference-sets *svc*) 447562003)
    (is (contains? (set (map :mapTarget (hermes/component-refset-items *svc* 24700007 447562003))) "G35") "Multiple sclerosis should map to ICD code G35")))

(deftest ^{:live true :uk true} test-reverse-map-prefix-uk
  (when (contains? (hermes/installed-reference-sets *svc*) 999002271000000101)
    (is (seq (hermes/member-field-prefix *svc* 999002271000000101 "mapTarget" "I30"))
        "Should find concepts mapping to I30* in UK ICD-10")
    (is (->> (hermes/reverse-map *svc* 999002271000000101 "G35X")
             (map :mapTarget)
             (every? #(.startsWith % "G35")))
        "Reverse map should return items with matching map target prefix")
    (is (->> (hermes/reverse-map-prefix *svc* 999002271000000101 "I30")
             (map :mapTarget)
             (every? #(.startsWith % "I30")))
        "Reverse map prefix should return items with matching map target prefix")))

(deftest ^{:live true :uk true} test-cross-map-uk
  (when (contains? (hermes/installed-reference-sets *svc*) 999002271000000101)
    (is (contains? (set (map :mapTarget (hermes/component-refset-items *svc* 24700007 999002271000000101))) "G35X")
        "Multiple sclerosis should map to ICD code G35X in UK edition")))

(deftest ^:live test-map-into
  (let [mapped (hermes/map-into *svc* [24700007 763794005 95883001] "118940003 OR 50043002 OR 40733004")]
    (is (= '(#{118940003} #{118940003} #{40733004 118940003}) mapped) "Mapping concepts to a subset defined by an ECL")))

(deftest ^:live test-ecl-contains
  (is (hermes/ecl-contains? *svc* [24700007] "<<24700007") "Descendant or self expression should include self")
  (is (hermes/ecl-contains? *svc* [816984002] "<<24700007") "Primary progressive multiple sclerosis is a type of MS")
  (when (contains? (hermes/installed-reference-sets *svc*) 447562003)
    (is (hermes/ecl-contains? *svc* [24700007] "^447562003") "Multiple sclerosis should be in the ICD-10 complex map reference set"))
  (when (contains? (hermes/installed-reference-sets *svc*) 999002271000000101)
    (is (hermes/ecl-contains? *svc* [24700007] "^999002271000000101") "Multiple sclerosis should be in the UK ICD-10 complex map reference set")))

(deftest ^:live test-intersect-ecl
  (is (= #{24700007} (hermes/intersect-ecl *svc* [24700007] "<<24700007")) "Descendant or self expression should include self")
  (is (= #{24700007} ((hermes/intersect-ecl-fn *svc* "<<24700007") [24700007])) "Descendant or self expression should include self")
  (is (= #{816984002} (hermes/intersect-ecl *svc* [816984002] "<<24700007")) "Primary progressive multiple sclerosis is a type of MS")
  (is (= #{816984002} ((hermes/intersect-ecl-fn *svc* "<<24700007") [816984002])) "Primary progressive multiple sclerosis is a type of MS")
  (when (contains? (hermes/installed-reference-sets *svc*) 447562003)
    (is (= #{24700007} (hermes/intersect-ecl *svc* [24700007] "^447562003")) "Multiple sclerosis should be in the ICD-10 complex map reference set")
    (is (= #{24700007} ((hermes/intersect-ecl-fn *svc* "^447562003") [24700007])) "Multiple sclerosis should be in the ICD-10 complex map reference set"))
  (when (contains? (hermes/installed-reference-sets *svc*) 999002271000000101)
    (is (= #{24700007} (hermes/intersect-ecl *svc* [24700007] "^999002271000000101")) "Multiple sclerosis should be in the UK ICD-10 complex map reference set")
    (is (= #{24700007} ((hermes/intersect-ecl-fn *svc* "^999002271000000101") [24700007])) "Multiple sclerosis should be in the UK ICD-10 complex map reference set"))
  (is (= #{24700007} (hermes/intersect-ecl *svc* #{315560000 24700007} "<64572001")) "Born in Wales is not a type of disease")
  (is (= #{24700007} ((hermes/intersect-ecl-fn *svc* "<64572001") #{315560000 24700007})) "Born in Wales is not a type of disease")
  (let [concept-ids-1 (set (map :conceptId (hermes/search *svc* {:s "m"})))
        concept-ids-2 (hermes/intersect-ecl *svc* concept-ids-1 "<<138875005")
        concept-ids-3 ((hermes/intersect-ecl-fn *svc* "<<138875005") concept-ids-1)]
    (is (empty? (set/difference concept-ids-1 concept-ids-2)) "All concepts should be children of root SNOMED CT")
    (is (empty? (set/difference concept-ids-1 concept-ids-3)) "All concepts should be children of root SNOMED CT")))

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
    (is (seq (filter #{"Secondary progressive multiple sclerosis"} (map :term synonyms))))))

(deftest ^:live test-search
  (let [ret-spec (:ret (s/get-spec `hermes/search))
        results (hermes/search *svc* {:s "MND"})]
    (is (s/valid? ret-spec results))
    (is (contains? (set (map :conceptId results)) 37340000) "Search for MND should find motor neurone disease"))
  (is (= 5 (count (hermes/search *svc* {:s "multiple sclerosis" :max-hits 5}))))
  (is (thrown? Exception (hermes/search *svc* {:s "huntington" :max-hits "abc"}))))

(deftest ^:live test-search-concept-ids
  (let [ret-spec (:ret (s/get-spec `hermes/search-concept-ids))
        r1 (vec (hermes/search-concept-ids *svc* {:accept-language "en-US"} [24700007 37340000 80146002]))
        r2 (vec (hermes/search-concept-ids *svc* {:accept-language "en-GB"} [24700007 37340000 80146002]))]
    (is (s/valid? ret-spec r1))
    (is (s/valid? ret-spec r2))
    (is (= 24700007 (get-in r1 [0 :conceptId]) (get-in r2 [0 :conceptId])))
    (is (= "Multiple sclerosis" (get-in r1 [0 :term]) (get-in r2 [0 :term])))
    (is (= 37340000 (get-in r1 [1 :conceptId]) (get-in r2 [1 :conceptId])))
    (is (= "Motor neuron disease" (get-in r1 [1 :term]) (get-in r2 [1 :term])))
    (is (= 80146002 (get-in r1 [2 :conceptId]) (get-in r2 [2 :conceptId])))
    (is (= "Appendectomy" (get-in r1 [2 :term])))
    (is (= "Appendicectomy" (get-in r2 [2 :term])))))

(deftest ^{:live true :uk true} test-localised-synonyms
  (let [en-GB (hermes/match-locale *svc* "en-GB")
        en-US (hermes/match-locale *svc* "en-US")
        r1 (hermes/synonyms *svc* 80146002)
        r2 (hermes/synonyms *svc* 80146002 en-GB)
        r3 (hermes/synonyms *svc* 80146002 en-US)]
    (is (set/subset? (set r2) (set r1)))
    (is (set/subset? (set r3) (set r1)))
    (is (not= r2 r3))
    (is (seq (filter #{"Appendectomy"} (map :term r2))) "Appendectomy should be an acceptable term for en-GB locale")
    (is (seq (filter #{"Appendicectomy"} (map :term r2))) "Appendicectomy should be a preferred term for en-GB locale")
    (is (seq (filter #{"Appendectomy"} (map :term r3))) "Appendectomy should be a preferred term for en-US locale")))

(deftest ^{:live true :uk true} test-with-historical
  (is (:active (hermes/concept *svc* 24700007)))
  (is (not (:active (hermes/concept *svc* 586591000000100))))
  (is (some false? (map #(:active (hermes/concept *svc* %)) (hermes/with-historical *svc* [24700007]))))
  (is (contains? (hermes/with-historical *svc* [586591000000100]) 586591000000100))
  (is (= (hermes/with-historical *svc* [24700007]) (hermes/with-historical *svc* [586591000000100]))))

(deftest ^:live test-refset-members
  (is (seq (hermes/refset-members *svc* 723264001))
      "Lateralizable body structure reference set should have at least one member"))

#_(deftest ^:live test-module-dependencies
    (is (every? :valid (hermes/module-dependencies *svc*))))

(deftest ^:live test-term-folding
  (testing "Search parameters"
    (is (= "hjarta" (:s (#'hermes/make-search-params *svc* {:s "hjärta" :accept-language "en"})))
        "In English, a search against the folded index should fold ä")
    (is (= "hjärta" (:s (#'hermes/make-search-params *svc* {:s "hjärta" :language-refset-ids [46011000052107]})))
        "In Swedish, a search against the folded index should not fold ä")))

#_(deftest ^:live test-historical-assumptions
    (let [counts (#'hermes/historical-association-counts *svc*)]
      (is (= 1 (get counts snomed/ReplacedByReferenceSet)))))

(deftest ^:live test-render-expression
  (testing "passthrough preserves existing terms"
    (is (clojure.string/includes? (hermes/render-expression* *svc* "24700007 |Multiple sclerosis|") "Multiple sclerosis")))
  (testing "passthrough without terms renders bare ids"
    (is (= "=== 24700007" (hermes/render-expression* *svc* "24700007"))))
  (testing "strip removes terms"
    (is (= "=== 24700007" (hermes/render-expression* *svc* "24700007 |Multiple sclerosis|" {:terms :strip}))))
  (testing "update replaces terms"
    (let [lang (hermes/match-locale *svc* "en-GB" true)]
      (is (clojure.string/includes?
            (hermes/render-expression* *svc* "24700007" {:terms :update :language-refset-ids lang})
            "Multiple sclerosis"))))
  (testing "add fills in missing terms"
    (let [lang (hermes/match-locale *svc* "en-GB" true)]
      (is (clojure.string/includes?
            (hermes/render-expression* *svc* "24700007" {:terms :add :language-refset-ids lang})
            "Multiple sclerosis"))))
  (testing "add preserves existing terms"
    (let [lang (hermes/match-locale *svc* "en-GB" true)]
      (is (clojure.string/includes?
            (hermes/render-expression* *svc* "24700007 |My custom term|" {:terms :add :language-refset-ids lang})
            "My custom term"))))
  (testing "convenience wrapper renders with terms"
    (is (clojure.string/includes? (hermes/render-expression *svc* "24700007") "Multiple sclerosis")))
  (testing "accepts concept id"
    (is (= "=== 24700007" (hermes/render-expression* *svc* 24700007)))))

(deftest ^:live test-refinements
  (testing "clinical finding has expected attributes"
    (let [attrs (hermes/refinements* *svc* 441806004)]
      (is (seq attrs))
      (is (every? :conceptId attrs))
      (is (every? #(contains? % :grouped) attrs))
      (is (every? :rangeConstraint attrs))
      (is (some #(= 363698007 (:conceptId %)) attrs) "Finding site should be permitted")
      (is (some #(= 246075003 (:conceptId %)) attrs) "Causative agent should be permitted")))
  (testing "lateralizable body structure has laterality"
    (let [attrs (hermes/refinements* *svc* 78277001)]
      (is (some #(and (= 272741003 (:conceptId %)) (not (:grouped %))) attrs)
          "Laterality should be permitted and ungrouped")))
  (testing "with language refset ids includes terms"
    (let [lang (hermes/match-locale *svc*)
          attrs (hermes/refinements* *svc* 441806004 lang)]
      (is (every? :term attrs))
      (is (some #(= "Finding site" (:term %)) attrs))))
  (testing "convenience wrapper includes terms"
    (let [attrs (hermes/refinements *svc* 441806004)]
      (is (every? :term attrs)))))

(deftest ^:live test-validate-expression
  (testing "valid expressions"
    (is (nil? (hermes/validate-expression *svc* "24700007"))
        "Simple active concept should be valid")
    (is (nil? (hermes/validate-expression *svc* "73211009 : { 363698007 = 39057004 }"))
        "Finding with grouped finding site should be valid")
    (is (nil? (hermes/validate-expression *svc* "80146002 : { 260686004 = 129304002 , 405813007 = 181255000 }"))
        "Procedure with grouped method + site should be valid"))
  (testing "unparseable expression throws"
    (is (thrown? Exception (hermes/validate-expression *svc* "not a valid expression!!!"))))
  (testing "invalid expressions return strings"
    (let [errs (hermes/validate-expression *svc* "100000102")]
      (is (seq errs) "Non-existent concept should be invalid")
      (is (every? string? errs)))
    (let [errs (hermes/validate-expression *svc* "80146002 : { 363698007 = 181255000 }")]
      (is (seq errs) "Finding site on procedure should be invalid")
      (is (every? string? errs)))
    (let [errs (hermes/validate-expression *svc* "73211009 : { 363698007 = 73211009 }")]
      (is (seq errs) "Clinical finding as finding site value should be invalid")
      (is (every? string? errs)))
    (let [errs (hermes/validate-expression *svc* "73211009 : 363698007 = 39057004")]
      (is (seq errs) "Grouped attribute used ungrouped should be invalid")
      (is (every? string? errs))))
  (testing "accepts different input types"
    (let [parsed (hermes/parse-expression *svc* "24700007")
          ret-spec (:ret (s/get-spec `hermes/parse-expression))]
      (is (s/valid? ret-spec parsed) "parse-expression return must match ret spec")
      (is (nil? (hermes/validate-expression *svc* parsed))
          "Should accept pre-parsed CTU expression"))
    (is (nil? (hermes/validate-expression *svc* 24700007))
        "Should accept a concept identifier")))

(deftest ^:live test-subsumes-concept-ids
  (let [ret-spec (:ret (s/get-spec `hermes/subsumes))]
    (testing "concept id fast path"
      (doseq [[a b expected msg]
              [[24700007 24700007 :equivalent "A concept is equivalent to itself"]
               [6118003 24700007 :subsumes "Disease of CNS subsumes Multiple sclerosis"]
               [24700007 6118003 :subsumed-by "Multiple sclerosis is subsumed by Disease of CNS"]
               [24700007 73211009 :not-subsumed "Multiple sclerosis and Diabetes mellitus are unrelated"]]]
        (let [result (hermes/subsumes *svc* a b)]
          (is (= expected result) msg)
          (is (s/valid? ret-spec result)))))))

(deftest ^:live test-subsumes-strings
  (testing "string expressions"
    (is (= :equivalent (hermes/subsumes *svc* "24700007" "24700007"))
        "Same concept as string")
    (is (= :subsumes (hermes/subsumes *svc* "6118003" "24700007"))
        "Parent subsumes child as strings")))

(deftest ^:live test-subsumes-mixed-input
  (testing "concept id and string"
    (is (= :subsumes (hermes/subsumes *svc* "6118003" 24700007))
        "String and concept id can be mixed")))

(deftest ^:live test-subsumes-owl-unavailable
  (when-not @reasoner/owl-loaded?
    (testing "throws when OWL mode requested but libraries unavailable"
      (is (thrown? clojure.lang.ExceptionInfo
                  (hermes/subsumes *svc* 24700007 24700007 :mode :owl))))))

(deftest ^:live test-history-profile
  (let [ret-spec (:ret (s/get-spec `hermes/history-profile))]
    (doseq [profile [:HISTORY-MIN :HISTORY-MOD :HISTORY-MAX]]
      (let [result (hermes/history-profile *svc* profile)]
        (is (s/valid? ret-spec result) (str "history-profile " profile " must match ret spec"))
        (is (seq result) (str "history-profile " profile " should return at least one refset id"))))))

(deftest ^:live test-reasoning-status
  (let [ret-spec (:ret (s/get-spec `hermes/reasoning-status))
        result (hermes/reasoning-status *svc*)]
    (is (s/valid? ret-spec result))))

(deftest ^:live test-expand-ecl*
  (let [ret-spec (:ret (s/get-spec `hermes/expand-ecl*))
        lang (hermes/match-locale *svc* "en-GB" true)
        results (hermes/expand-ecl* *svc* "<<24700007" lang)]
    (is (s/valid? ret-spec results))
    (is (seq results))))

(deftest ^:live test-expand-ecl-historic
  (let [ret-spec (:ret (s/get-spec `hermes/expand-ecl-historic))
        results (hermes/expand-ecl-historic *svc* "<24700007")]
    (is (s/valid? ret-spec results))
    (is (seq results))))


