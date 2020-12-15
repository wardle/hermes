(ns com.eldrix.hermes.expression.ecl
  "Implementation of the SNOMED CT expression constraint language.
  See http://snomed.org/ecl"
  (:require [clojure.data.zip.xml :as zx]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.zip :as zip]
            [com.eldrix.hermes.impl.language :as lang]
            [com.eldrix.hermes.impl.search :as search]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.snomed :as snomed]
            [instaparse.core :as insta])
  (:import (org.apache.lucene.search Query)))

(def ecl-parser
  (insta/parser (io/resource "ecl-v1.5.abnf") :input-format :abnf :output-format :enlive))

(declare parse)
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

(defn- parse-constraint-operator
  "constraintOperator = childOf / childOrSelfOf / descendantOrSelfOf / descendantOf / parentOf / parentOrSelfOf / ancestorOrSelfOf / ancestorOf"
  [loc]
  (:tag (first (zip/down loc))))

(defn- parse-focus-concept
  "eclFocusConcept = eclConceptReference / wildCard"
  [loc]
  (let [cr (zx/xml1-> loc :eclConceptReference parse-concept-reference)]
    (if cr cr :wildcard)))

(defn realise-concept-ids
  "Realise a query as a set of concept identifiers.
  TODO: exception if results > max-hits"
  [ctx ^Query q]
  (search/do-query-for-concepts (:searcher ctx) q 10000))


(defn- parse-conjunction-expression-constraint
  "conjunctionExpressionConstraint = subExpressionConstraint 1*(ws conjunction ws subExpressionConstraint)"
  [ctx loc]
  (search/q-and (zx/xml-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))))


(defn- parse-disjunction-expression-constraint
  "disjunctionExpressionConstraint = subExpressionConstraint 1*(ws disjunction ws subExpressionConstraint)"
  [ctx loc]
  (search/q-or (zx/xml-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))))

(defn- parse-exclusion-expression-constraint
  "Parse an exclusion expression contraint.
  Unlike conjunction and disjunction constraints, exclusion constraints have
  only two clauses.
  subExpressionConstraint ws exclusion ws subExpressionConstraint"
  [ctx loc]
  (let [[exp exclusion] (zx/xml-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))]
    (search/q-not exp exclusion)))

(defn- parse-compound-expression-constraint
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
        (let [attrs-concept-ids (realise-concept-ids ctx expression) ;; realise the concept-identifiers for the property (e.g. all descendants of "associated with")
              result (into #{} (mapcat #(store/get-parent-relationships-of-types (:store ctx) % attrs-concept-ids) concept-ids))] ;; and get those values for all of our current concepts
          (recur result (next attributes)))))))

(defn- parse-dotted-expression-constraint
  "dottedExpressionConstraint = subExpressionConstraint 1*(ws dottedExpressionAttribute)
  eg: <  19829001 |Disorder of lung| . < 47429007 |Associated with| . 363698007 |Finding site|"
  [ctx loc]
  (let [subexpression-constraint (zx/xml1-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))
        dotted-expression-attributes (zx/xml-> loc :dottedExpressionAttribute :eclAttributeName :subExpressionConstraint (partial parse-subexpression-constraint ctx))]
    (if (> (count dotted-expression-attributes) 0)
      (let [values (realise-concept-ids ctx subexpression-constraint)] ;;  get concepts of '< 19829001'
        (process-dotted ctx values dotted-expression-attributes))
      subexpression-constraint)))

(defn- parse-match-search-term-set [loc]
  (let [terms (zx/xml-> loc :matchSearchTerm zx/text)]
    (search/q-and (map search/q-term terms))))

(defn- parse-wild-search-term-set
  "wildSearchTermSet = QM wildSearchTerm QM"
  [loc]
  (let [term (zx/xml1-> loc :wildSearchTerm zx/text)]
    (search/q-wildcard term)))

(declare parse-typed-search-term)

(defn- parse-typed-search-term-set
  "typedSearchTermSet = \"(\" ws typedSearchTerm *(mws typedSearchTerm) ws \")\""
  [loc]
  (let [terms (zx/xml-> loc :typedSearchTerm parse-typed-search-term)]
    (search/q-and terms)))

