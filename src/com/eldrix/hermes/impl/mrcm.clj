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
            [com.eldrix.hermes.impl.search :as search]
            [com.eldrix.hermes.impl.store :as store]
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

(s/fdef domains
  :ret (s/coll-of :info.snomed/MRCMDomainRefset))

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
  the concept's domains are used to select the correct item."
  [{:keys [store] :as ctx} concept-id attribute-concept-id]
  (let [items (->> (refset-ids ctx snomed/MRCMAttributeDomainReferenceSet)
                   (mapcat #(store/component-refset-items store attribute-concept-id %))
                   (filter :active))]
    (case (count items)
      0 nil
      1 (first items)
      (let [domain-ids (concept-domains ctx concept-id)]
        (->> items
             (filter #(domain-ids (:domainId %)))
             (sort-by :effectiveTime)
             last)))))

(defn attribute-range
  "Return the active MRCMAttributeRangeRefsetItem for the given attribute."
  [{:keys [store] :as ctx} attribute-concept-id]
  (->> (refset-ids ctx snomed/MRCMAttributeRangeReferenceSet)
       (mapcat #(store/component-refset-items store attribute-concept-id %))
       (filter :active)
       (sort-by :effectiveTime)
       last))

(defn in-range?
  "Does the given concept-id satisfy the ECL range constraint string?"
  [{:keys [searcher] :as ctx} range-constraint concept-id]
  (let [q1 (search/q-self concept-id)
        q2 (ecl/parse ctx range-constraint)]
    (boolean (seq (search/do-query-for-concept-ids searcher (search/q-and [q1 q2]))))))
