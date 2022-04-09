(ns com.eldrix.hermes.impl.ser
  "Optimised hand-crafted serialization of SNOMED entities."
  (:require [com.eldrix.hermes.snomed :as snomed])
  (:import (com.eldrix.hermes.snomed Concept Description Relationship
                                     SimpleRefsetItem SimpleMapRefsetItem
                                     RefsetDescriptorRefsetItem
                                     LanguageRefsetItem ComplexMapRefsetItem
                                     ExtendedMapRefsetItem
                                     AttributeValueRefsetItem
                                     OWLExpressionRefsetItem
                                     AssociationRefsetItem)
           (java.time LocalDate)
           (java.io DataInput DataOutput)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn write-concept [^DataOutput out ^Concept o]
  (.writeLong out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o)) (.writeLong out (.-definitionStatusId o)))

(defn read-concept [^DataInput in]
  (let [id (.readLong in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        definition-status-id (.readLong in)]
    (snomed/->Concept id effectiveTime active moduleId definition-status-id)))

(defn write-description [^DataOutput out ^Description o]
  (.writeLong out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-conceptId o))
  (.writeUTF out (.-languageCode o))
  (.writeLong out (.-typeId o))
  (.writeUTF out (.-term o))
  (.writeLong out (.-caseSignificanceId o)))

