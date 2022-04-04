(ns com.eldrix.hermes.gen
  "Generators of synthetic SNOMED CT data, useful in creating a synthetic
  distribution for testing."
  (:require [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]
            [com.eldrix.hermes.rf2 :as rf2]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.verhoeff :as verhoeff])
  (:import (com.eldrix.hermes.snomed Concept)))

(defn make-simple-hierarchy []
  (let [concepts (gen/sample (rf2/gen-concept) 500)
        root-concept (first concepts)
        all-children (next concepts)
        descriptions (mapcat #(gen/sample (rf2/gen-description {:conceptId (:id %)})) concepts)
        relationships (loop [parent-id (:id root-concept)
                             batches (partition-all 20 all-children)
                             result []]
                        (let [batch (first batches)]
                          (if-not batch
                            result
                            (recur (:id (rand-nth batch))
                                   (next batches)
                                   (into result (gen/generate (rf2/gen-relationships-for-parent parent-id batch snomed/IsA)))))))]
    {:root-concept root-concept
     :concepts concepts
     :descriptions descriptions
     :relationships relationships}))


(defn make-language-refset-items
  "Create synthetic language refset items for the descriptions specified.
  For each language refset, every concept must have a preferred item."
  ([descriptions] (make-language-refset-items descriptions {}))
  ([descriptions item]
   (let [descriptions-by-concept (reduce (fn [acc d] (update acc (:conceptId d) conj d)) {} descriptions)
         concept-ids (keys descriptions-by-concept)]
     (->> concept-ids
          (map (fn [concept-id] (-> (gen/generate (s/gen :info.snomed/LanguageRefset))
                                    (merge item)
                                    (assoc :referencedComponentId (:id (rand-nth (get descriptions-by-concept concept-id)))))))
          (map snomed/map->LanguageRefsetItem)))))


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