(ns com.eldrix.hermes.owl-full-test
  "OWL reasoning tests using the full (unfiltered) reasoner.
  All tests are ^:live ^:slow — the fixture only fires when :slow is not excluded."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hermes.impl.owl :as owl]
            [com.eldrix.hermes.impl.scg :as scg]
            [com.eldrix.hermes.impl.store :as store]))

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

(defn- owl-subsumes?
  [reasoner a-str b-str]
  (let [a-cf (scg/ctu->cf (scg/str->ctu a-str))
        b-cf (scg/ctu->cf (scg/str->ctu b-str))
        result (owl/subsumes? reasoner a-cf b-cf)]
    (contains? #{:equivalent :subsumes} result)))

;;;; ── Classification against the full ontology ──

(def ^:private classify-cases
  [{:description        "MS has superclasses"
    :expression         "24700007"
    :has-supers         :any}
   {:description        "appendectomy has superclasses"
    :expression         "80146002"
    :has-supers         :any}
   {:description        "procedure has superclasses"
    :expression         "71388002"
    :has-supers         :any}
   {:description        "fully-defined concept is equivalent to itself"
    :expression         "80146002"
    :has-equivalents    #{80146002}}
   {:description        "normalized form loses equivalence but gains superclass"
    :expression         "80146002"
    :normalize?         true
    :lacks-equivalents  #{80146002}
    :has-supers         #{80146002}}
   {:description        "post-coordinated expression classified under base"
    :expression         "80146002 : 272741003 = 7771000"
    :has-supers         #{80146002}}])

(def ^:private subsumes-cases
  [{:description "equivalent"    :a "80146002" :b "80146002"                        :expected :equivalent}
   {:description "subsumes"      :a "80146002" :b "80146002 : 272741003 = 7771000"  :expected :subsumes}
   {:description "subsumed-by"   :a "80146002 : 272741003 = 7771000" :b "80146002"  :expected :subsumed-by}
   {:description "not-subsumed"  :a "80146002" :b "22298006"                        :expected :not-subsumed}])

(deftest ^:live ^:slow classification
  (let [st *store* reasoner *reasoner*]
    (doseq [{:keys [description expression normalize?
                    has-equivalents lacks-equivalents has-supers]} classify-cases]
      (testing description
        (let [cf     (if normalize?
                       (scg/ctu->cf+normalize st (scg/str->ctu expression))
                       (scg/ctu->cf (scg/str->ctu expression)))
              result (owl/classify reasoner cf)]
          (when has-equivalents
            (doseq [cid has-equivalents]
              (is (contains? (::owl/equivalent-concepts result) cid))))
          (when lacks-equivalents
            (doseq [cid lacks-equivalents]
              (is (not (contains? (::owl/equivalent-concepts result) cid)))))
          (when has-supers
            (if (= :any has-supers)
              (is (seq (::owl/direct-super-concepts result)))
              (doseq [cid has-supers]
                (is (contains? (::owl/direct-super-concepts result) cid))))))))))

(deftest ^:live ^:slow classification-subsumes
  (doseq [{:keys [description a b expected]} subsumes-cases]
    (testing description
      (let [cf-a (scg/ctu->cf (scg/str->ctu a))
            cf-b (scg/ctu->cf (scg/str->ctu b))]
        (is (= expected (owl/subsumes? *reasoner* cf-a cf-b)))))))

;;;; ── Oracle tests against the full ontology ──

(def ^:private subsumption-oracle-cases
  [{:description "concept subsumes itself"
    :a "73211009" :b "73211009" :expected true}
   {:description "parent subsumes child (demyelinating disease > MS)"
    :a "6118003" :b "24700007" :expected true}
   {:description "child does not subsume parent"
    :a "24700007" :b "6118003" :expected false}
   {:description "unrefined subsumes refined"
    :a "71388002" :b "71388002 : 260686004 = 129304002" :expected true}
   {:description "refined does not subsume unrefined"
    :a "71388002 : 260686004 = 129304002" :b "71388002" :expected false}
   {:description "more specific attribute value is subsumed"
    :a "64572001 : 363698007 = 39607008" :b "64572001 : 363698007 = 181216001"
    :expected true}
   {:description "unrelated attribute values — no subsumption"
    :a "64572001 : 363698007 = 39607008" :b "64572001 : 363698007 = 64033007"
    :expected false}
   {:description "fully-defined concept subsumes itself refined"
    :a "80146002" :b "80146002 : 272741003 = 7771000" :expected true}
   {:description "identical expressions — mutual subsumption"
    :a "80146002" :b "80146002" :expected true}
   {:description "terms ignored"
    :a "80146002 |Appendectomy|" :b "80146002" :expected true}])

(def ^:private concept-pair-cases
  [{:description "disease subsumes diabetes"
    :a "64572001" :b "73211009" :expected true}
   {:description "diabetes does not subsume disease"
    :a "73211009" :b "64572001" :expected false}
   {:description "clinical finding subsumes MS"
    :a "404684003" :b "24700007" :expected true}
   {:description "procedure subsumes appendectomy"
    :a "71388002" :b "80146002" :expected true}
   {:description "appendectomy does not subsume procedure"
    :a "80146002" :b "71388002" :expected false}
   {:description "procedure vs clinical finding — unrelated"
    :a "71388002" :b "404684003" :expected false}
   {:description "appendectomy does not subsume oophorectomy"
    :a "80146002" :b "83152002" :expected false}])

(deftest ^:live ^:slow oracle-subsumption
  (testing "hand-written subsumption cases against full ontology"
    (doseq [{:keys [description a b expected]} subsumption-oracle-cases]
      (testing description
        (is (= expected (owl-subsumes? *reasoner* a b))))))
  (testing "concept-pair subsumption against full ontology"
    (doseq [{:keys [description a b expected]} concept-pair-cases]
      (testing description
        (is (= expected (owl-subsumes? *reasoner* a b)))))))

(deftest ^:live ^:slow oracle-nnf
  (testing "NNF semantic correctness against full ontology"
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
              (str "NNF (as equiv) should subsume original. Got: " rev)))))))

(deftest ^:live ^:slow oracle-symmetry
  (let [base    (scg/ctu->cf (scg/str->ctu "80146002"))
        refined (scg/ctu->cf (scg/str->ctu "80146002 : 272741003 = 7771000"))]
    (is (= :equivalent (owl/subsumes? *reasoner* base base)))
    (is (= :subsumes (owl/subsumes? *reasoner* base refined)))
    (is (= :subsumed-by (owl/subsumes? *reasoner* refined base)))))
