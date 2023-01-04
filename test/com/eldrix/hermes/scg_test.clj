(ns com.eldrix.hermes.scg-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [com.eldrix.hermes.impl.scg :as scg]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.store-test]))


(def example-expressions
  {
   :simple-expression        {:multiple-sclerosis "24700007"
                              :diabetes-mellitis  "73211009 |Diabetes mellitus|"}
   :multiple-dose-forms      {:spray-suspension "421720008 |Spray dose form|  +  7946007 |Drug suspension|"
                              :missing-term     "421720008 +  7946007 |Drug suspension|"}
   :simple-refinements       {:oophorectomy "83152002 |Oophorectomy| :  405815000 |Procedure device|  =  122456005 |Laser device|"}
   :multiple-attributes      {:oophoerectomy " 71388002 |Procedure| :
                                               405815000 |Procedure device|  =  122456005 |Laser device| ,
                                               260686004 |Method|  =  129304002 |Excision - action| ,
                                               405813007 |Procedure site - direct|  =  15497006 |Ovarian structure|"}
   :conjoined-refinements    {:left-ulnar-epiphysis1 "   313056006 |Epiphysis of ulna| :  272741003 |Laterality|  =  7771000 |Left|"
                              :left-ulnar-epiphysis2 "   119189000 |Ulna part|  +  312845000 |Epiphysis of upper limb| :  272741003 |Laterality|  =  7771000 |Left|"}
   :attribute-groups         {:salpingo-oophorectomy   "71388002 |Procedure| :
                                                        {  260686004 |Method|  =  129304002 |Excision - action| ,
                                                           405813007 |Procedure site - direct|  =  15497006 |Ovarian structure| }
                                                        {  260686004 |Method|  =  129304002 |Excision - action| ,
                                                           405813007 |Procedure site - direct|  =  31435000 |Fallopian tube structure| }"
                              :with-diathermy-excision "71388002 |procedure| :
                                                        {  260686004 |method|                  =  129304002 |excision - action| ,
                                                           405813007 |procedure site - direct| =  20837000 |structure of right ovary| ,
                                                           424226004 |using device|            =  122456005 |laser device| }
                                                        {  260686004 |method|                  =  261519002 |diathermy excision - action| ,
                                                           405813007 |procedure site - direct| =  113293009 |structure of left fallopian tube| }"}
   :attributes-in-same-group {:no-brackets   "   71388002 |Procedure| :                 ;; these two should be equivalent
                                                 260686004 |Method|  =  129304002 |Excision - action| ,
                                                 405813007 |Procedure site - direct|  =  15497006 |Ovarian structure|"
                              :with-brackets "   71388002 |procedure| :
                                                 {  260686004 |method|  =  129304002 |excision - action| ,
                                                    405813007 |procedure site - direct|  =  15497006 |ovarian structure| } "}
   :nested-refinements       {:drug-spray-suspension " 373873005 |Pharmaceutical / biologic product| :
                                                       411116001 |Has dose form|  = ( 421720008 |Spray dose form|  +  7946007 |Drug suspension| )"
                              :left-hip              "  24136001 |Hip joint structure| :
                                                        272741003 |Laterality|  =  7771000 |Left|"
                              :left-hip-replacement  "397956004 |Prosthetic arthroplasty of the hip| :
                                                      363704007 |Procedure site|  = ( 24136001 |Hip joint structure| :
                                                      272741003 |Laterality|      =  7771000 |Left| )"}
   :concrete-values          {:amoxicillin-500          "774586009 |Amoxicillin only product| :
                                       411116001 |Has manufactured dose form| = 420692007 |Oral capsule| ,
                                       { 127489000 |Has active ingredient|    = 372687004 |Amoxicillin| ,
                                         179999999100 |Has basis of strength| = 372687004 |Amoxicillin| ,
                                         189999999103 |Has strength value|    = #500,
                                         199999999101 |Has strength unit|     =  258684004 |mg| }"
                              :albuterol-0.083          "91143003 |Albuterol| :
                                       411116001 |Has manufactured dose form|  =  385023001 |oral solution| ,
                                      { 127489000 |Has active ingredient|           =  372897005 |Albuterol| ,
                                        179999999100 |Has basis of strength|        =  372897005 |Albuterol| ,
                                        189999999103 |Has strength value|           = #0.083,
                                        199999999101 |Has strength numerator unit|  =  118582008 |%| }"
                              :paracetamol-panadol      "   322236009 |Paracetamol 500mg tablet| :  209999999104 |Has trade name|  = \"PANADOL\""
                              :irbesartan-reimburseable "318969005 |Irbesartan 150 mg oral tablet| :  859999999102 |Is in national benefit scheme|  = TRUE"}
   :subtype-of               {:diabetes-mellitis "  <<<  73211009 |Diabetes mellitus| :  363698007 |Finding site|  =  113331007 |Endocrine system|"}})



(deftest simple-concept
  (let [p (scg/parse "24700007")]
    (is (= 24700007 (:conceptId (first (get-in p [:subExpression :focusConcepts])))))
    (is (= "===" (:definitionStatus p)))))

(deftest refinements
  (let [p (scg/parse "80146002|appendectomy|:260870009|priority|=25876001|emergency|, 425391005|using access device|=86174004|laparoscope|")
        focus-concepts (map :conceptId (get-in p [:subExpression :focusConcepts]))]
    (is (= 1 (count focus-concepts)))
    (is (= 80146002 (first focus-concepts)))))

(defn test-roundtrip [s]
  (let [p1 (scg/parse s)
        r (scg/render {} p1)
        p2 (scg/parse r)]
    (is (= p1 p2))))

(deftest render-round-tripping
  (let [examples (flatten (map vals (vals example-expressions)))]
    (doseq [example examples]
      (test-roundtrip (apply str example)))))

(deftest ^:live updating-terms
    (with-open [st (store/open-store "snomed.db/store.db")]
      (let [updated (->> (get-in example-expressions [:concrete-values :albuterol-0.083])
                         (scg/parse)
                         (scg/render {:store st :update-terms? true :locale-priorities "en-GB"})
                         (scg/parse))
            has-active-ingredient {:conceptId 127489000, :term "Has active ingredient"}
            refinements-group2 (second (get-in updated [:subExpression :refinements]))]
        (is (= {:conceptId 372897005 :term "Salbutamol"} (get refinements-group2 has-active-ingredient  )) "Albuterol not updated to salbutamol for en-GB locale"))))

(comment
  (run-tests))

