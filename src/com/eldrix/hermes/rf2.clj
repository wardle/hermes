(ns com.eldrix.hermes.rf2
  "Specifications for the RF2 SNOMED format.
  See https://confluence.ihtsdotools.org/display/DOCRELFMT"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.verhoeff :as verhoeff])
  (:import [java.time LocalDate]
           [java.util Locale]))

(s/def ::component-types #{:info.snomed/Concept :info.snomed/Description :info.snomed/Relationship})

(s/fdef partitions-for-type
  :args (s/cat :type ::component-types))
(defn partitions-for-type [t]
  (reduce-kv (fn [acc k v] (if (= t v) (conj acc k) acc)) #{} snomed/partitions))

(def counter (atom 0))

(defn reset-identifier-counter []
  (reset! counter 0))

(defn gen-unique []
  (sgen/fmap (fn [_] (swap! counter inc)) (sgen/return nil)))

(defn gen-identifier
  "A generator of identifiers of the specified type.
  Parameters:
  - t : one of :info.snomed/Concept :info.snomed.Description or
        :info.snomed/Relatioship."
  [t]
  (sgen/fmap #(let [partition (rand-nth (seq (partitions-for-type t)))]
                (Long/parseLong (verhoeff/append (str % partition))))
            (gen-unique)))

(s/def ::effectiveTime (s/with-gen #(instance? LocalDate %)
                                   #(sgen/fmap (fn [days] (.minusDays (LocalDate/now) days))
                                              (s/gen (s/int-in 1 (* 365 10))))))

;;;;
;;;; RF2 concept specification
;;;;
(s/def :info.snomed.Concept/id (s/with-gen (s/and pos-int? verhoeff/valid? #(= :info.snomed/Concept (snomed/identifier->type %)))
                                           #(gen-identifier :info.snomed/Concept)))
(s/def :info.snomed.Concept/effectiveTime ::effectiveTime)
(s/def :info.snomed.Concept/active boolean?)
(s/def :info.snomed.Concept/moduleId :info.snomed.Concept/id)
(s/def :info.snomed.Concept/definitionStatusId #{snomed/Primitive snomed/Defined})
(s/def :info.snomed/Concept (s/keys :req-un [:info.snomed.Concept/id :info.snomed.Concept/effectiveTime
                                             :info.snomed.Concept/active :info.snomed.Concept/moduleId :info.snomed.Concept/definitionStatusId]))

;;;;
;;;; RF2 description specification.
;;;;
(s/def :info.snomed.Description/id (s/with-gen (s/and pos-int? verhoeff/valid? #(= :info.snomed/Description (snomed/identifier->type %)))
                                               #(gen-identifier :info.snomed/Description)))
(s/def :info.snomed.Description/effectiveTime ::effectiveTime)
(s/def :info.snomed.Description/active boolean?)
(s/def :info.snomed.Description/moduleId :info.snomed.Concept/id)
(s/def :info.snomed.Description/languageCode (set (Locale/getISOLanguages)))
(s/def :info.snomed.Description/typeId #{snomed/Synonym snomed/FullySpecifiedName snomed/Definition})
(s/def :info.snomed.Description/term (s/and string? #(> (.length %) 0)))
(s/def :info.snomed.Description/caseSignificanceId #{snomed/EntireTermCaseSensitive snomed/EntireTermCaseInsensitive snomed/OnlyInitialCharacterCaseInsensitive})
(s/def :info.snomed/Description (s/keys :req-un [:info.snomed.Description/id :info.snomed.Description/effectiveTime
                                                 :info.snomed.Description/active :info.snomed.Description/moduleId
                                                 :info.snomed.Description/languageCode :info.snomed.Description/typeId
                                                 :info.snomed.Description/term :info.snomed.Description/caseSignificanceId]))


;;;;
;;;; RF2 relationship specification
;;;;

(s/def :info.snomed.Relationship/id (s/with-gen (s/and pos-int? verhoeff/valid? #(= :info.snomed/Relationship (snomed/identifier->type %)))
                                                #(gen-identifier :info.snomed/Relationship)))
(s/def :info.snomed.Relationship/effectiveTime ::effectiveTime)
(s/def :info.snomed.Relationship/active boolean?)
(s/def :info.snomed.Relationship/moduleId :info.snomed.Concept/id)
(s/def :info.snomed.Relationship/sourceId :info.snomed.Concept/id)
(s/def :info.snomed.Relationship/destinationId :info.snomed.Concept/id)
(s/def :info.snomed.Relationship/relationshipGroup (s/with-gen nat-int?
                                                               #(s/gen (s/int-in 0 5))))
(s/def :info.snomed.Relationship/typeId :info.snomed.Concept/id)
(s/def :info.snomed.Relationship/characteristicTypeId :info.snomed.Concept/id)
(s/def :info.snomed.Relationship/modifierId (s/with-gen :info.snomed.Concept/id
                                                        #(sgen/return 900000000000451002)))
(s/def :info.snomed/Relationship (s/keys :req-un [:info.snomed.Relationship/id :info.snomed.Relationship/effectiveTime
                                                  :info.snomed.Relationship/active :info.snomed.Relationship/moduleId
                                                  :info.snomed.Relationship/sourceId :info.snomed.Relationship/destinationId
                                                  :info.snomed.Relationship/relationshipGroup :info.snomed.Relationship/typeId
                                                  :info.snomed.Relationship/characteristicTypeId :info.snomed.Relationship/modifierId]))
