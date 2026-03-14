(ns com.eldrix.hermes.owl-test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hermes.impl.owl :as owl]
            [com.eldrix.hermes.impl.scg :as scg])
  (:import (org.semanticweb.owlapi.apibinding OWLManager)
           (org.semanticweb.owlapi.model OWLEquivalentClassesAxiom OWLSubClassOfAxiom)
           (org.semanticweb.owlapi.util DefaultPrefixManager)))

(stest/instrument)

(def ^:private test-cases
  [{:description "Simple IS-A (primitive, single focus concept)"
    :concept-id  118956008
    :cf          {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{123037004}}
    :expected    "SubClassOf(:118956008 :123037004)"}

   {:description "Equivalent with two grouped relationships"
    :concept-id  10002003
    :cf          {:cf/definition-status :equivalent-to
                  :cf/focus-concepts    #{116175006}
                  :cf/groups            #{#{[260686004 [:concept 129304002]]
                                            [405813007 [:concept 414003]]}}}
    :expected    "EquivalentClasses(:10002003 ObjectIntersectionOf(:116175006 ObjectSomeValuesFrom(:609096000 ObjectIntersectionOf(ObjectSomeValuesFrom(:260686004 :129304002) ObjectSomeValuesFrom(:405813007 :414003)))))"}

   {:description "Primitive with single-attribute group"
    :concept-id  8801005
    :cf          {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{73211009}
                  :cf/groups            #{#{[100105001 [:concept 100101001]]}}}
    :expected    "SubClassOf(:8801005 ObjectIntersectionOf(:73211009 ObjectSomeValuesFrom(:609096000 ObjectSomeValuesFrom(:100105001 :100101001))))"}

   {:description "Equivalent with multiple focus concepts and ungrouped attribute"
    :concept-id  9846003
    :cf          {:cf/definition-status :equivalent-to
                  :cf/focus-concepts    #{39132006 64033007}
                  :cf/ungrouped         #{[272741003 [:concept 24028007]]}}
    :expected    "EquivalentClasses(:9846003 ObjectIntersectionOf(:39132006 :64033007 ObjectSomeValuesFrom(:272741003 :24028007)))"}

   {:description "Simple IS-A with no attributes"
    :concept-id  404684003
    :cf          {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{138875005}}
    :expected    "SubClassOf(:404684003 :138875005)"}

   {:description "Equivalent with concrete numeric value (DataHasValue)"
    :concept-id  322236009
    :cf          {:cf/definition-status :equivalent-to
                  :cf/focus-concepts    #{763158003}
                  :cf/ungrouped         #{[411116001 [:concept 421026006]]
                                          [763032000 [:concept 732936001]]
                                          [3264479001 [:numeric 1]]}}
    :expected    ["DataHasValue(:3264479001" ":322236009"]}

   {:description "Equivalent with multiple groups"
    :concept-id  71388002
    :cf          {:cf/definition-status :equivalent-to
                  :cf/focus-concepts    #{128927009}
                  :cf/groups            #{#{[260686004 [:concept 129304002]]
                                            [405813007 [:concept 15497006]]}
                                         #{[260686004 [:concept 129304002]]
                                            [405813007 [:concept 31435000]]}}}
    :expected    [":71388002" ":128927009" ":15497006" ":31435000"]}

   {:description "Single focus concept, no attributes"
    :concept-id  999999
    :cf          {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{404684003}}
    :expected    "SubClassOf(:999999 :404684003)"}

   {:description "Multiple focus concepts, no groups or ungrouped"
    :concept-id  999999
    :cf          {:cf/definition-status :equivalent-to
                  :cf/focus-concepts    #{404684003 138875005}}
    :expected    ["EquivalentClasses(" ":404684003" ":138875005"]}])

(deftest run-test-cases
  (let [manager (OWLManager/createOWLOntologyManager)
        factory (.getOWLDataFactory manager)
        pm      (doto (DefaultPrefixManager.) (.setDefaultPrefix "http://snomed.info/id/"))
        parse   (owl/create-axiom-deserializer)]
    (doseq [{:keys [description concept-id cf expected]} test-cases]
      (testing description
        (let [axiom   (owl/cf->axiom factory pm concept-id cf)
              owl-str (owl/axiom->owl-string axiom)]
          (if (= :subtype-of (:cf/definition-status cf))
            (is (instance? OWLSubClassOfAxiom axiom))
            (is (instance? OWLEquivalentClassesAxiom axiom)))
          (cond
            (string? expected)
            (is (= (parse expected) axiom)
                (str "Expected: " expected "\nGot: " owl-str))
            (vector? expected)
            (doseq [fragment expected]
              (is (.contains owl-str fragment)
                  (str "Expected " fragment " in: " owl-str)))))))))