(defn- parse-typed-search-term [loc]
  (or (zx/xml1-> loc :matchSearchTermSet parse-match-search-term-set)
      (zx/xml1-> loc :wildSearchTermSet parse-wild-search-term-set)))

(defn- parse-term-filter
  "termFilter = termKeyword ws booleanComparisonOperator ws (typedSearchTerm / typedSearchTermSet)"
  [loc]
  (let [boolean-comparison-operator (zx/xml1-> loc :booleanComparisonOperator zx/text) ;; "=" or "!="
        typed-search-term (zx/xml-> loc :typedSearchTerm parse-typed-search-term)
        typed-search-term-set (zx/xml1-> loc :typedSearchTermSet parse-typed-search-term-set)]
    (cond
      (and (= "=" boolean-comparison-operator) (seq typed-search-term))
      typed-search-term

      (and (= "=" boolean-comparison-operator) (seq typed-search-term-set))
      typed-search-term-set

      ;; TODO: support "!=" as a boolean comparison operator
      :else
      (throw (ex-info "unsupported term filter" {:s (zx/text loc)})))))

(defn- parse-language-filter [loc]
  (throw (ex-info "language filters are not supported and should be deprecated; please use dialect filter / language reference sets" {:text (zx/text loc)})))

(defn- parse-type-id-filter
  "typeIdFilter = typeId ws booleanComparisonOperator ws (eclConceptReference / eclConceptReferenceSet)\n"
  [ctx loc]
  (let [boolean-comparison-operator (zx/xml1-> loc :booleanComparisonOperator zx/text)
        ecl-concept-reference (zx/xml1-> loc :eclConceptReference :conceptId parse-conceptId)
        ecl-concept-references (zx/xml-> loc :eclConceptReferenceSet :eclConceptReference :conceptId parse-conceptId)]
    (cond
      (and (= "=" boolean-comparison-operator) ecl-concept-reference)
      (search/q-type ecl-concept-reference)

      (and (= "=" boolean-comparison-operator) ecl-concept-references)
      (search/q-typeAny ecl-concept-references)

      ;; for '!=", we ask SNOMED for all concepts that are a subtype of 900000000000446008 and then subtract the concept reference(s).
      (and (= "!=" boolean-comparison-operator) ecl-concept-reference)
      (search/q-typeAny (disj (store/get-all-children (:store ctx) 900000000000446008) ecl-concept-reference))

      (and (= "!=" boolean-comparison-operator) ecl-concept-references)
      (search/q-typeAny (set/difference (store/get-all-children (:store ctx) 900000000000446008) ecl-concept-references))

      :else
      (throw (ex-info "unknown type-id filter" {:s (zx/text loc)})))))

(def ^:private type-token->type-id
  {:FSN 900000000000003001
   :SYN 900000000000013009
   :DEF 900000000000550004})

(defn- parse-type-token-filter
  "type ws booleanComparisonOperator ws (typeToken / typeTokenSet)
  typeToken = synonym / fullySpecifiedName / definition
  typeTokenSet = \"(\" ws typeToken *(mws typeToken) ws \")\""
  [ctx loc]
  (let [boolean-comparison-operator (zx/xml1-> loc :booleanComparisonOperator zx/text)
        type-token (keyword (zx/xml1-> loc :typeToken zx/text))
        type-tokens (map keyword (zx/xml-> loc :typeTokenSet :typeToken zx/text))
        types (map type-token->type-id (filter identity (conj type-tokens type-token)))
        type-ids (case boolean-comparison-operator
                   "=" types
                   "!=" (set/difference (store/get-all-children (:store ctx) 900000000000446008) (set types))
                   (throw (ex-info "invalid boolean operator for type token filter" {:s (zx/text loc) :op boolean-comparison-operator})))]
    (search/q-typeAny type-ids)))

(defn- parse-type-filter
  "typeFilter = typeIdFilter / typeTokenFilter"
  [ctx loc]
  (or (zx/xml1-> loc :typeIdFilter (partial parse-type-id-filter ctx))
      (zx/xml1-> loc :typeTokenFilter (partial parse-type-token-filter ctx))))


(def ^:private acceptability->kw
  "Map a token or a concept identifier to a keyword."
  {"accept"           :acceptable-in
   "acceptable"       :acceptable-in
   900000000000549004 :acceptable-in
   "prefer"           :preferred-in
   "preferred"        :preferred-in
   900000000000548007 :preferred-in})

