; Copyright (c) 2020-2023 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns com.eldrix.hermes.rf2
  "Specifications for the RF2 SNOMED format.
  See https://confluence.ihtsdotools.org/display/DOCRELFMT"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.verhoeff :as verhoeff])
  (:import [java.time LocalDate]
           [java.util Locale]))

(s/def ::component-types #{:info.snomed/Concept :info.snomed/Description :info.snomed/Relationship})

(s/fdef partitions-for-type
  :args (s/cat :type ::component-types))
(defn partitions-for-type [t]
  (reduce-kv (fn [acc k v] (if (= t v) (conj acc k) acc)) #{} snomed/partitions))

(defn- gen-partition [t]
  (gen/elements (seq (partitions-for-type t))))

(def counter (atom 0))

(defn reset-identifier-counter []
  (reset! counter 0))

(defn gen-unique-identifier []
  (gen/fmap (fn [_] (swap! counter inc)) (gen/return nil)))

(defn- gen-identifier
  "A generator of identifiers of the specified type.
  Parameters:
  - t : one of :info.snomed/Concept :info.snomed.Description or
        :info.snomed/Relationship."
  [t]
  (gen/fmap (fn [[id partition-id]]
              (Long/parseLong (verhoeff/append (str id partition-id))))
            (gen/tuple (gen-unique-identifier) (gen-partition t))))

(defn gen-concept-id []
  (gen-identifier :info.snomed/Concept))
(defn gen-description-id []
  (gen-identifier :info.snomed/Description))
(defn gen-relationship-id []
  (gen-identifier :info.snomed/Relationship))
(defn gen-effective-time []
  (gen/fmap (fn [days] (.minusDays (LocalDate/now) days))
            (gen/choose 1 (* 365 10))))

(s/def ::active
  (s/with-gen boolean? #(gen/frequency [[8 (gen/return true)] [2 (gen/return false)]])))

(s/def ::effectiveTime
  (s/with-gen #(instance? LocalDate %) #(gen-effective-time)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; RF2 concept specification
;;;;
(s/def :info.snomed.Concept/id (s/with-gen (s/and pos-int? verhoeff/valid? #(= :info.snomed/Concept (snomed/identifier->type %)))
                                 gen-concept-id))
(s/def :info.snomed.Concept/effectiveTime ::effectiveTime)
(s/def :info.snomed.Concept/active ::active)
(s/def :info.snomed.Concept/moduleId :info.snomed.Concept/id)
(s/def :info.snomed.Concept/definitionStatusId #{snomed/Primitive snomed/Defined})
(s/def :info.snomed/Concept (s/keys :req-un [:info.snomed.Concept/id :info.snomed.Concept/effectiveTime
                                             :info.snomed.Concept/active :info.snomed.Concept/moduleId :info.snomed.Concept/definitionStatusId]))

(s/fdef gen-concept
  :args (s/cat :concept (s/? (s/keys :opt-un [:info.snomed.Concept/id
                                              :info.snomed.Concept/effectiveTime
                                              :info.snomed.Concept/active
                                              :info.snomed.Concept/moduleId
                                              :info.snomed.Concept/definitionStatusId]))))
(defn gen-concept
  "A generator of SNOMED RF2 Concept entities."
  ([] (gen/fmap snomed/map->Concept (s/gen :info.snomed/Concept)))
  ([concept] (gen/fmap #(merge % concept) (gen-concept))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; RF2 description specification.
;;;;

(defn ^:private gen-non-blank-string
  "Create a generator for non blank strings."
  []
  (s/gen (s/and string? (complement str/blank?))))

(defn ^:private gen-string-of-length
  "Create a generator for strings between `min-len` and `max-len` length."
  [min-len max-len]
  (gen/fmap str/join (gen/vector (gen/char-alphanumeric) min-len max-len)))

(defn ^:private gen-description-term
  []
  (gen/frequency [[18 (gen-non-blank-string)]
                  [1 (gen-string-of-length 0 512)]
                  [1 (gen-string-of-length 512 4096)]]))

(s/def :info.snomed.Description/id (s/with-gen (s/and pos-int? verhoeff/valid? #(= :info.snomed/Description (snomed/identifier->type %)))
                                     gen-description-id))
(s/def :info.snomed.Description/effectiveTime ::effectiveTime)
(s/def :info.snomed.Description/active ::active)
(s/def :info.snomed.Description/moduleId :info.snomed.Concept/id)
(s/def :info.snomed.Description/conceptId :info.snomed.Concept/id)
(s/def :info.snomed.Description/languageCode (set (Locale/getISOLanguages)))
(s/def :info.snomed.Description/typeId #{snomed/Synonym snomed/FullySpecifiedName snomed/Definition})
(s/def :info.snomed.Description/term (s/with-gen (s/and string? #(pos-int? (count %))) gen-description-term))
(s/def :info.snomed.Description/caseSignificanceId #{snomed/EntireTermCaseSensitive snomed/EntireTermCaseInsensitive snomed/OnlyInitialCharacterCaseInsensitive})
(s/def :info.snomed/Description (s/keys :req-un [:info.snomed.Description/id :info.snomed.Description/effectiveTime :info.snomed.Description/active
                                                 :info.snomed.Description/moduleId :info.snomed.Description/conceptId
                                                 :info.snomed.Description/languageCode :info.snomed.Description/typeId
                                                 :info.snomed.Description/term :info.snomed.Description/caseSignificanceId]))
(s/fdef gen-description
  :args (s/cat :description (s/? (s/keys :opt-un [:info.snomed.Description/id :info.snomed.Description/effectiveTime :info.snomed.Description/active
                                                  :info.snomed.Description/moduleId :info.snomed.Description/conceptId
                                                  :info.snomed.Description/languageCode :info.snomed.Description/typeId
                                                  :info.snomed.Description/term :info.snomed.Description/caseSignificanceId]))))
(defn gen-description
  "A generator of SNOMED RF2 Description entities."
  ([] (gen/fmap snomed/map->Description (s/gen :info.snomed/Description)))
  ([description] (gen/fmap #(merge % description) (gen-description))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; RF2 relationship specification
;;;;

(s/def :info.snomed.Relationship/id (s/with-gen (s/and pos-int? verhoeff/valid? #(= :info.snomed/Relationship (snomed/identifier->type %)))
                                      gen-relationship-id))
(s/def :info.snomed.Relationship/effectiveTime ::effectiveTime)
(s/def :info.snomed.Relationship/active ::active)
(s/def :info.snomed.Relationship/moduleId :info.snomed.Concept/id)
(s/def :info.snomed.Relationship/sourceId :info.snomed.Concept/id)
(s/def :info.snomed.Relationship/destinationId :info.snomed.Concept/id)
(s/def :info.snomed.Relationship/relationshipGroup (s/with-gen nat-int? #(gen/choose 0 5)))
(s/def :info.snomed.Relationship/typeId :info.snomed.Concept/id)
(s/def :info.snomed.Relationship/characteristicTypeId :info.snomed.Concept/id)
(s/def :info.snomed.Relationship/modifierId (s/with-gen :info.snomed.Concept/id
                                              #(gen/return 900000000000451002)))
(s/def :info.snomed/Relationship (s/keys :req-un [:info.snomed.Relationship/id :info.snomed.Relationship/effectiveTime
                                                  :info.snomed.Relationship/active :info.snomed.Relationship/moduleId
                                                  :info.snomed.Relationship/sourceId :info.snomed.Relationship/destinationId
                                                  :info.snomed.Relationship/relationshipGroup :info.snomed.Relationship/typeId
                                                  :info.snomed.Relationship/characteristicTypeId :info.snomed.Relationship/modifierId]))

(defn gen-relationship
  "A generator of SNOMED relationship entities."
  ([] (gen/fmap snomed/map->Relationship (s/gen :info.snomed/Relationship)))
  ([rel] (gen/fmap #(merge % rel) (gen-relationship))))

(s/fdef gen-relationships-for-parent
  :args (s/cat :parent-concept-id :info.snomed.Concept/id
               :child-concepts (s/coll-of :info.snomed/Concept)
               :type-id :info.snomed.Concept/id))
(defn gen-relationships-for-parent
  "A custom generator to build relationships between the parent concept
  and the collection of child concepts."
  [parent-concept-id child-concepts type-id]
  (gen/fmap (fn [rels]
              (map (fn [rel child]
                     (assoc rel :sourceId (:id child)
                            :active true
                            :destinationId parent-concept-id
                            :typeId type-id)) rels child-concepts))
            (gen/vector (gen-relationship) (count child-concepts))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; RF2 concrete values specification
;;;;

(s/def :info.snomed.ConcreteValue/id (s/with-gen (s/and pos-int? verhoeff/valid? #(= :info.snomed/Relationship (snomed/identifier->type %)))
                                       gen-relationship-id))
(s/def :info.snomed.ConcreteValue/effectiveTime ::effectiveTime)
(s/def :info.snomed.ConcreteValue/active ::active)
(s/def :info.snomed.ConcreteValue/moduleId :info.snomed.Concept/id)
(s/def :info.snomed.ConcreteValue/sourceId :info.snomed.Concept/id)
(s/def :info.snomed.ConcreteValue/value string?)
(s/def :info.snomed.ConcreteValue/relationshipGroup (s/with-gen nat-int? #(gen/choose 0 5)))
(s/def :info.snomed.ConcreteValue/typeId :info.snomed.Concept/id)
(s/def :info.snomed.ConcreteValue/characteristicTypeId :info.snomed.Concept/id)
(s/def :info.snomed.ConcreteValue/modifierId (s/with-gen :info.snomed.Concept/id
                                               #(gen/return 900000000000451002)))
(s/def :info.snomed/ConcreteValue (s/keys :req-un [:info.snomed.ConcreteValue/id :info.snomed.ConcreteValue/effectiveTime
                                                   :info.snomed.ConcreteValue/active :info.snomed.ConcreteValue/moduleId
                                                   :info.snomed.ConcreteValue/sourceId :info.snomed.ConcreteValue/value
                                                   :info.snomed.ConcreteValue/relationshipGroup :info.snomed.ConcreteValue/typeId
                                                   :info.snomed.ConcreteValue/characteristicTypeId :info.snomed.ConcreteValue/modifierId]))

(defn gen-concrete-value
  "A generator of SNOMED concrete value entities."
  ([] (gen/fmap snomed/map->ConcreteValue (s/gen :info.snomed/ConcreteValue)))
  ([rel] (gen/fmap #(merge % rel) (gen-concrete-value))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Extended concept specification
;;;;
(s/def :info.snomed/ExtendedConcept (s/keys :req-un [::concept
                                                     ::descriptions
                                                     ::parentRelationships
                                                     ::directParentRelationships
                                                     ::refsets]))
(s/def ::concept :info.snomed/Concept)
(s/def ::descriptions (s/coll-of :info.snomed/Description))
(s/def ::parentRelationships (s/nilable (s/map-of :info.snomed.Concept/id (s/coll-of :info.snomed.Concept/id :kind set?))))
(s/def ::directParentRelationships (s/nilable (s/map-of :info.snomed.Concept/id (s/coll-of :info.snomed.Concept/id :kind set?))))
(s/def ::refsets (s/nilable (s/coll-of :info.snomed.Concept/id :kind set?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; RF2 refset specifications
;;;;

(s/def :info.snomed.RefsetItem/id uuid?)
(s/def :info.snomed.RefsetItem/effectiveTime ::effectiveTime)
(s/def :info.snomed.RefsetItem/active boolean?)
(s/def :info.snomed.RefsetItem/moduleId :info.snomed.Concept/id)
(s/def :info.snomed.RefsetItem/refsetId :info.snomed.Concept/id)
(s/def :info.snomed.RefsetItem/referencedComponentId :info.snomed.Concept/id)
(s/def :info.snomed.RefsetItem/targetComponentId :info.snomed.Concept/id)
(s/def :info.snomed.RefsetItem/acceptabilityId :info.snomed.Concept/id)
(s/def :info.snomed.RefsetItem/attributeDescriptionId :info.snomed.Concept/id)
(s/def :info.snomed.RefsetItem/attributeTypeId :info.snomed.Concept/id)
(s/def :info.snomed.RefsetItem/attributeOrder (s/with-gen int? #(gen/fmap int (gen/choose 0 10))))
(s/def :info.snomed.RefsetItem/mapTarget (s/and string? #(pos? (count %))))
(s/def :info.snomed.RefsetItem/mapGroup (s/with-gen int? #(gen/choose 0 2)))
(s/def :info.snomed.RefsetItem/mapPriority (s/with-gen int? #(gen/choose 0 2)))
(s/def :info.snomed.RefsetItem/mapRule (s/and string? #(pos? (count %))))
(s/def :info.snomed.RefsetItem/mapAdvice (s/and string? #(pos? (count %))))
(s/def :info.snomed.RefsetItem/correlationId :info.snomed.Concept/id)
(s/def :info.snomed.RefsetItem/mapCategoryId :info.snomed.Concept/id)
(s/def :info.snomed.RefsetItem/valueId :info.snomed.Concept/id)
(s/def :info.snomed.RefsetItem/owlExpression string?)       ;;; TODO: could generate arbitrary OWL in future
(s/def :info.snomed.RefsetItem/sourceEffectiveTime ::effectiveTime)
(s/def :info.snomed.RefsetItem/targetEffectiveTime ::effectiveTime)
(s/def :info.snomed.RefsetItem/domainConstraint (s/and string? #(pos? (count %)))) ;; TODO: should generate ECL
(s/def :info.snomed.RefsetItem/parentDomain (s/and string? #(pos? (count %)))) ;; TODO: should generate ECL
(s/def :info.snomed.RefsetItem/proximalPrimitiveConstraint (s/and string? #(pos? (count %)))) ;; TODO: should generate ECL
(s/def :info.snomed.RefsetItem/proximalPrimitiveRefinement (s/and string? #(pos? (count %)))) ;; TODO: should generate ECL
(s/def :info.snomed.RefsetItem/domainTemplateForPrecoordination (s/and string? #(pos? (count %))))
(s/def :info.snomed.RefsetItem/domainTemplateForPostcoordination (s/and string? #(pos? (count %))))
(s/def :info.snomed.RefsetItem/guideURL (s/and string? #(pos? (count %))))
(s/def :info.snomed.RefsetItem/domainId :info.snomed.Concept/id)
(s/def :info.snomed.RefsetItem/grouped boolean?)
(s/def :info.snomed.RefsetItem/attributeCardinality (s/and string? #(pos? (count %)))) ;; TODO: should be 'minimum' to 'maximum' as per ECL grammar
(s/def :info.snomed.RefsetItem/attributeInGroupCardinality (s/and string? #(pos? (count %)))) ;; TODO: should be 'minimum' to 'maximum' as per ECL grammar
(s/def :info.snomed.RefsetItem/ruleStrengthId :info.snomed.Concept/id)  ;; always subtype of 723573005 | Concept model rule strength|
(s/def :info.snomed.RefsetItem/contentTypeId :info.snomed.Concept/id)   ;; always subtype of 723574004 | Content type|
(s/def :info.snomed.RefsetItem/rangeConstraint (s/and string? #(pos? (count %))))  ;; TODO: a complex parseable string of varying formats! See https://confluence.ihtsdotools.org/display/DOCMRCM/5.3+MRCM+Attribute+Range+Reference+Set
(s/def :info.snomed.RefsetItem/attributeRule (s/and string? #(pos? (count %))))
(s/def :info.snomed.RefsetItem/mrcmRuleRefsetId :info.snomed.Concept/id) ;; always a subtype of 723564002 | MRCM reference set|

(s/def :info.snomed.RefsetItem/field (s/or :concept :info.snomed.Concept/id
                                           :description :info.snomed.Description/id
                                           :relationship :info.snomed.Relationship/id
                                           :integer integer?
                                           :string string?))
(s/def :info.snomed.RefsetItem/fields (s/with-gen (s/coll-of :info.snomed.RefsetItem/field)
                                        #(gen/frequency [[6 (gen/return [])]
                                                         [4 (gen/vector (s/gen :info.snomed.RefsetItem/field) 1 4)]])))

(def ^:private field->pattern
  {:concept      \c
   :description  \c
   :relationship \c
   :string       \s
   :integer      \i})

(s/fdef pattern-for-fields
  :args (s/cat :fields (s/nilable :info.snomed.RefsetItem/fields)))
(defn pattern-for-fields
  "Returns a pattern for the fields specified.
  For example:
  ```
  (pattern-for-fields (:fields (gen/generate (gen-extended-map-refset))))
  => \"ccsccs\"
  ```"
  [fields]
  (when fields
    (->> (s/conform :info.snomed.RefsetItem/fields fields)
         (map (fn [[k _v]] (field->pattern k)))
         (apply str))))

(s/fdef pattern-for-refset-item
  :args (s/cat :spec (s/and qualified-keyword? #(isa? % :info.snomed/Refset))
               :v (s/keys :opt-un [:info.snomed.RefsetItem/fields])))
(defn pattern-for-refset-item
  "Generates a pattern for the given reference set item by concatenating the
  standard pattern for that type with the contents of any extended properties
  stored as 'fields'."
  [spec v]
  (str (snomed/refset-standard-patterns spec) (pattern-for-fields (:fields v))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :info.snomed/SimpleRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/fields]))

(defn gen-simple-refset
  "A generator of SNOMED SimpleRefset entities."
  ([] (gen/fmap snomed/map->SimpleRefsetItem (s/gen :info.snomed/SimpleRefset)))
  ([refset] (gen/fmap #(merge % refset) (gen-simple-refset))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :info.snomed/AssociationRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/targetComponentId
                   :info.snomed.RefsetItem/fields]))

(defn gen-association-refset
  "A generator of SNOMED AssociationRefset entities."
  ([] (gen/fmap snomed/map->AssociationRefsetItem (s/gen :info.snomed/AssociationRefset)))
  ([refset] (gen/fmap #(merge % refset) (gen-association-refset))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :info.snomed/LanguageRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/acceptabilityId
                   :info.snomed.RefsetItem/fields]))

(s/fdef gen-language-refset
  :args (s/cat :item (s/? (s/keys :opt-un [:info.snomed.RefsetItem/refsetId
                                           :info.snomed.RefsetItem/active
                                           :info.snomed.RefsetItem/referencedComponentId]))))
(defn gen-language-refset
  "A generator of SNOMED LanguageRefset entities."
  ([] (gen/fmap snomed/map->LanguageRefsetItem (s/gen :info.snomed/LanguageRefset)))
  ([item] (gen/fmap #(merge % item) (gen-language-refset))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :info.snomed/RefsetDescriptorRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/attributeDescriptionId
                   :info.snomed.RefsetItem/attributeTypeId
                   :info.snomed.RefsetItem/attributeOrder]))

(defn gen-refset-descriptor-refset
  "A generator of SNOMED RefsetDescriptorRefsetItem entities."
  ([] (gen/fmap snomed/map->RefsetDescriptorRefsetItem (s/gen :info.snomed/RefsetDescriptorRefset)))
  ([item] (gen/fmap #(merge % item) (gen-refset-descriptor-refset))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :info.snomed/SimpleMapRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/mapTarget
                   :info.snomed.RefsetItem/fields]))

(defn gen-simple-map-refset
  "A generator of SNOMED SimpleMapRefset entities."
  ([] (gen/fmap snomed/map->SimpleMapRefsetItem (s/gen :info.snomed/SimpleMapRefset)))
  ([item] (gen/fmap #(merge % item) (gen-simple-map-refset))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :info.snomed/ComplexMapRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/mapGroup
                   :info.snomed.RefsetItem/mapPriority
                   :info.snomed.RefsetItem/mapRule
                   :info.snomed.RefsetItem/mapAdvice
                   :info.snomed.RefsetItem/mapTarget
                   :info.snomed.RefsetItem/correlationId
                   :info.snomed.RefsetItem/fields]))

(defn gen-complex-map-refset
  "A generator of SNOMED ComplexMapRefset entities."
  ([] (gen/fmap snomed/map->ComplexMapRefsetItem (s/gen :info.snomed/ComplexMapRefset)))
  ([item] (gen/fmap #(merge % item) (gen-complex-map-refset))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :info.snomed/ExtendedMapRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/mapGroup
                   :info.snomed.RefsetItem/mapPriority
                   :info.snomed.RefsetItem/mapRule
                   :info.snomed.RefsetItem/mapAdvice
                   :info.snomed.RefsetItem/mapTarget
                   :info.snomed.RefsetItem/correlationId
                   :info.snomed.RefsetItem/mapCategoryId
                   :info.snomed.RefsetItem/fields]))

(defn gen-extended-map-refset
  "A generator of SNOMED ExtendedMapRefset entities."
  ([] (gen/fmap snomed/map->ExtendedMapRefsetItem (s/gen :info.snomed/ExtendedMapRefset)))
  ([item] (gen/fmap #(merge % item) (gen-extended-map-refset))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :info.snomed/AttributeValueRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/valueId
                   :info.snomed.RefsetItem/fields]))

(defn gen-attribute-value-refset
  "A generator of SNOMED AttributeValueRefset entities."
  ([] (gen/fmap snomed/map->AttributeValueRefsetItem (s/gen :info.snomed/AttributeValueRefset)))
  ([item] (gen/fmap #(merge % item) (gen-attribute-value-refset))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :info.snomed/OWLExpressionRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/owlExpression
                   :info.snomed.RefsetItem/fields]))

(defn gen-owl-expression-refset
  "A generator of SNOMED OWLExpressionRefset entities."
  ([] (gen/fmap snomed/map->OWLExpressionRefsetItem (s/gen :info.snomed/OWLExpressionRefset)))
  ([item] (gen/fmap #(merge % item) (gen-owl-expression-refset))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :info.snomed/ModuleDependencyRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/sourceEffectiveTime
                   :info.snomed.RefsetItem/targetEffectiveTime
                   :info.snomed.RefsetItem/fields]))

(defn gen-module-dependency-refset
  ([] (->> (s/gen :info.snomed/ModuleDependencyRefset)
           (gen/fmap snomed/map->ModuleDependencyRefsetItem)
           (gen/fmap #(merge % {:refsetId 900000000000534007}))))
  ([item] (->> (gen-module-dependency-refset)
               (gen/fmap #(merge % item)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :info.snomed/MRCMDomainRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/domainConstraint
                   :info.snomed.RefsetItem/parentDomain
                   :info.snomed.RefsetItem/proximalPrimitiveConstraint
                   :info.snomed.RefsetItem/proximalPrimitiveRefinement
                   :info.snomed.RefsetItem/domainTemplateForPrecoordination
                   :info.snomed.RefsetItem/domainTemplateForPostcoordination
                   :info.snomed.RefsetItem/guideURL]))

(defn gen-mrcm-domain-refset
  "A generator of SNOMED MRCM domain reference set entities."
  ([] (gen/fmap snomed/map->MRCMDomainRefsetItem (s/gen :info.snomed/MRCMDomainRefset)))
  ([refset] (gen/fmap #(merge % refset) (gen-mrcm-domain-refset))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :info.snomed/MRCMAttributeDomainRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/domainId
                   :info.snomed.RefsetItem/grouped
                   :info.snomed.RefsetItem/attributeCardinality
                   :info.snomed.RefsetItem/attributeInGroupCardinality
                   :info.snomed.RefsetItem/ruleStrengthId
                   :info.snomed.RefsetItem/contentTypeId]))

(defn gen-mrcm-attribute-domain-refset
  "A generator of SNOMED MRCM Attribute Domain reference set entities."
  ([] (gen/fmap snomed/map->MRCMAttributeDomainRefsetItem (s/gen :info.snomed/MRCMAttributeDomainRefset)))
  ([refset] (gen/fmap #(merge % refset) (gen-mrcm-attribute-domain-refset))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :info.snomed/MRCMAttributeRangeRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/rangeConstraint
                   :info.snomed.RefsetItem/attributeRule
                   :info.snomed.RefsetItem/ruleStrengthId
                   :info.snomed.RefsetItem/contentTypeId]))

(defn gen-mrcm-attribute-range-refset
  "A generator of SNOMED MRCM attribute range reference set entities."
  ([] (gen/fmap snomed/map->MRCMAttributeRangeRefsetItem (s/gen :info.snomed/MRCMAttributeRangeRefset)))
  ([refset] (gen/fmap #(merge % refset) (gen-simple-refset))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :info.snomed/MRCMModuleScopeRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/mrcmRuleRefsetId]))

(defn gen-mrcm-module-scope-refset
  "A generator of SNOMED MRCM module scope reference set entities."
  ([] (gen/fmap snomed/map->MRCMModuleScopeRefsetItem (s/gen :info.snomed/MRCMModuleScopeRefset)))
  ([refset] (gen/fmap #(merge % refset) (gen-mrcm-module-scope-refset))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn gen-refset
  "Generate a random reference set item, in frequencies approximately equal to
  the UK distribution.
  See [[com.eldrix.hermes.impl.store/refset-counts]] for the code to calculate
  frequencies from installed distributions."
  []
  (gen/frequency
   [[4 (gen-simple-map-refset)]
    [45 (gen-language-refset)]
    [14 (gen-extended-map-refset)]
    [15 (gen-simple-refset)]
    [10 (gen-attribute-value-refset)]
    [10 (gen-association-refset)]
    [1 (gen-refset-descriptor-refset)]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; RF2 release file name specification
;;;;
(s/def :info.snomed.ReleaseFile/path string?)
(s/def :info.snomed.ReleaseFile/format string?)
(s/def :info.snomed.ReleaseFile/filename string?)
(s/def :info.snomed.ReleaseFile/component string?)
(s/def :info.snomed.ReleaseFile/version-date ::effectiveTime)
(s/def :info.snomed.ReleaseFile/identifier keyword?)
(s/def :info.snomed.ReleaseFile/parser (s/nilable ifn?))
(s/def :info.snomed.ReleaseFile/language-code (s/nilable string?))
(s/def :info.snomed.ReleaseFile/doc-status (s/nilable string?))
(s/def :info.snomed.ReleaseFile/refset-type (s/nilable string?))
(s/def :info.snomed/ReleaseFile
  (s/keys :req-un [:info.snomed.ReleaseFile/path
                   :info.snomed.ReleaseFile/filename
                   :info.snomed.ReleaseFile/component
                   :info.snomed.ReleaseFile/identifier
                   :info.snomed.ReleaseFile/parser
                   :info.snomed.ReleaseFile/file-type
                   :info.snomed.ReleaseFile/status
                   :info.snomed.ReleaseFile/type
                   :info.snomed.ReleaseFile/format
                   :info.snomed.ReleaseFile/content-type
                   :info.snomed.ReleaseFile/pattern
                   :info.snomed.ReleaseFile/entity
                   :info.snomed.ReleaseFile/content-subtype
                   :info.snomed.ReleaseFile/summary
                   :info.snomed.ReleaseFile/refset-type
                   :info.snomed.ReleaseFile/summary-extra
                   :info.snomed.ReleaseFile/release-type
                   :info.snomed.ReleaseFile/doc-status
                   :info.snomed.ReleaseFile/language-code
                   :info.snomed.ReleaseFile/country-namespace
                   :info.snomed.ReleaseFile/country-code
                   :info.snomed.ReleaseFile/namespace-id
                   :info.snomed.ReleaseFile/version-date
                   :info.snomed.ReleaseFile/file-extension]
          :opt-un [:info.snomed.ReleaseFile/parser]))
