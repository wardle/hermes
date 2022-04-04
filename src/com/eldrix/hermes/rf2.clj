(ns com.eldrix.hermes.rf2
  "Specifications for the RF2 SNOMED format.
  See https://confluence.ihtsdotools.org/display/DOCRELFMT"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
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

(def gen-unique-identifier
  (gen/fmap (fn [_] (swap! counter inc)) (gen/return nil)))

(defn- gen-identifier
  "A generator of identifiers of the specified type.
  Parameters:
  - t : one of :info.snomed/Concept :info.snomed.Description or
        :info.snomed/Relatioship."
  [t]
  (gen/fmap (fn [[id partition]]
              (Long/parseLong (verhoeff/append (str id partition))))
            (gen/tuple gen-unique-identifier (gen-partition t))))

(defn gen-concept-id []
  (gen-identifier :info.snomed/Concept))
(defn gen-description-id []
  (gen-identifier :info.snomed/Description))
(defn gen-relationship-id []
  (gen-identifier :info.snomed/Relationship))
(defn gen-effective-time []
  (gen/fmap (fn [days] (.minusDays (LocalDate/now) days))
            (s/gen (s/int-in 1 (* 365 10)))))

(s/def ::active
  (s/with-gen boolean? #(gen/frequency [[8 (gen/return true)] [2 (gen/return false)]])))

(s/def ::effectiveTime
  (s/with-gen #(instance? LocalDate %) gen-effective-time))

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

(def gen-concept
  "A generator of SNOMED RF2 Concept entities."
  (gen/fmap snomed/map->Concept (s/gen :info.snomed/Concept)))

(s/fdef gen-concept-from
  :args (s/cat :concept (s/keys :opt-un [:info.snomed.Concept/id
                                         :info.snomed.Concept/effectiveTime
                                         :info.snomed.Concept/active
                                         :info.snomed.Concept/moduleId
                                         :info.snomed.Concept/definitionStatusId])))
(defn gen-concept-from
  "A generator of Concept entities using the concept specified as basis."
  [concept]
  (gen/fmap #(merge % concept) gen-concept))

;;;;
;;;; RF2 description specification.
;;;;
(s/def :info.snomed.Description/id (s/with-gen (s/and pos-int? verhoeff/valid? #(= :info.snomed/Description (snomed/identifier->type %)))
                                               gen-description-id))
(s/def :info.snomed.Description/effectiveTime ::effectiveTime)
(s/def :info.snomed.Description/active ::active)
(s/def :info.snomed.Description/moduleId :info.snomed.Concept/id)
(s/def :info.snomed.Description/conceptId :info.snomed.Concept/id)
(s/def :info.snomed.Description/languageCode (set (Locale/getISOLanguages)))
(s/def :info.snomed.Description/typeId #{snomed/Synonym snomed/FullySpecifiedName snomed/Definition})
(s/def :info.snomed.Description/term (s/and string? #(> (.length ^String %) 0)))
(s/def :info.snomed.Description/caseSignificanceId #{snomed/EntireTermCaseSensitive snomed/EntireTermCaseInsensitive snomed/OnlyInitialCharacterCaseInsensitive})
(s/def :info.snomed/Description (s/keys :req-un [:info.snomed.Description/id :info.snomed.Description/effectiveTime :info.snomed.Description/active
                                                 :info.snomed.Description/moduleId :info.snomed.Description/conceptId
                                                 :info.snomed.Description/languageCode :info.snomed.Description/typeId
                                                 :info.snomed.Description/term :info.snomed.Description/caseSignificanceId]))
(def gen-description
  "A generator of SNOMED RF2 Description entities."
  (gen/fmap snomed/map->Description (s/gen :info.snomed/Description)))

(defn gen-description-from
  "Creates a generator for descriptions using the given description as basis."
  [description]
  (gen/fmap #(merge % description) gen-description))

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
(s/def :info.snomed.Relationship/relationshipGroup (s/with-gen nat-int?
                                                               #(s/gen (s/int-in 0 5))))
(s/def :info.snomed.Relationship/typeId :info.snomed.Concept/id)
(s/def :info.snomed.Relationship/characteristicTypeId :info.snomed.Concept/id)
(s/def :info.snomed.Relationship/modifierId (s/with-gen :info.snomed.Concept/id
                                                        #(gen/return 900000000000451002)))
(s/def :info.snomed/Relationship (s/keys :req-un [:info.snomed.Relationship/id :info.snomed.Relationship/effectiveTime
                                                  :info.snomed.Relationship/active :info.snomed.Relationship/moduleId
                                                  :info.snomed.Relationship/sourceId :info.snomed.Relationship/destinationId
                                                  :info.snomed.Relationship/relationshipGroup :info.snomed.Relationship/typeId
                                                  :info.snomed.Relationship/characteristicTypeId :info.snomed.Relationship/modifierId]))

(def gen-relationship
  "A generator of SNOMED relationship entities."
  (gen/fmap snomed/map->Relationship (s/gen :info.snomed/Relationship)))

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
            (gen/vector gen-relationship (count child-concepts))))

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
(s/def :info.snomed.RefsetItem/attributeOrder (s/with-gen int? #(gen/fmap int (s/gen (s/int-in 0 10)))))
(s/def :info.snomed.RefsetItem/mapTarget (s/and string? #(> (.length ^String %) 0)))
(s/def :info.snomed.RefsetItem/mapGroup (s/with-gen int? #(s/gen (s/int-in 0 2))))
(s/def :info.snomed.RefsetItem/mapPriority (s/with-gen int? #(s/gen (s/int-in 0 2))))
(s/def :info.snomed.RefsetItem/mapRule (s/and string? #(> (.length ^String %) 0)))
(s/def :info.snomed.RefsetItem/mapAdvice (s/and string? #(> (.length ^String %) 0)))
(s/def :info.snomed.RefsetItem/correlationId :info.snomed.Concept/id)
(s/def :info.snomed.RefsetItem/mapCategoryId :info.snomed.Concept/id)
(s/def :info.snomed.RefsetItem/valueId :info.snomed.Concept/id)
(s/def :info.snomed.RefsetItem/owlExpression string?)       ;;; TODO: could generate arbitrary OWL in future

(s/def :info.snomed/SimpleRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId]))

(def gen-simple-refset
  "A generator of SNOMED SimpleRefset entities."
  (gen/fmap snomed/map->SimpleRefsetItem (s/gen :info.snomed/SimpleRefset)))

(s/fdef gen-simple-refset-from
  :args (s/cat :rel (s/keys :opt-un [:info.snomed.RefsetItem/refsetId
                                     :info.snomed.RefsetItem/active
                                     :info.snomed.RefsetItem/referencedComponentId])))
(defn gen-simple-refset-from
  "Create a generator for the given refset, that uses the given relationship
  as its basis."
  [rel]
  (gen/fmap #(merge % rel) gen-simple-refset))


(s/def :info.snomed/AssociationRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/targetComponentId]))

(s/def :info.snomed/LanguageRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/acceptabilityId]))

(def gen-language-refset
  "A generator of SNOMED LanguageRefset entities."
  (gen/fmap snomed/map->LanguageRefsetItem (s/gen :info.snomed/LanguageRefset)))

(s/fdef gen-language-refset-from
  :args (s/cat :rel (s/keys :opt-un [:info.snomed.RefsetItem/refsetId
                                     :info.snomed.RefsetItem/active
                                     :info.snomed.RefsetItem/referencedComponentId])))
(defn gen-language-refset-from
  "Create a generator for the given refset, that uses the given relationship
  as its basis."
  [rel]
  (gen/fmap #(merge % rel) gen-language-refset))

(s/def :info.snomed/RefsetDescriptorRefsetItem
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/attributeDescriptionId
                   :info.snomed.RefsetItem/attributeTypeId
                   :info.snomed.RefsetItem/attributeOrder]))

(s/def :info.snomed/SimpleMapRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/mapTarget]))

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
                   :info.snomed.RefsetItem/correlationId]))

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
                   :info.snomed.RefsetItem/mapCategoryId]))

(s/def :info.snomed/AttributeValueRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/valueId]))

(s/def :info.snomed/OWLExpressionRefset
  (s/keys :req-un [:info.snomed.RefsetItem/id
                   :info.snomed.RefsetItem/effectiveTime
                   :info.snomed.RefsetItem/active
                   :info.snomed.RefsetItem/moduleId
                   :info.snomed.RefsetItem/refsetId
                   :info.snomed.RefsetItem/referencedComponentId
                   :info.snomed.RefsetItem/owlExpression]))