(defn- parse-acceptability-set->kws
  "Parse acceptability set into a sequence of keywords.
  Result is either ':acceptable-in' or ':preferred-in'
  acceptabilitySet = acceptabilityIdSet / acceptabilityTokenSet
  acceptabilityIdSet = eclConceptReferenceSet
  acceptabilityTokenSet = ( ws acceptabilityToken *(mws acceptabilityToken) ws )
  acceptabilityToken = acceptable / preferred"
  [loc]
  (let [ids (or (seq (zx/xml-> loc :acceptabilityIdSet :eclConceptReferenceSet :eclConceptReference :conceptId parse-conceptId))
                (map str/lower-case (zx/xml-> loc :acceptabilityTokenSet :acceptabilityToken zx/text)))]
    (map acceptability->kw ids)))


(defn- parse-dialect-set
  "Parse either a dialect-alias-set or a dialect-id-set. Turns either a concept id or a dialect alias into
  a refset identifier. Returns as a vector - dialect reference set id then acceptability and so on.

  dialectAliasSet = \"(\" ws dialectAlias [ws acceptabilitySet] *(mws dialectAlias [ws acceptabilitySet] ) ws \")\"
  dialectIdSet = \"(\" ws eclConceptReference [ws acceptabilitySet] *(mws eclConceptReference [ws acceptabilitySet] ) ws \")\""
  [default-acceptability loc]
  ;; A dialect set is tricky to parse as there are optional acceptability sets refining the acceptability for the
  ;; alias/id in question, but a potential broad acceptability as defined at the filter level which should be applied to
  ;; each alias/id unless one is explicitly stated at the dialect alias/id set level.
  ;; So, here we get the components of the dialect alias/id set as a sequence of pairs, handling missing acceptabilities as
  ;; needed.
  (loop [tag (zip/down loc)
         results []]
    (if (zip/end? tag)
      (let [c (count results)]
        (cond (= c 0) nil
              (even? c) results
              :else (conj results default-acceptability)))
      (let [alias (zx/xml1-> tag :dialectAlias zx/text)
            mapped (lang/dialect->refset-id alias)          ;; doesn't matter if alias is nil
            concept-id (zx/xml1-> tag :eclConceptReference :conceptId parse-conceptId)
            acceptability (zx/xml1-> tag :acceptabilitySet parse-acceptability-set->kws)]
        (recur
          (zip/next tag)
          (let [c (count results)]
            (cond
              (and (nil? alias) (nil? concept-id) (nil? acceptability)) ;; keep on looping if its some other tag
              results

              (and alias (nil? mapped))
              (throw (ex-info "unknown dialect: '" alias "'"))

              (and (even? c) mapped)                        ;; if it's an alias or id, and we're ready for it, add it
              (conj results mapped)

              (and (even? c) concept-id)
              (conj results concept-id)

              (and (odd? c) mapped)                         ;; if it's an alias or id, and we're not ready, insert an acceptability first
              (apply conj results [default-acceptability mapped])

              (and (odd? c) concept-id)
              (apply conj results [default-acceptability concept-id])

              (and (even? c) acceptability)                 ;; if it's an acceptability and we've not had an alias - fail fast  (should never happen)
              (throw (ex-info "parse error: acceptability before dialect alias" {:s (zx/text loc) :alias alias :acceptability acceptability :results results :count count}))

              (and (odd? c) acceptability)                  ;; if it's an acceptability and we're ready, add it.
              (conj results acceptability))))))))

(defn- parse-dialect-id-filter
  "dialectIdFilter = dialectId ws booleanComparisonOperator ws (eclConceptReference / dialectIdSet)"
  [acceptability-set loc]
  (let [boolean-comparison-operator (zx/xml1-> loc :booleanComparisonOperator zx/text)
        refset-id (zx/xml1-> loc :eclConceptReference :conceptId parse-conceptId)
        refset-ids (zx/xml-> loc :dialectAliasSet (partial parse-dialect-set acceptability-set))]
    (cond
      (and (= "=" boolean-comparison-operator) refset-id acceptability-set)
      (search/q-acceptability acceptability-set refset-id)

      (and (= "=" boolean-comparison-operator) refset-id)
      (search/q-description-memberOf refset-id)

      (and (= "=" boolean-comparison-operator) (seq refset-ids))
      (let [m (apply hash-map refset-ids)]
        (search/q-or (map (fn [[refset-id accept]]
                            (if accept
                              (search/q-acceptability accept refset-id)
                              (search/q-description-memberOf refset-id))) m)))
      :else
      (throw (ex-info "unimplemented dialect alias filter" {:s (zx/text loc)})))))

