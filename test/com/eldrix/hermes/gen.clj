(ns com.eldrix.hermes.gen
  "Generators of synthetic SNOMED CT data, useful in creating a synthetic
  distribution for testing."
  (:require [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]
            [com.eldrix.hermes.rf2spec :as rf2]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.verhoeff :as verhoeff])
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
  return value will be a concept. Unfortunately, successive calls offer no
  guarantees that concept ids will not be re-used. If generating large numbers
  of concepts that must have unique concept-ids, use [[make-concepts]].
  As a convenience, you can provide a collection of moduleIds, and one will be
  selected at random."
  [& {:keys [_id _effectiveTime _active moduleIds _moduleId _definitionStatusId] :as concept}]
  (snomed/map->Concept (merge
                         (gen/generate (s/gen :info.snomed/Concept))
                         (when moduleIds {:moduleId (rand-nth (seq moduleIds))})
                         (select-keys concept [:id :effectiveTime :active :moduleId :definitionStatusId]))))


(s/fdef make-concepts
  :args (s/cat :defaults (s/keys* :opt-un [::n])))
(defn make-concepts
  "Creates a sequence of concepts with a guarantee that no concept generated
   will have the same concept id as another. The actual number of concepts
   generated may be much less than requested due to duplicates."
  [& {:keys [n] :or {n (rand-int 50)}}]
  (->> (repeatedly n make-concept)
       (reduce (fn [acc v]
                 (assoc acc (:id v) v)) {})
       vals))

(s/def ::moduleIds (s/coll-of :info.snomed.Description/moduleId))
(s/def ::languageCodes (s/coll-of :info.snomed.Description/languageCode))
(s/def ::n (s/with-gen nat-int?                             ;; we allow any natural integer,
                       #(s/gen (s/int-in 1 12))))           ;; but generate only a small range for generative testing
(s/fdef make-description
  :args (s/cat :conceptId :info.snomed.Concept/id
               :description (s/keys* :opt-un [:info.snomed.Description/id :info.snomed.Description/effectiveTime
                                              :info.snomed.Description/active :info.snomed.Description/moduleId ::moduleIds
                                              :info.snomed.Description/languageCode ::languageCodes
                                              :info.snomed.Description/typeId :info.snomed.Description/term :info.snomed.Description/caseSignificanceId])))
(defn make-description
  "Make a SNOMED CT Description for the specified concept-id."
  [conceptId & {:keys [id effectiveTime active moduleId module-ids languageCode languageCodes typeId term term-prefix caseSignificanceId] :as description}]
  (snomed/map->Description
    (merge (gen/generate (s/gen :info.snomed/Description))
           (when module-ids {:moduleId (rand-nth (seq module-ids))})
           (when term-prefix {:term (str term-prefix (gen/generate (s/gen string?)))})
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

(s/fdef make-descriptions
  :args (s/cat :concept (s/keys :req-un [:info.snomed.Concept/id])
               :defaults (s/? (s/keys :opt-un [::n]))))
(defn make-descriptions [{:keys [id]} & {:keys [n] :as defaults}]
  (repeatedly (or n (inc (rand-int 12))) #(make-description id defaults)))

(defn make-relationships [parent-concept-id concepts & {:keys [typeId] :or {typeId snomed/IsA} :as defaults}]
  (map #(make-relationship (merge defaults
                                  {:sourceId      (:id %)
                                   :destinationId parent-concept-id
                                   :typeId        typeId})) concepts))

(defn make-simple-hierarchy []
  (let [concepts (make-concepts :n 500)
        root-concept (first concepts)
        all-children (next concepts)
        descriptions (mapcat #(make-descriptions %) concepts)
        relationships (loop [parent-id (:id root-concept)
                             batches (partition-all 20 all-children)
                             relationships []]
                        (let [batch (first batches)]
                          (if-not batch
                            relationships
                            (recur (:id (rand-nth batch))
                                   (next batches)
                                   (into relationships (make-relationships parent-id batch :active true))))))]
    {:root-concept root-concept
     :concepts concepts
     :descriptions descriptions
     :relationships relationships}))

(comment
  (require '[clojure.spec.test.alpha :as stest])
  (stest/instrument)
  (def a1 (make-simple-hierarchy))
  (count (:descriptions a1))
  (count (set (map :id (:descriptions a1))))
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