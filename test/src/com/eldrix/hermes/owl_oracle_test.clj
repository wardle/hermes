(ns com.eldrix.hermes.owl-oracle-test
  "OWL reasoning tests using a subset reasoner (filtered to test concepts).
  Includes classification tests and oracle cross-checks of structural
  subsumption against the OWL reasoner.

  The structural side normalizes internally (scg/subsumes? calls ctu->cf+normalize).
  The OWL side uses ctu->cf to preserve concept identity — the reasoner already
  knows concept definitions from the ontology, so normalization would lose
  information."
  (:require [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hermes.impl.owl :as owl]
            [com.eldrix.hermes.impl.scg :as scg]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.snomed :as snomed]))

(stest/instrument)

(def ^:private store-path "snomed.db/store.db")

;;;; ── Shared fixture state ──

(def ^:dynamic *store* nil)
(def ^:dynamic *reasoner* nil)

;;;; ── Seed concepts spanning multiple hierarchies ──

(def ^:private test-concept-ids
  "Concepts from diverse hierarchies for broad oracle coverage."
  #{24700007     ;; MS
    6118003      ;; Demyelinating disease
    73211009     ;; Diabetes
    22298006     ;; MI
    195967001    ;; Asthma
    19242006     ;; Pulmonary edema
    404684003    ;; Clinical finding
    80146002     ;; Appendectomy
    71388002     ;; Procedure
    83152002     ;; Oophorectomy
    38102005     ;; Cholecystectomy
    398010007    ;; Prosthetic arthroplasty of hip
    129304002    ;; Excision
    39607008     ;; Lung
    181216001    ;; Entire lung
    64033007     ;; Kidney
    66754008     ;; Appendix
    15497006     ;; Ovary
    31435000     ;; Fallopian tube
    71341001     ;; Femur
    80891009     ;; Heart
    272741003    ;; Laterality
    7771000      ;; Left
    24028007     ;; Right
    363698007    ;; Finding site
    260686004    ;; Method
    405813007    ;; Procedure site
    116676008    ;; Associated morphology
    72704001     ;; Fracture
    79654002     ;; Edema
    32693004     ;; Demyelination
    55641003     ;; Infarct
    64572001})   ;; Disease

(defn- make-item-filter
  "Build an item filter that includes all ancestors of the test concepts."
  [st]
  (let [all-ids (store/all-parents st test-concept-ids)]
    (fn [item] (contains? all-ids (:referencedComponentId item)))))

;;;; ── Fixture ──

(defn- live-fixture [f]
  (with-open [st (store/open-store store-path)]
    (if-let [reasoner (owl/start-reasoner st {:n-parsers 4
                                               :item-filter (make-item-filter st)})]
      (try
        (binding [*store* st *reasoner* reasoner]
          (f))
        (finally
          (owl/stop-reasoner reasoner)))
      (println "WARNING: Skipping OWL subset tests — no OWL axioms in" store-path))))

(use-fixtures :once live-fixture)

(deftest ^:live test-concept-ids-active
  (doseq [id test-concept-ids]
    (is (:active (store/concept *store* id))
        (str "Test concept " id " is inactive — replace with an active equivalent"))))

;;;; ── Classification tests ──


(def ^:private subsumes-cases
  "Declarative subsumption test cases for owl/subsumes?."
  [{:description "equivalent expressions"
    :a "80146002" :b "80146002" :expected :equivalent}
   {:description "base concept subsumes refined expression"
    :a "80146002" :b "80146002 : 272741003 = 7771000" :expected :subsumes}
   {:description "refined expression is subsumed by base"
    :a "80146002 : 272741003 = 7771000" :b "80146002" :expected :subsumed-by}
   {:description "unrelated expressions"
    :a "80146002" :b "22298006" :expected :not-subsumed}])


(def ^:private ecl-cases
  "Declarative cf->ecl test cases."
  [{:description "fully-defined concept returns << equivalent"
    :expression  "80146002"
    :expected    "<< 80146002"}
   {:description "post-coordinated expression uses NNF"
    :expression  "80146002 : 272741003 = 7771000"
    :contains    ["272741003 = << 7771000"]
    :excludes    ["80146002"]}])

(defn- classify-str
  ([reasoner s] (owl/classify reasoner (scg/ctu->cf (scg/str->ctu s))))
  ([reasoner store s] (owl/classify reasoner (scg/ctu->cf+normalize store (scg/str->ctu s)))))