(defn- parse-dialect-alias-filter
  "dialectAliasFilter = dialect ws booleanComparisonOperator ws (dialectAlias / dialectAliasSet)"
  [acceptability-set loc]
  (let [op (zx/xml1-> loc :booleanComparisonOperator zx/text)
        dialect-alias (lang/dialect->refset-id (zx/xml1-> loc :dialectAlias zx/text))
        dialect-aliases (zx/xml-> loc :dialectAliasSet (partial parse-dialect-set acceptability-set))]
    (cond
      (and (= "=" op) acceptability-set dialect-alias)
      (search/q-acceptability acceptability-set dialect-alias)

      (and (= "=" op) dialect-alias)
      (search/q-description-memberOf dialect-alias)

      (and (= "=" op) (seq dialect-aliases))
      (let [m (apply hash-map dialect-aliases)]
        (search/q-or (map (fn [[refset-id accept]]
                            (if accept
                              (search/q-acceptability accept refset-id)
                              (search/q-description-memberOf refset-id))) m)))

      :else
      (throw (ex-info "unimplemented dialect alias filter" {:s (zx/text loc)})))))


(defn- parse-dialect-filter
  "dialectFilter = (dialectIdFilter / dialectAliasFilter) [ ws acceptabilitySet ]"
  [loc]
  ;; Pass the acceptability set to the parsers of dialectIdFilter or dialectAliasFilter
  ;  because adding acceptability changes the generated query from one of concept
  ;  refset membership to using the 'preferred-in' and 'acceptable-in' indexes
  ;  specially designed for that purpose.
  (let [acceptability-set (zx/xml1-> loc :acceptabilitySet parse-acceptability-set->kws)]
    (or (zx/xml1-> loc :dialectIdFilter (partial parse-dialect-id-filter acceptability-set))
        (zx/xml1-> loc :dialectAliasFilter (partial parse-dialect-alias-filter acceptability-set)))))

(defn- parse-filter
  "filter = termFilter / languageFilter / typeFilter / dialectFilter"
  [ctx loc]
  (or (zx/xml1-> loc :termFilter parse-term-filter)
      (zx/xml1-> loc :languageFilter parse-language-filter)
      (zx/xml1-> loc :typeFilter (partial parse-type-filter ctx))
      (zx/xml1-> loc :dialectFilter parse-dialect-filter)))

(defn- parse-filter-constraint
  "filterConstraint = \"{{\" ws filter *(ws \",\" ws filter) ws \"}}\""
  [ctx loc]
  (search/q-and (zx/xml-> loc :filter (partial parse-filter ctx))))

(defn- parse-cardinality [loc]
  (let [min-value (Long/parseLong (zx/xml1-> loc :minValue zx/text))
        max-value (zx/xml1-> loc :maxValue zx/text)]
    {:min-value min-value
     :max-value (if (= max-value "*")
                  0
                  (Long/parseLong max-value))}))

(defn- make-nested-query
  "Generate a nested query with the function 'f' specified. Each query
  is realised as a list of concept identifiers which are passed to `f`
  and then re-combined."
  [ctx ^Query query f]
  (let [[incl excl] (search/rewrite-query query)
        incl-concepts (when incl (realise-concept-ids ctx incl))
        excl-concepts (when excl (realise-concept-ids ctx excl))]
    (if (and incl excl)
      (search/q-not (f incl-concepts) (f excl-concepts))
      (f incl-concepts))))

