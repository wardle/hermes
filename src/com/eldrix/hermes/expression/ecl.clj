(ns com.eldrix.hermes.expression.ecl
  "Support for SNOMED CT expression constraint language.
  See http://snomed.org/ecl"
  (:require [clojure.data.zip.xml :as zx]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.zip :as zip]
            [com.eldrix.hermes.impl.language :as lang]
            [com.eldrix.hermes.impl.search :as search]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.snomed :as snomed]
            [instaparse.core :as insta])
  (:import (org.apache.lucene.search Query)))

(def ecl-parser
  (insta/parser (io/resource "ecl.abnf") :input-format :abnf :output-format :enlive))

(declare parse-ecl-attribute-set)
(declare parse-ecl-refinement)
(declare parse-expression-constraint)
(declare parse-subexpression-constraint)

(defn- parse-sctId [sctId]
  (edn/read-string (zx/xml1-> sctId zx/text)))

(defn- parse-conceptId [conceptId]
  (zx/xml1-> conceptId :sctId parse-sctId))

(defn- parse-concept-reference [cr]
  (let [conceptId (zx/xml1-> cr :conceptId parse-conceptId)
        term (zx/xml1-> cr :term zx/text)]
    (merge {:conceptId conceptId}
           (when term {:term term}))))

(defn parse-constraint-operator
  "constraintOperator = childOf / childOrSelfOf / descendantOrSelfOf / descendantOf / parentOf / parentOrSelfOf / ancestorOrSelfOf / ancestorOf"
  [loc]
  (:tag (first (zip/down loc))))

(defn parse-focus-concept
  "eclFocusConcept = eclConceptReference / wildCard"
  [loc]
  (let [cr (zx/xml1-> loc :eclConceptReference parse-concept-reference)
        wildcard (zx/xml1-> loc :wildCard zx/text)]
    (if cr
      cr
      :wildcard)))


(defn realise-concept-ids
  "Realise a query as a set of concept identifiers.
  TODO: exception if results > max-hits"
  [ctx ^Query q]
  (search/do-query (:searcher ctx) q 10000))


(defn parse-conjunction-expression-constraint
  "conjunctionExpressionConstraint = subExpressionConstraint 1*(ws conjunction ws subExpressionConstraint)"
  [ctx loc]
  (search/q-and (zx/xml-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))))


(defn parse-disjunction-expression-constraint
  "disjunctionExpressionConstraint = subExpressionConstraint 1*(ws disjunction ws subExpressionConstraint)"
  [ctx loc]
  (search/q-or (zx/xml-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))))

(defn parse-exclusion-expression-constraint [ctx loc])

(defn parse-compound-expression-constraint
  "compoundExpressionConstraint = conjunctionExpressionConstraint / disjunctionExpressionConstraint / exclusionExpressionConstraint"
  [ctx loc]
  (or (zx/xml1-> loc :conjunctionExpressionConstraint (partial parse-conjunction-expression-constraint ctx))
      (zx/xml1-> loc :disjunctionExpressionConstraint (partial parse-disjunction-expression-constraint ctx))
      (zx/xml1-> loc :exclusionExpressionConstraint (partial parse-exclusion-expression-constraint ctx))))

