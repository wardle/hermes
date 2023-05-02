(ns ^:no-doc com.eldrix.hermes.impl.ser
  "Optimised hand-crafted serialization of SNOMED entities."
  (:require [com.eldrix.hermes.snomed :as snomed])
  (:import (com.eldrix.hermes.snomed Concept Description Relationship ConcreteValue
                                     SimpleRefsetItem SimpleMapRefsetItem
                                     RefsetDescriptorRefsetItem
                                     LanguageRefsetItem
                                     ComplexMapRefsetItem ExtendedMapRefsetItem
                                     AttributeValueRefsetItem OWLExpressionRefsetItem
                                     AssociationRefsetItem ModuleDependencyRefsetItem
                                     MRCMAttributeDomainRefsetItem MRCMAttributeRangeRefsetItem MRCMDomainRefsetItem MRCMModuleScopeRefsetItem)
           (java.time LocalDate)
           (io.netty.buffer ByteBuf ByteBufUtil)
           (java.util UUID)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(defn writeUTF [^ByteBuf b ^String s]
  (.writeShort b (ByteBufUtil/utf8Bytes s))
  (ByteBufUtil/writeUtf8 b s))

(defn readUTF [^ByteBuf b]
    (.readCharSequence b (.readShort b) StandardCharsets/UTF_8))

(defn write-concept [^ByteBuf out ^Concept o]
  (.writeLong out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-definitionStatusId o)))

(defn read-concept [^ByteBuf in]
  (let [id (.readLong in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        definition-status-id (.readLong in)]
    (snomed/->Concept id effectiveTime active moduleId definition-status-id)))

(defn write-description [^ByteBuf out ^Description o]
  (.writeLong out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-conceptId o))
  (writeUTF out (.-languageCode o))
  (.writeLong out (.-typeId o))
  (writeUTF out (.-term o))
  (.writeLong out (.-caseSignificanceId o)))

(defn read-description [^ByteBuf in]
  (let [id (.readLong in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        concept-id (.readLong in)
        language-code (readUTF in)
        type-id (.readLong in)
        term (readUTF in)
        case-significance-id (.readLong in)]
    (snomed/->Description
      id effectiveTime active moduleId concept-id language-code type-id term case-significance-id)))

(defn write-relationship [^ByteBuf out ^Relationship o]
  (.writeLong out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o)) (.writeLong out (.-sourceId o))
  (.writeLong out (.-destinationId o))
  (.writeLong out (.-relationshipGroup o))
  (.writeLong out (.-typeId o))
  (.writeLong out (.-characteristicTypeId o))
  (.writeLong out (.-modifierId o)))

(defn read-relationship [^ByteBuf in]
  (let [id (.readLong in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        source-id (.readLong in)
        destination-id (.readLong in)
        relationship-group (.readLong in)
        type-id (.readLong in)
        characteristic-type-id (.readLong in)
        modifier-id (.readLong in)]
    (snomed/->Relationship
      id effectiveTime active moduleId
      source-id destination-id relationship-group type-id characteristic-type-id modifier-id)))

(defn write-concrete-value [^ByteBuf out ^ConcreteValue o]
  (.writeLong out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o)) (.writeLong out (.-sourceId o))
  (writeUTF out (.-value o))
  (.writeLong out (.-relationshipGroup o))
  (.writeLong out (.-typeId o))
  (.writeLong out (.-characteristicTypeId o))
  (.writeLong out (.-modifierId o)))

(defn read-concrete-value [^ByteBuf in]
  (let [id (.readLong in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        source-id (.readLong in)
        value (readUTF in)
        relationship-group (.readLong in)
        type-id (.readLong in)
        characteristic-type-id (.readLong in)
        modifier-id (.readLong in)]
    (snomed/->ConcreteValue
      id effectiveTime active moduleId
      source-id value relationship-group type-id characteristic-type-id modifier-id)))

;;;;
;;;; Reference set items
;;;;

(defn write-uuid
  "Write a UUID as two long integers (16 bytes)."
  [^ByteBuf out ^UUID uuid]
  (.writeLong out (.getMostSignificantBits uuid))
  (.writeLong out (.getLeastSignificantBits uuid)))

(defn read-uuid
  "Read two long integers as a 128-bit UUID."
  ^UUID [^ByteBuf in]
  (UUID. (.readLong in) (.readLong in)))

(defn write-field-names
  "Write a sequence of field names."
  [^ByteBuf out field-names]
  (.writeInt out (count field-names))
  (doseq [field-name field-names]
    (writeUTF out field-name)))

(defn read-field-names
  "Read a sequence of field names."
  [^ByteBuf in]
  (let [n-fields (.readInt in)]                             ;; read in count of custom fields, may be zero
    (loop [n 0 result []]
      (if (= n n-fields)
        result
        (recur (inc n)
               (conj result (readUTF in)))))))

(defmulti write-field (fn [_ v] (class v)))
(defmethod write-field Long [^ByteBuf out v]
  (.writeByte out (int \i))
  (.writeLong out v))
(defmethod write-field String [^ByteBuf out v]
  (.writeByte out (int \s))
  (writeUTF out v))

(defn read-field [^ByteBuf in]
  (let [field-type (char (.readByte in))]
    (case field-type
      \i (.readLong in)
      \s (readUTF in)
      (throw (ex-info "unknown refset field type" {:got      field-type
                                                   :expected #{\i \s}})))))
(defn write-fields
  [^ByteBuf out fields]
  (.writeInt out (count fields))                            ;; write out a count of the custom fields, may be zero
  (doseq [field fields]
    (write-field out field)))

(defn read-fields
  [^ByteBuf in]
  (let [n-fields (.readInt in)]                             ;; read in count of custom fields, may be zero
    (loop [n 0 result []]
      (if (= n n-fields)
        result
        (recur (inc n)
               (conj result (read-field in)))))))


(defn write-simple-refset-item [^ByteBuf out ^SimpleRefsetItem o]
  (write-uuid out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (write-fields out (.-fields o)))

(defn read-simple-refset-item [^ByteBuf in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        fields (read-fields in)]
    (snomed/->SimpleRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId fields)))


(defn write-association-refset-item [^ByteBuf out ^AssociationRefsetItem o]
  (write-uuid out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (.writeLong out (.-targetComponentId o))
  (write-fields out (.-fields o)))

(defn read-association-refset-item [^ByteBuf in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        targetComponentId (.readLong in)
        fields (read-fields in)]
    (snomed/->AssociationRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId targetComponentId fields)))


(defn write-language-refset-item [^ByteBuf out ^LanguageRefsetItem o]
  (write-uuid out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (.writeLong out (.-acceptabilityId o))
  (write-fields out (.-fields o)))

(defn read-language-refset-item [^ByteBuf in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        acceptabilityId (.readLong in)
        fields (read-fields in)]
    (snomed/->LanguageRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId acceptabilityId fields)))


(defn write-simple-map-refset-item [^ByteBuf out ^SimpleMapRefsetItem o]
  (write-uuid out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (writeUTF out (.-mapTarget o))
  (write-fields out (.-fields o)))

(defn read-simple-map-refset-item [^ByteBuf in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        mapTarget (readUTF in)
        fields (read-fields in)]
    (snomed/->SimpleMapRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId mapTarget fields)))


(defn write-complex-map-refset-item [^ByteBuf out ^ComplexMapRefsetItem o]
  (write-uuid out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o)) (.writeLong out (.-mapGroup o))
  (.writeLong out (.-mapPriority o))
  (writeUTF out (.-mapRule o))
  (writeUTF out (.-mapAdvice o))
  (writeUTF out (.-mapTarget o))
  (.writeLong out (.-correlationId o))
  (write-fields out (.-fields o)))

(defn read-complex-map-refset-item [^ByteBuf in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        mapGroup (.readLong in)
        mapPriority (.readLong in)
        mapRule (readUTF in)
        mapAdvice (readUTF in)
        mapTarget (readUTF in)
        correlationId (.readLong in)
        fields (read-fields in)]
    (snomed/->ComplexMapRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId
      mapGroup mapPriority mapRule mapAdvice mapTarget correlationId fields)))


(defn write-extended-map-refset-item [^ByteBuf out ^ExtendedMapRefsetItem o]
  (write-uuid out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o)) (.writeLong out (.-mapGroup o))
  (.writeLong out (.-mapPriority o))
  (writeUTF out (.-mapRule o))
  (writeUTF out (.-mapAdvice o))
  (writeUTF out (.-mapTarget o))
  (.writeLong out (.-correlationId o))
  (.writeLong out (.-mapCategoryId o))
  (write-fields out (.-fields o)))

(defn read-extended-map-refset-item [^ByteBuf in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        mapGroup (.readLong in)
        mapPriority (.readLong in)
        mapRule (readUTF in)
        mapAdvice (readUTF in)
        mapTarget (readUTF in)
        correlationId (.readLong in)
        mapCategoryId (.readLong in)
        fields (read-fields in)]
    (snomed/->ExtendedMapRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId
      mapGroup mapPriority mapRule mapAdvice mapTarget correlationId mapCategoryId fields)))


(defn write-attribute-value-refset-item [^ByteBuf out ^AttributeValueRefsetItem o]
  (write-uuid out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (.writeLong out (.-valueId o))
  (write-fields out (.-fields o)))

(defn read-attribute-value-refset-item [^ByteBuf in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        valueId (.readLong in)
        fields (read-fields in)]
    (snomed/->AttributeValueRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId valueId fields)))


(defn write-owl-expression-refset-item [^ByteBuf out ^OWLExpressionRefsetItem o]
  (write-uuid out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (writeUTF out (.-owlExpression o))                       ;; TODO: add compression?
  (write-fields out (.-fields o)))
(defn read-owl-expression-refset-item [^ByteBuf in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        owlExpression (readUTF in)
        fields (read-fields in)]
    (snomed/->OWLExpressionRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId owlExpression fields)))


(defn write-refset-descriptor-refset-item [^ByteBuf out ^RefsetDescriptorRefsetItem o]
  (write-uuid out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (.writeLong out (.-attributeDescriptionId o))
  (.writeLong out (.-attributeTypeId o))
  (.writeInt out (.-attributeOrder o)))

(defn read-refset-descriptor-refset-item [^ByteBuf in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        attributeDescriptionId (.readLong in)
        attributeTypeId (.readLong in)
        attributeOrder (.readInt in)]
    (snomed/->RefsetDescriptorRefsetItem
      id effectiveTime active moduleId
      refsetId referencedComponentId attributeDescriptionId attributeTypeId attributeOrder)))

(defn write-module-dependency-refset-item [^ByteBuf out ^ModuleDependencyRefsetItem o]
  (write-uuid out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (.writeLong out (.toEpochDay ^LocalDate (.-sourceEffectiveTime o)))
  (.writeLong out (.toEpochDay ^LocalDate (.-targetEffectiveTime o)))
  (write-fields out (.-fields o)))

(defn read-module-dependency-refset-item [^ByteBuf in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        sourceEffectiveTime (LocalDate/ofEpochDay (.readLong in))
        targetEffectiveTime (LocalDate/ofEpochDay (.readLong in))
        fields (read-fields in)]
    (snomed/->ModuleDependencyRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId sourceEffectiveTime targetEffectiveTime fields)))

(defn write-mrcm-domain-refset-item [^ByteBuf out ^MRCMDomainRefsetItem o]
  (write-uuid out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (writeUTF out (.-domainConstraint o))
  (writeUTF out (.-parentDomain o))
  (writeUTF out (.-proximalPrimitiveConstraint o))
  (writeUTF out (.-proximalPrimitiveRefinement o))
  (writeUTF out (.-domainTemplateForPrecoordination o))
  (writeUTF out (.-domainTemplateForPostcoordination o))
  (writeUTF out (.-guideURL o)))

(defn read-mrcm-domain-refset-item [^ByteBuf in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        domainConstraint (readUTF in)
        parentDomain (readUTF in)
        proximalPrimitiveConstraint (readUTF in)
        proximalPrimitiveRefinement (readUTF in)
        domainTemplateForPrecoordination (readUTF in)
        domainTemplateForPostcoordination (readUTF in)
        guideURL (readUTF in)]
    (snomed/->MRCMDomainRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId
      domainConstraint parentDomain
      proximalPrimitiveConstraint proximalPrimitiveRefinement
      domainTemplateForPrecoordination domainTemplateForPostcoordination
      guideURL)))


(defn write-mrcm-attribute-domain-refset-item [^ByteBuf out ^MRCMAttributeDomainRefsetItem o]
  (write-uuid out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (.writeLong out (.-domainId o))
  (.writeBoolean out (.-grouped o))
  (writeUTF out (.-attributeCardinality o))
  (writeUTF out (.-attributeInGroupCardinality o))
  (.writeLong out (.-ruleStrengthId o))
  (.writeLong out (.-contentTypeId o)))

(defn read-mrcm-attribute-domain-refset-item [^ByteBuf in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        domainId (.readLong in)
        grouped (.readBoolean in)
        attributeCardinality (readUTF in)
        attributeInGroupCardinality (readUTF in)
        ruleStrengthId (.readLong in)
        contentTypeId (.readLong in)]
    (snomed/->MRCMAttributeDomainRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId
      domainId grouped attributeCardinality attributeInGroupCardinality
      ruleStrengthId contentTypeId)))



(defn write-mrcm-attribute-range-refset-item [^ByteBuf out ^MRCMAttributeRangeRefsetItem o]
  (write-uuid out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (writeUTF out (.-rangeConstraint o))
  (writeUTF out (.-attributeRule o))
  (.writeLong out (.-ruleStrengthId o))
  (.writeLong out (.-contentTypeId o)))

(defn read-mrcm-attribute-range-refset-item [^ByteBuf in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        rangeConstraint (readUTF in)
        attributeRule (readUTF in)
        ruleStrengthId (.readLong in)
        contentTypeId (.readLong in)]
    (snomed/->MRCMAttributeRangeRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId
      rangeConstraint attributeRule ruleStrengthId contentTypeId)))


(defn write-mrcm-module-scope-refset-item [^ByteBuf out ^MRCMModuleScopeRefsetItem o]
  (write-uuid out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (.writeLong out (.-mrcmRuleRefsetId o)))

(defn read-mrcm-module-scope-refset-item [^ByteBuf in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        mrcmRuleRefsetId (.readLong in)]
    (snomed/->MRCMModuleScopeRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId
      mrcmRuleRefsetId)))


;;
;;
;;
(defmulti write-refset-item
  "Serialize a refset item with a single byte header indicating subtype."
  (fn [^ByteBuf _out o] (class o)))

(defmethod write-refset-item :info.snomed/SimpleRefset [^ByteBuf out o]
  (.writeByte out 1)
  (write-simple-refset-item out o))
(defmethod write-refset-item :info.snomed/LanguageRefset [^ByteBuf out o]
  (.writeByte out 2)
  (write-language-refset-item out o))
(defmethod write-refset-item :info.snomed/SimpleMapRefset [^ByteBuf out o]
  (.writeByte out 3)
  (write-simple-map-refset-item out o))
(defmethod write-refset-item :info.snomed/ComplexMapRefset [^ByteBuf out o]
  (.writeByte out 4)
  (write-complex-map-refset-item out o))
(defmethod write-refset-item :info.snomed/ExtendedMapRefset [^ByteBuf out o]
  (.writeByte out 5)
  (write-extended-map-refset-item out o))
(defmethod write-refset-item :info.snomed/OWLExpressionRefset [^ByteBuf out o]
  (.writeByte out 6)
  (write-owl-expression-refset-item out o))
(defmethod write-refset-item :info.snomed/AttributeValueRefset [^ByteBuf out o]
  (.writeByte out 7)
  (write-attribute-value-refset-item out o))
(defmethod write-refset-item :info.snomed/RefsetDescriptorRefset [^ByteBuf out o]
  (.writeByte out 8)
  (write-refset-descriptor-refset-item out o))
(defmethod write-refset-item :info.snomed/AssociationRefset [^ByteBuf out o]
  (.writeByte out 9)
  (write-association-refset-item out o))
(defmethod write-refset-item :info.snomed/ModuleDependencyRefset [^ByteBuf out o]
  (.writeByte out 10)
  (write-module-dependency-refset-item out o))
(defmethod write-refset-item :info.snomed/MRCMDomainRefset [^ByteBuf out o]
  (.writeByte out 11)
  (write-mrcm-domain-refset-item out o))
(defmethod write-refset-item :info.snomed/MRCMAttributeDomainRefset [^ByteBuf out o]
  (.writeByte out 12)
  (write-mrcm-attribute-domain-refset-item out o))
(defmethod write-refset-item :info.snomed/MRCMAttributeRangeRefset [^ByteBuf out o]
  (.writeByte out 13)
  (write-mrcm-attribute-range-refset-item out o))
(defmethod write-refset-item :info.snomed/MRCMModuleScopeRefset [^ByteBuf out o]
  (.writeByte out 14)
  (write-mrcm-module-scope-refset-item out o))


(defn read-refset-item [^ByteBuf in]
  (case (.readByte in)
    1 (read-simple-refset-item in)
    2 (read-language-refset-item in)
    3 (read-simple-map-refset-item in)
    4 (read-complex-map-refset-item in)
    5 (read-extended-map-refset-item in)
    6 (read-owl-expression-refset-item in)
    7 (read-attribute-value-refset-item in)
    8 (read-refset-descriptor-refset-item in)
    9 (read-association-refset-item in)
    10 (read-module-dependency-refset-item in)
    11 (read-mrcm-domain-refset-item in)
    12 (read-mrcm-attribute-domain-refset-item in)
    13 (read-mrcm-attribute-range-refset-item in)
    14 (read-mrcm-module-scope-refset-item in)))

(defn read-effective-time
  "Optimised fetch of only the effectiveTime of a SNOMED component.
  Core SNOMED concepts have an identifier of 8 bytes (64-bit unsigned long).
  Refset items have an identifier of 16 bytes (128-bit UUID)."
  [^ByteBuf in read-offset]
  (LocalDate/ofEpochDay (.getLong in read-offset)))

(comment)


