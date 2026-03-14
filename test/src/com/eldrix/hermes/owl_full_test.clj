(ns com.eldrix.hermes.owl-full-test
  "OWL reasoning tests using the full (unfiltered) reasoner.
  All tests are ^:live ^:slow — the fixture only fires when :slow is not excluded."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hermes.impl.owl :as owl]
            [com.eldrix.hermes.impl.scg :as scg]
            [com.eldrix.hermes.impl.store :as store]))

(stest/instrument)

(def ^:private store-path "snomed.db/store.db")

;;;; ── Shared fixture state ──

(def ^:dynamic *store* nil)
(def ^:dynamic *reasoner* nil)

;;;; ── Fixture ──

(defn- full-fixture [f]
  (with-open [st (store/open-store store-path)]
    (if-let [reasoner (owl/start-reasoner st {:n-parsers 4})]
      (try
        (binding [*store* st *reasoner* reasoner]
          (f))
        (finally
          (owl/stop-reasoner reasoner)))
      (println "WARNING: Skipping full OWL tests — no OWL axioms in" store-path))))

(use-fixtures :once full-fixture)

;;;; ── Helpers ──

(defn- classify-str
  "Classify an SCG string, returning the classification result map."
  ([reasoner s] (owl/classify reasoner (scg/ctu->cf (scg/str->ctu s))))
  ([reasoner store s normalize?]
   (owl/classify reasoner (if normalize?
                            (scg/ctu->cf+normalize store (scg/str->ctu s))
                            (scg/ctu->cf (scg/str->ctu s))))))

;;;; ── Classification: round-trip equivalence for fully-defined concepts ──
;; A fully-defined concept classified via ctu->cf should be found equivalent
;; to itself. This validates the entire pipeline: parse → CF → OWL → ELK → result.

(deftest ^:live ^:slow fully-defined-equivalence
  (testing "appendectomy (80146002) is equivalent to itself"
    (let [result (classify-str *reasoner* "80146002")]
      (is (= #{80146002} (:equivalent-concepts result)))))

  (testing "oophorectomy (83152002) is equivalent to itself"
    (let [result (classify-str *reasoner* "83152002")]
      (is (= #{83152002} (:equivalent-concepts result))))))

;;;; ── Normalization breaks equivalence but preserves subsumption ──
;; Normalizing a fully-defined concept expands it to proximal primitives,
;; losing the named concept identity. The normalized form should classify
;; as a subtype of the original concept, not equivalent.

(deftest ^:live ^:slow normalized-loses-equivalence
  (let [result (classify-str *reasoner* *store* "80146002" true)]
    (is (not (contains? (:equivalent-concepts result) 80146002))
        "normalized appendectomy should not be equivalent to the named concept")
    (is (contains? (:direct-super-concepts result) 80146002)
        "normalized appendectomy should have appendectomy as a direct superclass")))

;;;; ── Post-coordination classifies under base concept ──

(deftest ^:live ^:slow post-coordinated-classified-under-base
  (let [result (classify-str *reasoner* "80146002 : 272741003 = 7771000")]
    (is (contains? (:direct-super-concepts result) 80146002)
        "appendectomy + laterality should be classified under appendectomy")))

;;;; ── Generative: classify random expressions and validate against spec ──

(deftest ^:live ^:slow classify-conforms-to-spec
  (doseq [cf (gen/sample (s/gen :cf/expression) 50)]
    (let [result (owl/classify *reasoner* cf)]
      (is (s/valid? ::owl/classification-result result)
          (str "classify result does not conform to spec: "
               (s/explain-str ::owl/classification-result result))))))

;;;; ── Subsumption ──

(deftest ^:live ^:slow subsumption
  (let [classify (fn [s] (scg/ctu->cf (scg/str->ctu s)))]
    (is (= :equivalent   (owl/subsumes? *reasoner* (classify "80146002") (classify "80146002"))))
    (is (= :subsumes     (owl/subsumes? *reasoner* (classify "80146002") (classify "80146002 : 272741003 = 7771000"))))
    (is (= :subsumed-by  (owl/subsumes? *reasoner* (classify "80146002 : 272741003 = 7771000") (classify "80146002"))))
    (is (= :not-subsumed (owl/subsumes? *reasoner* (classify "80146002") (classify "22298006"))))))

;;;; ── Oracle: structural subsumption agrees with reasoner ──

(deftest ^:live ^:slow oracle-subsumption
  (let [owl-sub? (fn [a b]
                   (contains? #{:equivalent :subsumes}
                              (owl/subsumes? *reasoner*
                                             (scg/ctu->cf (scg/str->ctu a))
                                             (scg/ctu->cf (scg/str->ctu b)))))]
    (testing "IS-A relationships"
      (is (owl-sub? "6118003" "24700007")      "demyelinating disease subsumes MS")
      (is (not (owl-sub? "24700007" "6118003")) "MS does not subsume demyelinating disease")
      (is (owl-sub? "64572001" "73211009")      "disease subsumes diabetes")
      (is (owl-sub? "404684003" "24700007")     "clinical finding subsumes MS")
      (is (owl-sub? "71388002" "80146002")      "procedure subsumes appendectomy")
      (is (not (owl-sub? "80146002" "71388002")) "appendectomy does not subsume procedure")
      (is (not (owl-sub? "71388002" "404684003")) "procedure vs clinical finding — unrelated")
      (is (not (owl-sub? "80146002" "83152002")) "appendectomy does not subsume oophorectomy"))

    (testing "refinements"
      (is (owl-sub? "71388002" "71388002 : 260686004 = 129304002")
          "unrefined subsumes refined")
      (is (not (owl-sub? "71388002 : 260686004 = 129304002" "71388002"))
          "refined does not subsume unrefined")
      (is (owl-sub? "64572001 : 363698007 = 39607008" "64572001 : 363698007 = 181216001")
          "more specific attribute value is subsumed")
      (is (not (owl-sub? "64572001 : 363698007 = 39607008" "64572001 : 363698007 = 64033007"))
          "unrelated attribute values — no subsumption"))))

;;;; ── NNF semantic correctness ──

(deftest ^:live ^:slow oracle-nnf
  (doseq [expr-str ["80146002"
                     "80146002 : 272741003 = 7771000"
                     "83152002"]]
    (testing (str "NNF equivalence: " expr-str)
      (let [cf  (scg/ctu->cf (scg/str->ctu expr-str))
            nnf (owl/necessary-normal-form *reasoner* cf)
            fwd (owl/subsumes? *reasoner* cf nnf)
            nnf-eq (assoc nnf :cf/definition-status :equivalent-to)
            rev (owl/subsumes? *reasoner* nnf-eq cf)]
        (is (contains? #{:equivalent :subsumes} fwd)
            (str "original should subsume NNF. Got: " fwd))
        (is (contains? #{:equivalent :subsumes} rev)
            (str "NNF (as equiv) should subsume original. Got: " rev))))))