(defn- process-dotted
  "Sequentially resolve dotted attributes in sequence, evaluating from left to right.
   eg.
   <  19829001 |Disorder of lung| .
         < 47429007 |Associated with| .  363698007 |Finding site|

   1. Finding descendants of |Disorder of lung|
      - this should be provided as `base-concept-ids`
   2. Finding the set of attribute values for these concepts (with an attribute
      type that is any subtype of  |Associated with|),
   3. From these attribute value concepts, finding the value of any
   |Finding sites| attribute. "
  [ctx base-concept-ids dotted-expression-attributes]
  (loop [concept-ids base-concept-ids
         attributes dotted-expression-attributes]
    (let [expression (first attributes)]
      (if-not expression
        (search/q-concept-ids concept-ids)                  ;; return result as a query against the concept identifiers.
        (do
          (let [attrs-concept-ids (realise-concept-ids ctx expression) ;; realise the concept-identifiers for the property (e.g. all descendants of "associated with")
                result (into #{} (mapcat #(store/get-parent-relationships-of-types (:store ctx) % attrs-concept-ids) concept-ids))] ;; and get those values for all of our current concepts
            (recur result (next attributes))))))))

(defn parse-dotted-expression-constraint
  "dottedExpressionConstraint = subExpressionConstraint 1*(ws dottedExpressionAttribute)
  eg: <  19829001 |Disorder of lung| . < 47429007 |Associated with| . 363698007 |Finding site|"
  [ctx loc]
  (let [subexpression-constraint (zx/xml1-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))
        dotted-expression-attributes (zx/xml-> loc :dottedExpressionAttribute :eclAttributeName :subExpressionConstraint (partial parse-subexpression-constraint ctx))]
    (if (> (count dotted-expression-attributes) 0)
      (let [values (realise-concept-ids ctx subexpression-constraint)] ;;  get concepts of '< 19829001'
        (process-dotted ctx values dotted-expression-attributes))
      subexpression-constraint)))

(defn parse-filter-constraint [loc]
  (throw (ex-info "filter constraints not yet implemented" {:text (zx/text loc)})))

(defn parse-cardinality [loc]
  (let [min-value (Long/parseLong (zx/xml1-> loc :minValue zx/text))
        max-value (zx/xml1-> loc :maxValue zx/text)]
    {:min-value min-value
     :max-value (if (= max-value "*")
                  0
                  (Long/parseLong max-value))}))

(defn parse-attribute--expression
  [ctx cardinality reverse-flag? attribute-concept-ids loc]
  (let [
        ;; a list of concepts satisfying the subexpression constraint (ie the value of the property-value refinement)
        subexp-result (realise-concept-ids ctx (zx/xml1-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx)))
        ;; a query of concepts
        attribute-query (search/q-or (map #(search/q-attribute-in-set % subexp-result) attribute-concept-ids))]
    (cond
      ;; we are not trying to implement edge case of an expression containing both cardinality and reversal, at least not yet
      (and cardinality reverse-flag?)
      (throw (ex-info "expressions containing both cardinality and reverse flag not supported." {:text (zx/text loc)}))

      ;; if reverse, we need to take the values (subexp-result), and for each take the value(s) of the property
      ;; specified to build a list of concept identifiers from which to build a query.
      reverse-flag?
      (process-dotted ctx subexp-result [(search/q-concept-ids attribute-concept-ids)])

      ;; if we have cardinality, add a clause to ensure we have the right count for those properties
      cardinality
      (search/q-and
        (conj (map #(search/q-attribute-count % (:min-value cardinality) (:max-value cardinality)) attribute-concept-ids)
              attribute-query))

      :else
      attribute-query)))

(defn parse-ecl-attribute
  "eclAttribute = [\"[\" cardinality \"]\" ws]
  [reverseFlag ws] eclAttributeName ws
  (expressionComparisonOperator ws subExpressionConstraint /
  numericComparisonOperator ws \"#\" numericValue /
  stringComparisonOperator ws QM stringValue QM /
  booleanComparisonOperator ws booleanValue)"
  [ctx loc]
  (let [cardinality (zx/xml1-> loc :cardinality parse-cardinality)
        reverse-flag? (zx/xml1-> loc :reverseFlag zx/text)
        ecl-attribute-name (zx/xml1-> loc :eclAttributeName :subExpressionConstraint (partial parse-subexpression-constraint ctx))
        ;; resolve the attribute(s) - we logically AND to ensure all are valid attributes (ie descendants of 246061005 - snomed/Attribute)
        ;; this means a wildcard (*) attribute doesn't accidentally bring in the whole >600000 concepts in SNOMED CT!
        attribute-concept-ids (when ecl-attribute-name
                                (realise-concept-ids ctx (search/q-and [(search/q-descendantOf snomed/Attribute) ecl-attribute-name]))) ;; realise the attributes in the expression
        expression-operator (zx/xml1-> loc :expressionComparisonOperator zx/text)
        numeric-operator (zx/xml1-> loc :numericComparisonOperator zx/text)
        string-operator (zx/xml1-> loc :stringComparisonOperator zx/text)
        boolean-operator (zx/xml1-> loc :booleanComparisonOperator zx/text)]
    (cond
      expression-operator
      (parse-attribute--expression ctx cardinality reverse-flag? attribute-concept-ids loc)

      numeric-operator
      (throw (ex-info "expressions containing numeric concrete refinements not yet supported." {:text (zx/text loc)}))

      string-operator
      (throw (ex-info "expressions containing string concrete refinements not yet supported." {:text (zx/text loc)}))

      boolean-operator
      (throw (ex-info "expressions containing boolean concrete refinements not yet supported." {:text (zx/text loc)}))

      :else
      (throw (ex-info "expression ECLAttribute does not have a supported operator (expression/numeric/string/boolean)." {:text (zx/text loc)})))))


(defn parse-subattribute-set
  "subAttributeSet = eclAttribute / \"(\" ws eclAttributeSet ws \")\""
  [ctx loc]
  (let [ecl-attribute (zx/xml1-> loc :eclAttribute (partial parse-ecl-attribute ctx))
        ecl-attribute-set (zx/xml1-> loc :eclAttributeSet parse-ecl-attribute-set)]
    (cond
      (and ecl-attribute ecl-attribute-set) (search/q-and [ecl-attribute ecl-attribute-set])
      ecl-attribute ecl-attribute
      ecl-attribute-set ecl-attribute-set)))

(defn parse-ecl-attribute-set
  "eclAttributeSet = subAttributeSet ws [conjunctionAttributeSet / disjunctionAttributeSet]"
  [ctx loc]
  (let [subattribute-set (zx/xml1-> loc :subAttributeSet (partial parse-subattribute-set ctx))
        conjunction-attribute-set (zx/xml-> loc :conjunctionAttributeSet :subAttributeSet (partial parse-subattribute-set ctx))
        disjunction-attribute-set (zx/xml-> loc :disjunctionAttributeSet :subAttributeSet (partial parse-subattribute-set ctx))]
    (cond
      (and (conj conjunction-attribute-set subattribute-set))
      (search/q-and (conj conjunction-attribute-set subattribute-set))

      (and subattribute-set disjunction-attribute-set)
      (search/q-or (conj disjunction-attribute-set subattribute-set))

      :else
      subattribute-set)))

(defn parse-ecl-attribute-group
  "eclAttributeGroup = [\"[\" cardinality \"]\" ws] \"{\" ws eclAttributeSet ws \"}\""
  [ctx loc]
  (let [cardinality (zx/xml1-> loc :cardinality parse-cardinality)
        ecl-attribute-set (zx/xml1-> loc :eclAttributeSet (partial parse-ecl-attribute-set ctx))]
    (if-not cardinality
      ecl-attribute-set
      (throw (ex-info "cardinality in ECL attribute groups not yet implemented."
                      {:text            (zx/text loc)
                       :cardinality     cardinality
                       :eclAttributeSet ecl-attribute-set})))))

(defn parse-sub-refinement
  "subRefinement = eclAttributeSet / eclAttributeGroup / \"(\" ws eclRefinement ws \")\"\n"
  [ctx loc]
  (or (zx/xml1-> loc :eclAttributeSet (partial parse-ecl-attribute-set ctx))
      (zx/xml1-> loc :eclAttributeGroup (partial parse-ecl-attribute-group ctx))
      (zx/xml1-> loc :eclRefinement (partial parse-ecl-refinement ctx))))

(defn parse-ecl-refinement
  "subRefinement ws [conjunctionRefinementSet / disjunctionRefinementSet]"
  [ctx loc]
  (let [sub-refinement (zx/xml1-> loc :subRefinement (partial parse-sub-refinement ctx))
        conjunction-refinement-set (zx/xml-> loc :conjunctionRefinementSet :subRefinement (partial parse-sub-refinement ctx))
        disjunction-refinement-set (zx/xml-> loc :disjunctionRefinementSet :subRefinement (partial parse-sub-refinement ctx))]
    (cond
      (and sub-refinement conjunction-refinement-set)
      (search/q-and (conj conjunction-refinement-set sub-refinement))
      (and sub-refinement disjunction-refinement-set)
      (search/q-or (conj disjunction-refinement-set sub-refinement))
      :else sub-refinement)))


(defn parse-subexpression-constraint
  "subExpressionConstraint = [constraintOperator ws] [memberOf ws] (eclFocusConcept / \"(\" ws expressionConstraint ws \")\") *(ws filterConstraint)"
  [ctx loc]
  (let [constraint-operator (zx/xml1-> loc :constraintOperator parse-constraint-operator)
        member-of (zx/xml1-> loc :memberOf)
        focus-concept (zx/xml1-> loc :eclFocusConcept parse-focus-concept)
        wildcard? (= :wildcard focus-concept)
        expression-constraint (zx/xml1-> loc :expressionConstraint (partial parse-expression-constraint ctx))
        filter-constraints (zx/xml-> loc :filterConstraint parse-filter-constraint)]
    (cond
      ;; "*"
      (and (nil? member-of) (nil? constraint-operator) wildcard?) ;; "*" = all concepts
      (search/q-descendantOrSelfOf snomed/Root)             ;; see https://confluence.ihtsdotools.org/display/DOCECL/6.1+Simple+Expression+Constraints

      ;; "<< *"
      (and (= :descendantOrSelfOf constraint-operator) wildcard?) ;; "<< *" = all concepts
      (search/q-descendantOrSelfOf snomed/Root)

      ;; ">> *"
      (and (= :ancestorOrSelfOf constraint-operator) wildcard?) ;; ">> *" = all concepts
      (search/q-ancestorOrSelfOf ctx snomed/Root)

      ;; "< *"
      (and (= :descendantOf constraint-operator) wildcard?) ;; "< *" = all concepts except root
      (search/q-descendantOf snomed/Root)

      ;; "<! *"
      (and (= :childOf constraint-operator) wildcard?)      ;; "<! *" = all concepts except root
      (search/q-descendantOf snomed/Root)

      ;; "> *"
      (and (= :ancestorOf constraint-operator) wildcard?)   ;; TODO: support returning all non-leaf concepts
      (throw (ex-info "wildcard expressions containing '> *' not yet supported" {:text (zx/text loc)}))

      ;; ">! *"
      (and (= :parentOf constraint-operator) wildcard?)     ;; TODO: support returning all non-leaf concepts
      (throw (ex-info "wildcard expressions containing '>! *' not yet supported" {:text (zx/text loc)}))

      ;; "^ *"
      (and member-of wildcard?)                             ;; "^ *" = all concepts that are referenced by any reference set in the substrate:
      (search/q-memberOfInstalledReferenceSet (:store ctx))

      ;; "^ conceptId"
      (and member-of (:conceptId focus-concept))
      (search/q-memberOf (:conceptId focus-concept))

      (and member-of expression-constraint)
      (search/q-memberOf (realise-concept-ids ctx expression-constraint))

      (and (nil? constraint-operator) expression-constraint)
      expression-constraint

      (and (= :descendantOf constraint-operator) expression-constraint)
      (search/q-descendantOfAny (realise-concept-ids ctx expression-constraint))

      (and (= :descendentOrSelfOf constraint-operator) expression-constraint)
      (search/q-descendantOrSelfOfAny (realise-concept-ids ctx expression-constraint))

      (and (= :childOf constraint-operator) expression-constraint)
      (search/q-childOfAny (realise-concept-ids ctx expression-constraint))

      (and (= :childOrSelfOf constraint-operator) expression-constraint)
      (search/q-childOrSelfOfAny (realise-concept-ids ctx expression-constraint))

      (and (= :ancestorOf constraint-operator) expression-constraint)
      (search/q-ancestorOfAny (:store ctx) (realise-concept-ids ctx expression-constraint))

      (and (= :ancestorOrSelfOf constraint-operator) expression-constraint)
      (search/q-ancestorOrSelfOfAny (:store ctx) (realise-concept-ids ctx expression-constraint))

      (and (= :parentOf constraint-operator) expression-constraint)
      (search/q-parentOfAny (:store ctx) (realise-concept-ids ctx expression-constraint))

      (and (= :parentOrSelfOf constraint-operator) expression-constraint)
      (search/q-parentOrSelfOfAny (:store ctx) (realise-concept-ids ctx expression-constraint))

      ;; "conceptId"  == SELF
      (and (nil? constraint-operator) (:conceptId focus-concept))
      (search/q-self (:conceptId focus-concept))

      ;; "< conceptId"
      (and (= :descendantOf constraint-operator) (:conceptId focus-concept))
      (search/q-descendantOf (:conceptId focus-concept))

      ;; "<< conceptId"
      (and (= :descendantOrSelfOf constraint-operator) (:conceptId focus-concept))
      (search/q-descendantOrSelfOf (:conceptId focus-concept))

      ;; "<! conceptId"
      (and (= :childOf constraint-operator) (:conceptId focus-concept))
      (search/q-childOf (:conceptId focus-concept))

      ;; "<<! conceptId"
      (and (= :childOrSelfOf constraint-operator) (:conceptId focus-concept))
      (search/q-childOrSelfOf (:conceptId focus-concept))

      ;; "> conceptId"
      (and (= :ancestorOf constraint-operator) (:conceptId focus-concept))
      (search/q-ancestorOf (:store ctx) (:conceptId focus-concept))

      ;; ">> conceptId"
      (and (= :ancestorOrSelfOf constraint-operator) (:conceptId focus-concept))
      (search/q-ancestorOrSelfOf (:store ctx) (:conceptId focus-concept))

      ;; ">! conceptId"
      (and (= :parentOf constraint-operator) (:conceptId focus-concept))
      (search/q-parentOf (:store ctx) (:conceptId focus-concept))

      ;; ">>! conceptId"
      (and (= :parentOrSelfOf constraint-operator) (:conceptId focus-concept))
      (search/q-parentOrSelfOf (:store ctx) (:conceptId focus-concept))

      :else
      (throw (ex-info "error: unimplemented expression fragment; use `(ex-data *e)` to see context."
                      {:text                  (zx/text loc)
                       :constraint-operator   constraint-operator
                       :member-of             member-of
                       :focus-concept         focus-concept
                       :expression-constraint expression-constraint
                       :filter-constraints    filter-constraints})))))

(defn parse-refined-expression-constraint
  [ctx loc]
  (let [subexpression (zx/xml1-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))
        ecl-refinement (zx/xml1-> loc :eclRefinement (partial parse-ecl-refinement ctx))]
    (search/q-and [subexpression ecl-refinement])))

(defn parse-expression-constraint
  "expressionConstraint = ws ( refinedExpressionConstraint / compoundExpressionConstraint / dottedExpressionConstraint / subExpressionConstraint ) ws"
  [ctx loc]
  (or (zx/xml1-> loc :refinedExpressionConstraint (partial parse-refined-expression-constraint ctx))
      (zx/xml1-> loc :compoundExpressionConstraint (partial parse-compound-expression-constraint ctx))
      (zx/xml1-> loc :dottedExpressionConstraint (partial parse-dotted-expression-constraint ctx))
      (zx/xml1-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))))

(defn parse
  "Parse SNOMED-CT ECL, as defined by the expression constraint language
  See http://snomed.org/ecl"
  [store searcher s]
  (zx/xml1-> (zip/xml-zip (ecl-parser s))
             :expressionConstraint
             (partial parse-expression-constraint {:store    store
                                                   :searcher searcher})))

(comment
  (do
    (def store (store/open-store "snomed.db/store.db"))
    (def index-reader (search/open-index-reader "snomed.db/search.db"))
    (def searcher (org.apache.lucene.search.IndexSearcher. index-reader))
    (def testq (comp clojure.pprint/print-table (partial search/test-query store searcher)))
    (def pe (partial parse store searcher))
    )


  ;; this should be satisfied only by the specified concept
  (def self "404684003 |Clinical finding|")
  (pe self)

  (def descendantOf "<  404684003 |Clinical finding|")
  (pe descendantOf)

  (pe " <<  73211009 |Diabetes mellitus|")
  (pe " <  73211009 |Diabetes mellitus|")

  (pe "<!  404684003 |Clinical finding|")
  (pe "<<!  404684003 |Clinical finding|")
  (pe ">  40541001 |Acute pulmonary edema|")
  (pe ">>  40541001 |Acute pulmonary edema|")
  (pe ">!  40541001 |Acute pulmonary edema|")
  (pe ">>!  40541001 |Acute pulmonary edema|")
  (pe "^  700043003 |Example problem list concepts reference set|")

  (pe "*")
  (testq (pe "^*") 1000)

  (def refinement " <  19829001 |Disorder of lung| :         116676008 |Associated morphology|  =  79654002 |Edema|")
  (pe refinement)

  (pe "   <  19829001 |Disorder of lung| :          116676008 |Associated morphology|  = <<  79654002 |Edema|")
  (pe "<  404684003 |Clinical finding| :\n         363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure| , \n         116676008 |Associated morphology|  = <<  415582006 |Stenosis|")

  (pe "  <  404684003 |Clinical finding| :\n         363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure| , \n         116676008 |Associated morphology|  = <<  415582006 |Stenosis|")

  (pe "<  404684003 |Clinical finding| :\n         363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure| , \n         116676008 |Associated morphology|  = <<  415582006 |Stenosis|")

  ;; this has descendants of associated with as a property so should match any of those with
  ;; any of the descendants of oedema.
  (pe " <<  404684003 |Clinical finding| :\n        <<  47429007 |Associated with|  = <<  267038008 |Edema|")

  (pe "<  373873005 |Pharmaceutical / biologic product| : [3..3]  127489000 |Has active ingredient|  = <  105590001 |Substance|")


  (pe "<  404684003 |Clinical finding| :   363698007 |Finding site|  =     <<  39057004 |Pulmonary valve structure| ,  116676008 |Associated morphology|  =     <<  415582006 |Stenosis|")

  (def loc (zx/xml1-> (zip/xml-zip (ecl-parser refinement)) :expressionConstraint))
  loc



  (def conjunction1 "<  19829001 |Disorder of lung|  AND     <  301867009 |Edema of trunk|")
  (pe conjunction1)

  (testq (search/q-descendantOf 24700007) 1000)
  (testq (search/q-descendantOrSelfOf 24700007) 1000)
  (testq (search/q-childOf 24700007) 1000)
  (testq (pe "<! 24700007|Multiple sclerosis|") 1000)
  (testq (pe "^  991411000000109 |Emergency care diagnosis simple reference set|") 1000)
  (testq (search/q-attribute-count 127489000 4 4) 1000)
  (testq (search/q-and [
                        (search/q-attribute-count 127489000 4 4)
                        (search/q-attribute-in-set 127489000 (store/get-all-children store 387517004))]) 10000)
  )