(deftest ^:live classification
  (testing "fully-defined concept is equivalent to itself"
    (let [result (classify-str *reasoner* "80146002")]
      (is (= #{80146002} (:equivalent-concepts result)))))

  (testing "direct supers match stated IS-A parents from store"
    (let [result       (classify-str *reasoner* "80146002")
          store-parents (store/parent-relationships-of-type *store* 80146002 snomed/IsA)]
      (is (= store-parents (:direct-super-concepts result)))))

  (testing "proximal primitive supertypes are a subset of all ancestors"
    (let [result     (classify-str *reasoner* "80146002")
          all-ancestors (store/all-parents *store* 80146002)]
      (is (every? all-ancestors (:proximal-primitive-supertypes result)))))

  (testing "normalized form loses equivalence but gains superclass"
    (let [result (classify-str *reasoner* *store* "80146002")]
      (is (not (contains? (:equivalent-concepts result) 80146002)))
      (is (contains? (:direct-super-concepts result) 80146002))))

  (testing "post-coordinated expression classified under base concept"
    (let [result (classify-str *reasoner* "80146002 : 272741003 = 7771000")]
      (is (contains? (:direct-super-concepts result) 80146002)))))

(deftest ^:live classification-subsumes
  (doseq [{:keys [description a b expected]} subsumes-cases]
    (testing description
      (let [cf-a (scg/ctu->cf (scg/str->ctu a))
            cf-b (scg/ctu->cf (scg/str->ctu b))]
        (is (= expected (owl/subsumes? *reasoner* cf-a cf-b)))))))

(deftest ^:live classification-nnf
  (testing "appendectomy NNF is primitive with PPS as focus concepts"
    (let [cf     (scg/ctu->cf (scg/str->ctu "80146002"))
          nnf    (owl/necessary-normal-form *reasoner* cf)
          result (owl/classify *reasoner* cf)]
      (is (= :subtype-of (:cf/definition-status nnf)))
      (is (= (:cf/focus-concepts nnf) (:proximal-primitive-supertypes result))
          "NNF focus concepts should be the proximal primitive supertypes")
      (is (every? (store/all-parents *store* 80146002) (:cf/focus-concepts nnf))
          "NNF focus concepts should be ancestors of the original concept")
      (is (some (fn [group]
                  (some (fn [[type-id _]] (= type-id 260686004)) group))
                (:cf/groups nnf))
          "NNF should contain method (260686004) in a group")))

  (testing "appendectomy+laterality NNF preserves user refinement"
    (let [cf  (scg/ctu->cf (scg/str->ctu "80146002 : 272741003 = 7771000"))
          nnf (owl/necessary-normal-form *reasoner* cf)]
      (is (= :subtype-of (:cf/definition-status nnf)))
      (is (contains? (:cf/ungrouped nnf) [272741003 [:concept 7771000]])
          "laterality refinement should be preserved in NNF")
      (is (seq (:cf/groups nnf))
          "inherited groups should also be present")))

  (testing "procedure+method NNF focus concepts match classification PPS"
    (let [cf     (scg/ctu->cf (scg/str->ctu "71388002 : { 260686004 = 129304002 }"))
          nnf    (owl/necessary-normal-form *reasoner* cf)
          result (owl/classify *reasoner* cf)]
      (is (= :subtype-of (:cf/definition-status nnf)))
      (is (= (:cf/focus-concepts nnf) (:proximal-primitive-supertypes result))
          "NNF focus concepts should be the proximal primitive supertypes"))))

(deftest ^:live classification-ecl
  (doseq [{:keys [description expression expected contains excludes]} ecl-cases]
    (testing description
      (let [cf  (scg/ctu->cf (scg/str->ctu expression))
            ecl (owl/cf->ecl *reasoner* cf)]
        (when expected
          (is (= expected ecl)))
        (when contains
          (doseq [s contains]
            (is (.contains ecl s))))
        (when excludes
          (doseq [s excludes]
            (is (not (.contains ecl s)))))))))

(deftest ^:live classification-ecl-determinism
  (let [cf  (scg/ctu->cf (scg/str->ctu "80146002"))
        ecls (repeatedly 5 #(owl/cf->ecl *reasoner* cf))]
    (is (apply = ecls))))

(deftest ^:live classification-property-chains
  (let [chains (owl/extract-property-chains (:ontology *reasoner*))]
    (doseq [[super-id chain-set] chains
            chain chain-set]
      (is (= 2 (count chain))
          (str "property chain for " super-id " has " (count chain)
               " links: " (pr-str chain))))))

;;;; ── Oracle comparison helpers ──

(defn- owl-subsumes?
  "Use the OWL reasoner to test whether expression a subsumes expression b.
  Uses ctu->cf to preserve concept identity."
  [reasoner a-str b-str]
  (let [a-cf (scg/ctu->cf (scg/str->ctu a-str))
        b-cf (scg/ctu->cf (scg/str->ctu b-str))
        result (owl/subsumes? reasoner a-cf b-cf)]
    (contains? #{:equivalent :subsumes} result)))

(defn- structural-subsumes?
  "Use structural subsumption to test whether expression a subsumes b."
  [st a-str b-str]
  (scg/subsumes? st (scg/str->ctu a-str) (scg/str->ctu b-str)))

(defn- check-oracle-agreement
  "Compare structural and OWL subsumption for a pair, returning a result map."
  [st reasoner a-str b-str]
  (let [structural (structural-subsumes? st a-str b-str)
        owl-result (owl-subsumes? reasoner a-str b-str)]
    {:a a-str :b b-str
     :structural structural :owl owl-result
     :agree? (= structural owl-result)}))

;;;; ── Expression generation ──

(defn- render-cf
  "Render a CF expression to an SCG string via the existing pipeline."
  [cf]
  (scg/ctu->str (scg/cf->ctu cf)))

(defn gen-concept-id
  "Generator that picks from a collection of concept IDs."
  [concept-ids]
  (gen/elements (vec concept-ids)))

(defn gen-attribute-value
  "Generator for a CF attribute value: concept reference or nested expression."
  [concept-ids]
  (gen/frequency
    [[8 (gen/fmap (fn [id] [:concept id]) (gen-concept-id concept-ids))]
     [2 (gen/fmap (fn [id] [:expression {:cf/definition-status :subtype-of
                                          :cf/focus-concepts #{id}}])
                  (gen-concept-id concept-ids))]]))

(defn gen-attribute
  "Generator for a CF attribute [type-id value]."
  [concept-ids]
  (gen/tuple (gen-concept-id concept-ids) (gen-attribute-value concept-ids)))

(defn gen-cf-expression
  "Generator for a CF expression using real concept IDs.
  Produces bare concepts, concepts with ungrouped attributes, grouped
  attributes, and nested subexpressions."
  [concept-ids]
  (gen/bind (gen/tuple (gen/fmap set (gen/not-empty (gen/vector (gen-concept-id concept-ids) 1 2)))
                       (gen/elements [:subtype-of :equivalent-to])
                       (gen/vector (gen-attribute concept-ids) 0 3)
                       (gen/vector (gen/fmap set (gen/not-empty (gen/vector (gen-attribute concept-ids) 1 3)))
                                  0 2))
    (fn [[focus def-status ungrouped groups]]
      (gen/return
        (cond-> {:cf/definition-status def-status
                 :cf/focus-concepts focus}
          (seq ungrouped) (assoc :cf/ungrouped (set ungrouped))
          (seq groups) (assoc :cf/groups (set groups)))))))

;;;; ── Test 1: Hand-written cases ──

(def ^:private subsumption-oracle-cases
  [{:description "concept subsumes itself"
    :a "73211009" :b "73211009" :structural true}
   {:description "parent subsumes child (demyelinating disease > MS)"
    :a "6118003" :b "24700007" :structural true}
   {:description "child does not subsume parent"
    :a "24700007" :b "6118003" :structural false}
   {:description "unrefined subsumes refined"
    :a "71388002" :b "71388002 : 260686004 = 129304002" :structural true}
   {:description "refined does not subsume unrefined"
    :a "71388002 : 260686004 = 129304002" :b "71388002" :structural false}
   {:description "more specific attribute value is subsumed"
    :a "64572001 : 363698007 = 39607008" :b "64572001 : 363698007 = 181216001"
    :structural true}
   {:description "unrelated attribute values — no subsumption"
    :a "64572001 : 363698007 = 39607008" :b "64572001 : 363698007 = 64033007"
    :structural false}
   {:description "fully-defined concept subsumes itself refined"
    :a "80146002" :b "80146002 : 272741003 = 7771000" :structural true}
   {:description "fewer group attrs subsumes more"
    :a "71388002 : { 260686004 = 129304002 }"
    :b "71388002 : { 260686004 = 129304002, 405813007 = 66754008 }"
    :structural true}
   {:description "two groups does not subsume one group"
    :a "71388002 : { 260686004 = 129304002, 405813007 = 15497006 } { 260686004 = 129304002, 405813007 = 31435000 }"
    :b "71388002 : { 260686004 = 129304002, 405813007 = 15497006 }"
    :structural false}
   {:description "identical expressions — mutual subsumption"
    :a "80146002" :b "80146002" :structural true}
   {:description "terms ignored"
    :a "80146002 |Appendectomy|" :b "80146002" :structural true}])

;;;; ── Test 2: Systematic concept-pair subsumption across hierarchies ──

(def ^:private concept-pair-cases
  "Systematic pairs testing IS-A hierarchy, cross-hierarchy, and refinement
  patterns across multiple SNOMED hierarchies."
  [;; IS-A hierarchy: parent/child
   {:description "disease subsumes diabetes"
    :a "64572001" :b "73211009" :structural true}
   {:description "diabetes does not subsume disease"
    :a "73211009" :b "64572001" :structural false}
   {:description "clinical finding subsumes MS"
    :a "404684003" :b "24700007" :structural true}
   {:description "procedure subsumes appendectomy"
    :a "71388002" :b "80146002" :structural true}
   {:description "procedure subsumes oophorectomy"
    :a "71388002" :b "83152002" :structural true}
   {:description "procedure subsumes cholecystectomy"
    :a "71388002" :b "38102005" :structural true}
   {:description "appendectomy does not subsume procedure"
    :a "80146002" :b "71388002" :structural false}

   ;; Cross-hierarchy: unrelated concepts
   {:description "procedure vs clinical finding — unrelated"
    :a "71388002" :b "404684003" :structural false}
   {:description "diabetes vs appendectomy — unrelated"
    :a "73211009" :b "80146002" :structural false}
   {:description "lung vs kidney — unrelated"
    :a "39607008" :b "64033007" :structural false}
   {:description "MS vs MI — unrelated findings"
    :a "24700007" :b "22298006" :structural false}
   {:description "oophorectomy vs cholecystectomy — unrelated procedures"
    :a "83152002" :b "38102005" :structural false}
   {:description "asthma vs pulmonary edema — unrelated findings"
    :a "195967001" :b "19242006" :structural false}

   ;; Body structure subsumption
   {:description "lung subsumes entire lung"
    :a "39607008" :b "181216001" :structural true}
   {:description "entire lung does not subsume lung"
    :a "181216001" :b "39607008" :structural false}

   ;; Self-subsumption for various concepts
   {:description "diabetes subsumes itself"
    :a "73211009" :b "73211009" :structural true}
   {:description "oophorectomy subsumes itself"
    :a "83152002" :b "83152002" :structural true}
   {:description "lung subsumes itself"
    :a "39607008" :b "39607008" :structural true}

   ;; Refinement patterns: same base with different sites/values
   {:description "disease+lung subsumes disease+entire-lung"
    :a "64572001 : 363698007 = 39607008"
    :b "64572001 : 363698007 = 181216001"
    :structural true}
   {:description "disease+kidney does not subsume disease+lung"
    :a "64572001 : 363698007 = 64033007"
    :b "64572001 : 363698007 = 39607008"
    :structural false}
   {:description "procedure+method subsumes procedure+method+site (more refined)"
    :a "71388002 : { 260686004 = 129304002 }"
    :b "71388002 : { 260686004 = 129304002 , 405813007 = 15497006 }"
    :structural true}
   {:description "procedure+method+site does not subsume procedure+method"
    :a "71388002 : { 260686004 = 129304002 , 405813007 = 15497006 }"
    :b "71388002 : { 260686004 = 129304002 }"
    :structural false}

   ;; Laterality refinements
   {:description "appendectomy subsumes appendectomy+left"
    :a "80146002" :b "80146002 : 272741003 = 7771000" :structural true}
   {:description "appendectomy+left does not subsume appendectomy"
    :a "80146002 : 272741003 = 7771000" :b "80146002" :structural false}
   {:description "appendectomy+left does not subsume appendectomy+right"
    :a "80146002 : 272741003 = 7771000"
    :b "80146002 : 272741003 = 24028007"
    :structural false}
   {:description "appendectomy+right does not subsume appendectomy+left"
    :a "80146002 : 272741003 = 24028007"
    :b "80146002 : 272741003 = 7771000"
    :structural false}

   ;; Procedure with different sites
   {:description "procedure+ovary does not subsume procedure+fallopian-tube"
    :a "71388002 : { 260686004 = 129304002 , 405813007 = 15497006 }"
    :b "71388002 : { 260686004 = 129304002 , 405813007 = 31435000 }"
    :structural false}

   ;; Multiple groups
   {:description "one group subsumes same concept with two groups (extra group)"
    :a "71388002 : { 260686004 = 129304002 , 405813007 = 15497006 }"
    :b "71388002 : { 260686004 = 129304002 , 405813007 = 15497006 } { 260686004 = 129304002 , 405813007 = 31435000 }"
    :structural true}

   ;; Parent concept subsumes child with refinement
   {:description "procedure subsumes appendectomy+laterality"
    :a "71388002" :b "80146002 : 272741003 = 7771000" :structural true}
   {:description "disease subsumes disease+finding-site=lung"
    :a "64572001" :b "64572001 : 363698007 = 39607008" :structural true}

   ;; Fully-defined sibling concepts
   {:description "appendectomy does not subsume oophorectomy"
    :a "80146002" :b "83152002" :structural false}
   {:description "oophorectomy does not subsume appendectomy"
    :a "83152002" :b "80146002" :structural false}

   ;; Nested expressions: attribute value is a subexpression
   {:description "nested: disease+site subsumes disease+site with more specific nested site"
    :a "64572001 : 363698007 = (39607008)"
    :b "64572001 : 363698007 = (39607008 : 272741003 = 7771000)"
    :structural true}
   {:description "nested: more specific nested site does not subsume less specific"
    :a "64572001 : 363698007 = (39607008 : 272741003 = 7771000)"
    :b "64572001 : 363698007 = (39607008)"
    :structural false}
   {:description "nested: procedure with nested method subsumes procedure with more refined nested method"
    :a "71388002 : { 260686004 = (129304002) }"
    :b "71388002 : { 260686004 = (129304002 : 272741003 = 7771000) }"
    :structural true}
   {:description "nested: identical nested expressions are equivalent"
    :a "64572001 : 363698007 = (39607008 : 272741003 = 7771000)"
    :b "64572001 : 363698007 = (39607008 : 272741003 = 7771000)"
    :structural true}
   {:description "nested: different nested values — no subsumption"
    :a "64572001 : 363698007 = (39607008 : 272741003 = 7771000)"
    :b "64572001 : 363698007 = (39607008 : 272741003 = 24028007)"
    :structural false}])

;;;; ── Test 3: Generated refinement pairs ──

;;;; ── Test runners ──

(deftest ^:live oracle-hand-written-cases
  (doseq [{:keys [description a b structural]} subsumption-oracle-cases]
    (testing description
      (is (= structural (owl-subsumes? *reasoner* a b))
          (str "OWL disagrees with expected: " structural)))))

(deftest ^:live oracle-concept-pairs
  (doseq [{:keys [description a b structural]} concept-pair-cases]
    (testing description
      (is (= structural (owl-subsumes? *reasoner* a b))
          (str "OWL disagrees — expected: " structural)))))

(deftest ^:live oracle-random-agreement
  (let [expressions (gen/sample (gen-cf-expression test-concept-ids) 200)
        pairs (partition 2 expressions)
        results (mapv (fn [[a b]]
                        (check-oracle-agreement *store* *reasoner*
                                                (render-cf a) (render-cf b)))
                      pairs)
        agreements (count (filter :agree? results))
        total (count results)
        rate (/ (double agreements) total)]
    (is (> rate 0.90)
        (format "Agreement rate %.1f%% below 90%% threshold" (* 100.0 rate)))))

;;;; ── Equivalence discovery ──

(deftest ^:live oracle-equivalence-discovery
  (testing "fully-defined concept equivalent to itself via ctu->cf"
    (let [cf (scg/ctu->cf (scg/str->ctu "80146002"))
          result (owl/classify *reasoner* cf)]
      (is (contains? (:equivalent-concepts result) 80146002))))

  (testing "normalized form loses equivalence but gains superclass"
    (let [cf (scg/ctu->cf+normalize *store* (scg/str->ctu "80146002"))
          result (owl/classify *reasoner* cf)]
      (is (not (contains? (:equivalent-concepts result) 80146002)))
      (is (contains? (:direct-super-concepts result) 80146002))))

  (testing "post-coordinated expression classified under base concept"
    (let [cf (scg/ctu->cf (scg/str->ctu "80146002 : 272741003 = 7771000"))
          result (owl/classify *reasoner* cf)]
      (is (contains? (:direct-super-concepts result) 80146002)))))

;;;; ── Normalize validation via classification ──

(deftest ^:live oracle-normalize-preserves-semantics
  (doseq [expr-str ["80146002"
                     "73211009"
                     "80146002 : 272741003 = 7771000"
                     "64572001 : 363698007 = 39607008"
                     "83152002"
                     "22298006"
                     "24700007"
                     "38102005"]]
    (testing (str "normalize preserves semantics: " expr-str)
      (let [parsed      (scg/str->ctu expr-str)
            ctu-cf      (scg/ctu->cf parsed)
            norm-cf     (scg/ctu->cf+normalize *store* parsed)
            ctu-result  (owl/classify *reasoner* ctu-cf)
            norm-result (owl/classify *reasoner* norm-cf)]
        (is (or (seq (:equivalent-concepts ctu-result))
                (seq (:direct-super-concepts ctu-result)))
            "ctu->cf form should classify")
        (is (or (seq (:equivalent-concepts norm-result))
                (seq (:direct-super-concepts norm-result)))
            "normalized form should classify")))))

;;;; ── NNF semantic validation ──

(deftest ^:live oracle-nnf-semantic-correctness
  (testing "NNF is semantically equivalent to original expression"
    (doseq [expr-str ["80146002"
                       "80146002 : 272741003 = 7771000"
                       "83152002"]]
      (testing (str "NNF equivalence: " expr-str)
        (let [cf      (scg/ctu->cf (scg/str->ctu expr-str))
              nnf     (owl/necessary-normal-form *reasoner* cf)
              fwd     (owl/subsumes? *reasoner* cf nnf)
              nnf-eq  (assoc nnf :cf/definition-status :equivalent-to)
              rev     (owl/subsumes? *reasoner* nnf-eq cf)]
          (is (contains? #{:equivalent :subsumes} fwd)
              (str "original should subsume NNF (NNF at least as specific). Got: " fwd
                   "\nNNF: " (pr-str nnf)))
          (is (contains? #{:equivalent :subsumes} rev)
              (str "NNF (as equiv) should subsume original (NNF at least as general). Got: " rev
                   "\nNNF: " (pr-str nnf)))))))
  (testing "NNF contains inherited relationships from store"
    (let [cf            (scg/ctu->cf (scg/str->ctu "80146002"))
          nnf           (owl/necessary-normal-form *reasoner* cf)
          store-rels    (store/parent-relationships *store* 80146002)
          store-methods (get store-rels 260686004)]
      (is (some (fn [group]
                  (some (fn [[type-id [_ val]]] (and (= type-id 260686004) (contains? store-methods val)))
                        group))
                (:cf/groups nnf))
          "NNF groups should contain method attributes matching the store")))
  (testing "NNF preserves user refinements alongside inherited"
    (let [cf  (scg/ctu->cf (scg/str->ctu "80146002 : 272741003 = 7771000"))
          nnf (owl/necessary-normal-form *reasoner* cf)]
      (is (contains? (:cf/ungrouped nnf) [272741003 [:concept 7771000]])
          "laterality should be in NNF")
      (is (some (fn [group]
                  (some (fn [[type-id _]] (= type-id 260686004)) group))
                (:cf/groups nnf))
          "inherited groups with method attribute should be present"))))

;;;; ── Bidirectional subsumption agreement ──

(deftest ^:live oracle-subsumption-symmetry
  (testing "mutual subsumption = equivalence"
    (let [cf (scg/ctu->cf (scg/str->ctu "80146002"))]
      (is (= :equivalent (owl/subsumes? *reasoner* cf cf)))))

  (testing "asymmetric subsumption is consistent"
    (let [base    (scg/ctu->cf (scg/str->ctu "80146002"))
          refined (scg/ctu->cf (scg/str->ctu "80146002 : 272741003 = 7771000"))]
      (is (= :subsumes (owl/subsumes? *reasoner* base refined)))
      (is (= :subsumed-by (owl/subsumes? *reasoner* refined base)))))

  (testing "unrelated concepts are not-subsumed in both directions"
    (doseq [[a b] [["80146002" "22298006"]
                    ["73211009" "83152002"]
                    ["39607008" "64033007"]
                    ["24700007" "38102005"]]]
      (let [a-cf (scg/ctu->cf (scg/str->ctu a))
            b-cf (scg/ctu->cf (scg/str->ctu b))]
        (is (= :not-subsumed (owl/subsumes? *reasoner* a-cf b-cf))
            (str a " vs " b))
        (is (= :not-subsumed (owl/subsumes? *reasoner* b-cf a-cf))
            (str b " vs " a))))))
