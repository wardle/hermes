; Copyright (c) 2020-2023 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns ^:no-doc com.eldrix.hermes.graph
  "Provides a graph API around SNOMED CT structures.
  To use this namespace, you will need to ensure you 'require' the pathom3
  library. Add the following to your deps.edn file:
   ````
   {com.wsscode/pathom3              {:mvn/version \"2022.10.19-alpha\"}
   ```"
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.snomed :as snomed]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.interface.eql :as p.eql]))

(defn record->map
  "Turn a record into a namespaced map."
  [n r]
  (reduce-kv (fn [m k v] (assoc m (keyword n (name k)) v)) {} r))

(def concept-properties
  [:info.snomed.Concept/id
   :info.snomed.Concept/active
   :info.snomed.Concept/effectiveTime
   :info.snomed.Concept/moduleId
   :info.snomed.Concept/definitionStatusId])

(def description-properties
  [:info.snomed.Description/id
   :info.snomed.Description/effectiveTime
   :info.snomed.Description/active
   :info.snomed.Description/moduleId
   :info.snomed.Description/conceptId
   :info.snomed.Description/languageCode
   :info.snomed.Description/typeId
   :info.snomed.Description/term
   :info.snomed.Description/caseSignificanceId])

(def relationship-properties
  [:info.snomed.Relationship/id
   :info.snomed.Relationship/effectiveTime
   :info.snomed.Relationship/active
   :info.snomed.Relationship/moduleId
   :info.snomed.Relationship/sourceId
   :info.snomed.Relationship/destinationId
   :info.snomed.Relationship/relationshipGroup
   :info.snomed.Relationship/typeId
   :info.snomed.Relationship/characteristicTypeId
   :info.snomed.Relationship/modifierId])

(def refset-item-properties
  [:info.snomed.RefsetItem/id
   :info.snomed.RefsetItem/effectiveTime
   :info.snomed.RefsetItem/active
   :info.snomed.RefsetItem/moduleId
   :info.snomed.RefsetItem/refsetId
   :info.snomed.RefsetItem/referencedComponentId

   ;; optional properties; depends on type of reference set item
   :info.snomed.RefsetItem/targetComponentId
   :info.snomed.RefsetItem/attributeDescriptionId
   :info.snomed.RefsetItem/attributeTypeId
   :info.snomed.RefsetItem/attributeOrder
   :info.snomed.RefsetItem/acceptabilityId
   :info.snomed.RefsetItem/mapTarget
   :info.snomed.RefsetItem/mapGroup
   :info.snomed.RefsetItem/mapPriority
   :info.snomed.RefsetItem/mapRule
   :info.snomed.RefsetItem/mapAdvice
   :info.snomed.RefsetItem/correlationId
   :info.snomed.RefsetItem/mapCategoryId
   :info.snomed.RefsetItem/valueId
   :info.snomed.RefsetItem/owlExpression])

(pco/defresolver concept-by-id
  "Returns a concept by identifier; results namespaced to `:info.snomed.Concept/`"
  [{svc :com.eldrix/hermes} {:info.snomed.Concept/keys [id]}]
  {::pco/output concept-properties}
  (record->map "info.snomed.Concept" (hermes/concept svc id)))

(pco/defresolver description-by-id
  "Returns a description by identifier; results namespaced to
  `:info.snomed.Description/"
  [{svc :com.eldrix/hermes} {:info.snomed.Description/keys [id]}]
  {::pco/output description-properties}
  (record->map "info.snomed.Description" (hermes/description svc id)))

(pco/defresolver relationship-by-id
  [{svc :com.eldrix/hermes} {:info.snomed.Relationship/keys [id]}]
  {::pco/output relationship-properties}
  (record->map "info.snomed.Relationship" (hermes/relationship svc id)))

(pco/defresolver refset-item-by-id
  [{svc :com.eldrix/hermes} {:info.snomed.RefsetItem/keys [id]}]
  {::pco/output refset-item-properties}
  (record->map "info.snomed.RefsetItem" (hermes/refset-item svc id)))

(pco/defresolver component-by-id
  "Resolve an arbitrary SNOMED URI as per https://confluence.ihtsdotools.org/display/DOCURI/2.2+URIs+for+Components+and+Reference+Set+Members"
  [{:info.snomed/keys [id]}]
  {::pco/output [:info.snomed.Concept/id :info.snomed.Description/id
                 :info.snomed.Relationship/id :info.snomed.RefsetItem/id]}
  (cond
    (number? id) (case (snomed/identifier->type id)
                   :info.snomed/Concept {:info.snomed.Concept/id id}
                   :info.snomed/Description {:info.snomed.Description/id id}
                   :info.snomed/Relationship {:info.snomed.Relationship/id id}
                   nil)
    (uuid? id) {:info.snomed.RefsetItem/id id}
    (string? id) {:info.snomed.RefsetItem/id (parse-uuid id)}))

(pco/defresolver concept-defined?
  "Is a concept fully defined?"
  [{:keys [:info.snomed.Concept/definitionStatusId]}]
  {:info.snomed.Concept/defined (= snomed/Defined definitionStatusId)})

(pco/defresolver concept-primitive?
  "Is a concept primitive?"
  [{:keys [:info.snomed.Concept/definitionStatusId]}]
  {:info.snomed.Concept/primitive (= snomed/Primitive definitionStatusId)})

(pco/defresolver concept-descriptions
  "Return the descriptions for a given concept."
  [{svc :com.eldrix/hermes} {:info.snomed.Concept/keys [id]}]
  {::pco/input  [:info.snomed.Concept/id]
   ::pco/output [{:info.snomed.Concept/descriptions description-properties}]}
  {:info.snomed.Concept/descriptions (map (partial record->map "info.snomed.Description") (hermes/descriptions svc id))})

(pco/defresolver concept-synonyms
  "Returns descriptions of type 'synonym' for a given concept.
  If an 'accept-language' parameter is given, that will be used to limit to
  those that are preferred, or acceptable, in the language(s) specified."
  [{svc :com.eldrix/hermes, :as env} {:info.snomed.Concept/keys [id]}]
  {::pco/input  [:info.snomed.Concept/id]
   ::pco/output [{:info.snomed.Concept/synonyms description-properties}]}
  (let [lang-refset-ids (seq (some->> (get (pco/params env) :accept-language) (hermes/match-locale svc)))]
    {:info.snomed.Concept/synonyms
     (mapv (partial record->map "info.snomed.Description")
           (if lang-refset-ids
             (hermes/synonyms svc id lang-refset-ids)
             (hermes/synonyms svc id)))}))

(pco/defresolver concept-module
  "Return the module for a given concept."
  [{:info.snomed.Concept/keys [moduleId]}]
  {::pco/output [{:info.snomed.Concept/module [:info.snomed.Concept/id]}]}
  {:info.snomed.Concept/module {:info.snomed.Concept/id moduleId}})

(pco/defresolver preferred-description
  "Returns a concept's preferred description.
  Takes an optional single parameter :accept-language, a BCP 47 language
  preference string.

  For example:
  (p.eql/process registry {:id 80146002}
    [:info.snomed.Concept/id
     :info.snomed.Concept/active
     '(:info.snomed.Concept/preferred-description {:accept-language \"en-GB\"})
     {:info.snomed.Concept/descriptions
      [:info.snomed.Description/active :info.snomed.Description/term]}])"
  [{svc :com.eldrix/hermes :as env} {:info.snomed.Concept/keys [id]}]
  {::pco/input  [:info.snomed.Concept/id]
   ::pco/output [{:info.snomed.Concept/preferredDescription description-properties}]}
  {:info.snomed.Concept/preferredDescription
   (record->map "info.snomed.Description" (hermes/preferred-synonym svc id (:accept-language (pco/params env)) true))})

(pco/defresolver fully-specified-name
  [{svc :com.eldrix/hermes} {:info.snomed.Concept/keys [id]}]
  {::pco/output [{:info.snomed.Concept/fullySpecifiedName description-properties}]}
  {:info.snomed.Concept/fullySpecifiedName (record->map "info.snomed.Description" (hermes/fully-specified-name svc id))})

(pco/defresolver lowercase-term
  "Returns a lowercase term of a SNOMED CT description according to the rules
  of case sensitivity."
  [{:info.snomed.Description/keys [caseSignificanceId term]}]
  {:info.snomed.Description/lowercaseTerm
   (case caseSignificanceId
     ;; initial character is case-sensitive - we can make initial character lowercase
     900000000000020002
     (when (pos? (count term))
       (str (str/lower-case (first term)) (subs term 1)))
     ;; entire term case-insensitive - just make it all lower-case
     900000000000448009
     (str/lower-case term)
     ;; entire term is case-sensitive - can't do anything
     900000000000017005
     term
     ;; fallback option - don't do anything
     term)})

(pco/defresolver concept-refset-ids
  "Returns a concept's reference set identifiers."
  [{svc :com.eldrix/hermes} {:info.snomed.Concept/keys [id]}]
  {::pco/input  [:info.snomed.Concept/id]
   ::pco/output [:info.snomed.Concept/refsetIds]}
  {:info.snomed.Concept/refsetIds (set (hermes/component-refset-ids svc id))})

(pco/defresolver concept-refset-items
  "Returns the refset items for a concept."
  [{svc :com.eldrix/hermes :as env} {concept-id :info.snomed.Concept/id}]
  {::pco/output [{:info.snomed.Concept/refsetItems refset-item-properties}]}
  {:info.snomed.Concept/refsetItems (map #(record->map "info.snomed.RefsetItem" %)
                                         (if-let [refset-id (:refsetId (pco/params env))]
                                           (hermes/component-refset-items svc concept-id refset-id)
                                           (hermes/component-refset-items svc concept-id)))})

(pco/defresolver description-concepts
  [{:info.snomed.Description/keys [moduleId conceptId typeId caseSignificanceId]}]
  {::pco/output [{:info.snomed.Description/module [:info.snomed.Concept/id]}
                 {:info.snomed.Description/concept [:info.snomed.Concept/id]}
                 {:info.snomed.Description/type [:info.snomed.Concept/id]}
                 {:info.snomed.Description/caseSignificance [:info.snomed.Concept/id]}]}
  {:info.snomed.Description/module           {:info.snomed.Concept/id moduleId}
   :info.snomed.Description/concept          {:info.snomed.Concept/id conceptId}
   :info.snomed.Description/type             {:info.snomed.Concept/id typeId}
   :info.snomed.Description/caseSignificance {:info.snomed.Concept/id caseSignificanceId}})

(pco/defresolver refset-item-referenced-component
  [{:info.snomed.RefsetItem/keys [referencedComponentId]}]
  {::pco/output [{:info.snomed.RefsetItem/referencedComponent [{:info.snomed.Concept/id [:info.snomed.Concept/id]}
                                                               {:info.snomed.Description/id [:info.snomed.Description/id]}]}]}
  (case (snomed/identifier->type referencedComponentId)     ;; we can easily derive the type of component from the identifier
    :info.snomed/Concept
    {:info.snomed.RefsetItem/referencedComponent {:info.snomed.Concept/id referencedComponentId}}
    :info.snomed/Description
    {:info.snomed.RefsetItem/referencedComponent {:info.snomed.Description/id referencedComponentId}}
    :info.snomed/Relationship
    {:info.snomed.RefsetItem/referencedComponent {:info.snomed.Relationship/id referencedComponentId}}))

(pco/defresolver refset-item-concepts
  [{:info.snomed.RefsetItem/keys [moduleId refsetId]}]
  {::pco/output [{:info.snomed.RefsetItem/module [:info.snomed.Concept/id]}
                 {:info.snomed.RefsetItem/refset [:info.snomed.Concept/id]}]}
  {:info.snomed.RefsetItem/module {:info.snomed.Concept/id moduleId}
   :info.snomed.RefsetItem/refset {:info.snomed.Concept/id refsetId}})

(pco/defresolver refset-item-descriptor-concepts
  [{:info.snomed.RefsetItem/keys [attributeDescriptionId attributeTypeId]}]
  {::pco/output [{:info.snomed.RefsetItem/attributeDescription [:info.snomed.Concept/id]}
                 {:info.snomed.RefsetItem/attributeType [:info.snomed.Concept/id]}]}
  {:info.snomed.RefsetItem/attributeDescription {:info.snomed.Concept/id attributeDescriptionId}
   :info.snomed.RefsetItem/attributeType        {:info.snomed.Concept/id attributeTypeId}})

(pco/defresolver refset-item-acceptability-concept
  [{:info.snomed.RefsetItem/keys [acceptabilityId]}]
  {::pco/output [{:info.snomed.RefsetItem/acceptability [:info.snomed.Concept/id]}]}
  {:info.snomed.RefsetItem/acceptability {:info.snomed.Concept/id acceptabilityId}})

(pco/defresolver refset-item-target-component
  "Resolve the target component."
  [{:info.snomed.RefsetItem/keys [targetComponentId]}]
  {::pco/output [{:info.snomed.RefsetItem/targetComponent [{:info.snomed.Concept/id [:info.snomed.Concept/id]}
                                                           {:info.snomed.Description/id [:info.snomed.Description/id]}
                                                           {:info.snomed.Relationship/id [:info.snomed.Relationship/id]}]}]}
  (case (snomed/identifier->type targetComponentId)         ;; we can easily derive the type of component from the identifier
    :info.snomed/Concept
    {:info.snomed.RefsetItem/targetComponent {:info.snomed.Concept/id targetComponentId}}
    :info.snomed/Description
    {:info.snomed.RefsetItem/targetComponent {:info.snomed.Description/id targetComponentId}}
    :info.snomed/Relationship
    {:info.snomed.RefsetItem/targetComponent {:info.snomed.Relationship/id targetComponentId}}))

(pco/defresolver refset-item-correlation-concept
  [{:info.snomed.RefsetItem/keys [correlationId]}]
  {::pco/output [{:info.snomed.RefsetItem/correlation [:info.snomed.Concept/id]}]}
  {:info.snomed.RefsetItem/correlation {:info.snomed.Concept/id correlationId}})

(pco/defresolver refset-item-map-category-concept
  [{:info.snomed.RefsetItem/keys [mapCategoryId]}]
  {::pco/output [{:info.snomed.RefsetItem/mapCategory [:info.snomed.Concept/id]}]}
  {:info.snomed.RefsetItem/mapCategory {:info.snomed.Concept/id mapCategoryId}})

(pco/defresolver refset-item-value-concept
  [{:info.snomed.RefsetItem/keys [valueId]}]
  {::pco/output [{:info.snomed.RefsetItem/value [:info.snomed.Concept/id]}]}
  {:info.snomed.RefsetItem/value {:info.snomed.Concept/id valueId}})

(pco/defresolver concept-historical-associations
  [{svc :com.eldrix/hermes} {:info.snomed.Concept/keys [id]}]
  {::pco/output [{:info.snomed.Concept/historicalAssociations
                  [{:info.snomed.Concept/id [:info.snomed.Concept/id]}]}]}
  {:info.snomed.Concept/historicalAssociations
   (reduce-kv (fn [m k v] (assoc m {:info.snomed.Concept/id k}
                                 (map #(hash-map :info.snomed.Concept/id (:targetComponentId %)) v)))
              {}
              (hermes/historical-associations svc id))})

(pco/defresolver concept-replaced-by
  "Returns the single concept that this concept has been replaced by."
  [{svc :com.eldrix/hermes} {:info.snomed.Concept/keys [id]}]
  {::pco/output [{:info.snomed.Concept/replacedBy [:info.snomed.Concept/id]}]}
  (let [replaced-by (->> (hermes/component-refset-items svc id snomed/ReplacedByReferenceSet)
                         (filter :active)
                         (sort-by :effectiveTime)
                         last)]
    {:info.snomed.Concept/replacedBy
     (when replaced-by {:info.snomed.Concept/id (:targetComponentId replaced-by)})}))

(pco/defresolver concept-moved-to-namespace
  "Returns the namespace to which this concept moved."
  [{svc :com.eldrix/hermes} {:info.snomed.Concept/keys [id]}]
  {::pco/output [{:info.snomed.Concept/movedToNamespace [:info.snomed.Concept/id]}]}
  (let [replacement (first (filter :active (hermes/component-refset-items svc id snomed/MovedToReferenceSet)))]
    {:info.snomed.Concept/movedToNamespace (when replacement {:info.snomed.Concept/id (:targetComponentId replacement)})}))

(pco/defresolver concept-same-as
  "Returns multiple concepts that this concept is now thought to the same as."
  [{svc :com.eldrix/hermes} {concept-id :info.snomed.Concept/id}]
  {::pco/input  [:info.snomed.Concept/id]
   ::pco/output [{:info.snomed.Concept/sameAs [:info.snomed.Concept/id]}]}
  {:info.snomed.Concept/sameAs
   (->> (hermes/component-refset-items svc concept-id snomed/SameAsReferenceSet)
        (filter :active)
        (map #(hash-map :info.snomed.Concept/id (:targetComponentId %)))
        seq)})

(pco/defresolver concept-possibly-equivalent
  "Returns multiple concepts to which this concept might be possibly equivalent."
  [{svc :com.eldrix/hermes} {:info.snomed.Concept/keys [id]}]
  {::pco/output [{:info.snomed.Concept/possiblyEquivalentTo [:info.snomed.Concept/id]}]}
  {:info.snomed.Concept/possiblyEquivalentTo
   (->> (hermes/component-refset-items svc id snomed/PossiblyEquivalentToReferenceSet)
        (filter :active)
        (map #(hash-map :info.snomed.Concept/id (:targetComponentId %)))
        seq)})

(pco/defresolver relationship-concepts
  [{:info.snomed.Relationship/keys [sourceId moduleId destinationId characteristicTypeId modifierId]}]
  {:info.snomed.Relationship/source             {:info.snomed.Concept/id sourceId}
   :info.snomed.Relationship/module             {:info.snomed.Concept/id moduleId}
   :info.snomed.Relationship/destination        {:info.snomed.Concept/id destinationId}
   :info.snomed.Relationship/characteristicType {:info.snomed.Concept/id characteristicTypeId}
   :info.snomed.Relationship/modifier           {:info.snomed.Concept/id modifierId}})

(pco/defresolver concept-relationships
  "Returns the concept's relationships. Accepts a parameter :type, specifying the
  type of relationship. If :type is omitted, all types of relationship will be
  returned."
  [{svc :com.eldrix/hermes :as env} {concept-id :info.snomed.Concept/id}]
  {::pco/output [:info.snomed.Concept/parentRelationshipIds
                 :info.snomed.Concept/directParentRelationshipIds]}
  (if-let [rel-type (:type (pco/params env))]
    {:info.snomed.Concept/parentRelationshipIds       (hermes/parent-relationships-expanded svc concept-id rel-type)
     :info.snomed.Concept/directParentRelationshipIds {rel-type (hermes/parent-relationships-of-type svc concept-id rel-type)}}
    {:info.snomed.Concept/parentRelationshipIds       (hermes/parent-relationships-expanded svc concept-id)
     :info.snomed.Concept/directParentRelationshipIds (hermes/parent-relationships svc concept-id)}))

(pco/defresolver readctv3-concept
  "Each Read CTV3 code has a direct one-to-one map to a SNOMED identifier."
  [{svc :com.eldrix/hermes} {:info.read/keys [ctv3]}]
  {::pco/output [:info.snomed.Concept/id]}
  {:info.snomed.Concept/id (first (hermes/member-field svc 900000000000497000 "mapTarget" ctv3))})

(pco/defresolver concept-readctv3
  "Each Read CTV3 code has a direct one-to-one map to a SNOMED identifier."
  [{svc :com.eldrix/hermes} {:info.snomed.Concept/keys [id]}]
  {::pco/output [:info.read/ctv3]}
  {:info.read/ctv3 (:mapTarget (first (hermes/component-refset-items svc id 900000000000497000)))})

(defn- normalise-result
  [{:keys [id conceptId term preferredTerm]}]
  {:info.snomed.Description/id               id
   :info.snomed.Concept/id                   conceptId
   :info.snomed.Description/term             term
   :info.snomed.Concept/preferredDescription {:info.snomed.Description/term preferredTerm}})

(s/fdef perform-search :args (s/cat :hermes/svc ::hermes/svc :params ::hermes/search-params))
(defn perform-search [svc params]
  (->> (hermes/search svc (select-keys params [:s :constraint :fuzzy :fallback-fuzzy :max-hits :remove-duplicates? :accept-language :language-refset-ids]))
       (mapv normalise-result)))

(pco/defresolver search-resolver
  [{svc :com.eldrix/hermes :as env} _]
  {::pco/output [{:info.snomed.Search/search
                  [:info.snomed.Description/id
                   :info.snomed.Concept/id
                   :info.snomed.Description/term
                   {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]}
  {:info.snomed.Search/search (perform-search svc (pco/params env))})

(pco/defmutation search
  "Performs a search. Parameters:
    |- :s                  : search string to use
    |- :constraint         : SNOMED ECL constraint to apply
    |- :fuzzy              : whether to perform fuzzy matching or not
    |- :fallback-fuzzy     : fuzzy matching to use if no results without fuzz
    |- :max-hits           : maximum hits (if omitted returns unlimited but
    |                        *unsorted* results).
    |- :remove-duplicates? : remove consecutive duplicates by concept id and
                             term."
  [{svc :com.eldrix/hermes} params]
  {::pco/op-name 'info.snomed.Search/search
   ::pco/params  [:s :constraint :max-hits]
   ::pco/output  [:info.snomed.Description/id
                  :info.snomed.Concept/id
                  :info.snomed.Description/term
                  {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
  (perform-search svc params))

(pco/defmutation expand
  "Expands an ECL expression, returning matching concepts with synonyms.
  May return multiple descriptions per concept. Best for analytics and research.
  Parameters:
   - :ecl              : SNOMED ECL expression to expand (required)
   - :include-historic?: include historical associations (default false)

  See also [[expand*]] for UI use cases where one term per concept is needed."
  [{svc :com.eldrix/hermes} {:keys [ecl include-historic?]}]
  {::pco/op-name 'info.snomed/expand
   ::pco/params  [:ecl :include-historic?]
   ::pco/output  [:info.snomed.Description/id
                  :info.snomed.Concept/id
                  :info.snomed.Description/term
                  {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
  (when-not (str/blank? ecl)
    (if include-historic?
      (mapv normalise-result (hermes/expand-ecl-historic svc ecl))
      (mapv normalise-result (hermes/expand-ecl svc ecl)))))

(pco/defmutation expand*
  "Expands an ECL expression, returning preferred descriptions only.
  Best for user interfaces and value sets.
  Parameters:
   - :ecl                 : SNOMED ECL expression to expand (required)
   - :accept-language     : BCP 47 language preference (e.g. 'en-GB')
   - :language-refset-ids : explicit language reference set IDs

  To return a single result per concept, use a single language reference set.
  See also [[expand]] for analytics use cases where synonyms are needed."
  [{svc :com.eldrix/hermes} {:keys [ecl accept-language language-refset-ids]}]
  {::pco/op-name 'info.snomed/expand*
   ::pco/params  [:ecl :accept-language :language-refset-ids]
   ::pco/output  [:info.snomed.Description/id
                  :info.snomed.Concept/id
                  :info.snomed.Description/term
                  {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
  (when-not (str/blank? ecl)
    (let [refset-ids (or (seq language-refset-ids)
                         (hermes/match-locale svc accept-language true))]
      (mapv normalise-result (hermes/expand-ecl* svc ecl refset-ids)))))

(pco/defresolver installed-refsets
  [{svc :com.eldrix/hermes} _]
  {::pco/output [{:info.snomed/installedReferenceSets [:info.snomed.Concept/id]}]}
  {:info.snomed/installedReferenceSets (mapv #(hash-map :info.snomed.Concept/id %) (hermes/installed-reference-sets svc))})

(def all-resolvers
  "SNOMED resolvers; each expects an environment that contains
  a key :com.eldrix/hermes representing a Hermes service."
  [concept-by-id
   description-by-id
   relationship-by-id
   refset-item-by-id
   component-by-id
   concept-defined?
   concept-primitive?
   concept-descriptions
   concept-synonyms
   concept-module
   concept-refset-ids
   concept-refset-items
   description-concepts
   refset-item-referenced-component
   refset-item-concepts
   refset-item-descriptor-concepts
   refset-item-acceptability-concept
   refset-item-target-component
   refset-item-correlation-concept
   refset-item-map-category-concept
   refset-item-value-concept
   concept-historical-associations
   concept-replaced-by
   concept-moved-to-namespace
   concept-same-as
   concept-possibly-equivalent
   relationship-concepts
   readctv3-concept
   concept-readctv3
   preferred-description
   fully-specified-name
   concept-relationships
   lowercase-term
   search-resolver
   search
   expand
   expand*
   installed-refsets])

(comment
  (def svc (hermes/open "/Users/mark/Dev/hermes/snomed.db"))
  svc
  (hermes/concept svc 24700007)
  (hermes/extended-concept svc 24700007)
  (hermes/search svc {:s          "polymyositis"
                      :fuzzy      2
                      :constraint "<404684003"
                      :max-hits   10})

  (map (partial record->map "info.snomed.Description") (hermes/descriptions svc 24700007))

  concept-by-id
  (concept-by-id {:com.eldrix/hermes svc} {:info.snomed.Concept/id 24700007})
  (concept-descriptions {:com.eldrix/hermes svc} {:info.snomed.Concept/id 24700007})
  (preferred-description {:com.eldrix/hermes svc} {:info.snomed.Concept/id 24700007})

  (concept-replaced-by {:com.eldrix/hermes svc} {:info.snomed.Concept/id 100005})

  (def registry (-> (pci/register all-resolvers)
                    (assoc :com.eldrix/hermes svc)))
  (require '[com.wsscode.pathom.viz.ws-connector.pathom3 :as p.connector])
  (p.connector/connect-env registry {:com.wsscode.pathom.viz.ws-connector.core/parser-id 'hermes})

  (sort (map #(vector (:id %) (:term %))
             (map #(hermes/preferred-synonym svc % "en-GB") (hermes/installed-reference-sets svc))))

  (first (hermes/component-refset-items svc 24700007 900000000000497000))
  (p.eql/process registry
                 {:info.snomed.Concept/id 80146002}
                 [:info.snomed.Concept/id
                  :info.snomed.Concept/active
                  '(:info.snomed.Concept/preferredDescription {:accept-language "en-GB"})
                  :info.snomed.Concept/refsetIds
                  {:info.snomed.Concept/descriptions
                   [:info.snomed.Description/active :info.snomed.Description/lowercaseTerm]}])

  (p.eql/process registry
                 {:info.snomed.Concept/id 24700007}
                 [:info.snomed.Concept/id
                  :info.snomed.Concept/refsetItems
                  :info.read/ctv3
                  '(:info.snomed.Concept/parentRelationshipIds {:type 116676008})
                  '(:info.snomed.Concept/preferredDescription {:accept-language "en-GB"})])

  (p.eql/process registry
                 {:info.read/ctv3 "F20.."}
                 [:info.snomed.Concept/id
                  {:info.snomed.Concept/preferredDescription [:info.snomed.Description/lowercaseTerm]}])

  (hermes/member-field svc 900000000000497000 "mapTarget" "F20..")

  (p.eql/process registry
                 [{'(info.snomed.Search/search
                     {:s          "mult scl"
                      :constraint "<404684003"
                      :max-hits   10})
                   [:info.snomed.Concept/id
                    :info.snomed.Description/id
                    :info.snomed.Description/term
                    {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}
                    :info.snomed.Concept/active]}])

  (p.eql/process registry
                 [{[:info.snomed.Concept/id 203004]
                   [:info.snomed.Concept/id
                    {:info.snomed.Concept/module [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
                    :info.snomed.Concept/active
                    {:info.snomed.Concept/preferredDescription
                     [:info.snomed.Description/term
                      :info.snomed.Concept/active]}
                    {:info.snomed.Concept/possiblyEquivalentTo
                     [:info.snomed.Concept/id
                      :info.snomed.Concept/active
                      {:info.snomed.Concept/preferredDescription
                       [:info.snomed.Description/term]}]}
                    {:info.snomed.Concept/replacedBy
                     [:info.snomed.Concept/id
                      :info.snomed.Concept/active
                      {:info.snomed.Concept/preferredDescription
                       [:info.snomed.Description/term]}]}
                    {:info.snomed.Concept/sameAs [:info.snomed.Concept/id
                                                  :info.snomed.Concept/active
                                                  {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]}]))