(deftest generative-properties
  (let [manager (OWLManager/createOWLOntologyManager)
        factory (.getOWLDataFactory manager)
        pm      (doto (DefaultPrefixManager.) (.setDefaultPrefix "http://snomed.info/id/"))
        parse   (owl/create-axiom-deserializer)]
    (doseq [[cf-expr concept-id] (map vector
                                      (gen/sample (s/gen :cf/expression) 100)
                                      (gen/sample (s/gen :info.snomed.Concept/id) 100))]
      (let [axiom (owl/cf->axiom factory pm concept-id cf-expr)
            owl-s (owl/axiom->owl-string axiom)]
        (if (= :subtype-of (:cf/definition-status cf-expr))
          (is (instance? OWLSubClassOfAxiom axiom))
          (is (instance? OWLEquivalentClassesAxiom axiom)))
        (is (or (.startsWith owl-s "SubClassOf(")
                (.startsWith owl-s "EquivalentClasses(")))
        (is (.contains owl-s (str ":" concept-id)))
        (is (zero? (reduce (fn [depth c]
                             (case c \( (inc depth) \) (dec depth) depth))
                           0 owl-s)))
        (is (= axiom (parse owl-s))
            (str "Round-trip failed for: " owl-s))))))

;;;; ── Error cases ──

(deftest deserialize-axiom-errors
  (let [parse (owl/create-axiom-deserializer)]
    (testing "garbage input throws"
      (is (thrown? Exception (parse "this is not OWL"))))
    (testing "empty string throws"
      (is (thrown? Exception (parse ""))))
    (testing "annotation axiom is parsed correctly"
      (is (some? (parse "SubAnnotationPropertyOf(:1295449009 :1295447006)"))))))

;;;; ── ECL generation ──