(defn- make-attribute-query
  "Generate a nested query for the attributes specified, rewriting any
  exclusion clauses in the parent nested context. "
  [ctx query attribute-concept-ids]
  (let [[incl excl] (search/rewrite-query query)
        incl-concepts (when incl (realise-concept-ids ctx incl))
        excl-concepts (when excl (realise-concept-ids ctx excl))]
    (if (and incl excl)
      (search/q-not (search/q-or (map #(search/q-attribute-in-set % incl-concepts) attribute-concept-ids))
                    (search/q-and (map #(search/q-attribute-in-set % excl-concepts) attribute-concept-ids)))
      (search/q-or (map #(search/q-attribute-in-set % incl-concepts) attribute-concept-ids)))))

(defn- parse-attribute--expression
  [ctx cardinality reverse-flag? attribute-concept-ids loc]
  (let [
        sub-expression (zx/xml1-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))
        attribute-query (make-attribute-query ctx sub-expression attribute-concept-ids)]
    (cond

      ;; we are not trying to implement edge case of an expression containing both cardinality and reversal, at least not yet
      ;; see https://confluence.ihtsdotools.org/display/DOCECL/6.3+Cardinality for how it *should* work
      (and cardinality reverse-flag?)
      (throw (ex-info "expressions containing both cardinality and reverse flag not yet supported." {:text (zx/text loc)}))

      ;; if reverse, we need to take the values (subexp-result), and for each take the value(s) of the property
      ;; specified to build a list of concept identifiers from which to build a query.
      reverse-flag?
      (process-dotted ctx (realise-concept-ids ctx sub-expression) [(search/q-concept-ids attribute-concept-ids)])

      ;; if we have cardinality, add a clause to ensure we have the right count for those properties
      cardinality
      (search/q-and (filter identity
                            (conj (map #(search/q-attribute-count % (:min-value cardinality) (:max-value cardinality)) attribute-concept-ids)
                                  attribute-query)))

      :else
      attribute-query)))

(defn- parse-ecl-attribute
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
    (when-not (seq attribute-concept-ids)
      (throw (ex-info "attribute expression resulted in no valid attributes" {:s (zx/text loc) :eclAttributeName ecl-attribute-name})))
    (cond
      expression-operator
      (case expression-operator
        "=" (parse-attribute--expression ctx cardinality reverse-flag? attribute-concept-ids loc)
        "!=" (search/q-not (search/q-match-all) (parse-attribute--expression ctx cardinality reverse-flag? attribute-concept-ids loc))
        (throw (ex-info (str "unsupported expression operator " expression-operator) {:s (zx/text loc) :eclAttributeName ecl-attribute-name})))

      numeric-operator
      (throw (ex-info "expressions containing numeric concrete refinements not yet supported." {:text (zx/text loc)}))

      string-operator
      (throw (ex-info "expressions containing string concrete refinements not yet supported." {:text (zx/text loc)}))

      boolean-operator
      (throw (ex-info "expressions containing boolean concrete refinements not yet supported." {:text (zx/text loc)}))

      :else
      (throw (ex-info "expression does not have a supported operator (expression/numeric/string/boolean)." {:text (zx/text loc)})))))

(defn- parse-subattribute-set
  "subAttributeSet = eclAttribute / \"(\" ws eclAttributeSet ws \")\""
  [ctx loc]
  (let [ecl-attribute (zx/xml1-> loc :eclAttribute (partial parse-ecl-attribute ctx))
        ecl-attribute-set (zx/xml1-> loc :eclAttributeSet parse-ecl-attribute-set)]
    (cond
      (and ecl-attribute ecl-attribute-set)
      (search/q-and [ecl-attribute ecl-attribute-set])

      ecl-attribute ecl-attribute
      ecl-attribute-set ecl-attribute-set)))

(defn- parse-ecl-attribute-set
  "eclAttributeSet = subAttributeSet ws [conjunctionAttributeSet / disjunctionAttributeSet]"
  [ctx loc]
  (let [subattribute-set (zx/xml1-> loc :subAttributeSet (partial parse-subattribute-set ctx))
        conjunction-attribute-set (zx/xml-> loc :conjunctionAttributeSet :subAttributeSet (partial parse-subattribute-set ctx))
        disjunction-attribute-set (zx/xml-> loc :disjunctionAttributeSet :subAttributeSet (partial parse-subattribute-set ctx))]
    (cond
      (and conjunction-attribute-set subattribute-set)
      (search/q-and (conj conjunction-attribute-set subattribute-set))

      (and subattribute-set disjunction-attribute-set)
      (search/q-or (conj disjunction-attribute-set subattribute-set))

      :else
      subattribute-set)))

(defn- parse-ecl-attribute-group
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

(defn- parse-sub-refinement
  "subRefinement = eclAttributeSet / eclAttributeGroup / \"(\" ws eclRefinement ws \")\"\n"
  [ctx loc]
  (or (zx/xml1-> loc :eclAttributeSet (partial parse-ecl-attribute-set ctx))
      (zx/xml1-> loc :eclAttributeGroup (partial parse-ecl-attribute-group ctx))
      (zx/xml1-> loc :eclRefinement (partial parse-ecl-refinement ctx))))

(defn- parse-ecl-refinement
  "subRefinement ws [conjunctionRefinementSet / disjunctionRefinementSet]"
  [ctx loc]
  (let [sub-refinement (zx/xml1-> loc :subRefinement (partial parse-sub-refinement ctx))
        conjunction-refinement-set (zx/xml-> loc :conjunctionRefinementSet :subRefinement (partial parse-sub-refinement ctx))
        disjunction-refinement-set (zx/xml-> loc :disjunctionRefinementSet :subRefinement (partial parse-sub-refinement ctx))]
    (cond
      (and sub-refinement (seq conjunction-refinement-set))
      (search/q-and (conj conjunction-refinement-set sub-refinement))
      (and sub-refinement (seq disjunction-refinement-set))
      (search/q-or (conj disjunction-refinement-set sub-refinement))
      :else sub-refinement)))


(defn- parse-subexpression-constraint
  "subExpressionConstraint = [constraintOperator ws] [memberOf ws] (eclFocusConcept / \"(\" ws expressionConstraint ws \")\") *(ws filterConstraint)"
  [ctx loc]
  (let [constraint-operator (zx/xml1-> loc :constraintOperator parse-constraint-operator)
        member-of (zx/xml1-> loc :memberOf)
        focus-concept (zx/xml1-> loc :eclFocusConcept parse-focus-concept)
        wildcard? (= :wildcard focus-concept)
        expression-constraint (zx/xml1-> loc :expressionConstraint (partial parse-expression-constraint ctx))
        filter-constraints (zx/xml-> loc :filterConstraint (partial parse-filter-constraint ctx))
        base-query (cond
                     ;; "*"
                     (and (nil? member-of) (nil? constraint-operator) wildcard?) ;; "*" = all concepts
                     (search/q-descendantOrSelfOf snomed/Root) ;; see https://confluence.ihtsdotools.org/display/DOCECL/6.1+Simple+Expression+Constraints

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
                     (and (= :childOf constraint-operator) wildcard?) ;; "<! *" = all concepts except root
                     (search/q-descendantOf snomed/Root)

                     ;; "> *"
                     (and (= :ancestorOf constraint-operator) wildcard?) ;; TODO: support returning all non-leaf concepts
                     (throw (ex-info "wildcard expressions containing '> *' not yet supported" {:text (zx/text loc)}))

                     ;; ">! *"
                     (and (= :parentOf constraint-operator) wildcard?) ;; TODO: support returning all non-leaf concepts
                     (throw (ex-info "wildcard expressions containing '>! *' not yet supported" {:text (zx/text loc)}))

                     ;; "^ *"
                     (and member-of wildcard?)              ;; "^ *" = all concepts that are referenced by any reference set in the substrate:
                     (search/q-memberOfInstalledReferenceSet (:store ctx))

                     ;; "^ conceptId"
                     (and member-of (:conceptId focus-concept))
                     (search/q-memberOf (:conceptId focus-concept))

                     (and member-of expression-constraint)
                     (make-nested-query ctx expression-constraint search/q-memberOfAny)

                     (and (nil? constraint-operator) expression-constraint)
                     expression-constraint

                     (and (= :descendantOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint search/q-descendantOfAny)

                     (and (= :descendentOrSelfOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint search/q-descendantOrSelfOfAny)

                     (and (= :childOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint search/q-childOfAny)

                     (and (= :childOrSelfOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint search/q-childOrSelfOfAny)

                     (and (= :ancestorOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint (partial search/q-ancestorOfAny (:store ctx)))

                     (and (= :ancestorOrSelfOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint (partial search/q-ancestorOrSelfOfAny (:store ctx)))

                     (and (= :parentOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint (partial search/q-parentOfAny (:store ctx)))

                     (and (= :parentOrSelfOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint (partial search/q-parentOrSelfOfAny (:store ctx)))

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
                                      :filter-constraints    filter-constraints})))]
    (if filter-constraints
      (search/q-and (conj filter-constraints base-query))
      base-query)))

(defn- parse-refined-expression-constraint
  [ctx loc]
  (let [subexpression (zx/xml1-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))
        ecl-refinement (zx/xml1-> loc :eclRefinement (partial parse-ecl-refinement ctx))]
    (search/q-and [subexpression ecl-refinement])))

(defn- parse-expression-constraint
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
  (let [p (ecl-parser s)]
    (if (insta/failure? p)
      (let [fail (insta/get-failure p)]
        (throw (ex-info (str "invalid SNOMED ECL expression at line " (:line p) ", column " (:column p) ": '" (:text p) "'.") fail)))
      (zx/xml1-> (zip/xml-zip p)
                 :expressionConstraint
                 (partial parse-expression-constraint {:store    store
                                                       :searcher searcher})))))

(comment
  ;; TODO: move into live service test suite
  (do
    (def store (store/open-store "snomed.db/store.db"))
    (def index-reader (search/open-index-reader "snomed.db/search.db"))
    (def searcher (org.apache.lucene.search.IndexSearcher. index-reader))
    (require '[clojure.pprint :as pp])
    (def testq (comp pp/print-table (partial search/test-query store searcher)))
    (def pe (partial parse store searcher))
    )
  (pe "404684003 |Clinical finding|")
  (pe "<  404684003 |Clinical finding|")
  (pe " <<  73211009 |Diabetes mellitus|")
  (pe " <  73211009 |Diabetes mellitus|")
  (pe "<!  404684003 |Clinical finding|")
  (pe "<<!  404684003 |Clinical finding|")
  (pe ">  40541001 |Acute pulmonary edema|")
  (pe ">>  40541001 |Acute pulmonary edema|")
  (pe ">!  40541001 |Acute pulmonary edema|")
  (pe ">>!  40541001 |Acute pulmonary edema|")
  (pe "^  700043003 |Example problem list concepts reference set|")
  (pe " <  19829001 |Disorder of lung| :         116676008 |Associated morphology|  =  79654002 |Edema|")
  (testq (pe " <  19829001 |Disorder of lung| :         116676008 |Associated morphology|  =  79654002 |Edema|") 1000)
  (pe "   <  19829001 |Disorder of lung| :          116676008 |Associated morphology|  = <<  79654002 |Edema|")
  (pe "<  404684003 |Clinical finding| :\n         363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure| , \n         116676008 |Associated morphology|  = <<  415582006 |Stenosis|")
  (pe "  <  404684003 |Clinical finding| :\n         363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure| , \n         116676008 |Associated morphology|  = <<  415582006 |Stenosis|")
  (pe "<  404684003 |Clinical finding| :\n         363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure| , \n         116676008 |Associated morphology|  = <<  415582006 |Stenosis|")

  ;; this has descendants of associated with as a property so should match any of those with
  ;; any of the descendants of oedema.
  (testq (pe " <<  404684003 |Clinical finding| :\n        <<  47429007 |Associated with|  = <<  267038008 |Edema|") 100000)
  (testq (pe "<  373873005 |Pharmaceutical / biologic product| : [3..5]  127489000 |Has active ingredient|  = <  105590001 |Substance|") 10000)
  (pe "<  404684003 |Clinical finding| :   363698007 |Finding site|  =     <<  39057004 |Pulmonary valve structure| ,  116676008 |Associated morphology|  =     <<  415582006 |Stenosis|")
  (pe "<  19829001 |Disorder of lung|  AND     <  301867009 |Edema of trunk|")
  (pe "<  64572001 |Disease|  {{ term = \"box\", type = syn, dialect = ( en-gb (accept) en-nhs-clinical )  }}")
  (pe "<  64572001 |Disease|  {{ term = \"box\", type = syn, dialect = ( en-gb (accept) en-nhs-clinical )  }}")
  (pe "<  404684003 |Clinical finding| : 116676008 |Associated morphology|  =
     ((<<  56208002 |Ulcer|  AND \n    <<  50960005 |Hemorrhage| ) MINUS \n    <<  26036001 |Obstruction| )")
  )