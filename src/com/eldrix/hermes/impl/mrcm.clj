; Copyright (c) 2020-2026 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns ^:no-doc com.eldrix.hermes.impl.mrcm
  "MRCM (Machine Readable Concept Model) operations.
  Provides attribute domain and range lookups for validating SNOMED CT
  expressions against MRCM constraints."
  (:require [clojure.spec.alpha :as s]
            [com.eldrix.hermes.impl.ecl :as ecl]
            [com.eldrix.hermes.impl.members :as members]
            [com.eldrix.hermes.impl.scg :as scg]
            [com.eldrix.hermes.impl.search :as search]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.rf2]
            [com.eldrix.hermes.snomed :as snomed]))

(defn refset-ids
  "Return a set of MRCM reference set ids, optionally of the specified type."
  ([{:keys [store memberSearcher]}]
   (into #{}
         (comp (mapcat #(store/component-refset-items store % snomed/MRCMModuleScopeReferenceSet))
               (map :mrcmRuleRefsetId))
         (members/search memberSearcher (members/q-refset-id snomed/MRCMModuleScopeReferenceSet))))
  ([{:keys [store memberSearcher]} type-id]
   (into #{}
         (comp (mapcat #(store/component-refset-items store % snomed/MRCMModuleScopeReferenceSet))
               (map :mrcmRuleRefsetId)
               (filter #(store/is-a? store % type-id)))
         (members/search memberSearcher (members/q-refset-id snomed/MRCMModuleScopeReferenceSet)))))

(defn domains
  "Return a sequence of MRCM Domain reference set items."
  [{:keys [store memberSearcher] :as ctx}]
  (let [rids (refset-ids ctx snomed/MRCMDomainReferenceSet)]
    (->> (members/search memberSearcher (members/q-refset-ids rids))
         (mapcat #(mapcat (fn [rid] (store/component-refset-items store % rid)) rids)))))

(defn make-domain-fn
  "Create a function (fn [concept-id]) -> #{domain-ids} that returns the set
  of MRCM domain identifiers a concept belongs to. Parses and caches the ECL
  domain constraints for efficient repeated lookups."
  [{:keys [searcher] :as ctx}]
  (let [domain-queries (->> (domains ctx)
                            (reduce (fn [acc v]
                                      (assoc acc (:referencedComponentId v)
                                                 (ecl/parse ctx (:domainConstraint v))))
                                    {}))]
    (fn [concept-id]
      (let [q1 (search/q-self concept-id)]
        (reduce-kv (fn [acc domain-id q2]
                     (if (seq (search/do-query-for-concept-ids searcher (search/q-and [q1 q2])))
                       (conj acc domain-id) acc))
                   #{} domain-queries)))))

(defn concept-domains
  "Return a set of concept ids representing the MRCM domains for the given
  concept. The context must contain a `:mrcmDomainFn` key, as created by
  [[make-domain-fn]]."
  [ctx concept-id]
  ((:mrcmDomainFn ctx) concept-id))

(defn attribute-domain
  "Returns a single MRCMAttributeDomainRefsetItem for the attribute specified
  in the context of the concept specified.

  Some attributes can be used in multiple domains, so there may be multiple
  reference set items for the same attribute. When there are multiple items,
  the concept's domains are used to select the correct item.

  The optional `content-type-id` parameter filters items to those applicable
  to the specified content type (e.g. `snomed/PostcoordinatedContent`)."
  ([ctx concept-id attribute-concept-id]
   (attribute-domain ctx concept-id attribute-concept-id nil))
  ([{:keys [store] :as ctx} concept-id attribute-concept-id content-type-id]
   (let [items (cond->> (->> (refset-ids ctx snomed/MRCMAttributeDomainReferenceSet)
                             (mapcat #(store/component-refset-items store attribute-concept-id %))
                             (filter :active))
                 content-type-id (filter #(store/is-a? store (:contentTypeId %) content-type-id)))]
     (case (count items)
       0 nil
       1 (first items)
       (let [domain-ids (concept-domains ctx concept-id)]
         (->> items
              (filter #(domain-ids (:domainId %)))
              (sort-by :effectiveTime)
              last))))))

(defn attribute-range
  "Return the active MRCMAttributeRangeRefsetItem for the given attribute.
  The optional `content-type-id` parameter filters items to those applicable
  to the specified content type."
  ([ctx attribute-concept-id]
   (attribute-range ctx attribute-concept-id nil))
  ([{:keys [store] :as ctx} attribute-concept-id content-type-id]
   (cond->> (->> (refset-ids ctx snomed/MRCMAttributeRangeReferenceSet)
                 (mapcat #(store/component-refset-items store attribute-concept-id %))
                 (filter :active))
     content-type-id (filter #(store/is-a? store (:contentTypeId %) content-type-id))
     true (sort-by :effectiveTime)
     true last)))

(defn allowed-attributes
  "Return MRCM-permitted attributes for a concept under the given content type.
  Returns a sequence of maps with :conceptId, :grouped and :rangeConstraint."
  [{:keys [store memberSearcher] :as ctx} concept-id content-type-id]
  (let [rids (refset-ids ctx snomed/MRCMAttributeDomainReferenceSet)
        domain-ids (concept-domains ctx concept-id)
        attr-ids (mapcat #(members/search memberSearcher (members/q-refset-id %)) rids)]
    (->> attr-ids
         (mapcat (fn [attr-id]
                   (->> rids
                        (mapcat #(store/component-refset-items store attr-id %))
                        (filter :active)
                        (filter #(domain-ids (:domainId %)))
                        (filter #(store/is-a? store (:contentTypeId %) content-type-id)))))
         (group-by :referencedComponentId)
         vals
         (mapv (fn [group]
                 (let [{:keys [referencedComponentId grouped]} (first group)
                       {:keys [rangeConstraint]} (attribute-range ctx referencedComponentId content-type-id)]
                   {:conceptId       referencedComponentId
                    :grouped         grouped
                    :rangeConstraint rangeConstraint}))))))

(defn in-range?
  "Does the given concept-id satisfy the ECL range constraint string?"
  [{:keys [searcher] :as ctx} range-constraint concept-id]
  (let [q1 (search/q-self concept-id)
        q2 (ecl/parse ctx range-constraint)]
    (boolean (seq (search/do-query-for-concept-ids searcher (search/q-and [q1 q2]))))))

;;
;; Expression validation against MRCM constraints.
;;
;; Pipeline checks thread ctx, accumulating ::errors (a set).
;; ::stop halts the name→domain→range/grouping chain for an attribute.
;; ::value-err skips the range check (value concept was invalid).
;;

(def error-types
  #{:concept-not-found
    :concept-inactive
    :attribute-invalid
    :attribute-not-in-domain
    :value-out-of-range
    :attribute-must-be-grouped
    :attribute-must-be-ungrouped})

(s/def ::error error-types)
(s/def ::concept-id :info.snomed.Concept/id)
(s/def ::attribute-id :info.snomed.Concept/id)
(s/def ::value-id :info.snomed.Concept/id)
(s/def ::focus-concept-ids (s/coll-of :info.snomed.Concept/id :kind vector?))
(s/def ::range-constraint string?)

(s/def ::expression-error
  (s/keys :req-un [::error]
          :opt-un [::concept-id ::attribute-id ::value-id
                   ::focus-concept-ids ::range-constraint]))

(defn concept-error
  "Return an error map if concept-id is missing or inactive, nil otherwise."
  [{:keys [store]} concept-id]
  (let [c (store/concept store concept-id)]
    (cond
      (nil? c)        {:error :concept-not-found :concept-id concept-id}
      (not (:active c)) {:error :concept-inactive :concept-id concept-id})))

(defn attribute-in-domain?
  "True if the attribute is valid for at least one of the focus concepts
  under the given content type rules."
  [ctx focus-concept-ids attribute-id content-type-id]
  (some (fn [fc-id]
          (when-let [ad (attribute-domain ctx fc-id attribute-id content-type-id)]
            (contains? (concept-domains ctx fc-id) (:domainId ad))))
        focus-concept-ids))

(defn value-in-range?
  "True if the value concept satisfies the MRCM range constraint for the attribute."
  [ctx attribute-id value-id content-type-id]
  (if-let [range-item (attribute-range ctx attribute-id content-type-id)]
    (in-range? ctx (:rangeConstraint range-item) value-id)
    true))

(defn valid-attribute-grouping?
  "True if the attribute's grouped/ungrouped usage matches MRCM rules."
  [ctx focus-concept-ids attribute-id grouped? content-type-id]
  (if-let [ad (some #(attribute-domain ctx % attribute-id content-type-id)
                     focus-concept-ids)]
    (= (:grouped ad) grouped?)
    true))

;; Pipeline check functions.
;; Each takes ctx as first arg and returns ctx, possibly with added errors/flags.

(defn check-name
  "Check the attribute name concept. Sets ::stop if invalid."
  [{::keys [stop] :as ctx} attr-id]
  (if stop
    ctx
    (if-let [e (concept-error ctx attr-id)]
      (-> ctx (update ::errors conj e) (assoc ::stop true))
      ctx)))

(defn check-domain
  "Check the attribute is in domain for the focus concepts. Sets ::stop if not."
  [{::keys [stop] :keys [store] :as ctx} focus-concept-ids attr-id content-type-id]
  (cond
    stop
    ctx

    (scg/attribute-type-error store attr-id)
    (-> ctx
        (update ::errors conj {:error :attribute-invalid
                               :concept-id attr-id})
        (assoc ::stop true))

    (attribute-in-domain? ctx focus-concept-ids attr-id content-type-id)
    ctx

    :else
    (-> ctx
        (update ::errors conj {:error :attribute-not-in-domain
                               :attribute-id attr-id
                               :focus-concept-ids (vec focus-concept-ids)})
        (assoc ::stop true))))

(defn check-value
  "Check the value concept. Always runs. Sets ::value-err if invalid."
  [ctx value-id]
  (if (nil? value-id)
    ctx
    (if-let [e (concept-error ctx value-id)]
      (-> ctx (update ::errors conj e) (assoc ::value-err true))
      ctx)))

(defn check-range
  "Check the value satisfies the MRCM range constraint.
  Skips if stopped, no value concept, or value concept was invalid."
  [{::keys [stop value-err] :as ctx} attr-id value-id content-type-id]
  (cond
    (or stop (nil? value-id) value-err)
    ctx

    (value-in-range? ctx attr-id value-id content-type-id)
    ctx

    :else
    (update ctx ::errors conj {:error :value-out-of-range
                               :attribute-id attr-id
                               :value-id value-id
                               :range-constraint (:rangeConstraint (attribute-range ctx attr-id content-type-id))})))

(defn check-grouping
  "Check the attribute's grouped/ungrouped usage. Skips if stopped."
  [{::keys [stop] :as ctx} focus-concept-ids attr-id grouped? content-type-id]
  (cond
    stop
    ctx

    (valid-attribute-grouping? ctx focus-concept-ids attr-id grouped? content-type-id)
    ctx

    :else
    (update ctx ::errors conj {:error (if grouped? :attribute-must-be-ungrouped :attribute-must-be-grouped)
                               :attribute-id attr-id})))

(defn check-attribute
  "Run the MRCM validation pipeline for a single attribute."
  [ctx focus-concept-ids [attr-name attr-value] grouped? content-type-id]
  (let [attr-id  (:conceptId attr-name)
        value-id (when (map? attr-value) (:conceptId attr-value))]
    (-> ctx
        (assoc ::stop false ::value-err false)
        (check-name attr-id)
        (check-domain focus-concept-ids attr-id content-type-id)
        (check-value value-id)
        (check-range attr-id value-id content-type-id)
        (check-grouping focus-concept-ids attr-id grouped? content-type-id)
        (dissoc ::stop ::value-err))))

(defn check-refinements
  "Run MRCM validation for all refinements."
  [ctx focus-concept-ids refinements content-type-id]
  (reduce (fn [ctx r]
            (let [attrs    (if (set? r) r [r])
                  grouped? (set? r)]
              (reduce #(check-attribute %1 focus-concept-ids %2 grouped? content-type-id)
                      ctx attrs)))
          ctx refinements))

(defn check-focus-concepts
  "Check all focus concepts exist and are active."
  [ctx focus-concepts]
  (reduce (fn [ctx fc]
            (if-let [e (concept-error ctx (:conceptId fc))]
              (update ctx ::errors conj e)
              ctx))
          ctx focus-concepts))

(s/fdef expression-errors
  :args (s/cat :ctx any? :expression :ctu/expression :content-type-id :info.snomed.Concept/id)
  :ret (s/nilable (s/coll-of ::expression-error :kind set?)))

(defn expression-errors
  "Validate a CTU expression against MRCM constraints.
  ctx must be a map with keys :store, :searcher, :memberSearcher, :mrcmDomainFn.
  content-type-id selects the applicable MRCM rules (e.g.
  `snomed/PostcoordinatedContent`). Returns a set of error maps or nil."
  [ctx expression content-type-id]
  (let [{:keys [focusConcepts refinements]} (:subExpression expression)
        result (-> (assoc ctx ::errors #{})
                   (check-focus-concepts focusConcepts)
                   (check-refinements (mapv :conceptId focusConcepts) refinements content-type-id))]
    (not-empty (::errors result))))
