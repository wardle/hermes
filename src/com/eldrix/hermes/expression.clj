(ns com.eldrix.hermes.expression
  (:require [clojure.java.io :as io]
            [instaparse.core :as insta]
            [clojure.data.zip.xml :as zx]
            [clojure.zip :as zip]))

(def cg-parser
  (insta/parser (io/resource "cg-v2.4.abnf") :input-format :abnf :output-format :enlive))

(def ecl-parser
  (insta/parser (io/resource "ecl.abnf") :input-format :abnf :output-format :enlive))

(defn parse-sctId [sctId]
  (clojure.edn/read-string (zx/xml1-> sctId zx/text)))

(defn parse-conceptId [conceptId]
  {:sctId (zx/xml1-> conceptId :sctId parse-sctId)})
(defn parse-conceptId [conceptId]
  (zx/xml1-> conceptId :sctId parse-sctId))

(defn parse-concept-reference [cr]
  (let [conceptId (zx/xml1-> cr :conceptId parse-conceptId)
        term (zx/xml1-> cr :term zx/text)]
    (merge {:conceptId conceptId}
           (when term {:term term}))))

(defn parse-focus-concept [focus-concept]
  ;  {:conceptReferences (zx/xml-> focus-concept :conceptReference parse-concept-reference)})
  (zx/xml-> focus-concept :conceptReference parse-concept-reference))

(defn parse-attribute-name
  "attributeName = conceptReference"
  [attribute-name]
  ;;  {:conceptReference (zx/xml1-> attribute-name :conceptReference parse-concept-reference)})
  (zx/xml1-> attribute-name :conceptReference parse-concept-reference))

(declare parse-subexpression)

(defn parse-expression-value
  "expressionValue = conceptReference / '(' ws subExpression ws ')'"
  [expression-value]
  (let [conceptReference (zx/xml1-> expression-value :conceptReference parse-concept-reference)
        subExpression (zx/xml1-> expression-value :subExpression parse-subexpression)]
    (if conceptReference conceptReference subExpression))
  )

(defn parse-attribute-value
  "attributeValue = expressionValue / QM stringValue QM / '#' numericValue / booleanValue"
  [attribute-value]
  (let [expressionValue (zx/xml1-> attribute-value :expressionValue parse-expression-value)
        stringValue (zx/xml1-> attribute-value :stringValue zx/text)
        numericValue (zx/xml1-> attribute-value :numericValue zx/text)
        booleanValue (zx/xml1-> attribute-value :booleanValue zx/text)]
    (cond
      expressionValue expressionValue
      stringValue stringValue
      numericValue (clojure.edn/read-string numericValue)
      booleanValue [(Boolean/parseBoolean booleanValue)])))      ;; hide boolean in vector so we don't break zipper

(defn parse-attribute
  "attribute = attributeName ws \"=\" ws attributeValue"
  [attribute]
  {(zx/xml1-> attribute :attributeName parse-attribute-name)
   (zx/xml1-> attribute :attributeValue parse-attribute-value)})

(defn parse-attribute-set
  "attributeSet = attribute *(ws \",\" ws attribute)"
  [attribute-set]
  (apply merge (zx/xml-> attribute-set :attribute parse-attribute)))

(defn parse-attribute-group
  "attributeGroup = '{' ws attributeSet ws '}'"
  [attribute-group]
  (zx/xml-> attribute-group :attributeSet parse-attribute-set))

(defn parse-refinement
  "refinement = (attributeSet / attributeGroup) *( ws [\",\" ws] attributeGroup )"
  [refinement]
  (let [attributeSet (zx/xml-> refinement :attributeSet parse-attribute-set)
        attributeGroup (zx/xml-> refinement :attributeGroup parse-attribute-group)]
    (concat
      (when (seq attributeSet) attributeSet)
      (when (seq attributeGroup) attributeGroup)
      )))

(defn parse-subexpression
  "subExpression = focusConcept [ws \":\" ws refinement]"
  [subexpression]
  (let [focusConcept {:focusConcepts (zx/xml-> subexpression :focusConcept parse-focus-concept)}
        refinements (zx/xml-> subexpression :refinement parse-refinement)]
    (if (seq refinements) (merge focusConcept {:refinements refinements})
                          focusConcept)))

