; Copyright (c) 2020-2023 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns ^:no-doc com.eldrix.hermes.impl.ecl
  "Implementation of the SNOMED CT expression constraint language.
  See http://snomed.org/ecl"
  (:require [clojure.data.zip.xml :as zx]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.zip :as zip]
            [com.eldrix.hermes.impl.language :as lang]
            [com.eldrix.hermes.impl.members :as members]
            [com.eldrix.hermes.impl.search :as search]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.rf2]
            [com.eldrix.hermes.snomed :as snomed]
            [instaparse.combinators :as c]
            [instaparse.core :as insta])
  (:import (org.apache.lucene.search Query IndexSearcher)
           (java.time LocalDate)
           (java.util.regex Pattern)
           (com.eldrix.hermes.snomed Result)))

(s/def ::query #(instance? Query %))
(s/def ::store any?)
(s/def ::searcher #(instance? IndexSearcher %))
(s/def ::memberSearcher #(instance? IndexSearcher %))
(s/def ::localeMatchFn fn?)
(s/def ::ctx (s/keys :req-un [::store ::searcher ::memberSearcher ::localeMatchFn]))
(s/def ::loc any?)

(def ecl-grammar
  (-> (c/abnf (slurp (io/resource "ecl-v2.2.abnf")))
      (update :memberFilter #(apply c/ord (:parsers %)))   ;; see https://github.com/wardle/hermes/issues/72 - force ordering
      (assoc
       :UTF8-2 (c/unicode-char 0x0080 0x07ff)
       :UTF8-3 (c/unicode-char 0x0800 0xffff)
       :UTF8-4 (c/unicode-char 0x10000 0x10ffff))))

(insta/defparser ecl-parser
  ecl-grammar
  :start         :expressionConstraint
  :input-format  :abnf
  :output-format :enlive)

(declare parse)
(declare parse-ecl-attribute-set)
(declare parse-ecl-refinement)
(declare parse-expression-constraint)
(declare parse-subexpression-constraint)

(defn parse-sctId [sctId]
  (parse-long (zx/xml1-> sctId zx/text)))

(defn parse-conceptId [conceptId]
  (zx/xml1-> conceptId :sctId parse-sctId))

(defn parse-concept-reference [cr]
  (let [conceptId (zx/xml1-> cr :conceptId parse-conceptId)
        term (zx/xml1-> cr :term zx/text)]
    (cond-> {:conceptId conceptId}
      term (assoc :term term))))

(defn parse-constraint-operator
  "Returns constraint operator as a keyword.
  For example, :descendantOrSelfOf

  constraintOperator = childOf / childOrSelfOf / descendantOrSelfOf / descendantOf / parentOf / parentOrSelfOf / ancestorOrSelfOf / ancestorOf / top / bottom"
  [loc]
  (:tag (first (zip/down loc))))

(defn parse-alt-identifier
  " altIdentifier = (QM altIdentifierSchemeAlias \" #\" altIdentifierCodeWithinQuotes QM / altIdentifierSchemeAlias \" #\" altIdentifierCodeWithoutQuotes) [ws \" | \" ws term ws \" | \"]
    altIdentifierSchemeAlias = alpha *(dash / alpha / integerValue)
    altIdentifierCodeWithinQuotes = 1*anyNonEscapedChar
    altIdentifierCodeWithoutQuotes = 1*(alpha / digit / dash / \" . \" / \" _ \")"
  [loc]
  (throw (ex-info "unsupported clause altIdentifier " {:s (zx/text loc)})))

(defn parse-focus-concept
  "eclFocusConcept = eclConceptReference / wildCard / altIdentifier"
  [loc]
  (or (zx/xml1-> loc :eclConceptReference parse-concept-reference)
      (zx/xml1-> loc :altIdentifier parse-alt-identifier)
      :wildcard))

(s/fdef realise-concept-ids
  :args (s/cat :ctx ::ctx :q ::query))
(defn realise-concept-ids
  "Realise a query as a set of concept identifiers."
  [{:keys [searcher]} ^Query q]
  (search/do-query-for-concept-ids searcher q))

(defn parse-conjunction-expression-constraint
  "conjunctionExpressionConstraint = subExpressionConstraint 1*(ws conjunction ws subExpressionConstraint)"
  [ctx loc]
  (search/q-and (zx/xml-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))))

(defn parse-disjunction-expression-constraint
  "disjunctionExpressionConstraint = subExpressionConstraint 1*(ws disjunction ws subExpressionConstraint)"
  [ctx loc]
  (search/q-or (zx/xml-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))))

(defn parse-exclusion-expression-constraint
  "Parse an exclusion expression contraint.
  Unlike conjunction and disjunction constraints, exclusion constraints have
  only two clauses.
  subExpressionConstraint ws exclusion ws subExpressionConstraint"
  [ctx loc]
  (let [[exp exclusion] (zx/xml-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))]
    (search/q-not exp exclusion)))

(defn parse-compound-expression-constraint
  "compoundExpressionConstraint = conjunctionExpressionConstraint / disjunctionExpressionConstraint / exclusionExpressionConstraint"
  [ctx loc]
  (or (zx/xml1-> loc :conjunctionExpressionConstraint (partial parse-conjunction-expression-constraint ctx))
      (zx/xml1-> loc :disjunctionExpressionConstraint (partial parse-disjunction-expression-constraint ctx))
      (zx/xml1-> loc :exclusionExpressionConstraint (partial parse-exclusion-expression-constraint ctx))))

(defn process-dotted
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
  [{:keys [store] :as ctx} base-concept-ids dotted-expression-attributes]
  (loop [concept-ids base-concept-ids
         attributes dotted-expression-attributes]
    (let [expression (first attributes)]
      (if-not expression
        (search/q-concept-ids concept-ids)                  ;; return result as a query against the concept identifiers.
        (let [attrs-concept-ids (realise-concept-ids ctx expression) ;; realise the concept-identifiers for the property (e.g. all descendants of "associated with")
              result (into #{} (mapcat #(store/parent-relationships-of-types store % attrs-concept-ids)) concept-ids)] ;; and get those values for all of our current concepts
          (recur result (next attributes)))))))

(defn parse-dotted-expression-constraint
  "dottedExpressionConstraint = subExpressionConstraint 1*(ws dottedExpressionAttribute)
  eg: <  19829001 |Disorder of lung| . < 47429007 |Associated with| . 363698007 |Finding site|"
  [ctx loc]
  (let [subexpression-constraint (zx/xml1-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))
        dotted-expression-attributes (zx/xml-> loc :dottedExpressionAttribute :eclAttributeName :subExpressionConstraint (partial parse-subexpression-constraint ctx))]
    (if (pos? (count dotted-expression-attributes))
      (let [values (realise-concept-ids ctx subexpression-constraint)] ;;  get concepts of '< 19829001'
        (process-dotted ctx values dotted-expression-attributes))
      subexpression-constraint)))

(defn fold
  "Fold text according to the database default locale."
  [{:keys [localeMatchFn]} s]
  (let [refset-id (first (localeMatchFn))]
    (lang/fold refset-id s)))

(defn match-by-wildcard
  "Return a function that, given a search [[Result]], returns the description
  id when its folded lowercase term matches `pattern`, otherwise nil. Intended
  for use with `keep` over a sequence of results."
  [ctx ^Pattern pattern]
  (fn [^Result r]
    (let [term (.term r)]
      (when (and term (re-matches pattern (str/lower-case (fold ctx term))))
        (.id r)))))

(defn parse-match-search-term-set
  [ctx loc]
  (let [terms (zx/xml-> loc :matchSearchTerm zx/text)]
    (search/q-and (map #(search/q-term (fold ctx %)) terms))))

(s/fdef ecl-wildcard->pattern
  :args (s/cat :s string?))
(defn ecl-wildcard->pattern
  "Compile a folded ECL wildcard string into a regex pattern intended for
  `re-matches` (which requires the whole input to match). ECL uses '*' as the
  only wildcard character. '\\*', '\\\\' and '\\\"' represent literal '*',
  '\\' and '\"' respectively."
  ^Pattern [^String s]
  (let [sb (StringBuilder.)]
    (loop [i 0]
      (if (>= i (count s))
        (Pattern/compile (.toString sb))
        (let [ch (.charAt s i)]
          (if (= ch \\)
            (if (< (inc i) (count s))
              (let [next-ch (.charAt s (inc i))]
                (case next-ch
                  \* (do (.append sb (Pattern/quote "*"))
                         (recur (+ i 2)))
                  \\ (do (.append sb (Pattern/quote "\\"))
                         (recur (+ i 2)))
                  \" (do (.append sb (Pattern/quote "\""))
                         (recur (+ i 2)))
                  (do (.append sb (Pattern/quote "\\"))
                      (recur (inc i)))))
              (do (.append sb (Pattern/quote "\\"))
                  (recur (inc i))))
            (do
              (.append sb (if (= ch \*) ".*" (Pattern/quote (str ch))))
              (recur (inc i)))))))))

(defn wildcard-prefilter-clause
  "Build the fastest available Lucene query for wildcard string `s`:
  1. Pure alphanumeric `foo`        → `q-term` (exact token match)
  2. Alphanumeric then trailing `*` → `q-wildcard` (term-dictionary prefix scan)
  3. Otherwise                      → `q-and` of `*run*` wildcards, one per
                                      contiguous alphanumeric run (term-dictionary contains scan)

  Returns nil when `s` has no alphanumeric content (e.g. a bare `*`)."
  [s]
  (cond
    (re-matches #"(?u)\p{Alnum}+" s)
    (search/q-term s)

    (re-matches #"(?u)\p{Alnum}+\*+" s)
    (search/q-wildcard s)

    :else
    (when-let [runs (seq (re-seq #"(?u)\p{Alnum}+" s))]
      (search/q-and (map #(search/q-wildcard (str "*" % "*")) runs)))))

(defn wildcard-prefilter-query
  "Generate a best-effort candidate query for a folded ECL wildcard string.
  Correctness comes from the whole-term post-filter, so the prefilter only
  needs to be a sound (no false-negative) approximation.

  Falls back to match-all when no component contains any alphanumeric content
  (e.g. a bare `*`). **Callers must guard this fallback**: running the
  Java-side post-filter against every description in the index is an
  O(all-descriptions) scan. [[parse-wild-search-term-set]] enforces the guard
  by requiring a narrowing outer subexpression before accepting a match-all
  prefilter; any future caller must do the same."
  [folded-term]
  (let [clauses (->> (str/split folded-term #"\s+")
                     (remove str/blank?)
                     (map wildcard-prefilter-clause)
                     (remove nil?))]
    (or (search/q-and clauses) (search/q-match-all))))

(defn parse-wild-search-term-set
  "wildSearchTermSet = QM wildSearchTerm QM

  When `base-query` is supplied and is not a match-all, it is ANDed with the
  wildcard prefilter before iterating descriptions. This restricts the Java-
  side regex post-filter to descriptions whose concept is in the outer
  substrate, avoiding a full description-index scan for expressions like
  `<< 24700007 {{ D term = wild:\"*sclero*\" }}`. A wildcard with no
  alphanumeric content (e.g. bare `*`) produces a match-all candidate; we
  reject it unless the outer substrate can narrow the scan, to avoid
  iterating every description in the index."
  [ctx base-query loc]
  (let [term (zx/xml1-> loc :wildSearchTerm zx/text)
        folded-term (str/lower-case (fold ctx term))
        pattern (ecl-wildcard->pattern folded-term)
        candidate-query (wildcard-prefilter-query folded-term)
        narrowing-base? (and base-query (not (search/q-match-all? base-query)))]
    (when (and (not narrowing-base?) (search/q-match-all? candidate-query))
      (throw (ex-info "wildcard term with no alphanumeric content requires an outer subexpression to narrow the description scan"
                      {:term term})))
    (let [narrowed-query (if narrowing-base?
                           (search/q-and [base-query candidate-query])
                           candidate-query)]
      (->> (search/do-query-for-results (:searcher ctx) narrowed-query nil)
           (into #{} (keep (match-by-wildcard ctx pattern)))
           (search/q-description-ids)))))

(defn parse-typed-search-term
  [ctx base-query loc]
  (or (zx/xml1-> loc :matchSearchTermSet #(parse-match-search-term-set ctx %))
      (zx/xml1-> loc :wildSearchTermSet #(parse-wild-search-term-set ctx base-query %))))

(defn parse-typed-search-term-set
  "typedSearchTermSet = \"(\" ws typedSearchTerm *(mws typedSearchTerm) ws \")\""
  [ctx base-query loc]
  (let [terms (zx/xml-> loc :typedSearchTerm #(parse-typed-search-term ctx base-query %))]
    (search/q-or terms)))

(defn parse-term-filter
  "termFilter = termKeyword ws stringComparisonOperator ws (typedSearchTerm / typedSearchTermSet)\n"
  [ctx base-query loc]
  (let [op (zx/xml1-> loc :stringComparisonOperator zx/text) ;; "=" or "!="
        typed-search-term (zx/xml1-> loc :typedSearchTerm #(parse-typed-search-term ctx base-query %))
        typed-search-term-set (zx/xml1-> loc :typedSearchTermSet #(parse-typed-search-term-set ctx base-query %))]
    (cond
      (and (= "=" op) typed-search-term)
      typed-search-term

      (and (= "=" op) typed-search-term-set)
      typed-search-term-set

      ;; SNOMED ECL description-filter negation is existential at the
      ;; description level. A concept matches `term != "x"` if it has any
      ;; matching description that does not satisfy the positive term query.
      ;; The spec's description-filter examples explicitly note that concepts
      ;; may match via a different synonym, and that universal concept-level
      ;; negation should be expressed with MINUS instead.
      (and (= "!=" op) typed-search-term)
      (search/q-not (search/q-match-all) typed-search-term)

      (and (= "!=" op) typed-search-term-set)
      (search/q-not (search/q-match-all) typed-search-term-set)

      :else
      (throw (ex-info "unsupported term filter" {:s         (zx/text loc)
                                                 :op        op
                                                 :term      typed-search-term
                                                 :term-sets typed-search-term-set})))))

(defn parse-language-filter [loc]
  (throw (ex-info "language filters are not supported and should be deprecated; please use dialect filter / language reference sets" {:text (zx/text loc)})))

(defn parse-type-id-filter
  "typeIdFilter = typeId ws booleanComparisonOperator ws (subExpressionConstraint / eclConceptReferenceSet"
  [{:keys [store] :as ctx} loc]
  (let [boolean-comparison-operator (zx/xml1-> loc :booleanComparisonOperator zx/text)
        type-ids (or (seq (when-let [subexpr (zx/xml1-> loc :subExpressionConstraint #(parse-subexpression-constraint ctx %))]
                            (realise-concept-ids ctx subexpr)))
                     (zx/xml-> loc :eclConceptReferenceSet :eclConceptReference :conceptId parse-conceptId))]
    (cond
      (empty? type-ids)
      (throw (ex-info (str "unknown type-id filter; type ids not found: " (zx/text loc)) {:text (zx/text loc)}))

      (= "=" boolean-comparison-operator)
      (search/q-typeAny type-ids)

      ;; for "!=", we ask SNOMED for all concepts that are a subtype of 900000000000446008 and then subtract the concept reference(s).
      (= "!=" boolean-comparison-operator)
      (search/q-typeAny (set/difference (store/all-children store 900000000000446008) type-ids))

      :else
      (throw (ex-info "unknown type-id filter" {:text (zx/text loc)})))))

(def ^:private type-token->type-id
  {:FSN 900000000000003001
   :SYN 900000000000013009
   :DEF 900000000000550004})

(defn parse-type-token-filter
  "type ws booleanComparisonOperator ws (typeToken / typeTokenSet)
  typeToken = synonym / fullySpecifiedName / definition
  typeTokenSet = \"(\" ws typeToken *(mws typeToken) ws \")\""
  [{:keys [store]} loc]
  (let [boolean-comparison-operator (zx/xml1-> loc :booleanComparisonOperator zx/text)
        type-token (keyword (zx/xml1-> loc :typeToken zx/text))
        type-tokens (map keyword (zx/xml-> loc :typeTokenSet :typeToken zx/text))
        types (map type-token->type-id (filter identity (conj type-tokens type-token)))
        type-ids (case boolean-comparison-operator
                   "=" types
                   "!=" (set/difference (store/all-children store 900000000000446008) (set types))
                   (throw (ex-info "invalid boolean operator for type token filter" {:text (zx/text loc) :op boolean-comparison-operator})))]
    (search/q-typeAny type-ids)))

(defn parse-type-filter
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

(defn parse-acceptability-set->kws
  "Parse acceptability set into a sequence of keywords.
  Result is either ':acceptable-in' or ':preferred-in'
  acceptabilitySet = acceptabilityConceptReferenceSet / acceptabilityTokenSet
  acceptabilityConceptReferenceSet = ( ws eclConceptReference *(mws eclConceptReference) ws)

  acceptabilityIdSet = eclConceptReferenceSet
  acceptabilityTokenSet = ( ws acceptabilityToken *(mws acceptabilityToken) ws )
  acceptabilityToken = acceptable / preferred"
  [loc]
  (let [ids (or (seq (zx/xml-> loc :acceptabilityConceptReferenceSet :eclConceptReference :conceptId parse-conceptId))
                (map str/lower-case (zx/xml-> loc :acceptabilityTokenSet :acceptabilityToken zx/text)))]
    (map acceptability->kw ids)))

(defn parse-dialect-set
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
        (cond (zero? c) nil
              (even? c) results
              :else (conj results default-acceptability)))
      (let [d-alias (zx/xml1-> tag :dialectAlias zx/text)
            mapped (lang/dialect->refset-id d-alias)        ;; doesn't matter if alias is nil
            concept-id (zx/xml1-> tag :eclConceptReference :conceptId parse-conceptId)
            acceptability (zx/xml1-> tag :acceptabilitySet parse-acceptability-set->kws)]
        (recur
         (zip/next tag)
         (let [c (count results), is-even? (even? c), is-odd? (not is-even?)]
           (cond
             (and (nil? d-alias) (nil? concept-id) (nil? acceptability)) ;; keep on looping if its some other tag
             results

             (and d-alias (nil? mapped))
             (throw (ex-info (str "unknown dialect: '" d-alias "'") {:text (zx/text loc)}))

             (and is-even? mapped)                         ;; if it's an alias or id, and we're ready for it, add it
             (conj results mapped)

             (and is-even? concept-id)
             (conj results concept-id)

             (and is-odd? mapped)                          ;; if it's an alias or id, and we're not ready, insert an acceptability first
             (apply conj results [default-acceptability mapped])

             (and is-odd? concept-id)
             (apply conj results [default-acceptability concept-id])

             (and is-even? acceptability)                  ;; if it's an acceptability and we've not had an alias - fail fast  (should never happen)
             (throw (ex-info "parse error: acceptability before dialect alias" {:text (zx/text loc) :alias d-alias :acceptability acceptability :results results :count count}))

             (and is-odd? acceptability)                   ;; if it's an acceptability and we're ready, add it.
             (conj results acceptability))))))))

(defn parse-dialect-id-filter
  "dialectIdFilter = dialectId ws booleanComparisonOperator ws (subExpressionConstraint / dialectIdSet)"
  [ctx acceptability-set loc]
  (let [boolean-comparison-operator (zx/xml1-> loc :booleanComparisonOperator zx/text)
        refset-ids (or (when-let [subexp (zx/xml1-> loc :subExpressionConstraint #(parse-subexpression-constraint ctx %))]
                         (interleave (realise-concept-ids ctx subexp) (repeat acceptability-set)))
                       (zx/xml-> loc :dialectIdSet #(parse-dialect-set acceptability-set %)))]
    (cond
      (and (= "=" boolean-comparison-operator) (seq refset-ids))
      (let [m (apply hash-map refset-ids)]
        (search/q-or (map (fn [[refset-id accept]]
                            (if accept
                              (search/q-acceptability accept refset-id)
                              (search/q-description-memberOf refset-id))) m)))

      (empty? refset-ids)                                   ;; if we did not realise any concepts from the subexpression
      (throw (ex-info (str "dialect ids not found:" (zx/xml1-> loc :subExpressionConstraint zx/text)) {:text (zx/text loc)}))
      :else
      (throw (ex-info "unimplemented dialect alias filter" {:text (zx/text loc) :op boolean-comparison-operator :refset-ids refset-ids})))))

(defn parse-dialect-alias-filter
  "dialectAliasFilter = dialect ws booleanComparisonOperator ws (dialectAlias / dialectAliasSet)"
  [acceptability-set loc]
  (let [op (zx/xml1-> loc :booleanComparisonOperator zx/text)
        dialect-alias (lang/dialect->refset-id (zx/xml1-> loc :dialectAlias zx/text))
        dialect-aliases (zx/xml-> loc :dialectAliasSet #(parse-dialect-set acceptability-set %))]
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
      (throw (ex-info "unimplemented dialect alias filter" {:text (zx/text loc)})))))

(defn parse-dialect-filter
  "dialectFilter = (dialectIdFilter / dialectAliasFilter) [ ws acceptabilitySet ]"
  [ctx loc]
  ;; Pass the acceptability set to the parsers of dialectIdFilter or dialectAliasFilter
  ;  because adding acceptability changes the generated query from one of concept
  ;  refset membership to using the 'preferred-in' and 'acceptable-in' indexes
  ;  specially designed for that purpose.
  (let [acceptability-set (zx/xml1-> loc :acceptabilitySet parse-acceptability-set->kws)]
    (or (zx/xml1-> loc :dialectIdFilter #(parse-dialect-id-filter ctx acceptability-set %))
        (zx/xml1-> loc :dialectAliasFilter #(parse-dialect-alias-filter acceptability-set %)))))

(defn parse-description-active-filter
  "activeFilter = activeKeyword ws booleanComparisonOperator ws activeValue
  activeValue = activeTrueValue / activeFalseValue
  activeTrueValue = \"1\" / \"true\"
  activeFalseValue = \"0\" / \"false\""
  [loc]
  (let [active? (boolean (zx/xml1-> loc :activeValue :activeTrueValue))
        op (zx/xml1-> loc :booleanComparisonOperator zx/text)]
    (case op
      "=" (search/q-description-active active?)
      "!=" (search/q-description-active (not active?)))))

(defn parse-description-filter
  "2.0: descriptionFilter = termFilter / languageFilter / typeFilter / dialectFilter / moduleFilter / effectiveTimeFilter / activeFilter\n
  1.5: filter = termFilter / languageFilter / typeFilter / dialectFilter"
  [ctx base-query loc]
  (or (zx/xml1-> loc :termFilter #(parse-term-filter ctx base-query %))
      (zx/xml1-> loc :languageFilter parse-language-filter)
      (zx/xml1-> loc :typeFilter #(parse-type-filter ctx %))
      (zx/xml1-> loc :dialectFilter #(parse-dialect-filter ctx %))
      (zx/xml1-> loc :activeFilter parse-description-active-filter)
      (throw (ex-info "Unsupported description filter" {:ecl (zx/text loc)}))))

(defn parse-description-filter-constraint
  "2.0: descriptionFilterConstraint = {{ ws [ d / D ] ws descriptionFilter *(ws , ws descriptionFilter) ws }}
  1.5 : filterConstraint = {{ ws filter *(ws , ws filter) ws }}
  At the moment, search term text folding (removing diacritics) uses the database default language reference set. This
  is a reasonable assumption, and could be the default if instead we used any specific language or dialect choices at
  this level within the ECL itself. TODO: implement more local choice on locale to use for case folding.

  When `base-query` is supplied, it is threaded through to the wildcard term
  filter to narrow the Java-side regex scan to descriptions whose concept is
  in the outer substrate."
  [ctx base-query loc]
  (search/q-and (zx/xml-> loc :descriptionFilter #(parse-description-filter ctx base-query %))))

(defn parse-concept-active-filter
  "activeFilter = activeKeyword ws booleanComparisonOperator ws activeValue"
  [loc]
  (let [active? (boolean (zx/xml1-> loc :activeValue :activeTrueValue))
        op (zx/xml1-> loc :booleanComparisonOperator zx/text)]
    (case op
      "=" (search/q-concept-active active?)
      "!=" (search/q-concept-active (not active?)))))

(defn parse-concept-filter
  "conceptFilter = definitionStatusFilter / moduleFilter / effectiveTimeFilter / activeFilter"
  [_ctx loc]
  (or (zx/xml1-> loc :activeFilter parse-concept-active-filter)
      (throw (ex-info "Unsupported concept filter" {:text (zx/text loc)}))))

(defn parse-concept-filter-constraint
  "conceptFilterConstraint = {{\" ws (\"c\" / \"C\") ws conceptFilter *(ws \",\" ws conceptFilter) ws \"}}"
  [ctx loc]
  (search/q-and (zx/xml-> loc :conceptFilter #(parse-concept-filter ctx %))))

(defn parse-cardinality
  "cardinality = minValue to maxValue
  minValue = nonNegativeIntegerValue
  to = \"..\"
  maxValue = nonNegativeIntegerValue / many
  many = \"*\""
  [loc]
  (let [min-value (Integer/parseInt (zx/xml1-> loc :minValue zx/text))
        max-value (zx/xml1-> loc :maxValue zx/text)]
    {:min-value min-value
     :max-value (if (= max-value "*") Integer/MAX_VALUE (Integer/parseInt max-value))}))

(defn make-nested-query
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

(defn q-or-or-none
  "Build a disjunction of queries, falling back to MatchNoDocs when empty."
  [queries]
  (or (search/q-or (remove nil? queries))
      (search/q-match-none)))

(defn q-wildcard-attribute-in-set
  "Lucene query matching concepts with any active concept-model-attribute
  relationship whose direct target is in `value-concept-ids`. Projects sources
  via the LMDB child-relationships index instead of building one Lucene clause
  per attribute type (which would blow past BooleanQuery's maxClauseCount).
  Filters to descendants of 410662002 |Concept model attribute| per ECL §8.5,
  so IS-A (116680003) — a taxonomic relationship, not an attribute — is
  excluded."
  [{:keys [store]} value-concept-ids]
  (let [attribute-types (store/all-children store snomed/ConceptModelAttribute)
        sources (store/child-relationships-of-types store value-concept-ids attribute-types)]
    (if (seq sources)
      (search/q-concept-ids sources)
      (search/q-match-none))))

(defn make-attribute-query
  "Generate a nested query for the attributes specified, rewriting any
  exclusion clauses in the parent nested context. If the nested query returns
  no results, then this takes care to return `q-match-none` rather than nil."
  [ctx query attribute-concept-ids]
  (let [[incl excl] (search/rewrite-query query)]
    (cond
      ;; if there is a star symbol for an attribute value, we convert into a search for any match WITH that attribute (ie count > 0)
      (and (search/q-match-all? incl) (nil? excl))
      (search/q-or (map #(search/q-attribute-count % 1 Integer/MAX_VALUE) attribute-concept-ids))
      ;; if there is a star symbol, but also exclusions, exclude them
      (search/q-match-all? incl)
      (let [excl' (realise-concept-ids ctx excl)]
        (search/q-not (search/q-or (map #(search/q-attribute-count % 1 Integer/MAX_VALUE) attribute-concept-ids))
                      (search/q-and (map #(search/q-attribute-in-set % excl') attribute-concept-ids))))

      ;; if we have inclusions and exclusions, realise the concepts
      (and incl excl)
      (let [incl' (realise-concept-ids ctx incl)
            excl' (realise-concept-ids ctx excl)]
        (search/q-not (search/q-or (map #(search/q-attribute-in-set % incl') attribute-concept-ids))
                      (search/q-and (map #(search/q-attribute-in-set % excl') attribute-concept-ids))))
      ;; we only have inclusions?
      incl
      (let [incl' (realise-concept-ids ctx incl)]
        (or (search/q-or (map #(search/q-attribute-in-set % incl') attribute-concept-ids))
            (search/q-match-none)))

      excl
      (let [excl' (realise-concept-ids ctx excl)]
        (search/q-not (search/q-match-all) (search/q-and (map #(search/q-attribute-in-set % excl') attribute-concept-ids))))

      :else
      (search/q-match-none))))

(defn parse-attribute--expression
  [ctx {min-value :min-value max-value :max-value :as cardinality} reverse-flag? attribute-concept-ids loc]
  (let [sub-expression (zx/xml1-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))
        attribute-query (when-not (and cardinality (zero? min-value)) (make-attribute-query ctx sub-expression attribute-concept-ids))
        cardinality-queries (when cardinality (seq (remove nil? (map #(search/q-attribute-count % min-value max-value) attribute-concept-ids))))]
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
      (and attribute-query cardinality-queries)
      (search/q-and (conj cardinality-queries attribute-query))

      attribute-query
      attribute-query

      cardinality-queries
      (search/q-and cardinality-queries)

      :else
      (throw (ex-info "invalid attribute query" {:text (zx/text loc)})))))

;; Group constraint evaluation
;; ---------------------------
;; Predicates that interpret ECL operator semantics (:in :not-in :minus :*
;; := :!= :> :< :>= :<=) over a concept's raw relationship data, plus the
;; concrete-value wire-format parser ('#N', '"s"', 'true'/'false') used to
;; coerce stored values for numeric and typed comparisons. Consumed by
;; `parse-ecl-attribute` (ungrouped) and `parse-ecl-attribute-group` (grouped)
;; below.

(defn parse-concrete-value
  "Parse a concrete value string to its typed value.
  Returns a number for '#N', a string for '\"text\"', a boolean for
  'true'/'false', or nil for non-concrete values (e.g. concept ID longs)."
  [v]
  (when (string? v)
    (cond
      (str/starts-with? v "#")  (parse-double (subs v 1))
      (str/starts-with? v "\"") (subs v 1 (dec (count v)))
      :else                     (parse-boolean v))))

(defn constraint-satisfied?
  "Does a single group's properties satisfy a constraint?
  `group-props` is {type-id #{values}} for one group.
  `constraint` is [op attribute-ids value]. `attribute-ids` may be the
  keyword `:wildcard` meaning 'any concept model attribute'; when present,
  `cma-types` (a set of CMA descendant type-ids) restricts which group types
  are considered. Without `cma-types`, `:wildcard` matches any type in the
  group (a tighter restriction from callers is recommended to honour
  ECL §8.5)."
  ([group-props constraint]
   (constraint-satisfied? group-props nil constraint))
  ([group-props cma-types [op attribute-ids value]]
   (let [actuals (if (= :wildcard attribute-ids)
                   (into #{} cat (vals (if cma-types
                                         (select-keys group-props cma-types)
                                         group-props)))
                   (into #{} (mapcat #(get group-props %)) attribute-ids))]
    (case op
      :in     (boolean (some value actuals))
      ;; ECL '!=' is existential rather than vacuous: the attribute must exist,
      ;; and at least one value in the group must fall outside the target set.
      ;; Absence is expressed separately with cardinality, e.g. [0..0].
      :not-in (boolean (some #(not (value %)) actuals))
      :minus  (boolean (some #(not (value %)) actuals))
      :*      (boolean (seq actuals))
      (let [parsed (keep parse-concrete-value actuals)]
        (case op
          :=  (boolean (some #(= % value) parsed))
          :!= (boolean (some #(not= % value) parsed))
          (let [v (double value)]                         ;; numeric comparisons only
            (case op
              :>  (boolean (some #(> (double %) v) parsed))
              :<  (boolean (some #(< (double %) v) parsed))
              :>= (boolean (some #(>= (double %) v) parsed))
              :<= (boolean (some #(<= (double %) v) parsed))))))))))

(s/def ::group-operator #{:in :not-in :minus :* := :!= :> :< :>= :<=})
(s/def ::group-constraint-value
  (s/or :concept-ids (s/every :info.snomed.Concept/id :kind set?)
        :number number?
        :string string?
        :boolean boolean?
        :nil nil?))
(s/def ::group-constraint-attribute-ids
  (s/or :wildcard #{:wildcard}
        :ids      (s/every :info.snomed.Concept/id :kind set?)))
(s/def ::group-constraint
  (s/tuple ::group-operator
           ::group-constraint-attribute-ids
           ::group-constraint-value))

(s/fdef group-constraints-satisfied?
  :args (s/cat :properties map?
               :cma-types (s/? (s/nilable (s/every :info.snomed.Concept/id :kind set?)))
               :constraints (s/coll-of ::group-constraint)))
(defn group-constraints-satisfied?
  "Does at least one non-zero relationship group satisfy all constraints?
  `properties` is {group-id {type-id #{values}}} as from [[store/properties]].
  Relationship values must be raw targets, not ancestor-expanded values,
  otherwise negative operators such as :not-in and :minus become unsound.
  `cma-types`, when supplied, is the set of type-ids that are descendants of
  410662002 |Concept model attribute| (ECL §8.5); it restricts which group
  types are considered for `:wildcard` constraints.
  `constraints` is a coll of [op attribute-ids value] tuples where:
  - op is a keyword: :in, :not-in, :minus, :*, :=, :!=, :>, :<, :>=, :<=
  - attribute-ids is a set of type concept IDs, or the keyword `:wildcard`
  - value is a set of concept IDs (for :in/:not-in/:minus), nil (for :*),
    or a number/string/boolean (for comparison operators)"
  ([properties constraints]
   (group-constraints-satisfied? properties nil constraints))
  ([properties cma-types constraints]
   (boolean
     (some (fn [[group-id group-props]]
             (when (pos? group-id)
               (every? #(constraint-satisfied? group-props cma-types %) constraints)))
           properties))))

(defn wildcard-constraint? [[_ attribute-ids _]]
  (= :wildcard attribute-ids))

(s/fdef concept-satisfies-group-constraints?
  :args (s/cat :store ::store
               :cma-types (s/? (s/nilable (s/every :info.snomed.Concept/id :kind set?)))
               :concept-id :info.snomed.Concept/id
               :constraints (s/coll-of ::group-constraint)))
(defn concept-satisfies-group-constraints?
  "Does at least one non-zero relationship group of concept-id satisfy all
  constraints? Fetches [[store/properties]] and delegates to
  [[group-constraints-satisfied?]]. `cma-types`, when supplied, is the set
  of Concept model attribute descendants (ECL §8.5) to restrict `:wildcard`
  matching; callers iterating many candidates should pre-resolve it with
  [[cma-attribute-types]] to avoid repeated store lookups."
  ([store concept-id constraints]
   (concept-satisfies-group-constraints? store nil concept-id constraints))
  ([store cma-types concept-id constraints]
   (group-constraints-satisfied?
     (store/properties store concept-id) cma-types constraints)))

(defn cma-attribute-types
  "Resolve the set of type-ids that are descendants of 410662002
  |Concept model attribute|, if needed by any constraint in `constraints`.
  Returns nil when no constraint uses `:wildcard`, so the validator does not
  apply a restriction."
  [store constraints]
  (when (some wildcard-constraint? constraints)
    (store/all-children store snomed/ConceptModelAttribute)))

(s/fdef ungrouped-constraint-satisfied?
  :args (s/cat :properties map?
               :constraint ::group-constraint))
(defn ungrouped-constraint-satisfied?
  "Does the concept satisfy an ungrouped constraint?
  For ungrouped refinements, the constraint is evaluated across ALL
  relationships of the concept (all groups including group 0). This differs
  from grouped evaluation where all constraints must be met within a single
  non-zero group. `properties` is {group-id {type-id #{values}}} as from
  [[store/properties]]. `constraint` is [op attribute-ids value]."
  [properties constraint]
  (let [merged-props (apply merge-with into (vals properties))]
    (constraint-satisfied? merged-props constraint)))

(s/fdef concept-satisfies-ungrouped-constraint?
  :args (s/cat :store ::store
               :concept-id :info.snomed.Concept/id
               :constraint ::group-constraint))
(defn concept-satisfies-ungrouped-constraint?
  "Does the concept satisfy an ungrouped attribute constraint?
  Fetches [[store/properties]] and delegates to
  [[ungrouped-constraint-satisfied?]]."
  [store concept-id constraint]
  (ungrouped-constraint-satisfied? (store/properties store concept-id) constraint))

(def ^:private concrete-numeric-comparison-ops
  {"="  search/q-concrete=
   ">"  search/q-concrete>
   "<"  search/q-concrete<
   ">=" search/q-concrete>=
   "<=" search/q-concrete<=
   "!=" search/q-concrete!=})

(defn resolve-attribute-ids
  "Resolve an ECL attribute-name subexpression to either the keyword
  `:wildcard` (when it matched `*`) or a set of attribute concept ids,
  filtered to descendants of the ECL §8.5 umbrella: 410662002 |Concept
  model attribute| for expression comparisons, or 762706009 |Concept model
  data attribute| for concrete-value comparisons. Returns nil when
  `ecl-attribute-name` is nil."
  [ctx ecl-attribute-name expression?]
  (when ecl-attribute-name
    (if (search/q-match-all? ecl-attribute-name)
      :wildcard
      (let [parent (if expression? snomed/ConceptModelAttribute snomed/ConceptModelDataAttribute)]
        (into #{} (realise-concept-ids ctx (search/q-and [(search/q-descendantOf parent) ecl-attribute-name])))))))

(defn parse-wildcard-attribute-equals
  "Handle `* = S` where the attribute name is a wildcard. Projects sources via
  the store's child-relationships index; realising all CMA attribute ids as
  per-attribute OR clauses would exceed BooleanQuery's maxClauseCount.
  Rejects cardinality and reverse flag."
  [ctx cardinality reverse-flag? loc]
  (when cardinality
    (throw (ex-info "cardinality with wildcard attribute name not supported"
                    {:text (zx/text loc)})))
  (when reverse-flag?
    (throw (ex-info "reverse flag with wildcard attribute name not supported"
                    {:text (zx/text loc)})))
  (let [sub-expr (zx/xml1-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))
        [incl excl] (search/rewrite-query sub-expr)]
    (cond
      (and (search/q-match-all? incl) (nil? excl))
      (throw (ex-info "`* = *` not supported" {:text (zx/text loc)}))

      (search/q-match-all? incl)
      (throw (ex-info "`* = (* MINUS …)` not supported" {:text (zx/text loc)}))

      :else
      (let [incl-ids (when incl (realise-concept-ids ctx incl))
            excl-ids (when excl (realise-concept-ids ctx excl))
            incl-q   (when (seq incl-ids) (q-wildcard-attribute-in-set ctx incl-ids))
            excl-q   (when (seq excl-ids) (q-wildcard-attribute-in-set ctx excl-ids))]
        (cond
          (and incl-q excl-q) (search/q-not incl-q excl-q)
          incl-q              incl-q
          :else               (search/q-match-none))))))

(defn parse-attribute-not-equals
  "Handle `A != S` at the concept level. ECL §6.5 requires an existential
  check: a matching concept must have at least one value for A, and at
  least one such value must not be in S. Plain Lucene negation would
  wrongly exclude concepts with both in-range and out-of-range values, and
  wrongly include concepts with no such attribute. We split candidates
  with Lucene into those confirmable trivially (has A but no raw value in
  S) vs those needing a store check, and validate only the latter.
  `base-query`, when supplied, narrows the candidate set."
  [ctx base-query cardinality reverse-flag? attribute-concept-ids loc]
  (cond
    (and cardinality (zero? (:min-value cardinality)) (zero? (:max-value cardinality)))
    (throw (ex-info "[0..0] cardinality with != not yet supported (requires universal quantification)"
                    {:text (zx/text loc)}))

    cardinality
    (throw (ex-info "cardinality with != not yet supported"
                    {:text (zx/text loc)}))

    reverse-flag?
    (throw (ex-info "reverse flag with != not yet supported"
                    {:text (zx/text loc)}))

    :else
    (let [sub-expr (zx/xml1-> loc :subExpressionConstraint #(parse-subexpression-constraint ctx %))
          target-ids (realise-concept-ids ctx sub-expr)
          attribute-ids (set attribute-concept-ids)
          constraint [:not-in attribute-ids target-ids]
          presence-query (q-or-or-none
                          (map #(search/q-attribute-count % 1 Integer/MAX_VALUE) attribute-concept-ids))
          has-target-query (q-or-or-none
                            (map #(search/q-attribute-in-set % target-ids) attribute-concept-ids))
          narrow #(if base-query (search/q-and [base-query %]) %)
          ;; Candidates split into:
          ;;  definitely-match — has attr but no raw value in target → all values
          ;;                     outside target → satisfies != trivially
          ;;  maybe-match      — has attr AND at least one value in target →
          ;;                     could also have a non-target value; needs store check
          definitely-match-query (search/q-not (narrow presence-query) has-target-query)
          maybe-match-ids (realise-concept-ids ctx (narrow (search/q-and [presence-query has-target-query])))
          validated-maybe (into #{}
                                (filter #(concept-satisfies-ungrouped-constraint?
                                          (:store ctx) % constraint))
                                maybe-match-ids)]
      (search/q-or [definitely-match-query (search/q-concept-ids validated-maybe)]))))

(defn parse-ecl-attribute
  "1.5: eclAttribute = [\"[\" cardinality \"]\" ws] [reverseFlag ws] eclAttributeName ws (expressionComparisonOperator ws subExpressionConstraint / numericComparisonOperator ws \"#\" numericValue / stringComparisonOperator ws QM stringValue QM / booleanComparisonOperator ws booleanValue)

   2.0: eclAttribute = [\"[\" cardinality \"]\" ws] [reverseFlag ws] eclAttributeName ws (expressionComparisonOperator ws subExpressionConstraint / numericComparisonOperator ws \"#\" numericValue / stringComparisonOperator ws (typedSearchTerm / typedSearchTermSet) / booleanComparisonOperator ws booleanValue)

  `base-query` is an optional Lucene query representing the outer subexpression
  constraint (e.g. `< 19829001`). When present, it narrows the candidate set for
  two-phase validators (expression `!=`), avoiding materialisation of all
  concepts with the attribute across the entire terminology.\n"
  [ctx base-query loc]
  (let [cardinality (zx/xml1-> loc :cardinality parse-cardinality)
        reverse-flag? (zx/xml1-> loc :reverseFlag zx/text)
        ecl-attribute-name (zx/xml1-> loc :eclAttributeName :subExpressionConstraint #(parse-subexpression-constraint ctx %))
        expression-operator (zx/xml1-> loc :expressionComparisonOperator zx/text)
        numeric-operator (zx/xml1-> loc :numericComparisonOperator zx/text)
        string-operator (zx/xml1-> loc :stringComparisonOperator zx/text)
        boolean-operator (zx/xml1-> loc :booleanComparisonOperator zx/text)
        ;; A wildcard `*` attribute name would otherwise realise thousands of
        ;; ids and blow past BooleanQuery's maxClauseCount on downstream
        ;; OR-per-id clauses; dispatch that case to a store-projection path.
        attribute-ids (resolve-attribute-ids ctx ecl-attribute-name (boolean expression-operator))
        wildcard? (= :wildcard attribute-ids)
        attribute-concept-ids (when-not wildcard? attribute-ids)]
    (when-not (or wildcard? (seq attribute-concept-ids))
      (throw (ex-info "attribute expression resulted in no valid attributes" {:text (zx/text loc) :eclAttributeName ecl-attribute-name})))
    (cond
      expression-operator
      (case expression-operator
        "=" (if wildcard?
              (parse-wildcard-attribute-equals ctx cardinality reverse-flag? loc)
              (parse-attribute--expression ctx cardinality reverse-flag? attribute-concept-ids loc))
        "!=" (do
               (when wildcard?
                 (throw (ex-info "`* != V` with wildcard attribute name not supported — would require iterating every concept"
                                 {:text (zx/text loc)})))
               (parse-attribute-not-equals ctx base-query cardinality reverse-flag? attribute-concept-ids loc))
        (throw (ex-info (str "unsupported expression operator " expression-operator) {:text (zx/text loc) :eclAttributeName ecl-attribute-name})))

      numeric-operator
      (if wildcard?
        (throw (ex-info "numeric comparison with wildcard attribute name not supported; use an explicit attribute"
                        {:text (zx/text loc) :operator numeric-operator}))
        (let [v (Double/parseDouble (zx/xml1-> loc :numericValue zx/text))
              op (concrete-numeric-comparison-ops numeric-operator)]
          (search/q-or (map (fn [type-id] (op type-id v)) attribute-concept-ids))))

      string-operator
      (throw (ex-info "expressions containing string concrete refinements not yet supported." {:text (zx/text loc)}))

      boolean-operator
      (throw (ex-info "expressions containing boolean concrete refinements not yet supported." {:text (zx/text loc)}))

      :else
      (throw (ex-info "expression does not have a supported operator (expression/numeric/string/boolean)." {:text (zx/text loc)})))))

(defn parse-subattribute-set
  "subAttributeSet = eclAttribute / \"(\" ws eclAttributeSet ws \")\""
  [ctx base-query loc]
  (let [ecl-attribute (zx/xml1-> loc :eclAttribute #(parse-ecl-attribute ctx base-query %))
        ecl-attribute-set (zx/xml1-> loc :eclAttributeSet #(parse-ecl-attribute-set ctx base-query %))]
    (cond
      (and ecl-attribute ecl-attribute-set)
      (search/q-and [ecl-attribute ecl-attribute-set])

      ecl-attribute ecl-attribute
      ecl-attribute-set ecl-attribute-set)))

(defn parse-ecl-attribute-set
  "eclAttributeSet = subAttributeSet ws [conjunctionAttributeSet / disjunctionAttributeSet]"
  [ctx base-query loc]
  (let [subattribute-set (zx/xml1-> loc :subAttributeSet #(parse-subattribute-set ctx base-query %))
        conjunction-attribute-set (zx/xml-> loc :conjunctionAttributeSet :subAttributeSet #(parse-subattribute-set ctx base-query %))
        disjunction-attribute-set (zx/xml-> loc :disjunctionAttributeSet :subAttributeSet #(parse-subattribute-set ctx base-query %))]
    (cond
      (and conjunction-attribute-set subattribute-set)
      (search/q-and (conj conjunction-attribute-set subattribute-set))

      (and subattribute-set disjunction-attribute-set)
      (search/q-or (conj disjunction-attribute-set subattribute-set))

      :else
      subattribute-set)))

(def ecl-numeric-op->keyword
  {"=" := "!=" :!= ">" :> "<" :< ">=" :>= "<=" :<=})

(defn extract-attribute-constraint
  "Extract a group constraint tuple from a single eclAttribute XML node.
  Returns [op attribute-ids value] for use with [[group-constraints-satisfied?]].
  `attribute-ids` is either a set of concept ids or the keyword `:wildcard`
  (when the attribute name was `*`).
  Throws for syntax that cannot be faithfully represented by the current
  same-group evaluator (e.g. reverse flag)."
  [ctx loc]
  (when (zx/xml1-> loc :reverseFlag zx/text)
    (throw (ex-info "reverse flag within an ECL attribute group is not supported"
                    {:text (zx/text loc) :reason :reverse-flag-in-group})))
  (let [ecl-attribute-name (zx/xml1-> loc :eclAttributeName :subExpressionConstraint (partial parse-subexpression-constraint ctx))
        expression-operator (zx/xml1-> loc :expressionComparisonOperator zx/text)
        numeric-operator (zx/xml1-> loc :numericComparisonOperator zx/text)
        string-operator (zx/xml1-> loc :stringComparisonOperator zx/text)
        boolean-operator (zx/xml1-> loc :booleanComparisonOperator zx/text)
        attribute-ids (resolve-attribute-ids ctx ecl-attribute-name (boolean expression-operator))
        wildcard? (= :wildcard attribute-ids)]
    (when (and (not wildcard?) (not (seq attribute-ids)))
      (throw (ex-info "attribute expression resulted in no valid attributes"
                      {:text (zx/text loc)
                       :eclAttributeName ecl-attribute-name})))
    (cond
      expression-operator
      (let [sub-expr (zx/xml1-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))
            [incl excl] (search/rewrite-query sub-expr)]
        (cond
          ;; wildcard attribute with a wildcard value — match-all semantics
          ;; aren't meaningful here; reject for consistency with non-grouped path
          (and wildcard? (= "=" expression-operator) (search/q-match-all? incl))
          (throw (ex-info "wildcard attribute name with wildcard value inside a group not supported"
                          {:text (zx/text loc)}))
          ;; = *
          (and (= "=" expression-operator) (search/q-match-all? incl) (nil? excl))
          [:* attribute-ids nil]
          ;; = (* MINUS S)
          (and (= "=" expression-operator) (search/q-match-all? incl) excl)
          [:minus attribute-ids (realise-concept-ids ctx excl)]
          ;; = S or != S (possibly with exclusions resolved via set difference).
          ;; For grouped '!=', constraint-satisfied? enforces the ECL
          ;; existential semantics: a value for the attribute must exist in
          ;; the group and at least one such value must lie outside target-ids.
          :else
          (let [target-ids (realise-concept-ids ctx sub-expr)]
            [(if (= "=" expression-operator) :in :not-in) attribute-ids target-ids])))

      numeric-operator
      (if wildcard?
        (throw (ex-info "numeric comparison with wildcard attribute name inside a group not supported"
                        {:text (zx/text loc) :operator numeric-operator}))
        [(ecl-numeric-op->keyword numeric-operator) attribute-ids
         (Double/parseDouble (zx/xml1-> loc :numericValue zx/text))])

      string-operator
      (throw (ex-info "string concrete refinements within an ECL attribute group not yet supported"
                      {:text (zx/text loc) :reason :string-in-group}))

      boolean-operator
      (throw (ex-info "boolean concrete refinements within an ECL attribute group not yet supported"
                      {:text (zx/text loc) :reason :boolean-in-group}))

      :else
      (throw (ex-info "unsupported attribute operator within an ECL attribute group"
                      {:text (zx/text loc) :reason :unknown-operator-in-group})))))

(defn extract-group-constraints
  "Extract constraint tuples from an eclAttributeSet within a group.
  Returns a vector of [op attribute-ids value] tuples for a flat conjunction.
  Throws with a specific `:reason` when the set uses syntax that cannot be
  faithfully represented by the current same-group evaluator (disjunction,
  nesting, reverse attributes)."
  [ctx loc]
  (let [has-disjunction? (zx/xml1-> loc :disjunctionAttributeSet)
        first-sub (zx/xml1-> loc :subAttributeSet)
        conjunction-subs (zx/xml-> loc :conjunctionAttributeSet :subAttributeSet)
        all-subs (cons first-sub conjunction-subs)]
    (cond
      has-disjunction?
      (throw (ex-info "disjunction (OR) within an ECL attribute group is not supported"
                      {:text (zx/text loc) :reason :disjunction-in-group}))
      (not (every? #(zx/xml1-> % :eclAttribute) all-subs))
      (throw (ex-info "nested attribute sets within an ECL attribute group are not supported"
                      {:text (zx/text loc) :reason :nested-attribute-set-in-group}))
      :else
      (mapv #(extract-attribute-constraint ctx (zx/xml1-> % :eclAttribute)) all-subs))))

(defn prefilter-for-constraint
  "Build a broad Lucene prefilter for a single same-group constraint.
  Negative expression operators are reduced to attribute-presence checks to
  avoid concept-level false negatives before the store-level group validator.
  When `attribute-ids` is `:wildcard`, use store-projected queries instead
  of per-attribute OR disjunctions to avoid Lucene's maxClauseCount."
  [ctx [op attribute-ids value]]
  (if (= :wildcard attribute-ids)
    (case op
      :in (q-wildcard-attribute-in-set ctx value)
      (throw (ex-info (str "grouped `" (name op) "` with wildcard attribute name not supported — would require iterating every concept")
                      {:op op})))
    (let [presence-query (q-or-or-none
                          (map #(search/q-attribute-count % 1 Integer/MAX_VALUE) attribute-ids))]
      (case op
        :in  (q-or-or-none (map #(search/q-attribute-in-set % value) attribute-ids))
        :not-in presence-query
        :minus  presence-query
        :*      presence-query
        :=   (q-or-or-none (map #(search/q-concrete= % value) attribute-ids))
        :!=  (q-or-or-none (map #(search/q-concrete!= % value) attribute-ids))
        :>   (q-or-or-none (map #(search/q-concrete> % value) attribute-ids))
        :<   (q-or-or-none (map #(search/q-concrete< % value) attribute-ids))
        :>=  (q-or-or-none (map #(search/q-concrete>= % value) attribute-ids))
        :<=  (q-or-or-none (map #(search/q-concrete<= % value) attribute-ids))))))

(defn prefilter-for-group
  "Build a safe broad prefilter for a whole same-group conjunction of constraints."
  [ctx constraints]
  (search/q-and (map #(prefilter-for-constraint ctx %) constraints)))

(defn parse-ecl-attribute-group
  "eclAttributeGroup = [\"[\" cardinality \"]\" ws] \"{\" ws eclAttributeSet ws \"}\"
  Enforces same-group matching: all attributes within { } must be satisfied
  by a single relationship group. Uses a safe broad Lucene pre-filter, then
  validates against the raw grouped relationship data from the store.

  Fail closed if the group uses syntax that this evaluator cannot preserve.
  Returning the raw Lucene attribute query here would silently degrade to
  cross-group semantics, and for grouped '!=' would also introduce false
  negatives, which is worse than rejecting the expression.

  `base-query` is an optional Lucene query narrowing the candidate set."
  [ctx base-query loc]
  (when-let [cardinality (zx/xml1-> loc :cardinality parse-cardinality)]
    (throw (ex-info "cardinality in ECL attribute groups not yet implemented."
                    {:text        (zx/text loc)
                     :cardinality cardinality})))
  (let [attr-set-loc (zx/xml1-> loc :eclAttributeSet)
        group-constraints (extract-group-constraints ctx attr-set-loc)
        broad-query (prefilter-for-group ctx group-constraints)
        candidate-query (if base-query (search/q-and [base-query broad-query]) broad-query)
        candidate-ids (realise-concept-ids ctx candidate-query)
        cma-types (cma-attribute-types (:store ctx) group-constraints)
        validated-ids (into #{}
                            (filter #(concept-satisfies-group-constraints? (:store ctx) cma-types % group-constraints))
                            candidate-ids)]
    (search/q-concept-ids validated-ids)))

(defn parse-sub-refinement
  "subRefinement = eclAttributeSet / eclAttributeGroup / \"(\" ws eclRefinement ws \")\"\n"
  [ctx base-query loc]
  (or (zx/xml1-> loc :eclAttributeSet #(parse-ecl-attribute-set ctx base-query %))
      (zx/xml1-> loc :eclAttributeGroup #(parse-ecl-attribute-group ctx base-query %))
      (zx/xml1-> loc :eclRefinement #(parse-ecl-refinement ctx base-query %))))

(defn parse-ecl-refinement
  "subRefinement ws [conjunctionRefinementSet / disjunctionRefinementSet]"
  [ctx base-query loc]
  (let [sub-refinement (zx/xml1-> loc :subRefinement #(parse-sub-refinement ctx base-query %))
        conjunction-refinement-set (zx/xml-> loc :conjunctionRefinementSet :subRefinement #(parse-sub-refinement ctx base-query %))
        disjunction-refinement-set (zx/xml-> loc :disjunctionRefinementSet :subRefinement #(parse-sub-refinement ctx base-query %))]
    (cond
      (and sub-refinement (seq conjunction-refinement-set))
      (search/q-and (conj conjunction-refinement-set sub-refinement))
      (and sub-refinement (seq disjunction-refinement-set))
      (search/q-or (conj disjunction-refinement-set sub-refinement))
      :else sub-refinement)))

(defn parse-member-filter--match-search-term-set
  "matchSearchTermSet = QM ws matchSearchTerm *(mws matchSearchTerm) ws QM
  Note, as an optimisation, we bring through the reference set identifier "
  [_ctx refset-id refset-field-name comparison-op loc]
  (let [terms (zx/xml-> loc :matchSearchTerm zx/text)
        query (members/q-or (map #(members/q-prefix refset-field-name %) terms))]
    (case comparison-op
      "=" (members/q-and [(members/q-refset-id refset-id) query])
      "!=" (members/q-not (members/q-refset-id refset-id) query))))

(defn parse-member-filter--wild-search-term-set
  "wildSearchTermSet = QM wildSearchTerm QM
  wildSearchTerm = 1*(anyNonEscapedChar / escapedWildChar)"
  [_ctx refset-id refset-field-name comparison-op loc]
  (let [terms (zx/xml-> loc :wildSearchTerm zx/text)
        query (members/q-or (map #(members/q-wildcard refset-field-name %) terms))]
    (case comparison-op
      "=" (members/q-and [(members/q-refset-id refset-id) query])
      "!=" (members/q-not (members/q-refset-id refset-id) query))))

(s/fdef parse-member-filter-typed-search-term
  :args (s/cat :ctx ::ctx
               :refset-id :info.snomed.Concept/id
               :refset-field-name string?
               :comparison-op #{"=" "!="} :loc ::loc))
(defn parse-member-filter-typed-search-term
  "Parse a typedSearchTerm in the context of a member filter.

  typedSearchTerm = ( [ match ws : ws ] matchSearchTermSet ) / ( wild ws : ws wildSearchTermSet )
  match = (m/M) (a/A) (t/T) (c/C) (h/H)
  wildSearchTermSet = QM wildSearchTerm QM"
  [ctx refset-id refset-field-name comparison-op loc]
  (or (zx/xml1-> loc :matchSearchTermSet #(parse-member-filter--match-search-term-set ctx refset-id refset-field-name comparison-op %))
      (zx/xml1-> loc :wildSearchTermSet #(parse-member-filter--wild-search-term-set ctx refset-id refset-field-name comparison-op %))))

(def ^:private numeric-comparison-ops
  {"="  members/q-field=
   "<"  members/q-field<
   "<=" members/q-field<=
   ">"  members/q-field>
   ">=" members/q-field>=
   "!=" members/q-field!=})

(defn parse-member-filter--numeric
  [_ctx refset-id refset-field-name comparison-op loc]
  (let [v (zx/xml1-> loc zx/text parse-long)
        f (or (get numeric-comparison-ops comparison-op) (throw (ex-info "Invalid comparison operator" {:text (zx/text loc) :op comparison-op})))]
    (members/q-and [(members/q-refset-id refset-id) (f refset-field-name v)])))

(defn parse-member-filter--subexpression-constraint
  "Parse a member filter that is a subexpression constraint.
  We realise the concept identifiers for the subexpression, and simply use them
  in our member filter.
  ```
  ^ [targetComponentId]  900000000000527005 |SAME AS association reference set|  {{ M referencedComponentId =  67415000 |Hay asthma|  }}
  ```"
  [ctx refset-id refset-field-name comparison-op loc]
  (let [values (realise-concept-ids ctx (parse-subexpression-constraint ctx loc))]
    (case comparison-op
      "=" (members/q-and [(members/q-refset-id refset-id) (members/q-field-in refset-field-name values)])
      "!=" (members/q-not (members/q-refset-id refset-id) (members/q-field-in refset-field-name values))
      (throw (ex-info "Invalid operation for subexpression constraint" {:op comparison-op :text (zx/text loc)})))))

(defn parse-member-field--boolean
  [_ctx refset-id refset-field-name comparison-op loc]
  (let [v (parse-boolean (zx/text loc))]
    (case comparison-op
      "=" (members/q-and [(members/q-refset-id refset-id) (members/q-field-boolean refset-field-name v)])
      "!=" (members/q-and [(members/q-refset-id refset-id) (members/q-field-boolean refset-field-name (not v))]))))

(defn parse-member-field-filter
  "memberFieldFilter = refsetFieldName ws (expressionComparisonOperator ws subExpressionConstraint / numericComparisonOperator ws
  # numericValue / stringComparisonOperator ws (typedSearchTerm / typedSearchTermSet) / booleanComparisonOperator ws booleanValue /
  ws timeComparisonOperator ws (timeValue / timeValueSet) )"
  [ctx refset-id loc]
  (let [refset-field-name (zx/xml1-> loc :refsetFieldName zx/text)
        comparison-op (or (zx/xml1-> loc :stringComparisonOperator zx/text)
                          (zx/xml1-> loc :expressionComparisonOperator zx/text)
                          (zx/xml1-> loc :numericComparisonOperator zx/text)
                          (zx/xml1-> loc :booleanComparisonOperator zx/text)
                          (zx/xml1-> loc :timeComparisonOperator zx/text))]
    (or (zx/xml1-> loc :typedSearchTerm #(parse-member-filter-typed-search-term ctx refset-id refset-field-name comparison-op %))
        (zx/xml1-> loc :typedSearchTermSet :typedSearchTerm #(parse-member-filter-typed-search-term ctx refset-id refset-field-name comparison-op %))
        (zx/xml1-> loc :numericValue #(parse-member-filter--numeric ctx refset-id refset-field-name comparison-op %))
        (zx/xml1-> loc :subExpressionConstraint #(parse-member-filter--subexpression-constraint ctx refset-id refset-field-name comparison-op %))
        (zx/xml1-> loc :booleanValue #(parse-member-field--boolean ctx refset-id refset-field-name comparison-op %))
        (throw (ex-info "Unsupported member field filter:" {:text                (zx/text loc)
                                                            :refset-field-name   refset-field-name
                                                            :comparison-operator comparison-op})))))

(def ^:private time-comparison-ops
  {"="  members/q-time=
   ">"  members/q-time>
   "<"  members/q-time<
   ">=" members/q-time>=
   "<=" members/q-time<=
   "!=" members/q-time!=})

(defn parse-time-value
  "timeValue = QM [ year month day ] QM"
  [loc]
  (let [^int year (zx/xml1-> loc :year zx/text #(Integer/parseInt %))
        ^int month (zx/xml1-> loc :month zx/text #(Integer/parseInt %))
        ^int day (zx/xml1-> loc :day zx/text #(Integer/parseInt %))]
    (LocalDate/of year month day)))

(defn parse-member-effective-time-filter
  "effectiveTimeFilter = effectiveTimeKeyword ws timeComparisonOperator ws ( timeValue / timeValueSet )
   timeValueSet = \"(\" ws timeValue *(mws timeValue) ws \")"
  [_ctx refset-id loc]
  (let [op (zx/xml1-> loc :timeComparisonOperator zx/text)
        f (get time-comparison-ops op)
        values (or (seq (zx/xml-> loc :timeValue parse-time-value)) (zx/xml-> loc :timeValueSet :timeValue parse-time-value))
        _ (println "values: " {:op op :f f :values values})]
    (members/q-and (into [(members/q-refset-id refset-id)] (mapv #(f "effectiveTime" %) values)))))

(defn parse-member-filter--active-filter
  "activeFilter = activeKeyword ws booleanComparisonOperator ws activeValue
   booleanComparisonOperator = \"=\" / \"!=\"
   activeValue = activeTrueValue / activeFalseValue"
  [_ctx refset-id loc]
  (let [active? (boolean (zx/xml1-> loc :activeValue :activeTrueValue))
        op (zx/xml1-> loc :booleanComparisonOperator zx/text)]
    (members/q-and [(members/q-refset-id refset-id)
                    (members/q-field-boolean "active" (if (= "=" op) active? (not active?)))])))

(defn parse-member-filter--module-filter
  "moduleFilter = moduleIdKeyword ws booleanComparisonOperator ws (subExpressionConstraint / eclConceptReferenceSet)"
  [ctx refset-id loc]
  (let [concept-ids (or (seq (zx/xml1-> loc :subExpressionConstraint
                                        #(parse-subexpression-constraint ctx %)
                                        #(realise-concept-ids ctx %)))
                        (zx/xml-> loc :eclConceptReferenceSet :eclConceptReference :conceptId parse-conceptId))
        op (zx/xml1-> loc :booleanComparisonOperator zx/text)]
    (case op
      "=" (members/q-and [(members/q-refset-id refset-id) (members/q-module-ids concept-ids)])
      "!=" (members/q-not (members/q-refset-id refset-id) (members/q-module-ids concept-ids)))))

(defn parse-member-filter
  "memberFilter = memberFieldFilter / moduleFilter / effectiveTimeFilter / activeFilter"
  [ctx refset-id loc]
  ;; note, must try to parse memberFieldFilter last as least specific
  (or (zx/xml1-> loc :activeFilter #(parse-member-filter--active-filter ctx refset-id %))
      (zx/xml1-> loc :moduleFilter #(parse-member-filter--module-filter ctx refset-id %))
      (zx/xml1-> loc :effectiveTimeFilter #(parse-member-effective-time-filter ctx refset-id %))
      (zx/xml1-> loc :memberFieldFilter #(parse-member-field-filter ctx refset-id %))))

(s/fdef parse-member-filter-constraint
  :args (s/cat :ctx ::ctx
               :refsets (s/or :wildcard #(= :wildcard %)
                              :focus-concept (s/keys :req-un [::conceptId])
                              :expression-constraint #(instance? Query %))
               :loc any?))
(defn parse-member-filter-constraint
  "Parse a member filter constraint. Unlike most parsers, we must have the
  context of the wider expression as 'refsets', which is a query for concepts
  that are a type of reference set. As such, we are forced to realise that query, and then
  filter accordingly. 'refsets' should be a Lucene query, or :wildcard,
  representing all reference sets.
  memberFilterConstraint = {{ ws (m / M) ws memberFilter *(ws , ws memberFilter) ws }}"
  [{:keys [store memberSearcher] :as ctx} refsets loc]
  (let [refset-ids (cond (= :wildcard refsets)
                         (store/installed-reference-sets store)
                         (:conceptId refsets)
                         [(:conceptId refsets)]
                         :else
                         (realise-concept-ids ctx (search/q-and [(search/q-descendantOf 900000000000455006) refsets])))]
    (when (seq refset-ids)
      (let [queries (map (fn [refset-id]
                           (let [filters (zx/xml-> loc :memberFilter #(parse-member-filter ctx refset-id %))
                                 filters (if (seq (zx/xml-> loc :memberFilter :activeFilter))
                                           filters
                                           (cons (members/q-and [(members/q-refset-id refset-id)
                                                                 (members/q-field-boolean "active" true)])
                                                 filters))]
                             (members/q-and filters)))
                         refset-ids)
            q (members/q-or queries)
            referenced-component-ids (members/search memberSearcher q)]
        (search/q-concept-ids referenced-component-ids)))))

(defn parse-history-supplement
  "historySupplement = {{ ws + ws historyKeyword [ historyProfileSuffix / ws historySubset ] ws }}

  Returns a query for the reference set identifiers to use in sourcing historical associations."
  [{:keys [store] :as ctx} loc]
  (let [history-keyword (zx/xml1-> loc :historyKeyword zx/text)
        history-profile-suffix (zx/xml1-> loc :historyProfileSuffix zx/text)
        profile (when history-profile-suffix (keyword (str/upper-case (str history-keyword history-profile-suffix))))
        history-subset (zx/xml1-> loc :historySubset :expressionConstraint #(parse-expression-constraint ctx %))]
    (or history-subset
        (search/q-concept-ids (store/history-profile store profile)))))

(defn apply-filter-constraints
  [base-query _ctx filter-constraints]
  (if (seq filter-constraints)
    (search/q-and (conj filter-constraints base-query))
    base-query))

(s/fdef apply-history-supplement
  :args (s/cat :base-query ::query :ctx ::ctx :history-supplement (s/nilable ::query)))
(defn apply-history-supplement
  [base-query {:keys [store] :as ctx} history-supplement]
  (if history-supplement
    (let [concept-ids (realise-concept-ids ctx base-query)
          refset-ids (realise-concept-ids ctx history-supplement)
          concept-ids' (store/with-historical store concept-ids refset-ids)]
      (search/q-concept-ids concept-ids'))
    base-query))

(defn parse-member-of
  "memberOf = \"^\" [ ws \"[\" ws (refsetFieldNameSet / wildCard) ws \"]\"]
  refsetFieldNameSet = refsetFieldName *(ws \",\" ws refsetFieldName)
  refsetFieldName = 1*alpha
  wildCard = \"*\""
  [ctx loc]
  (let [refset-field-names (zx/xml-> loc :refsetFieldNameSet :refsetFieldName zx/text)
        wildcard (zx/xml1-> loc :wildCard)]
    (if (or (seq refset-field-names) wildcard)
      (throw (ex-info "selection of other fields in refset(s) not supported" {:wildcard (boolean wildcard) :refset-field-names refset-field-names :text (zx/text loc)}))
      true)))

(defn parse-subexpression-constraint
  "subExpressionConstraint = [constraintOperator ws] ( ( [memberOf ws] (eclFocusConcept / ( ws expressionConstraint ws )) *(ws memberFilterConstraint)) / (eclFocusConcept / ( ws expressionConstraint ws )) ) *(ws (descriptionFilterConstraint / conceptFilterConstraint)) [ws historySupplement]

  For the history supplement, the current implementation realises the main
  constraint and then expands to include historic associations. An alternative
  approach would be first-class indexing of these associations within the search
  index.

  From the documentation:

  \"A sub expression constraint optionally begins with a constraint operator
  and/or a memberOf function. It then includes either a single focus concept or
  an expression constraint (enclosed in brackets). If the memberOf function
  is applied, a member filter may be used. A sub expression constraint may then
  optionally include one or more concept or description filter constraints,
  followed optionally by a history supplement.

  Notes: A memberOf function should be used only when the eclFocusConcept or
  expressionConstraint refers to a reference set concept, a set of reference set
  concepts, or a wild card. When both a constraintOperator and a memberOf
  function are used, they are applied from the inside to out (i.e. from right to
  left) - see 5.4 Order of Operation. Therefore, if a constraintOperator is
  followed by a memberOf function, then the memberOf function is processed prior
  to the constraintOperator.\""
  [{:keys [store] :as ctx} loc]
  (let [constraint-operator (zx/xml1-> loc :constraintOperator parse-constraint-operator)
        member-of (zx/xml1-> loc :memberOf #(parse-member-of ctx %))
        focus-concept (zx/xml1-> loc :eclFocusConcept parse-focus-concept)
        wildcard? (= :wildcard focus-concept)
        expression-constraint (zx/xml1-> loc :expressionConstraint (partial parse-expression-constraint ctx))
        concept-filter-constraints (zx/xml-> loc :conceptFilterConstraint #(parse-concept-filter-constraint ctx %))
        member-filter-constraints (zx/xml-> loc :memberFilterConstraint
                                            #(parse-member-filter-constraint ctx (or focus-concept expression-constraint) %))
        history-supplement (zx/xml1-> loc :historySupplement #(parse-history-supplement ctx %))
        base-query (cond
                     ;; "*"
                     (and (nil? member-of) (nil? constraint-operator) wildcard?) ;; "*" = all concepts
                     (search/q-match-all)                   ;; see https://confluence.ihtsdotools.org/display/DOCECL/6.1+Simple+Expression+Constraints

                     ;; "<< *"
                     (and (= :descendantOrSelfOf constraint-operator) wildcard?) ;; "<< *" = all concepts
                     (search/q-match-all)

                     ;; ">> *"
                     (and (= :ancestorOrSelfOf constraint-operator) wildcard?) ;; ">> *" = all concepts (each is ancestor-or-self of itself)
                     (search/q-match-all)

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
                     (search/q-memberOfInstalledReferenceSet store)

                     ;; "^ conceptId {{ M mapTarget="J45.9"}}"   ;; member of, but with filter constraints
                     (and member-of (or focus-concept expression-constraint) (seq member-filter-constraints))
                     (search/q-and member-filter-constraints)

                     ;; "^ conceptId"
                     (and member-of (:conceptId focus-concept))
                     (search/q-memberOf (:conceptId focus-concept))

                     (and member-of expression-constraint)
                     (make-nested-query ctx expression-constraint search/q-memberOfAny)

                     (and (nil? constraint-operator) expression-constraint)
                     expression-constraint

                     (and (= :descendantOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint search/q-descendantOfAny)

                     (and (= :descendantOrSelfOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint search/q-descendantOrSelfOfAny)

                     (and (= :childOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint search/q-childOfAny)

                     (and (= :childOrSelfOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint search/q-childOrSelfOfAny)

                     (and (= :ancestorOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint #(search/q-ancestorOfAny store %))

                     (and (= :ancestorOrSelfOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint #(search/q-ancestorOrSelfOfAny store %))

                     (and (= :parentOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint #(search/q-parentOfAny store %))

                     (and (= :parentOrSelfOf constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint #(search/q-parentOrSelfOfAny store %))

                     (and (= :bottom constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint #(search/q-bottomOfSet store %))

                     (and (= :top constraint-operator) expression-constraint)
                     (make-nested-query ctx expression-constraint search/q-topOfSet)

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
                     (search/q-ancestorOf store (:conceptId focus-concept))

                     ;; ">> conceptId"
                     (and (= :ancestorOrSelfOf constraint-operator) (:conceptId focus-concept))
                     (search/q-ancestorOrSelfOf store (:conceptId focus-concept))

                     ;; ">! conceptId"
                     (and (= :parentOf constraint-operator) (:conceptId focus-concept))
                     (search/q-parentOf store (:conceptId focus-concept))

                     ;; ">>! conceptId"
                     (and (= :parentOrSelfOf constraint-operator) (:conceptId focus-concept))
                     (search/q-parentOrSelfOf store (:conceptId focus-concept))

                     :else
                     (throw (ex-info "error: unimplemented expression fragment; use `(ex-data *e)` to see context."
                                     {:text                           (zx/text loc)
                                      :constraint-operator            constraint-operator
                                      :member-of                      member-of
                                      :focus-concept                  focus-concept
                                      :expression-constraint          expression-constraint})))
        ;; description filters are parsed AFTER base-query so the outer
        ;; substrate can narrow wildcard term scans (see parse-wild-search-term-set)
        description-filter-constraints (zx/xml-> loc :descriptionFilterConstraint
                                                 #(parse-description-filter-constraint ctx base-query %))]
    ;; now take base query (as 'b') and process according to the constraints
    (-> base-query
        (apply-filter-constraints ctx concept-filter-constraints)
        (apply-filter-constraints ctx description-filter-constraints)
        (apply-history-supplement ctx history-supplement))))

(defn parse-refined-expression-constraint
  [ctx loc]
  (let [subexpression (zx/xml1-> loc :subExpressionConstraint #(parse-subexpression-constraint ctx %))
        ecl-refinement (zx/xml1-> loc :eclRefinement #(parse-ecl-refinement ctx subexpression %))]
    (search/q-and [subexpression ecl-refinement])))

(defn parse-expression-constraint
  "expressionConstraint = ws ( refinedExpressionConstraint / compoundExpressionConstraint / dottedExpressionConstraint / subExpressionConstraint ) ws"
  [ctx loc]
  (or (zx/xml1-> loc :refinedExpressionConstraint (partial parse-refined-expression-constraint ctx))
      (zx/xml1-> loc :compoundExpressionConstraint (partial parse-compound-expression-constraint ctx))
      (zx/xml1-> loc :dottedExpressionConstraint (partial parse-dotted-expression-constraint ctx))
      (zx/xml1-> loc :subExpressionConstraint (partial parse-subexpression-constraint ctx))))

(s/fdef parse
  :args (s/cat :ctx ::ctx :s string?))
(defn parse
  "Parse SNOMED-CT ECL, as defined by the expression constraint language.
  Returns a Lucene query.
  See http://snomed.org/ecl"
  [ctx s]
  (let [p (ecl-parser s)]
    (if (insta/failure? p)
      (let [fail (insta/get-failure p)]
        (throw (ex-info (str "invalid SNOMED ECL expression at line " (:line p) ", column " (:column p) ": '" (:text p) "'.") fail)))
      (zx/xml1-> (zip/xml-zip p)
                 :expressionConstraint
                 (partial parse-expression-constraint ctx)))))

(defn valid?
  "Is the given string valid ECL?"
  [s]
  (let [p (ecl-parser s)]
    (not (insta/failure? p))))

(defn invalid?
  "Returns parsing failure, if string is not valid ECL."
  [s]
  (let [p (ecl-parser s)]
    (insta/get-failure p)))

(comment
  ;; TODO: move into live service test suite
  (do
    (def store (store/open-store "snomed.db/store.db"))
    (def index-reader (search/open-index-reader "snomed.db/search.db"))
    (def searcher (org.apache.lucene.search.IndexSearcher. index-reader))
    (def member-index-reader (members/open-index-reader "snomed.db/members.db"))
    (def member-searcher (IndexSearcher. member-index-reader))
    (def ctx {:store store :searcher searcher :memberSearcher member-searcher})
    (require '[clojure.pprint :as pp])
    (def testq (comp pp/print-table (partial search/test-query store searcher)))
    (def pe (partial parse ctx)))

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
  (testq (pe "<  64572001 |Disease|  {{ term = \"box\", type = syn, dialect = ( en-gb (accept) en-nhs-clinical )  }}") 10000)
  (pe "<  64572001 |Disease|  {{ term = \"box\", type = syn, dialect = ( en-gb (accept) en-nhs-clinical )  }}")
  (pe "<  404684003 |Clinical finding| : 116676008 |Associated morphology|  =
     ((<<  56208002 |Ulcer|  AND \n    <<  50960005 |Hemorrhage| ) MINUS \n    <<  26036001 |Obstruction| )")

  (testq (pe "<< 50043002 : << 263502005 = << 19939008") 100000)
  (pe "<  64572001 |Disease| {{ term = wild:\"cardi*opathy\"}}"))

(comment
  (ecl-parser "<  64572001 |Disease| {{ term = \"hjärt\"}}"))
