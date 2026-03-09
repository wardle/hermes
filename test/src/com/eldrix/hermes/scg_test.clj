(ns com.eldrix.hermes.scg-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing run-tests]]
            [com.eldrix.hermes.impl.scg :as scg]
            [com.eldrix.hermes.impl.store :as store]))

(def test-cases
  [{:description "Simple concept ID without term"
    :expression  "24700007"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 24700007}]}}}

   {:description "Concept ID with term"
    :expression  "73211009 |Diabetes mellitus|"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 73211009 :term "Diabetes mellitus"}]}}}

   {:description "Multiple focus concepts (dose forms)"
    :expression  "421720008 |Spray dose form| + 7946007 |Drug suspension|"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 421720008 :term "Spray dose form"}
                                                     {:conceptId 7946007 :term "Drug suspension"}]}}}

   {:description "Single ungrouped attribute"
    :expression  "83152002 |Oophorectomy| : 405815000 |Procedure device| = 122456005 |Laser device|"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 83152002 :term "Oophorectomy"}]
                                     :refinements   [[{:conceptId 405815000 :term "Procedure device"}
                                                      {:conceptId 122456005 :term "Laser device"}]]}}}

   {:description "Multiple ungrouped attributes"
    :expression  "80146002 |appendectomy| : 260870009 |priority| = 25876001 |emergency| , 425391005 |using access device| = 86174004 |laparoscope|"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 80146002 :term "appendectomy"}]
                                     :refinements   [[{:conceptId 260870009 :term "priority"}
                                                      {:conceptId 25876001 :term "emergency"}]
                                                     [{:conceptId 425391005 :term "using access device"}
                                                      {:conceptId 86174004 :term "laparoscope"}]]}}}

   {:description "Two attribute groups"
    :expression  "71388002 |Procedure| : { 260686004 |Method| = 129304002 |Excision - action| , 405813007 |Procedure site - direct| = 15497006 |Ovarian structure| } { 260686004 |Method| = 129304002 |Excision - action| , 405813007 |Procedure site - direct| = 31435000 |Fallopian tube structure| }"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 71388002 :term "Procedure"}]
                                     :refinements   [#{[{:conceptId 260686004 :term "Method"}
                                                        {:conceptId 129304002 :term "Excision - action"}]
                                                       [{:conceptId 405813007 :term "Procedure site - direct"}
                                                        {:conceptId 15497006 :term "Ovarian structure"}]}
                                                     #{[{:conceptId 260686004 :term "Method"}
                                                        {:conceptId 129304002 :term "Excision - action"}]
                                                       [{:conceptId 405813007 :term "Procedure site - direct"}
                                                        {:conceptId 31435000 :term "Fallopian tube structure"}]}]}}}

   {:description "Numeric (integer) concrete value"
    :expression  "774586009 |Amoxicillin| : 189999999103 |Has strength value| = #500"
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 774586009 :term "Amoxicillin"}]
                                     :refinements   [[{:conceptId 189999999103 :term "Has strength value"} 500]]}}}

   {:description "Numeric (decimal) concrete value"
    :expression  "91143003 |Albuterol| : 189999999103 |Has strength value| = #0.083"
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 91143003 :term "Albuterol"}]
                                     :refinements   [[{:conceptId 189999999103 :term "Has strength value"} 0.083]]}}}

   {:description "String concrete value"
    :expression  "322236009 |Paracetamol 500mg tablet| : 209999999104 |Has trade name| = \"PANADOL\""
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 322236009 :term "Paracetamol 500mg tablet"}]
                                     :refinements   [[{:conceptId 209999999104 :term "Has trade name"} "PANADOL"]]}}}

   {:description "Boolean concrete value"
    :expression  "318969005 |Irbesartan 150 mg oral tablet| : 859999999102 |Is in national benefit scheme| = TRUE"
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 318969005 :term "Irbesartan 150 mg oral tablet"}]
                                     :refinements   [[{:conceptId 859999999102 :term "Is in national benefit scheme"} true]]}}}

   {:description "Subtype definition status"
    :expression  "<<< 73211009 |Diabetes mellitus| : 363698007 |Finding site| = 113331007 |Endocrine system|"
    :live        true
    :parsed      {:definitionStatus :subtype-of
                  :subExpression    {:focusConcepts [{:conceptId 73211009 :term "Diabetes mellitus"}]
                                     :refinements   [[{:conceptId 363698007 :term "Finding site"}
                                                      {:conceptId 113331007 :term "Endocrine system"}]]}}}

   {:description "Nested expression value (focus concepts in attribute value)"
    :expression  "373873005 |Pharmaceutical / biologic product| : 411116001 |Has dose form| = ( 421720008 |Spray dose form| + 7946007 |Drug suspension| )"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 373873005 :term "Pharmaceutical / biologic product"}]
                                     :refinements   [[{:conceptId 411116001 :term "Has dose form"}
                                                      {:focusConcepts [{:conceptId 421720008 :term "Spray dose form"}
                                                                       {:conceptId 7946007 :term "Drug suspension"}]}]]}}}

   {:description "Nested refinement (sub-expression with refinement in attribute value)"
    :expression  "397956004 |Prosthetic arthroplasty of the hip| : 363704007 |Procedure site| = ( 24136001 |Hip joint structure| : 272741003 |Laterality| = 7771000 |Left| )"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 397956004 :term "Prosthetic arthroplasty of the hip"}]
                                     :refinements   [[{:conceptId 363704007 :term "Procedure site"}
                                                      {:focusConcepts [{:conceptId 24136001 :term "Hip joint structure"}]
                                                       :refinements   [[{:conceptId 272741003 :term "Laterality"}
                                                                        {:conceptId 7771000 :term "Left"}]]}]]}}}

   {:description "Ungrouped attributes followed by a group"
    :expression  "774586009 |Amoxicillin only product| : 411116001 |Has manufactured dose form| = 420692007 |Oral capsule| , { 127489000 |Has active ingredient| = 372687004 |Amoxicillin| , 189999999103 |Has strength value| = #500 }"
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 774586009 :term "Amoxicillin only product"}]
                                     :refinements   [[{:conceptId 411116001 :term "Has manufactured dose form"}
                                                      {:conceptId 420692007 :term "Oral capsule"}]
                                                     #{[{:conceptId 127489000 :term "Has active ingredient"}
                                                        {:conceptId 372687004 :term "Amoxicillin"}]
                                                       [{:conceptId 189999999103 :term "Has strength value"}
                                                        500]}]}}}

   {:description "Multiple focus concepts with shared refinement"
    :expression  "119189000 |Ulna part| + 312845000 |Epiphysis of upper limb| : 272741003 |Laterality| = 7771000 |Left|"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 119189000 :term "Ulna part"}
                                                     {:conceptId 312845000 :term "Epiphysis of upper limb"}]
                                     :refinements   [[{:conceptId 272741003 :term "Laterality"}
                                                      {:conceptId 7771000 :term "Left"}]]}}}

   {:description "Duplicate attributes in same attribute set (issue #84 §1)"
    :expression  "71388002 |Procedure| : 260686004 |Method| = 129304002 |Excision - action| , 260686004 |Method| = 261519002 |Diathermy excision - action|"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 71388002 :term "Procedure"}]
                                     :refinements   [[{:conceptId 260686004 :term "Method"}
                                                      {:conceptId 129304002 :term "Excision - action"}]
                                                     [{:conceptId 260686004 :term "Method"}
                                                      {:conceptId 261519002 :term "Diathermy excision - action"}]]}}}

   {:description "Single attribute group preserves grouping (issue #84 §3/§4)"
    :expression  "71388002 |Procedure| : { 260686004 |Method| = 129304002 |Excision - action| , 405813007 |Procedure site - direct| = 15497006 |Ovarian structure| }"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 71388002 :term "Procedure"}]
                                     :refinements   [#{[{:conceptId 260686004 :term "Method"}
                                                        {:conceptId 129304002 :term "Excision - action"}]
                                                       [{:conceptId 405813007 :term "Procedure site - direct"}
                                                        {:conceptId 15497006 :term "Ovarian structure"}]}]}}}

   {:description "Simple laterality refinement (§6.3)"
    :expression  "182201002 |Hip joint| : 272741003 |Laterality| = 24028007 |Right|"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 182201002 :term "Hip joint"}]
                                     :refinements   [[{:conceptId 272741003 :term "Laterality"}
                                                      {:conceptId 24028007 :term "Right"}]]}}}

   {:description "Boolean FALSE concrete value"
    :expression  "318969005 |Irbesartan 150 mg oral tablet| : 859999999102 |Is in national benefit scheme| = FALSE"
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 318969005 :term "Irbesartan 150 mg oral tablet"}]
                                     :refinements   [[{:conceptId 859999999102 :term "Is in national benefit scheme"} false]]}}}

   {:description "Deeply nested situation with explicit context (§6.5)"
    :expression  "243796009 |Situation with explicit context| : { 408731000 |Temporal context| = 410512000 |Current or specified| , 408729009 |Finding context| = 410515003 |Known present| , 246090004 |Associated finding| = ( 195967001 |Asthma| : { 363698007 |Finding site| = 321667001 |Respiratory tract structure| } ) }"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 243796009 :term "Situation with explicit context"}]
                                     :refinements   [#{[{:conceptId 408731000 :term "Temporal context"}
                                                        {:conceptId 410512000 :term "Current or specified"}]
                                                       [{:conceptId 408729009 :term "Finding context"}
                                                        {:conceptId 410515003 :term "Known present"}]
                                                       [{:conceptId 246090004 :term "Associated finding"}
                                                        {:focusConcepts [{:conceptId 195967001 :term "Asthma"}]
                                                         :refinements   [#{[{:conceptId 363698007 :term "Finding site"}
                                                                            {:conceptId 321667001 :term "Respiratory tract structure"}]}]}]}]}}}

   {:description "Full amoxicillin product with multiple concrete values (§6.6)"
    :expression  "774586009 |Amoxicillin only product| : 411116001 |Has manufactured dose form| = 420692007 |Oral capsule| , { 127489000 |Has active ingredient| = 372687004 |Amoxicillin| , 179999999100 |Has basis of strength| = 372687004 |Amoxicillin| , 189999999103 |Has strength value| = #500 , 199999999101 |Has strength unit| = 258684004 |mg| }"
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 774586009 :term "Amoxicillin only product"}]
                                     :refinements   [[{:conceptId 411116001 :term "Has manufactured dose form"}
                                                      {:conceptId 420692007 :term "Oral capsule"}]
                                                     #{[{:conceptId 127489000 :term "Has active ingredient"}
                                                        {:conceptId 372687004 :term "Amoxicillin"}]
                                                       [{:conceptId 179999999100 :term "Has basis of strength"}
                                                        {:conceptId 372687004 :term "Amoxicillin"}]
                                                       [{:conceptId 189999999103 :term "Has strength value"}
                                                        500]
                                                       [{:conceptId 199999999101 :term "Has strength unit"}
                                                        {:conceptId 258684004 :term "mg"}]}]}}}

   {:description "Equivalent-to with multiple focus concepts and refinement (§6.7)"
    :expression  "=== 313056006 |Epiphysis of ulna| + 312845000 |Epiphysis of upper limb| : 272741003 |Laterality| = 7771000 |Left|"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 313056006 :term "Epiphysis of ulna"}
                                                     {:conceptId 312845000 :term "Epiphysis of upper limb"}]
                                     :refinements   [[{:conceptId 272741003 :term "Laterality"}
                                                      {:conceptId 7771000 :term "Left"}]]}}}

   {:description "Negative numeric concrete value"
    :expression  "774586009 |Amoxicillin| : 189999999103 |Has strength value| = #-10"
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 774586009 :term "Amoxicillin"}]
                                     :refinements   [[{:conceptId 189999999103 :term "Has strength value"} -10]]}}}

   {:description "String value with escaped quote"
    :expression  "322236009 |Paracetamol 500mg tablet| : 209999999104 |Has trade name| = \"PAN\\\"ADOL\""
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 322236009 :term "Paracetamol 500mg tablet"}]
                                     :refinements   [[{:conceptId 209999999104 :term "Has trade name"} "PAN\"ADOL"]]}}}

   {:description "Multiple focus concepts, one with term, one without"
    :expression  "421720008 + 7946007 |Drug suspension|"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 421720008}
                                                     {:conceptId 7946007 :term "Drug suspension"}]}}}

   {:description "Three ungrouped attributes"
    :expression  "71388002 |Procedure| : 405815000 |Procedure device| = 122456005 |Laser device| , 260686004 |Method| = 129304002 |Excision - action| , 405813007 |Procedure site - direct| = 15497006 |Ovarian structure|"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 71388002 :term "Procedure"}]
                                     :refinements   [[{:conceptId 405815000 :term "Procedure device"}
                                                      {:conceptId 122456005 :term "Laser device"}]
                                                     [{:conceptId 260686004 :term "Method"}
                                                      {:conceptId 129304002 :term "Excision - action"}]
                                                     [{:conceptId 405813007 :term "Procedure site - direct"}
                                                      {:conceptId 15497006 :term "Ovarian structure"}]]}}}

   {:description "Two attribute groups with three attributes each"
    :expression  "71388002 |procedure| : { 260686004 |method| = 129304002 |excision - action| , 405813007 |procedure site - direct| = 20837000 |structure of right ovary| , 424226004 |using device| = 122456005 |laser device| } { 260686004 |method| = 261519002 |diathermy excision - action| , 405813007 |procedure site - direct| = 113293009 |structure of left fallopian tube| }"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 71388002 :term "procedure"}]
                                     :refinements   [#{[{:conceptId 260686004 :term "method"}
                                                        {:conceptId 129304002 :term "excision - action"}]
                                                       [{:conceptId 405813007 :term "procedure site - direct"}
                                                        {:conceptId 20837000 :term "structure of right ovary"}]
                                                       [{:conceptId 424226004 :term "using device"}
                                                        {:conceptId 122456005 :term "laser device"}]}
                                                     #{[{:conceptId 260686004 :term "method"}
                                                        {:conceptId 261519002 :term "diathermy excision - action"}]
                                                       [{:conceptId 405813007 :term "procedure site - direct"}
                                                        {:conceptId 113293009 :term "structure of left fallopian tube"}]}]}}}

   {:description "Leading whitespace and extra internal spacing"
    :expression  "   313056006 |Epiphysis of ulna| :  272741003 |Laterality|  =  7771000 |Left|"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 313056006 :term "Epiphysis of ulna"}]
                                     :refinements   [[{:conceptId 272741003 :term "Laterality"}
                                                      {:conceptId 7771000 :term "Left"}]]}}}

   ;; Error cases — non-existent concepts
   {:description "Non-existent focus concept"
    :expression  "100000102"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 100000102}]}}
    :errors      [{:type :concept-not-found :conceptId 100000102 :role :focus-concept}]}

   {:description "Non-existent attribute type"
    :expression  "73211009 : 100000102 = 7771000"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 73211009}]
                                     :refinements   [[{:conceptId 100000102} {:conceptId 7771000}]]}}
    :errors      [{:type :concept-not-found :conceptId 100000102 :role :attribute-type}]}

   {:description "Non-existent attribute value"
    :expression  "73211009 : 272741003 = 100000102"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 73211009}]
                                     :refinements   [[{:conceptId 272741003} {:conceptId 100000102}]]}}
    :errors      [{:type :concept-not-found :conceptId 100000102 :role :attribute-value}]}

   {:description "Multiple errors — bad focus and bad value"
    :expression  "100000102 : 272741003 = 100000102"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 100000102}]
                                     :refinements   [[{:conceptId 272741003} {:conceptId 100000102}]]}}
    :errors      [{:type :concept-not-found :conceptId 100000102 :role :focus-concept}
                  {:type :concept-not-found :conceptId 100000102 :role :attribute-value}]}

   {:description "Valid grouped attributes"
    :expression  "80146002 : { 260686004 = 129304002 , 405813007 = 66754008 }"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 80146002}]
                                     :refinements   [#{[{:conceptId 260686004} {:conceptId 129304002}]
                                                       [{:conceptId 405813007} {:conceptId 66754008}]}]}}}

   {:description "Primitive concept — normalize includes own defining attrs"
    :expression  "73211009"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 73211009}]}}
    :normalized  {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{73211009}
                  :cf/groups           #{#{[363698007 113331007]}}}}

   {:description "Fully-defined concept — normalize expands to proximal primitives"
    :expression  "80146002"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 80146002}]}}
    :normalized  {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{71388002 118698009 387713003}
                  :cf/groups           #{#{[260686004 129304002]
                                          [405813007 66754008]}}}}

   {:description "Post-coordinated — user refinement merged with defining attrs"
    :expression  "80146002 : 272741003 = 7771000"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 80146002}]
                                     :refinements   [[{:conceptId 272741003} {:conceptId 7771000}]]}}
    :normalized  {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{71388002 118698009 387713003}
                  :cf/ungrouped        #{[272741003 7771000]}
                  :cf/groups           #{#{[260686004 129304002]
                                          [405813007 66754008]}}}}

   {:description "Primitive with user refinement — own attrs included"
    :expression  "73211009 : 272741003 = 7771000"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 73211009}]
                                     :refinements   [[{:conceptId 272741003} {:conceptId 7771000}]]}}
    :normalized  {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{73211009}
                  :cf/ungrouped        #{[272741003 7771000]}
                  :cf/groups           #{#{[363698007 113331007]}}}}

   {:description "Simple laterality — normalize preserves user refinement on primitive"
    :expression  "182201002 : 272741003 = 24028007"
    :live        true
    :parsed      {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 182201002}]
                                     :refinements   [[{:conceptId 272741003} {:conceptId 24028007}]]}}
    :normalized  {:cf/definition-status :subtype-of
                  :cf/focus-concepts    #{182201002}
                  :cf/ungrouped        #{[272741003 24028007]}}}])

(deftest ^:live expression-tests
  (with-open [st (store/open-store "snomed.db/store.db")]
    (doseq [{:keys [description expression parsed errors normalized live]} test-cases]
      (testing description
        (let [result (scg/parse expression)]
          (is (= parsed result) (str "Parse mismatch for: " expression))
          (is (s/valid? :ctu/expression result)
              (str "Spec failure for: " expression "\n"
                   (s/explain-str :ctu/expression result)))
          (let [rendered (scg/render result)
                reparsed (scg/parse rendered)]
            (is (= result reparsed)
                (str "Roundtrip mismatch for: " expression "\n  rendered: " rendered)))
          (let [canon (scg/canonicalize result)]
            (is (= canon (scg/canonicalize canon))
                (str "Canonicalize not idempotent for: " expression))
            (is (s/valid? :ctu/expression canon)
                (str "Canonical form fails spec for: " expression)))
          (when live
            (is (= errors (scg/errors st result))))
          (when normalized
            (let [norm (scg/normalize st result)]
              (is (= normalized norm))
              (is (s/valid? :cf/expression norm)
                  (s/explain-str :cf/expression norm))
              (let [round-tripped (scg/normalize st (scg/classifiable->ctu norm))]
                (is (= norm round-tripped)
                    "Normalization should be idempotent through round-trip")))))))))

(def canonical-equivalences
  "Groups of expressions that should produce identical canonical forms."
  [["7946007 |Drug suspension| + 421720008 |Spray dose form|"
    "421720008 |Spray dose form| + 7946007 |Drug suspension|"
    "421720008 + 7946007"]
   ["71388002 : 425391005 = 86174004 , 260870009 = 25876001"
    "71388002 : 260870009 = 25876001 , 425391005 = 86174004"]
   ["71388002 : { 405813007 = 31435000 } { 260686004 = 129304002 }"
    "71388002 : { 260686004 = 129304002 } { 405813007 = 31435000 }"]
   ["73211009 |Diabetes mellitus| : 363698007 |Finding site| = 113331007 |Endocrine system|"
    "73211009 |DM| : 363698007 |Site| = 113331007 |Endocrine|"]])

(deftest canonicalize-equivalence
  (doseq [exprs canonical-equivalences]
    (let [canonical-forms (map (comp scg/canonicalize scg/parse) exprs)]
      (is (apply = canonical-forms)
          (str "Not canonically equivalent: " (pr-str exprs))))))

(deftest ^:live updating-terms
  (with-open [st (store/open-store "snomed.db/store.db")]
    (let [parsed (scg/parse "91143003 |Albuterol| : 411116001 |Has manufactured dose form| = 385023001 |oral solution| , { 127489000 |Has active ingredient| = 372897005 |Albuterol| , 179999999100 |Has basis of strength| = 372897005 |Albuterol| , 189999999103 |Has strength value| = #0.083 , 199999999101 |Has strength numerator unit| = 118582008 |%| }")
          updated (scg/parse (scg/render st parsed {:update-terms? true :accept-language "en-GB"}))
          group2 (second (get-in updated [:subExpression :refinements]))
          active-ingredient-pair (first (filter #(= 127489000 (:conceptId (first %))) group2))]
      (is (= {:conceptId 372897005 :term "Salbutamol"} (second active-ingredient-pair))
          "Albuterol not updated to salbutamol for en-GB locale"))))



(def concept->expression*-cases
  [{:description "Primitive concept (Clinical finding) — itself as sole focus, <<< status"
    :concept-id  404684003
    :defined?    false
    :properties  {0 {116680003 #{138875005}}}
    :expected    {:definitionStatus :subtype-of
                  :subExpression    {:focusConcepts [{:conceptId 404684003}]}}}

   {:description "Primitive concept (Multiple sclerosis) with grouped refinements"
    :concept-id  24700007
    :defined?    false
    :properties  {0 {116680003 #{6118003 413834006 128283000 39367000}}
                  1 {116676008 #{32693004} 363698007 #{21483005} 370135005 #{769247005}}
                  2 {116676008 #{409774005} 363698007 #{21483005} 370135005 #{769247005}}
                  3 {263502005 #{90734009}}}
    :expected    {:definitionStatus :subtype-of
                  :subExpression    {:focusConcepts [{:conceptId 24700007}]
                                     :refinements   [#{[{:conceptId 116676008} {:conceptId 32693004}]
                                                       [{:conceptId 363698007} {:conceptId 21483005}]
                                                       [{:conceptId 370135005} {:conceptId 769247005}]}
                                                     #{[{:conceptId 116676008} {:conceptId 409774005}]
                                                       [{:conceptId 363698007} {:conceptId 21483005}]
                                                       [{:conceptId 370135005} {:conceptId 769247005}]}
                                                     #{[{:conceptId 263502005} {:conceptId 90734009}]}]}}}

   {:description "Fully-defined concept (Appendectomy) — IS-A parents as foci, === status"
    :concept-id  80146002
    :defined?    true
    :properties  {0 {116680003 #{27010001 8613002}}
                  1 {260686004 #{129304002} 405813007 #{66754008}}}
    :expected    {:definitionStatus :equivalent-to
                  :subExpression    {:focusConcepts [{:conceptId 8613002}
                                                     {:conceptId 27010001}]
                                     :refinements   [#{[{:conceptId 260686004} {:conceptId 129304002}]
                                                       [{:conceptId 405813007} {:conceptId 66754008}]}]}}}])

(deftest concept->expression*
  (doseq [{:keys [description concept-id defined? properties expected]} concept->expression*-cases]
    (testing description
      (let [expr (scg/concept->expression* concept-id defined? properties)]
        (is (= expected expr))
        (is (s/valid? :ctu/expression expr)
            (s/explain-str :ctu/expression expr))
        (is (= expr (scg/canonicalize expr))
            "Should already be in canonical form")
        (let [rendered (scg/render (scg/strip-terms expr))
              reparsed (scg/parse rendered)]
          (is (= expr reparsed)
              (str "Round-trip failed. Rendered: " rendered)))))))



(deftest ^:live concept->expression
  (with-open [st (store/open-store "snomed.db/store.db")]
    (testing "Primitive concept (Multiple sclerosis) — self as focus, <<< status"
      (let [expr (scg/concept->expression st 24700007)]
        (is (= :subtype-of (:definitionStatus expr)))
        (is (= [{:conceptId 24700007}] (get-in expr [:subExpression :focusConcepts])))
        (is (seq (get-in expr [:subExpression :refinements]))
            "Should have refinements from properties")
        (is (s/valid? :ctu/expression expr)
            (s/explain-str :ctu/expression expr))
        (is (= expr (scg/canonicalize expr))
            "Should already be in canonical form")))
    (testing "Fully-defined concept (Appendectomy) — IS-A parents as foci, === status"
      (let [expr (scg/concept->expression st 80146002)]
        (is (= :equivalent-to (:definitionStatus expr)))
        (is (< 1 (count (get-in expr [:subExpression :focusConcepts])))
            "Fully-defined concept should have multiple focus concepts (IS-A parents)")
        (is (not (some #(= 80146002 (:conceptId %)) (get-in expr [:subExpression :focusConcepts])))
            "Fully-defined concept should not include itself as focus")
        (is (s/valid? :ctu/expression expr)
            (s/explain-str :ctu/expression expr))
        (is (= expr (scg/canonicalize expr))
            "Should already be in canonical form")))
    (testing "Concept with no non-IS-A properties (Clinical finding)"
      (let [expr (scg/concept->expression st 404684003)]
        (is (= :subtype-of (:definitionStatus expr)))
        (is (= [{:conceptId 404684003}] (get-in expr [:subExpression :focusConcepts])))
        (is (nil? (get-in expr [:subExpression :refinements]))
            "Should have no refinements when only IS-A properties exist")
        (is (s/valid? :ctu/expression expr))))
    (testing "Round-trip through render and parse"
      (doseq [concept-id [24700007 80146002 404684003]]
        (let [expr (scg/concept->expression st concept-id)
              rendered (scg/render (scg/strip-terms expr))
              reparsed (scg/parse rendered)]
          (is (= expr reparsed)
              (str "Round-trip failed for " concept-id ". Rendered: " rendered)))))
    (testing "Non-existent concept returns nil"
      (is (nil? (scg/concept->expression st 100000102))))))

(def subsumption-test-cases
  "Test cases for expression subsumption.
  Complexity: O(F_a×F_b + U_a×U_b + G_a×G_b×K²) where F=focus concepts,
  U=ungrouped attrs, G=groups, K=attrs per group. All are small (single digits)
  so effectively constant. is-a? is O(1) via transitive closure lookup."
  [{:description "concept subsumes itself"
    :exp true :a "73211009" :b "73211009"}

   {:description "parent subsumes child (demyelinating disease > MS)"
    :exp true :a "6118003" :b "24700007"}

   {:description "child does not subsume parent (MS > demyelinating disease)"
    :exp false :a "24700007" :b "6118003"}

   {:description "unrefined subsumes refined"
    :exp true :a "71388002" :b "71388002 : 260686004 = 129304002"}

   {:description "refined does not subsume unrefined"
    :exp false :a "71388002 : 260686004 = 129304002" :b "71388002"}

   ;; 39607008 = Lung structure, 181216001 = Entire lung (is-a Lung structure)
   {:description "more specific attribute value is subsumed"
    :exp true
    :a   "64572001 : 363698007 = 39607008"
    :b   "64572001 : 363698007 = 181216001"}

   ;; 39607008 = Lung, 64033007 = Kidney
   {:description "unrelated attribute values — no subsumption"
    :exp false
    :a   "64572001 : 363698007 = 39607008"
    :b   "64572001 : 363698007 = 64033007"}

   {:description "fully-defined concept subsumes itself refined"
    :exp true
    :a   "80146002"
    :b   "80146002 : 272741003 = 7771000"}

   {:description "fewer group attrs subsumes more group attrs"
    :exp true
    :a   "71388002 : { 260686004 = 129304002 }"
    :b   "71388002 : { 260686004 = 129304002, 405813007 = 66754008 }"}

   {:description "two groups does not subsume one group"
    :exp false
    :a   "71388002 : { 260686004 = 129304002, 405813007 = 15497006 } { 260686004 = 129304002, 405813007 = 31435000 }"
    :b   "71388002 : { 260686004 = 129304002, 405813007 = 15497006 }"}

   {:description "identical expressions — mutual subsumption"
    :exp true :a "80146002" :b "80146002"}

   {:description "terms ignored — with and without term"
    :exp true :a "80146002 |Appendectomy|" :b "80146002"}])

(deftest ^:live subsumption
  (with-open [st (store/open-store "snomed.db/store.db")]
    (doseq [{:keys [description exp a b]} subsumption-test-cases]
      (testing description
        (is (= exp (scg/subsumes? st (scg/parse a) (scg/parse b))))))))

(deftest ^:live subsumption-symmetry
  (with-open [st (store/open-store "snomed.db/store.db")]
    (testing "mutual subsumption = equivalence"
      (let [a (scg/parse "80146002")
            b (scg/parse "80146002")]
        (is (and (scg/subsumes? st a b) (scg/subsumes? st b a)))))
    (testing "terms don't affect symmetry"
      (let [a (scg/parse "80146002 |Appendectomy|")
            b (scg/parse "80146002")]
        (is (and (scg/subsumes? st a b) (scg/subsumes? st b a)))))))

(comment
  (run-tests))