(defn read-description [^DataInput in]
  (let [id (.readLong in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        concept-id (.readLong in)
        language-code (.readUTF in)
        type-id (.readLong in)
        term (.readUTF in)
        case-significance-id (.readLong in)]
    (snomed/->Description
      id effectiveTime active moduleId concept-id language-code type-id term case-significance-id)))

(defn write-relationship [^DataOutput out ^Relationship o]
  (.writeLong out (.-id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o)) (.writeLong out (.-sourceId o))
  (.writeLong out (.-destinationId o))
  (.writeLong out (.-relationshipGroup o))
  (.writeLong out (.-typeId o))
  (.writeLong out (.-characteristicTypeId o))
  (.writeLong out (.-modifierId o)))

(defn read-relationship [^DataInput in]
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



;;;;
;;;; Reference set items
;;;;

(defn write-uuid
  "Write a UUID as two long integers (16 bytes)."
  [^DataOutput out ^UUID uuid]
  (.writeLong out (.getMostSignificantBits uuid))
  (.writeLong out (.getLeastSignificantBits uuid)))

(defn ^UUID read-uuid
  "Read two long integers as a 128-bit UUID."
  [^DataInput in]
  (UUID. (.readLong in) (.readLong in)))


(defmulti write-field (fn [_ v] (class v)))
(defmethod write-field Long [^DataOutput out v]
  (.writeByte out (int \i))
  (.writeLong out v))
(defmethod write-field String [^DataOutput out v]
  (.writeByte out (int \s))
  (.writeUTF out v))

(defn read-field [^DataInput in]
  (let [field-type (char (.readByte in))]
    (case field-type
      \i (.readLong in)
      \s (.readUTF in)
      (throw (ex-info "unknown refset field type" {:got      field-type
                                                   :expected #{\i \s}})))))
(defn write-fields
  [^DataOutput out fields]
  (.writeInt out (count fields))                            ;; write out a count of the custom fields, may be zero
  (doseq [field fields]
    (write-field out field)))

(defn read-fields
  [^DataInput in]
  (let [n-fields (.readInt in)]                             ;; read in count of custom fields, may be zero
    (loop [n 0 result []]
      (if (= n n-fields)
        result
        (recur (inc n)
               (conj result (read-field in)))))))


(defn write-simple-refset-item [^DataOutput out ^SimpleRefsetItem o]
  (write-uuid out (.id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (write-fields out (.-fields o)))

(defn read-simple-refset-item [^DataInput in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        fields (read-fields in)]
    (snomed/->SimpleRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId fields)))


(defn write-association-refset-item [^DataOutput out ^AssociationRefsetItem o]
  (write-uuid out (.id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (.writeLong out (.-targetComponentId o))
  (write-fields out (.-fields o)))

(defn read-association-refset-item [^DataInput in]
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

(defn write-language-refset-item [^DataOutput out ^LanguageRefsetItem o]
  (write-uuid out (.id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (.writeLong out (.-acceptabilityId o))
  (write-fields out (.-fields o)))

(defn read-language-refset-item [^DataInput in]
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


(defn write-simple-map-refset-item [^DataOutput out ^SimpleMapRefsetItem o]
  (write-uuid out (.id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (.writeUTF out (.-mapTarget o))
  (write-fields out (.-fields o)))

(defn read-simple-map-refset-item [^DataInput in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        mapTarget (.readUTF in)
        fields (read-fields in)]
    (snomed/->SimpleMapRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId mapTarget fields)))


(defn write-complex-map-refset-item [^DataOutput out ^ComplexMapRefsetItem o]
  (write-uuid out (.id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o)) (.writeLong out (.-mapGroup o))
  (.writeLong out (.-mapPriority o))
  (.writeUTF out (.-mapRule o))
  (.writeUTF out (.-mapAdvice o))
  (.writeUTF out (.-mapTarget o))
  (.writeLong out (.-correlationId o))
  (write-fields out (.-fields o)))

(defn read-complex-map-refset-item [^DataInput in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        mapGroup (.readLong in)
        mapPriority (.readLong in)
        mapRule (.readUTF in)
        mapAdvice (.readUTF in)
        mapTarget (.readUTF in)
        correlationId (.readLong in)
        fields (read-fields in)]
    (snomed/->ComplexMapRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId
      mapGroup mapPriority mapRule mapAdvice mapTarget correlationId fields)))


(defn write-extended-map-refset-item [^DataOutput out ^ExtendedMapRefsetItem o]
  (write-uuid out (.id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o)) (.writeLong out (.-mapGroup o))
  (.writeLong out (.-mapPriority o))
  (.writeUTF out (.-mapRule o))
  (.writeUTF out (.-mapAdvice o))
  (.writeUTF out (.-mapTarget o))
  (.writeLong out (.-correlationId o))
  (.writeLong out (.-mapCategoryId o))
  (write-fields out (.-fields o)))

(defn read-extended-map-refset-item [^DataInput in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        mapGroup (.readLong in)
        mapPriority (.readLong in)
        mapRule (.readUTF in)
        mapAdvice (.readUTF in)
        mapTarget (.readUTF in)
        correlationId (.readLong in)
        mapCategoryId (.readLong in)
        fields (read-fields in)]
    (snomed/->ExtendedMapRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId
      mapGroup mapPriority mapRule mapAdvice mapTarget correlationId mapCategoryId fields)))

(defn write-attribute-value-refset-item [^DataOutput out ^AttributeValueRefsetItem o]
  (write-uuid out (.id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (.writeLong out (.-valueId o))
  (write-fields out (.-fields o)))

(defn read-attribute-value-refset-item [^DataInput in]
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

(defn write-owl-expression-refset-item [^DataOutput out ^OWLExpressionRefsetItem o]
  (write-uuid out (.id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (.writeUTF out (.-owlExpression o))                       ;; TODO: add compression?
  (write-fields out (.-fields o)))
(defn read-owl-expression-refset-item [^DataInput in]
  (let [id (read-uuid in)
        effectiveTime (LocalDate/ofEpochDay (.readLong in))
        active (.readBoolean in)
        moduleId (.readLong in)
        refsetId (.readLong in)
        referencedComponentId (.readLong in)
        owlExpression (.readUTF in)
        fields (read-fields in)]
    (snomed/->OWLExpressionRefsetItem
      id effectiveTime active moduleId refsetId referencedComponentId owlExpression fields)))


(defn write-refset-descriptor-refset-item [^DataOutput out ^RefsetDescriptorRefsetItem o]
  (write-uuid out (.id o))
  (.writeLong out (.toEpochDay ^LocalDate (.-effectiveTime o)))
  (.writeBoolean out (.-active o))
  (.writeLong out (.-moduleId o))
  (.writeLong out (.-refsetId o))
  (.writeLong out (.-referencedComponentId o))
  (.writeLong out (.-attributeDescriptionId o))
  (.writeLong out (.-attributeTypeId o))
  (.writeInt out (.-attributeOrder o)))

(defn read-refset-descriptor-refset-item [^DataInput in]
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

;;
;;
;;
(defmulti write-refset-item
  "Serialize a refset item with a single byte header indicated subtype."
  (fn [^DataOutput _out o] (class o)))

(defmethod write-refset-item :info.snomed/SimpleRefset [^DataOutput out o]
  (.writeByte out 1)
  (write-simple-refset-item out o))
(defmethod write-refset-item :info.snomed/LanguageRefset [^DataOutput out o]
  (.writeByte out 2)
  (write-language-refset-item out o))
(defmethod write-refset-item :info.snomed/SimpleMapRefset [^DataOutput out o]
  (.writeByte out 3)
  (write-simple-map-refset-item out o))
(defmethod write-refset-item :info.snomed/ComplexMapRefset [^DataOutput out o]
  (.writeByte out 4)
  (write-complex-map-refset-item out o))
(defmethod write-refset-item :info.snomed/ExtendedMapRefset [^DataOutput out o]
  (.writeByte out 5)
  (write-extended-map-refset-item out o))
(defmethod write-refset-item :info.snomed/OWLExpressionRefset [^DataOutput out o]
  (.writeByte out 6)
  (write-owl-expression-refset-item out o))
(defmethod write-refset-item :info.snomed/AttributeValueRefset [^DataOutput out o]
  (.writeByte out 7)
  (write-attribute-value-refset-item out o))
(defmethod write-refset-item :info.snomed/RefsetDescriptorRefset [^DataOutput out o]
  (.writeByte out 8)
  (write-refset-descriptor-refset-item out o))
(defmethod write-refset-item :info.snomed/AssociationRefset [^DataOutput out o]
  (.writeByte out 9)
  (write-association-refset-item out o))

(defn read-refset-item [^DataInput in]
  (case (.readByte in)
    1 (read-simple-refset-item in)
    2 (read-language-refset-item in)
    3 (read-simple-map-refset-item in)
    4 (read-complex-map-refset-item in)
    5 (read-extended-map-refset-item in)
    6 (read-owl-expression-refset-item in)
    7 (read-attribute-value-refset-item in)
    8 (read-refset-descriptor-refset-item in)
    9 (read-association-refset-item in)))

(defn read-effective-time
  "Optimised fetch of only the effectiveTime of a SNOMED component.
  Core SNOMED concepts have an identifier of 8 bytes (64-bit unsigned long).
  Refset items have an identifier of 16 bytes (128-bit UUID)."
  [^DataInput in read-offset]
  (.skipBytes in read-offset)
  (LocalDate/ofEpochDay (.readLong in)))

(comment)