(deftest cf->ecl*-simple-concept
  (testing "single focus concept, no attributes"
    (is (= "<< 404684003"
           (owl/cf->ecl* {:cf/definition-status :subtype-of
                           :cf/focus-concepts    #{404684003}}))))
  (testing "multiple focus concepts"
    (is (= "(<< 138875005 AND << 404684003)"
           (owl/cf->ecl* {:cf/definition-status :equivalent-to
                           :cf/focus-concepts    #{404684003 138875005}})))))

(deftest cf->ecl*-with-refinements
  (testing "ungrouped attribute"
    (is (= "<< 80146002 : 272741003 = << 7771000"
           (owl/cf->ecl* {:cf/definition-status :equivalent-to
                           :cf/focus-concepts    #{80146002}
                           :cf/ungrouped         #{[272741003 [:concept 7771000]]}}))))
  (testing "grouped attributes"
    (let [ecl (owl/cf->ecl* {:cf/definition-status :equivalent-to
                              :cf/focus-concepts    #{128927009}
                              :cf/groups            #{#{[260686004 [:concept 129304002]]
                                                        [405813007 [:concept 15497006]]}}})]
      (is (.startsWith ecl "<< 128927009 : { "))
      (is (.contains ecl "260686004 = << 129304002"))
      (is (.contains ecl "405813007 = << 15497006"))))
  (testing "concrete numeric value"
    (is (= "<< 763158003 : 3264479001 = #1"
           (owl/cf->ecl* {:cf/definition-status :equivalent-to
                           :cf/focus-concepts    #{763158003}
                           :cf/ungrouped         #{[3264479001 [:numeric 1]]}})))))

;;;; ── compute-nnf (pure — no store or reasoner needed) ──

(def ^:private compute-nnf-cases
  "Declarative test cases for compute-nnf. Each case specifies the inputs and
  expected outputs. reduce-attrs-fn, reduce-groups-fn, property-chains,
  rel-targets-fn, and is-a-fn default to identity/empty when not specified."
  [{:description "merges inherited + user attributes"
    :pps-ids     #{71388002 118698009}
    :cf-expr     {:cf/definition-status :equivalent-to
                  :cf/focus-concepts    #{80146002}
                  :cf/ungrouped         #{[272741003 [:concept 7771000]]}}
    :inherited   {:ungrouped #{}
                  :groups    #{#{[260686004 [:concept 129304002]]
                                [405813007 [:concept 66754008]]}}}
    :expected    {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{71388002 118698009}
                  :cf/ungrouped         #{[272741003 [:concept 7771000]]}
                  :cf/groups            #{#{[260686004 [:concept 129304002]]
                                           [405813007 [:concept 66754008]]}}}}

   {:description "no inherited, no user refinements — bare focus concepts"
    :pps-ids     #{404684003}
    :cf-expr     {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{404684003}}
    :inherited   {:ungrouped #{} :groups #{}}
    :expected    {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{404684003}}}

   {:description "unknown focus concepts — only user refinements survive"
    :pps-ids     #{999999}
    :cf-expr     {:cf/definition-status :equivalent-to
                  :cf/focus-concepts    #{888888}
                  :cf/ungrouped         #{[272741003 [:concept 7771000]]}
                  :cf/groups            #{#{[260686004 [:concept 129304002]]}}}
    :inherited   {:ungrouped #{} :groups #{}}
    :expected    {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{999999}
                  :cf/ungrouped         #{[272741003 [:concept 7771000]]}
                  :cf/groups            #{#{[260686004 [:concept 129304002]]}}}}

   {:description "unknown focus + no user refinements — bare NNF"
    :pps-ids     #{999999}
    :cf-expr     {:cf/definition-status :equivalent-to
                  :cf/focus-concepts    #{888888}}
    :inherited   {:ungrouped #{} :groups #{}}
    :expected    {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{999999}}}

   {:description "mixed known/unknown focus — inherited from known only"
    :pps-ids     #{111 222}
    :cf-expr     {:cf/definition-status :equivalent-to
                  :cf/focus-concepts    #{111 888888}
                  :cf/ungrouped         #{[300 [:concept 400]]}}
    :inherited   {:ungrouped #{[500 [:concept 600]]}
                  :groups    #{}}
    :expected    {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{111 222}
                  :cf/ungrouped         #{[300 [:concept 400]] [500 [:concept 600]]}}}])

(def ^:private compute-nnf-reduce-cases
  "Cases testing reduce-attrs-fn and reduce-groups-fn application."
  [{:description "reduce-attrs-fn applied to ungrouped and each group"
    :pps-ids     #{1}
    :cf-expr     {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{1}
                  :cf/ungrouped         #{[999 [:concept 2]] [100 [:concept 3]]}}
    :inherited   {:ungrouped #{} :groups #{#{[999 [:concept 4]] [200 [:concept 5]]}}}
    :reduce-attrs (fn [attrs] (into #{} (remove #(= 999 (first %))) attrs))
    :expected    {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{1}
                  :cf/ungrouped         #{[100 [:concept 3]]}
                  :cf/groups            #{#{[200 [:concept 5]]}}}}

   {:description "reduce-groups-fn applied after per-group reduction"
    :pps-ids     #{1}
    :cf-expr     {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{1}}
    :inherited   {:ungrouped #{}
                  :groups    #{#{[200 [:concept 5]]}
                              #{[300 [:concept 6]]}}}
    :reduce-groups (fn [groups]
                     (into #{} (remove #(some (fn [[t]] (= 200 t)) %)) groups))
    :expected    {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{1}
                  :cf/groups            #{#{[300 [:concept 6]]}}}}])

(def ^:private compute-nnf-chain-cases
  "Cases testing property chain redundancy removal."
  [{:description "chain removes redundant attribute from group"
    :pps-ids     #{1}
    :cf-expr     {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{1}}
    :inherited   {:ungrouped #{} :groups #{#{[200 [:concept 50]] [100 [:concept 60]]}}}
    :chains      {100 #{[200 300]}}
    :rel-targets      (fn [cid tid] (if (and (= cid 50) (= tid 300)) #{60} #{}))
    :expected    {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{1}
                  :cf/groups            #{#{[200 [:concept 50]]}}}}

   {:description "no chains — groups untouched"
    :pps-ids     #{1}
    :cf-expr     {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{1}}
    :inherited   {:ungrouped #{} :groups #{#{[200 [:concept 50]] [100 [:concept 60]]}}}
    :chains      {}
    :expected    {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{1}
                  :cf/groups            #{#{[200 [:concept 50]] [100 [:concept 60]]}}}}

   {:description "chain with is-a: value-id is-a target triggers redundancy"
    :pps-ids     #{1}
    :cf-expr     {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{1}}
    :inherited   {:ungrouped #{} :groups #{#{[200 [:concept 50]] [100 [:concept 60]]}}}
    :chains      {100 #{[200 300]}}
    :rel-targets      (fn [cid tid] (if (and (= cid 50) (= tid 300)) #{70} #{}))
    :is-a?     (fn [child parent] (and (= child 60) (= parent 70)))
    :expected    {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{1}
                  :cf/groups            #{#{[200 [:concept 50]]}}}}

   {:description "non-concept values unaffected by chains"
    :pps-ids     #{1}
    :cf-expr     {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{1}}
    :inherited   {:ungrouped #{} :groups #{#{[200 [:concept 50]] [100 [:numeric 42]]}}}
    :chains      {100 #{[200 300]}}
    :expected    {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{1}
                  :cf/groups            #{#{[200 [:concept 50]] [100 [:numeric 42]]}}}}])

(defn- run-compute-nnf-case
  [{:keys [description pps-ids cf-expr inherited expected] :as tc}]
  (testing description
    (let [nnf (owl/compute-nnf pps-ids cf-expr inherited tc)]
      (is (= (:cf/definition-status expected) (:cf/definition-status nnf)))
      (is (= (:cf/focus-concepts expected) (:cf/focus-concepts nnf)))
      (is (= (:cf/ungrouped expected) (:cf/ungrouped nnf)))
      (is (= (:cf/groups expected) (:cf/groups nnf))))))

(deftest compute-nnf-merge-and-assembly
  (doseq [tc compute-nnf-cases]
    (run-compute-nnf-case tc)))

(deftest compute-nnf-reduce-fns
  (doseq [tc compute-nnf-reduce-cases]
    (run-compute-nnf-case tc)))

(deftest compute-nnf-chain-redundancy
  (doseq [tc compute-nnf-chain-cases]
    (run-compute-nnf-case tc)))

