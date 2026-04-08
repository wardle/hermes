; Copyright (c) 2020-2026 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns ^:no-doc com.eldrix.hermes.impl.scg
  "Support for SNOMED CT compositional grammar.
  See http://snomed.org/scg"
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.rf2]
            [com.eldrix.hermes.snomed :as snomed]
            [instaparse.core :as insta]))

(def ^:private cg-parser
  (insta/parser (io/resource "cg-v2.4.abnf") :input-format :abnf))

(defn- syntax-literal?
  "Returns true for single-character grammar syntax literals."
  [x]
  (and (string? x) (contains? #{"|" "+" ":" "=" "," "{" "}" "(" ")" "#"} x)))

(defn- remove-noise
  "Remove nil values and syntax literal strings from transformed args."
  [args]
  (remove #(or (nil? %) (syntax-literal? %)) args))

(def ^:private scg-transform
  {:ws                (constantly nil)
   :SP                str
   :HTAB              (constantly nil)
   :CR                (constantly nil)
   :LF                (constantly nil)
   :digit             str
   :digitNonZero      str
   :zero              (constantly "0")
   :sctId             (fn [& chs] (parse-long (apply str chs)))
   :conceptId         identity
   :term              (fn [& parts] (apply str parts))
   :nonwsNonPipe      str
   :conceptReference  (fn [& args]
                        (let [parts (vec (remove-noise args))]
                          (if (= 1 (count parts))
                            {:conceptId (first parts)}
                            {:conceptId (first parts) :term (second parts)})))
   :focusConcept      (fn [& args] (vec (remove-noise args)))
   :attributeName     identity
   :stringValue       (fn [& chs] (apply str chs))
   :anyNonEscapedChar str
   :escapedChar       (fn [_bs ch] (if (nil? ch) "\"" "\\"))
   :integerValue      (fn [& chs] (apply str chs))
   :decimalValue      (fn [& parts] (apply str parts))
   :numericValue      (fn [& parts] (parse-double (apply str parts)))
   :true              (constantly true)
   :false             (constantly false)
   :booleanValue      identity
   :QM                (constantly nil)
   :BS                (constantly ::backslash)
   :expressionValue   (fn [& args] (first (remove-noise args)))
   :attributeValue    (fn [& args] (first (remove-noise args)))
   :attribute         (fn [& args] (vec (remove-noise args)))
   :attributeSet      (fn [& args] (vec (remove-noise args)))
   :attributeGroup    (fn [& args]
                        (set (first (remove-noise args))))
   :refinement        (fn [& args]
                        (vec (mapcat (fn [x]
                                       (cond
                                         (nil? x) nil
                                         (syntax-literal? x) nil
                                         (set? x) [x]
                                         (vector? x) x
                                         :else [x]))
                                     args)))
   :subExpression     (fn [& args]
                        (let [parts (vec (remove-noise args))
                              focus (first parts)
                              refinement (second parts)]
                          (cond-> {:focusConcepts focus}
                            refinement (assoc :refinements refinement))))
   :definitionStatus  identity
   :equivalentTo      (constantly :equivalent-to)
   :subtypeOf         (constantly :subtype-of)
   :expression        (fn [& args]
                        (let [parts (vec (remove-noise args))]
                          (if (= 1 (count parts))
                            {:definitionStatus :equivalent-to :subExpression (first parts)}
                            {:definitionStatus (first parts) :subExpression (second parts)})))})

;;
;; Specs for the close-to-user (CTU) expression IR.
;; As this is based on the scg grammar, we use camelcase here.
;; Preserves user terms, uses concept maps {:conceptId ... :term ...}.
;;
(s/def :ctu/conceptId :info.snomed.Concept/id)
(s/def :ctu/term string?)
(s/def :ctu/concept (s/keys :req-un [:ctu/conceptId] :opt-un [:ctu/term]))
(s/def :ctu/focusConcepts (s/coll-of :ctu/concept :kind vector? :min-count 1))
(s/def :ctu/attributeValue (s/or :concept :ctu/concept
                                 :expression :ctu/subExpression
                                 :numeric number?
                                 :boolean boolean?
                                 :string string?))
(s/def :ctu/attribute (s/tuple :ctu/concept :ctu/attributeValue))
(s/def :ctu/group (s/coll-of :ctu/attribute :kind set? :min-count 1))
(s/def :ctu/refinements (s/cat :ungrouped (s/* :ctu/attribute)
                               :groups (s/* :ctu/group)))
(s/def :ctu/subExpression (s/keys :req-un [:ctu/focusConcepts] :opt-un [:ctu/refinements]))
(s/def :ctu/definitionStatus #{:equivalent-to :subtype-of})
(s/def :ctu/expression (s/keys :req-un [:ctu/definitionStatus :ctu/subExpression]))

(s/fdef str->ctu
  :args (s/cat :s string?)
  :ret :ctu/expression)

(defn str->ctu
  "Parse a SNOMED-CT expression, as defined by the compositional grammar.
  See https://confluence.ihtsdotools.org/display/DOCSCG/Compositional+Grammar+-+Specification+and+Guide

  The result is a map with keys:
  - :definitionStatus - :equivalent-to or :subtype-of
  - :subExpression    - a map with:
    - :focusConcepts  - vector of concept references [{:conceptId long, :term string?} ...]
    - :refinements    - vector of refinement items, where each item is either:
      - [name value] pair (ungrouped attribute, SNOMED group 0)
      - #{[name value] ...} set (attribute group, SNOMED non-zero group)"
  [s]
  (let [p (cg-parser s)]
    (if (insta/failure? p)
      (let [fail (insta/get-failure p)]
        (throw (ex-info (str "invalid SNOMED CT compositional grammar expression at line " (:line p) ", column " (:column p) ": '" (:text p) "'.") fail)))
      (insta/transform scg-transform p))))

;;
;; Specs for the classifiable form (CF) IR.
;; Bare concept IDs (longs), sets throughout, no terms.
;; Produced by `ctu->cf+normalize`; suitable for comparison and subsumption testing.
;;
(defmulti ^:private cf-attribute-value-type first)
(defmethod cf-attribute-value-type :concept [_] (s/tuple #{:concept} :info.snomed.Concept/id))
(defmethod cf-attribute-value-type :expression [_] (s/tuple #{:expression} :cf/expression))
(defmethod cf-attribute-value-type :numeric [_] (s/tuple #{:numeric} number?))
(defmethod cf-attribute-value-type :boolean [_] (s/tuple #{:boolean} boolean?))
(defmethod cf-attribute-value-type :string [_] (s/tuple #{:string} string?))
(defn- gen-leaf-attribute-value
  "Generator for non-recursive CF attribute values."
  []
  (gen/one-of [(gen/fmap (fn [id] [:concept id]) (s/gen :info.snomed.Concept/id))
               (gen/fmap (fn [n] [:numeric (long n)]) (gen/choose 0 1000))
               (gen/fmap (fn [n] [:numeric (/ (double n) 100.0)]) (gen/choose 0 100000))
               (gen/fmap (fn [s] [:string s]) (gen/string-alphanumeric))
               (gen/fmap (fn [b] [:boolean b]) (gen/boolean))]))

(defn- gen-nested-expression
  "Generator for a simple nested CF expression (single focus concept, no further nesting)."
  []
  (gen/fmap (fn [[fc ds]]
              [:expression {:cf/focus-concepts    #{fc}
                            :cf/definition-status ds}])
            (gen/tuple (s/gen :info.snomed.Concept/id)
                       (gen/elements [:subtype-of :equivalent-to]))))

(s/def :cf/attribute-value
  (s/with-gen
    (s/multi-spec cf-attribute-value-type first)
    #(gen/frequency [[8 (gen-leaf-attribute-value)]
                     [2 (gen-nested-expression)]])))
(s/def :cf/attribute (s/tuple :info.snomed.Concept/id :cf/attribute-value))
(s/def :cf/focus-concepts (s/coll-of :info.snomed.Concept/id :kind set? :min-count 1))
(s/def :cf/ungrouped (s/coll-of :cf/attribute :kind set?))
(s/def :cf/group (s/coll-of :cf/attribute :kind set? :min-count 1))
(s/def :cf/groups (s/coll-of :cf/group :kind set?))
(s/def :cf/definition-status #{:equivalent-to :subtype-of})
(s/def :cf/expression (s/keys :req [:cf/focus-concepts :cf/definition-status]
                              :opt [:cf/ungrouped :cf/groups]))

(s/def ::update-terms? boolean?)
(s/def ::hide-terms? boolean?)

(defn strip-terms
  "Recursively strip all :term keys from an expression."
  [expression]
  (walk/postwalk
    (fn [node]
      (if (and (map? node) (contains? node :term))
        (dissoc node :term)
        node))
    expression))

(declare canonicalize-subexpression)

(defn- canonicalize-value
  "Canonicalize an attribute value, recursing into nested subexpressions."
  [value]
  (if (and (map? value) (contains? value :focusConcepts))
    (canonicalize-subexpression value)
    value))

(defn- canonicalize-attribute
  "Canonicalize a single attribute pair, recursing into its value."
  [[attr-name value]]
  [attr-name (canonicalize-value value)])

(defn- sort-attributes
  "Sort a collection of attribute pairs by their name concept ID."
  [attrs]
  (vec (sort-by (comp :conceptId first) attrs)))

(defn- sort-groups
  "Sort a collection of attribute groups by the minimum name concept ID in each."
  [groups]
  (vec (sort-by (fn [g] (apply min (map (comp :conceptId first) g))) groups)))

(defn- canonicalize-refinements
  "Sort ungrouped attributes and groups independently."
  [refinements]
  (let [ungrouped (take-while vector? refinements)
        groups (drop-while vector? refinements)]
    (into (sort-attributes (map canonicalize-attribute ungrouped))
          (sort-groups (map #(set (map canonicalize-attribute %)) groups)))))

(defn- canonicalize-subexpression
  [{:keys [focusConcepts refinements]}]
  (cond-> {:focusConcepts (vec (sort-by :conceptId focusConcepts))}
    refinements
    (assoc :refinements (canonicalize-refinements refinements))))

(defn canonicalize
  "Return a canonical form of an expression: terms stripped, all elements sorted
  deterministically. Two semantically equivalent expressions produce identical
  canonical forms."
  [expression]
  (let [stripped (strip-terms expression)]
    (assoc stripped :subExpression (canonicalize-subexpression (:subExpression stripped)))))

(s/fdef concept->ctu*
  :args (s/cat :concept-id :info.snomed.Concept/id
               :defined? boolean?
               :properties map?))

(defn concept->ctu*
  "Build an SCG expression IR from a concept's properties-by-group data.
  Parameters:
  - concept-id  : the SNOMED CT concept identifier
  - defined?    : whether the concept is fully defined
  - properties  : result of store/properties-by-group for this concept"
  [concept-id defined? properties]
  (let [group-0 (get properties 0)
        focus-ids (sort (get group-0 snomed/IsA))
        ungrouped (->> (dissoc group-0 snomed/IsA)
                       (mapcat (fn [[type-id target-ids]]
                                 (map (fn [tid] [{:conceptId type-id} {:conceptId tid}]) (sort target-ids))))
                       (sort-by (comp :conceptId first))
                       vec)
        groups (->> (dissoc properties 0)
                    (sort-by key)
                    (mapv (fn [[_gid attrs]]
                            (->> attrs
                                 (mapcat (fn [[type-id target-ids]]
                                           (map (fn [tid] [{:conceptId type-id} {:conceptId tid}]) (sort target-ids))))
                                 (sort-by (comp :conceptId first))
                                 set)))
                    (sort-by (fn [g] (apply min (map (comp :conceptId first) g))))
                    vec)
        refinements (into ungrouped groups)]
    {:definitionStatus (if defined? :equivalent-to :subtype-of)
     :subExpression    (cond-> {:focusConcepts (if defined?
                                                 (mapv (fn [id] {:conceptId id}) focus-ids)
                                                 [{:conceptId concept-id}])}
                         (seq refinements)
                         (assoc :refinements refinements))}))

(defn- lookup-term
  [{:keys [store language-refset-ids]} concept-id]
  (when (and store language-refset-ids)
    (:term (store/preferred-synonym store concept-id language-refset-ids))))

(defn- render-concept
  [{:keys [terms] :as ctx} {:keys [conceptId term]}]
  (let [rendered-term (case terms
                        :strip nil
                        :update (or (lookup-term ctx conceptId) term)
                        :add (or term (lookup-term ctx conceptId))
                        term)]
    (if rendered-term
      (str conceptId "|" rendered-term "|")
      (str conceptId))))

(declare render-subexpression)

(defn- render-value
  [config value]
  (cond
    (and (map? value) (get value :conceptId)) (render-concept config value)
    (and (map? value) (get value :focusConcepts)) (str "(" (render-subexpression config value) ")")
    (map? value) (throw (ex-info (str "** unknown value:'" value "' **") {:error "Unknown value" :value value}))
    (number? value) (str "#" value)
    (boolean? value) (str/upper-case (str value))
    (string? value) (str "\"" (str/replace (str/replace value "\\" "\\\\") "\"" "\\\"") "\"")
    :else (throw (ex-info (str "** unknown value:'" value "' **") {:error "Unknown value" :value value}))))

(defn- render-attribute
  [config [k v]]
  (str (render-concept config k) "=" (render-value config v)))

(defn- render-refinements
  "Render refinements: ungrouped pairs [name value] are rendered directly,
  groups (sets of pairs) are rendered with braces."
  [config refinements]
  (let [;; Partition into leading ungrouped pairs and the rest
        ungrouped (vec (take-while vector? refinements))
        remaining (drop-while vector? refinements)
        parts (concat
                (when (seq ungrouped)
                  [(str/join "," (map (partial render-attribute config) ungrouped))])
                (map (fn [group]
                       (str "{" (str/join "," (map (partial render-attribute config) group)) "}"))
                     remaining))]
    (str/join "," parts)))

(defn- render-subexpression
  [config subexp]
  (let [focus-concepts (str/join "+" (map (partial render-concept config) (:focusConcepts subexp)))
        refinements (:refinements subexp)]
    (if refinements (str focus-concepts ":" (render-refinements config refinements)) focus-concepts)))

(s/def ::terms #{:strip :update :add})
(s/def ::definition-status #{:auto :always})
(s/def ::render-opts (s/nilable (s/keys :opt-un [::terms ::definition-status])))

(s/fdef ctu->str
  :args (s/alt :unary (s/cat :expression :ctu/expression)
               :ternary (s/cat :store any? :expression :ctu/expression :opts ::render-opts))
  :ret string?)

(defn ctu->str
  "Render an expression into string form.
  Single-arity renders using terms already present in the expression.
  Three-arity takes a store and options:
    :terms              - :strip, :update, :add, or nil (passthrough, default)
    :language-refset-ids - required for :update and :add
    :definition-status  - :always (always include, default) or :auto (only
                          include when not the default :equivalent-to)"
  ([expression]
   (str ({:equivalent-to "===" :subtype-of "<<<"} (:definitionStatus expression))
        " " (render-subexpression {} (:subExpression expression))))
  ([store expression {:keys [definition-status] :as opts}]
   (let [ctx (assoc opts :store store)
         ds (:definitionStatus expression)
         prefix (case (or definition-status :always)
                  :always (str ({:equivalent-to "===" :subtype-of "<<<"} ds) " ")
                  :auto (when (= :subtype-of ds) "<<< "))]
     (str prefix (render-subexpression ctx (:subExpression expression))))))


(s/fdef concept->ctu
  :args (s/cat :store any?
               :concept-id :info.snomed.Concept/id))

(defn concept->ctu
  "Return the definition of a concept as an SCG expression IR.
  For a primitive concept, the concept itself is the sole focus concept with
  definition status :subtype-of. For a fully-defined concept, the IS-A parents become
  the focus concepts with definition status :equivalent-to. In both cases, non-IS-A
  properties are included as refinements.
  Returns nil if the concept does not exist."
  [store concept-id]
  (when-let [c (store/concept store concept-id)]
    (concept->ctu* concept-id (snomed/defined? c) (store/properties-by-group store concept-id))))

(defn properties->attributes
  "Extract non-IS-A attributes from properties-by-group as ungrouped + groups.
  Values from the store are always concept references."
  [props]
  (let [group-0 (get props 0)
        ungrouped (->> (dissoc group-0 snomed/IsA)
                       (mapcat (fn [[tid targets]]
                                 (map #(vector tid [:concept %]) targets)))
                       set)
        groups (->> (dissoc props 0)
                    (vals)
                    (into #{}
                          (map (fn [attrs]
                                 (set (mapcat (fn [[tid targets]]
                                                (map #(vector tid [:concept %]) targets))
                                              attrs))))))]
    {:ungrouped ungrouped :groups groups}))

(defn- collect-primitives-and-attributes
  "Recursively expand a concept to proximal primitive supertypes, collecting
  all defining (non-IS-A) attributes from the concept and its fully-defined
  ancestors. Primitive concepts include their own defining attributes but
  do not recurse further. Results are memoized within a single call to avoid
  redundant expansion of shared ancestors.
  Returns {:primitives #{id ...} :ungrouped #{[tid vid] ...} :groups #{#{[tid vid] ...} ...}}"
  [store concept-id]
  (let [cache (volatile! {})
        collect (fn collect [concept-id]
                  (if-let [cached (get @cache concept-id)]
                    cached
                    (let [c (store/concept store concept-id)
                          result
                          (cond
                            (nil? c)
                            {:primitives #{} :ungrouped #{} :groups #{}}

                            (snomed/primitive? c)
                            (let [{:keys [ungrouped groups]} (properties->attributes (store/properties-by-group store concept-id))]
                              {:primitives #{concept-id} :ungrouped ungrouped :groups groups})

                            :else
                            (let [props (store/properties-by-group store concept-id)
                                  {:keys [ungrouped groups]} (properties->attributes props)
                                  parent-ids (get (get props 0) snomed/IsA)
                                  parent-results (mapv collect parent-ids)]
                              {:primitives (into #{} (mapcat :primitives) parent-results)
                               :ungrouped  (into ungrouped (mapcat :ungrouped parent-results))
                               :groups     (into groups (mapcat :groups parent-results))}))]
                      (vswap! cache assoc concept-id result)
                      result)))]
    (collect concept-id)))

(defn- concept-valued?
  "True if an attribute's value is a concept reference."
  [[_ [tag]]]
  (= :concept tag))

(defn- leaf-values-for-type
  "Given attributes sharing the same type, keep only those with the most
  specific (leaf) values — i.e., remove values subsumed by another."
  [store attrs]
  (let [value-ids (map (fn [[_ [_ v]]] v) attrs)
        leaf-ids (store/leaves store value-ids)]
    (filterv (fn [[_ [_ v]]] (contains? leaf-ids v)) attrs)))

(defn remove-subsumed-attributes
  "Remove attributes whose value is subsumed by another with the same type.
  For each attribute type, keeps only the most specific (leaf) values."
  [store attrs]
  (let [by-concept (filterv concept-valued? attrs)
        other (filterv (complement concept-valued?) attrs)]
    (->> (group-by first by-concept)
         (vals)
         (into (set other) (mapcat #(leaf-values-for-type store %))))))

(defn- attribute-subsumes?
  "True if attribute a1 subsumes a2: a2's type is-a a1's type and a2's value
  is subsumed by a1's value. Concept values use is-a subsumption; concrete
  values (numeric, string, boolean) require exact match."
  [store [tid1 [tag1 vid1]] [tid2 [tag2 vid2]]]
  (and (or (= tid2 tid1) (store/is-a? store tid2 tid1))
       (cond
         (and (= :concept tag1) (= :concept tag2))
         (or (= vid2 vid1) (store/is-a? store vid2 vid1))
         (= tag1 tag2) (= vid1 vid2)
         :else false)))

(defn- group-subsumes?
  "True if group g1 subsumes g2: every attribute in g1 has a more specific
  (or equal) match in g2. O(K²) where K = attributes per group (typically <5)."
  [store g1 g2]
  (every? (fn [a1] (some #(attribute-subsumes? store a1 %) g2)) g1))

(defn remove-subsumed-groups
  "Remove groups that are subsumed by a different, more specific group."
  [store groups]
  (into #{} (remove (fn [g] (some #(and (not= g %) (group-subsumes? store g %)) groups)) groups)))

(defn- ctu-attr->cf-attr
  "Convert a CTU attribute [concept-ref value] to a CF attribute [type-id tagged-value].
  Values are tagged: [:concept id], [:expression expr], [:numeric n],
  [:string s], [:boolean b]."
  [[attr-name attr-value]]
  [(:conceptId attr-name)
   (cond
     (and (map? attr-value) (:conceptId attr-value)) [:concept (:conceptId attr-value)]
     (and (map? attr-value) (:focusConcepts attr-value)) [:expression attr-value] ;; nested subExpression, normalized later
     (number? attr-value) [:numeric attr-value]
     (string? attr-value) [:string attr-value]
     (boolean? attr-value) [:boolean attr-value]
     :else (throw (ex-info "Unsupported CTU attribute value" {:value attr-value})))])

(s/fdef ctu->cf
  :args (s/cat :expression :ctu/expression)
  :ret :cf/expression)

(defn ctu->cf
  "Convert a close-to-user expression to classifiable form without expansion.
  Preserves original focus concept IDs and definition status, only transforming
  the data shape: CTU concept maps become bare IDs, attribute values become
  tagged pairs. Suitable for OWL classification where the reasoner handles
  expansion via the loaded ontology.

  Contrast with [[ctu->cf+normalize]], which expands fully-defined focus concepts to
  proximal primitive supertypes and merges defining attributes — appropriate for
  structural subsumption testing but lossy for OWL reasoning."
  [expression]
  (let [{:keys [focusConcepts refinements]} (:subExpression expression)
        focus-ids (into #{} (map :conceptId) focusConcepts)
        ungrouped (->> (filterv vector? (or refinements []))
                       (map ctu-attr->cf-attr)
                       set)
        groups (->> (filterv set? (or refinements []))
                    (into #{} (map (fn [g] (set (map ctu-attr->cf-attr g))))))
        norm-val (fn [[tag v :as tagged]]
                   (if (= :expression tag)
                     [:expression (ctu->cf {:definitionStatus :equivalent-to :subExpression v})]
                     tagged))
        norm-attr (fn [[t v]] [t (norm-val v)])
        ungrouped (set (map norm-attr ungrouped))
        groups (into #{} (map (fn [g] (set (map norm-attr g)))) groups)]
    (cond-> {:cf/definition-status (:definitionStatus expression)
             :cf/focus-concepts    focus-ids}
      (seq ungrouped) (assoc :cf/ungrouped ungrouped)
      (seq groups) (assoc :cf/groups groups))))

(declare valid-subexpression?)

(defn valid-concept?
  [store {:keys [conceptId]}]
  (:active (store/concept store conceptId)))

(defn valid-attribute?
  [store [attr-name attr-value]]
  (and (valid-concept? store attr-name)
       (cond
         (and (map? attr-value) (:focusConcepts attr-value))
         (valid-subexpression? store attr-value)
         (and (map? attr-value) (:conceptId attr-value))
         (valid-concept? store attr-value)
         :else true)))

(defn valid-refinement?
  [store refinement]
  ;; a refinement is either a single attribute (vector pair) or a group (set of attributes)
  (if (set? refinement)
    (every? #(valid-attribute? store %) refinement)
    (valid-attribute? store refinement)))

(defn valid-subexpression?
  [store {:keys [focusConcepts refinements]}]
  (and (every? #(valid-concept? store %) focusConcepts)
       (or (nil? refinements)
           (every? #(valid-refinement? store %) refinements))))

(defn valid?
  "Returns true if all concept references in an expression exist and are active."
  [store expression]
  (valid-subexpression? store (:subExpression expression)))

(declare errors-subexpression)

(defn- concept-error
  "Return an error map for a concept reference, or nil if valid and active."
  [store {:keys [conceptId]}]
  (let [c (store/concept store conceptId)]
    (cond
      (nil? c) {:error :concept-not-found :concept-id conceptId}
      (not (:active c)) {:error :concept-inactive :concept-id conceptId})))

(defn attribute-type-error
  "Return an error if the concept is not a valid SNOMED CT attribute type
  (descendant of ConceptModelAttribute or LinkageConcept), or nil."
  [store concept-id]
  (when-not (or (store/is-a? store concept-id snomed/ConceptModelAttribute)
                (store/is-a? store concept-id snomed/LinkageConcept))
    {:error :attribute-invalid :concept-id concept-id}))

(defn- errors-attribute
  "Return errors for a single attribute name-value pair."
  [store [attr-name attr-value]]
  (let [name-err (or (concept-error store attr-name)
                     (attribute-type-error store (:conceptId attr-name)))
        value-errs (cond
                     (and (map? attr-value) (:focusConcepts attr-value))
                     (errors-subexpression store attr-value)
                     (and (map? attr-value) (:conceptId attr-value))
                     (when-let [e (concept-error store attr-value)] [e]))]
    (cond-> (if name-err [name-err] [])
      value-errs (into value-errs))))

(defn- errors-refinement
  "Return errors for a refinement (single attribute or group of attributes)."
  [store r]
  (if (set? r)
    (mapcat #(errors-attribute store %) r)
    (errors-attribute store r)))

(defn- errors-refinements
  [store refinements]
  (mapcat #(errors-refinement store %) refinements))

(defn- errors-subexpression
  [store {:keys [focusConcepts refinements]}]
  (into (vec (keep #(concept-error store %) focusConcepts))
        (when refinements
          (errors-refinements store refinements))))

(defn errors
  "Return a sequence of error maps for structural problems in the expression.
  Returns nil if all concepts are valid and active and structurally correct.
  Each error is a map with :error and :concept-id. Error types:
  - :concept-not-found — concept does not exist
  - :concept-inactive — concept is inactive
  - :attribute-invalid — concept used as attribute is not a valid attribute type
  Unlike [[valid?]], does not short-circuit and checks active status per SCG
  spec section 7.3."
  [store expression]
  (seq (errors-subexpression store (:subExpression expression))))

(defn- replace-concept
  "Replace an inactive concept with its historical target, if available.
  Returns the concept unchanged if active, or if no single replacement exists.
  `refset-ids` is a set of historical association refset identifiers to use."
  [store refset-ids {:keys [conceptId] :as concept}]
  (let [c (store/concept store conceptId)]
    (if (or (nil? c) (:active c))
      concept
      (let [items (->> (store/component-refset-items store conceptId)
                       (filter #(and (:active %) (refset-ids (:refsetId %)))))]
        (if (= 1 (count items))
          (let [target-id (:targetComponentId (first items))
                target (store/concept store target-id)]
            (if (and target (:active target))
              {:conceptId target-id}
              concept))
          concept)))))

(declare replace-subexpression)

(defn- replace-refinement-value
  [store refset-ids v]
  (cond
    (and (map? v) (:focusConcepts v)) (replace-subexpression store refset-ids v)
    (and (map? v) (:conceptId v)) (replace-concept store refset-ids v)
    :else v))

(defn- replace-refinements
  [store refset-ids refinements]
  (mapv (fn [r]
          (if (set? r)
            (set (map (fn [[attr-name attr-value]]
                        [(replace-concept store refset-ids attr-name)
                         (replace-refinement-value store refset-ids attr-value)])
                      r))
            [(replace-concept store refset-ids (first r))
             (replace-refinement-value store refset-ids (second r))]))
        refinements))

(defn- replace-subexpression
  [store refset-ids {:keys [focusConcepts refinements] :as subexpr}]
  (cond-> (assoc subexpr :focusConcepts (mapv #(replace-concept store refset-ids %) focusConcepts))
    refinements (assoc :refinements (replace-refinements store refset-ids refinements))))

(s/fdef replace-historical
  :args (s/cat :store any? :expression :ctu/expression
               :kwargs (s/keys* :opt-un [::profile]))
  :ret :ctu/expression)
(defn replace-historical
  "Replace inactive concept references in an expression with their historical
  targets. By default, uses :HISTORY-MIN (SAME_AS only) which is the only
  lossless replacement. Replacement occurs only when a single active target
  exists for the given history profile.
  Options:
    :profile - :HISTORY-MIN (default), :HISTORY-MOD or :HISTORY-MAX"
  [store expression & {:keys [profile] :or {profile :HISTORY-MIN}}]
  (let [refset-ids (set (store/history-profile store profile))]
    (update expression :subExpression #(replace-subexpression store refset-ids %))))

(s/fdef ctu->cf+normalize
  :args (s/cat :store any? :expression :ctu/expression))

(defn ctu->cf+normalize
  "Transform a close-to-user expression to classifiable form.
  Expands fully-defined focus concepts to proximal primitive supertypes,
  merges defining attributes with user refinements, removes redundancy.
  Returns a map with namespaced :cf/ keys:
    :cf/definition-status  - always :subtype-of
    :cf/focus-concepts     - set of proximal primitive concept IDs
    :cf/ungrouped         - set of [type-id value] attribute pairs
    :cf/groups            - set of #{[type-id value] ...} attribute groups
  Throws if any referenced concepts are not found in the store."
  [store expression]
  (when-not (valid? store expression)
    (throw (ex-info "Expression contains invalid concept references" {:expression expression})))
  (let [{:keys [focusConcepts refinements]} (:subExpression expression)
        user-ungrouped (->> (filterv vector? (or refinements []))
                            (map ctu-attr->cf-attr)
                            set)
        user-groups (->> (filterv set? (or refinements []))
                         (into #{} (map (fn [g] (set (map ctu-attr->cf-attr g))))))
        expansions (mapv #(collect-primitives-and-attributes store (:conceptId %)) focusConcepts)
        all-primitives (into #{} (mapcat :primitives) expansions)
        all-ungrouped (into (into #{} (mapcat :ungrouped) expansions) user-ungrouped)
        all-groups (into (into #{} (mapcat :groups) expansions) user-groups)
        norm-val (fn [[tag v :as tagged]]
                   (if (= :expression tag)
                     [:expression (ctu->cf+normalize store {:definitionStatus :equivalent-to :subExpression v})]
                     tagged))
        norm-attr (fn [[t v]] [t (norm-val v)])
        all-ungrouped (set (map norm-attr all-ungrouped))
        all-groups (into #{} (map (fn [g] (set (map norm-attr g)))) all-groups)
        all-ungrouped (remove-subsumed-attributes store all-ungrouped)
        all-groups (into #{} (map (fn [g] (set (remove-subsumed-attributes store g)))) all-groups)
        all-groups (remove-subsumed-groups store all-groups)]
    (cond-> {:cf/definition-status :subtype-of
             :cf/focus-concepts    all-primitives}
      (seq all-ungrouped) (assoc :cf/ungrouped all-ungrouped)
      (seq all-groups) (assoc :cf/groups all-groups))))

(declare cf->ctu)

(defn- cf-value->ctu-value
  "Convert a classifiable form tagged attribute value to a CTU attribute value."
  [[tag v]]
  (case tag
    :concept {:conceptId v}
    :expression (:subExpression (cf->ctu v))
    (:numeric :string :boolean) v
    (throw (ex-info "Unknown CF attribute value tag" {:tag tag :value v}))))

(s/fdef cf->ctu
  :args (s/cat :normal-form :cf/expression))

(defn cf->ctu
  "Convert a classifiable form expression to a close-to-user expression IR
  suitable for rendering."
  [{:cf/keys [definition-status focus-concepts ungrouped groups]}]
  (let [focus (mapv #(hash-map :conceptId %) (sort focus-concepts))
        ungrouped-attrs (when (seq ungrouped)
                          (->> ungrouped
                               (map (fn [[t v]] [{:conceptId t} (cf-value->ctu-value v)]))
                               (sort-by (comp :conceptId first))
                               vec))
        grouped-attrs (when (seq groups)
                        (->> groups
                             (map (fn [g] (set (map (fn [[t v]] [{:conceptId t} (cf-value->ctu-value v)]) g))))
                             (sort-by (fn [g] (apply min (map (comp :conceptId first) g))))
                             vec))
        refinements (into (or ungrouped-attrs []) (or grouped-attrs []))]
    {:definitionStatus definition-status
     :subExpression    (cond-> {:focusConcepts focus}
                         (seq refinements)
                         (assoc :refinements refinements))}))

(s/fdef cf-subsumes?
  :args (s/cat :store any? :a :cf/expression :b :cf/expression))

(defn cf-subsumes?
  "Does classifiable form expression a subsume b?
  Operates on pre-normalized CF expressions:
    1. Both expressions must have non-empty focus concepts
    2. Every focus concept in A must subsume at least one in B — O(F_a × F_b)
    3. Every ungrouped attribute in A must be subsumed by one in B — O(U_a × U_b)
    4. Every group in A must match a group in B — O(G_a × G_b × K²)
  where F=focus concepts, U=ungrouped attrs, G=groups, K=attrs per group.
  All counts are small (single digits), so effectively constant.
  is-a? is O(1) via precomputed transitive closure."
  [store a b]
  (boolean
    (let [focus-a (:cf/focus-concepts a)
          focus-b (:cf/focus-concepts b)]
      (and (seq focus-a)
           (seq focus-b)
           (every? (fn [fa]
                     (some (fn [fb] (or (= fb fa) (store/is-a? store fb fa)))
                           focus-b))
                   focus-a)
           (every? (fn [ua]
                     (some #(attribute-subsumes? store ua %) (:cf/ungrouped b)))
                   (:cf/ungrouped a))
           (every? (fn [ga]
                     (some #(group-subsumes? store ga %) (:cf/groups b)))
                   (:cf/groups a))))))

(s/fdef subsumes?
  :args (s/cat :store any? :a :ctu/expression :b :ctu/expression))

(defn subsumes?
  "Does expression a subsume expression b?
  Normalizes both expressions to classifiable form and compares.
  See [[cf-subsumes?]] for use with pre-normalized expressions."
  [store a b]
  (cf-subsumes? store (ctu->cf+normalize store a) (ctu->cf+normalize store b)))


(comment
  (def st (store/open-store "snomed.db/store.db"))
  (concept->ctu st 24700007)
  (ctu->cf+normalize st (str->ctu "24700007"))
  (ctu->cf+normalize st (str->ctu "80146002"))
  (valid? st (str->ctu "24700007"))
  (valid? st (str->ctu "100000102"))
  (replace-historical st (str->ctu "100000102"))
  (replace-historical st (str->ctu "100000102") :profile :HISTORY-MOD)
  )
