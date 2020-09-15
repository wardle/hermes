(ns com.eldrix.hermes.snomed
  (:require [clojure.instant :as instant]
            [com.eldrix.hermes.verhoeff :as verhoeff])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]))

(defrecord Concept [^Long id effectiveTime ^boolean active ^Long moduleId ^Long definitionStatusId])
(defrecord Description [^Long id effectiveTime ^boolean active ^Long moduleId ^Long conceptId ^String languageCode ^Long typeId ^String term ^Long caseSignificanceId])
(defrecord Relationship [^Long id effectiveTime ^boolean active ^Long moduleId ^Long sourceId ^Long destinationId ^Long relationshipGroup ^Long typeId ^Long characteristicTypeId ^Long modifierId])
(defrecord ReferenceSetItem [^String id effectiveTime ^boolean active ^Long moduleId ^Long refsetId ^Long referencedComponentId])

(defrecord ExtendedConcept [concept descriptions parent-relationships child-relationships reference-sets])

(defprotocol Validatable
  (validate [m]))

(extend-protocol Validatable
  Concept
  (validate [m] (verhoeff/valid? (:id m))))

(defn ^java.time.LocalDate parse-date [^String s] (LocalDate/parse s (DateTimeFormatter/BASIC_ISO_DATE)))

;; use vector indexing rather than converting each row of the CSV to a map for speed
;; TODO: test whether this assumption is correct
(defn parse-concept [v]
  (->Concept (Long/parseLong (v 0))
             (parse-date (v 1))
             (Boolean/parseBoolean (v 2))
             (Long/parseLong (v 3))
             (Long/parseLong (v 4))))

(defn parse-description [v]
  (->Description (Long/parseLong (v 0))
                 (parse-date (v 1))
                 (Boolean/parseBoolean (v 2))
                 (Long/parseLong (v 3))
                 (Long/parseLong (v 4))
                 (v 5)
                 (Long/parseLong (v 6))
                 (v 7)
                 (Long/parseLong (v 8))))


(defn parse-relationship [v]
  (->Relationship (Long/parseLong (v 0))
                  (parse-date (v 1))
                  (Boolean/parseBoolean (v 2))
                  (Long/parseLong (v 3))                    ;; moduleId
                  (Long/parseLong (v 4))                    ;; sourceId
                  (Long/parseLong (v 5))                    ;; destinationId
                  (Long/parseLong (v 6))                    ;; relationshipGroup
                  (Long/parseLong (v 7))                    ;; typeId
                  (Long/parseLong (v 8))                    ;; characteristicTypeId
                  (Long/parseLong (v 9))))                  ;; modifierId

(defrecord ReferenceSetItem [^String id effectiveTime ^boolean active ^Long moduleId ^Long refsetId ^String referencedComponentId])
(defn parse-reference-set-item [v]
  (->ReferenceSetItem (Long/parseLong (v 0))
                      (parse-date (v 1))
                      (Boolean/parseBoolean (v 2))
                      (Long/parseLong (v 3))
                      (Long/parseLong (v 4))
                      (v 5)))

  (def parsers {:snomed/concept            parse-concept
                :snomed/description        parse-description
                :snomed/relationship       parse-relationship
                :snomed/reference-set-item parse-reference-set-item})

  (defn parse-batch
    [batch]
    (if-let [parse (get parsers (:type batch))]
      (try
        (map parse (:data batch))
        (catch Exception e (ex-info "unable to parse" (dissoc batch :data) e)))
      (throw (Exception. (str "no parser for batch type" (:type batch))))))
