(ns com.eldrix.hermes.gen
  "Generators of synthetic SNOMED CT data, useful in creating a synthetic
  distribution for testing."
  (:require [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]
            [com.eldrix.hermes.verhoeff :as verhoeff]
            [com.eldrix.hermes.snomed :as snomed])
  (:import [java.time LocalDate]
           (com.eldrix.hermes.snomed Concept)
           (java.util Locale)))

(s/def ::component-type #{:info.snomed/Concept :info.snomed/Description :info.snomed/Relationship})

(s/fdef partitions-for-type
  :args (s/cat :type ::component-type))
(defn partitions-for-type [t]
  (reduce-kv (fn [acc k v] (if (= t v) (conj acc k) acc)) #{} snomed/partitions))

(defn gen-identifier
  "A generator of identifiers of the specified type.
  This may generate duplicate identifiers.
  Parameters:
  - t : one of :info.snomed/Concept :info.snomed.Description or
        :info.snomed/Relatioship."
  [t]
  (gen/fmap #(let [partition (rand-nth (seq (partitions-for-type t)))]
               (Long/parseLong (verhoeff/append (str % partition))))
            (s/gen (s/int-in 100000 Long/MAX_VALUE))))

(def gen-local-time
  "Generator of a random java.time.LocalDate in the past."
  (gen/fmap (fn [days] (.minusDays (LocalDate/now) days))
            (s/gen (s/int-in 1 (* 365 10)))))
(s/def ::effectiveTime (s/with-gen #(instance? LocalDate %)
                                   (fn [] gen-local-time)))

;;;;
;;;; RF2 concept specification
;;;;
(s/def :info.snomed.Concept/id (s/with-gen (s/and pos-int? verhoeff/valid? #(= :info.snomed/Concept (snomed/identifier->type %)))
                                           #(gen-identifier :info.snomed/Concept)))
(s/def :info.snomed.Concept/effectiveTime ::effectiveTime)
(s/def :info.snomed.Concept/active boolean?)
(s/def :info.snomed.Concept/moduleId :info.snomed.Concept/id)
(s/def :info.snomed.Concept/definitionStatusId #{snomed/Primitive snomed/Defined})
(s/def :info.snomed/Concept (s/keys :req-un [:info.snomed.Concept/id ::effectiveTime ::active ::moduleId ::definitionStatusId]))

(s/def ::moduleIds (s/coll-of ::id))
(s/fdef make-concept
  :args (s/cat :concept (s/keys* :opt-un [:info.snomed.Concept/id ::effectiveTime ::active ::moduleIds ::moduleId ::definitionStatusId]))
  :ret #(instance? Concept %))
(defn make-concept
  "Make a SNOMED CT concept. Without arguments, a totally random concept will be
  generated. Any fields to be manually set can be provided. While elements
  could be modified after generation, using the constructor means that the
  return value will be a concept.
  As a convenience, you can provide a collection of moduleIds, and one will be
  selected at random."
  [& {:keys [_id _effectiveTime _active moduleIds _moduleId _definitionStatusId] :as concept}]
  (snomed/map->Concept (merge {:id                 (gen/generate (gen-identifier :info.snomed/Concept))
                               :effectiveTime      (gen/generate gen-local-time)
                               :active             (gen/generate gen/boolean)
                               :moduleId           (if-let [module-ids' (seq moduleIds)]
                                                     (rand-nth module-ids')
                                                     (gen/generate (gen-identifier :info.snomed/Concept)))
                               :definitionStatusId (rand-nth [snomed/Primitive snomed/Defined])}
                              (dissoc concept :moduleIds))))


;;;;
;;;; RF2 description specification. This ideally would be built dynamically from the metadata model
;;;;
(s/def :info.snomed.Description/id (s/with-gen (s/and pos-int? verhoeff/valid? #(= :info.snomed/Description (snomed/identifier->type %)))
                                               #(gen-identifier :info.snomed/Description)))
(s/def :info.snomed.Description/effectiveTime ::effectiveTime)
(s/def :info.snomed.Description/active boolean?)
(s/def :info.snomed.Description/moduleId :info.snomed.Concept/id)
(s/def ::moduleIds (s/coll-of :info.snomed.Description/moduleId))
(s/def :info.snomed.Description/languageCode (set (Locale/getISOLanguages)))
(s/def ::languageCodes (s/coll-of :info.snomed.Description/languageCode))
(s/def :info.snomed.Description/typeId #{snomed/Synonym snomed/FullySpecifiedName snomed/Definition})
(s/def :info.snomed.Description/term (s/and string? #(> (.length %) 0)))
(s/def :info.snomed.Description/caseSignificanceId #{snomed/EntireTermCaseSensitive snomed/EntireTermCaseInsensitive snomed/OnlyInitialCharacterCaseInsensitive})
(s/fdef make-description
  :args (s/cat :conceptId :info.snomed.Concept/id
               :description (s/keys* :opt-un [:info.snomed.Description/id :info.snomed.Description/effectiveTime
                                              :info.snomed.Description/active :info.snomed.Description/moduleId ::moduleIds
                                              :info.snomed.Description/languageCode ::languageCodes
                                              :info.snomed.Description/typeId :info.snomed.Description/term :info.snomed.Description/caseSignificanceId])))
(defn make-description
  "Make a SNOMED CT Description for the specified concept-id"
  [conceptId & {:keys [id effectiveTime active moduleId moduleIds languageCode languageCodes typeId term caseSignificanceId]}]
  (snomed/map->Description {:id                 (or id (gen/generate (gen-identifier :info.snomed/Description)))
                            :effectiveTime      (or effectiveTime  (gen/generate gen-local-time))
                            :active             (or active  (gen/generate gen/boolean))
                            :moduleId           (or moduleId (if-let [module-ids' (seq moduleIds)]
                                                               (rand-nth module-ids')
                                                               (gen/generate (gen-identifier :info.snomed/Concept))))
                            :conceptId          conceptId
                            :languageCode       (or languageCode (if languageCodes
                                                                   (rand-nth (seq languageCodes))
                                                                   (gen/generate (s/gen :info.snomed.Description/languageCode))))
                            :typeId             (or typeId (gen/generate (s/gen :info.snomed.Description/typeId)))
                            :term               (or term (gen/generate (s/gen :info.snomed.Description/term)))
                            :caseSignificanceId (or caseSignificanceId (gen/generate (s/gen :info.snomed.Description/caseSignificanceId)))}))

;;;;
;;;; RF2 relationship specification
;;;;

(s/def :info.snomed.Relationship/id (s/with-gen (s/and pos-int? verhoeff/valid? #(= :info.snomed/Relationship (snomed/identifier->type %)))
                                                #(gen-identifier :info.snomed/Relationship)))


(comment
  (require '[clojure.spec.test.alpha :as stest])
  (stest/instrument)
  (let [concept (make-concept)]
    (repeatedly 5 #(make-description (.id concept) :languageCode "en" :moduleId (.moduleId concept))))
  (s/exercise-fn `make-concept)
  (s/exercise-fn `make-description)
  (clojure.spec.test.alpha/instrument)
  (s/valid? ::id 24700007)
  (s/explain ::id 24700007)
  (pos-int? 24700007)
  (take 500 (repeatedly make-concept))
  (gen/list-distinct)
  (gen/sample gen-local-time)
  (gen/sample (gen-identifier :info.snomed/Concept))
  (map snomed/identifier->type (gen/sample (s/gen ::relationship-id)))
  (gen/generate (s/gen ::description-id))
  (com.eldrix.hermes.verhoeff/valid? 24700007))