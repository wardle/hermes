(ns com.eldrix.hermes.gen
  "Generators of synthetic SNOMED CT data, useful in creating a synthetic
  distribution for testing."
  (:require [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]
            [com.eldrix.hermes.rf2spec :as rf2]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (com.eldrix.hermes.snomed Concept)))

(s/def ::moduleIds (s/coll-of :info.snomed.Concept/id))

(s/fdef make-concept
  :args (s/cat :concept (s/keys* :opt-un [:info.snomed.Concept/id :info.snomed.Concept/effectiveTime
                                          :info.snomed.Concept/active ::moduleIds :info.snomed.Concept/moduleId
                                          :info.snomed.Concept/definitionStatusId]))
  :ret #(instance? Concept %))
(defn make-concept
  "Make a SNOMED CT concept. Without arguments, a totally random concept will be
  generated. Any fields to be manually set can be provided. While elements
  could be modified after generation, using the constructor means that the
  return value will be a concept.
  As a convenience, you can provide a collection of moduleIds, and one will be
  selected at random."
  [& {:keys [_id _effectiveTime _active moduleIds _moduleId _definitionStatusId] :as concept}]
  (snomed/map->Concept (merge
                         (gen/generate (s/gen :info.snomed/Concept))
                         (when moduleIds {:moduleId (rand-nth (seq moduleIds))})
                         (select-keys concept [:id :effectiveTime :active :moduleId :definitionStatusId]))))

(s/def ::moduleIds (s/coll-of :info.snomed.Description/moduleId))
(s/def ::languageCodes (s/coll-of :info.snomed.Description/languageCode))
(s/fdef make-description
  :args (s/cat :conceptId :info.snomed.Concept/id
               :description (s/keys* :opt-un [:info.snomed.Description/id :info.snomed.Description/effectiveTime
                                              :info.snomed.Description/active :info.snomed.Description/moduleId ::moduleIds
                                              :info.snomed.Description/languageCode ::languageCodes
                                              :info.snomed.Description/typeId :info.snomed.Description/term :info.snomed.Description/caseSignificanceId])))
(defn make-description
  "Make a SNOMED CT Description for the specified concept-id."
  [conceptId & {:keys [id effectiveTime active moduleId moduleIds languageCode languageCodes typeId term caseSignificanceId] :as description}]
  (snomed/map->Description
    (merge (gen/generate (s/gen :info.snomed/Description))
           (when moduleIds {:moduleId (rand-nth (seq moduleIds))})
           (when languageCodes {:languageCode (rand-nth (seq languageCodes))})
           (select-keys description [:id :effectiveTime :active :moduleId :languageCode :typeId :term :caseSignificanceId])
           {:conceptId conceptId})))


(s/fdef make-relationship
  :args (s/cat :relationship (s/keys* :opt-un [:info.snomed.Relationship/id :info.snomed.Relationship/active
                                               :info.snomed.Relationship/moduleId
                                               :info.snomed.Relationship/sourceId :info.snomed.Relationship/destinationId
                                               :info.snomed.Relationship/relationshipGroup :info.snomed.Relationship/typeId
                                               :info.snomed.Relationship/characteristicTypeId :info.snomed.Relationship/modifierId])))
(defn make-relationship
  "Make a SNOMED CT Relationship. It would be usual to at least specify
  'sourceId' 'destinationId' and 'typeId'."
  [& {:keys [id active moduleId sourceId destinationId relationshipGroup typeId characteristicTypeId modifierId] :as relationship}]
  (snomed/map->Relationship (merge (gen/generate (s/gen :info.snomed/Relationship))
                                   relationship)))

(defn make-descriptions [{:keys [id] :as concept} & {:keys [n] :or {n (rand-int 12)} :as defaults}]
  (let [descriptions (repeatedly n #(make-description id defaults))]
    {:concept      concept
     :descriptions descriptions}))

(s/def ::n (s/with-gen nat-int?                             ;; we allow any natural integer,
                       #(s/gen (s/int-in 1 12))))           ;; but generate only a small range for generative testing
(s/fdef make-children
  :args (s/cat :concept (s/keys :req-un [:info.snomed.Concept/id])
               :defaults (s/keys :req-un [::n :info.snomed.Relationship/typeId])))
(defn make-children
  "Create 'n' or a random small number of child concepts for the concept."
  [{:keys [id] :as concept} & {:keys [n typeId] :as defaults :or {n (rand-int 12) typeId snomed/IsA}}]
  (let [concepts (repeatedly n #(make-concept defaults))
        relationships (map #(make-relationship (merge defaults
                                                      {:sourceId      (:id %)
                                                       :destinationId id
                                                       :typeId        typeId})) concepts)]
    {:concept       concept
     :concepts      concepts
     :relationships relationships}))


(comment
  (require '[clojure.spec.test.alpha :as stest])
  (stest/instrument)
  (s/exercise-fn `make-children)
  (let [concept (make-concept)]
    (repeatedly 5 #(make-description (.id concept) :languageCodes #{"en" "fr"} :moduleId (.moduleId concept))))
  (make-relationship :active false)
  (make-concept)
  (make-children (make-concept :moduleId 24700007))
  (make-descriptions (make-concept) :languageCode "en")
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