(defn parse-expression [expression]
  (let [ds (zx/xml1-> expression :definitionStatus zx/text)]
    {:definitionStatus (if ds ds "===")
     :subExpression    (zx/xml1-> expression :subExpression parse-subexpression)}))

(comment
  (def p (cg-parser "24700007"))
  (def p (cg-parser "80146002|appendectomy|:260870009|priority|=25876001|emergency|, 425391005|using access device|=86174004|laparoscope|"))
  (def p (cg-parser "421720008 |Spray dose form|  +  7946007 |Drug suspension|"))
  (def p (cg-parser "  71388002 |Procedure| :
       405815000 |Procedure device|  =  122456005 |Laser device| ,
       260686004 |Method|  =  129304002 |Excision - action| ,
       405813007 |Procedure site - direct|  =  15497006 |Ovarian structure|"))
  (def p (cg-parser "313056006 |Epiphysis of ulna| :  272741003 |Laterality|  =  7771000 |Left|"))
  (def p (cg-parser "   119189000 |Ulna part|  +  312845000 |Epiphysis of upper limb| :  272741003 |Laterality|  =  7771000 |Left|"))
  (def p (cg-parser "    71388002 |Procedure| :
   {  260686004 |Method|  =  129304002 |Excision - action| ,
      405813007 |Procedure site - direct|  =  15497006 |Ovarian structure| }
   {  260686004 |Method|  =  129304002 |Excision - action| ,
      405813007 |Procedure site - direct|  =  31435000 |Fallopian tube structure| }"))
  (def p (cg-parser "    71388002 |procedure| :
   {  260686004 |method|  =  129304002 |excision - action| ,
      405813007 |procedure site - direct| =  20837000 |structure of right ovary| ,
      424226004 |using device|  =  122456005 |laser device| }
   {  260686004 |method|  =  261519002 |diathermy excision - action| ,
      405813007 |procedure site - direct| =  113293009 |structure of left fallopian tube| }"))
  (def p (cg-parser "   373873005 |Pharmaceutical / biologic product| :
       411116001 |Has dose form|  = ( 421720008 |Spray dose form|  +  7946007 |Drug suspension| )"))
  (def p (cg-parser "    243796009 |Situation with explicit context| :
   {  408730004 |Procedure context|  =  385658003 |Done| ,
      408731000 |Temporal context|  =  410512000 |Current or specified| ,
      408732007 |Subject relationship context|  =  410604004 |Subject of record| ,
      363589002 |Associated procedure|  =
     (  397956004 |Prosthetic arthroplasty of the hip| :
           405814001 |Procedure site - indirect|  = ( 24136001 |Hip joint structure| :
              272741003 |Laterality|  =  7771000 |Left| )
          {  363699004 |Direct device|  =  304120007 |Total hip replacement prosthesis| ,
             260686004 |Method|  =  425362007 |Surgical insertion - action| } ) }"))
  (def p (cg-parser "  774586009 |Amoxicillin only product| :
       411116001 |Has manufactured dose form|  =  420692007 |Oral capsule| ,
      {  127489000 |Has active ingredient|  =  372687004 |Amoxicillin| ,
         179999999100 |Has basis of strength|  =  372687004 |Amoxicillin| ,
         189999999103 |Has strength value|  = #500,  199999999101 |Has strength unit|  =  258684004 |mg| }"))
  (def p (cg-parser " 91143003 |Albuterol| :
       411116001 |Has manufactured dose form|  =  385023001 |oral solution| ,
      { 127489000 |Has active ingredient|  =  372897005 |Albuterol| ,
        179999999100 |Has basis of strength|  =  372897005 |Albuterol| ,
        189999999103 |Has strength value|  = #0.083,  199999999101 |Has strength numerator unit|  =  118582008 |%| }"))
  (def p (cg-parser "   322236009 |Paracetamol 500mg tablet| :  209999999104 |Has trade name|  = \"PANADOL\""))
  (def p (cg-parser "318969005 |Irbesartan 150 mg oral tablet| :  859999999102 |Is in national benefit scheme|  = TRUE"))
  (def p (cg-parser " <<<  73211009 |Diabetes mellitus| :  363698007 |Finding site|  =  113331007 |Endocrine system|"))
  (do
    (def root (zip/xml-zip p))
    (zx/xml1-> root :expression parse-expression))
  (def result (zx/xml1-> root :expression parse-expression))

  (require '[clojure.data.json :as json])
  (println (json/write-str result))

  )
