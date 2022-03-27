(ns com.eldrix.hermes.specs
  "Specifications for the core API of hermes."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.impl.search :as search]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.rf2 :as-alias rf2]
            [com.eldrix.hermes.verhoeff :as verhoeff])
  (:import (com.eldrix.hermes.snomed Result)
           (org.apache.lucene.search Query IndexSearcher)
           (com.eldrix.hermes.impl.store MapDBStore)))

(set! *warn-on-reflection* true)

(s/def ::svc any?)
(s/def ::store #(instance? MapDBStore %))
(s/def ::searcher #(instance? IndexSearcher %))
(s/def ::result #(instance? Result %))
(s/def ::component-id (s/and pos-int? verhoeff/valid?))
(s/def ::s string?)
(s/def ::non-blank-string (s/and string? (complement str/blank?)))
(s/def ::refset-filename-pattern (s/with-gen (s/and string? #(every? #{\c \i \s} %))
                                             #(gen/fmap (fn [n] (apply str (repeatedly n (fn [] (rand-nth [\c \i \s])))))
                                                        (s/gen (s/int-in 1 10)))))
(s/def ::constraint string?)
(s/def ::max-hits pos-int?)
(s/def ::fuzzy (s/int-in 0 2))
(s/def ::fallback-fuzzy (s/int-in 0 2))
(s/def ::query #(instance? Query %))
(s/def ::show-fsn? boolean?)
(s/def ::inactive-concepts? boolean?)
(s/def ::inactive-descriptions? boolean?)
(s/def ::properties (s/map-of int? int?))
(s/def ::concept-refsets (s/coll-of :info.snomed.Concept/id))
(s/def ::search-params (s/keys :req-un [(or ::s ::constraint)]
                               :opt-un [::max-hits ::fuzzy ::fallback-fuzzy ::query
                                        ::show-fsn? ::inactive-concepts? ::inactive-descriptions?
                                        ::properties ::concept-refsets]))
(s/def ::search-params-impl (s/keys :req-un [::s]
                                    :opt-un [::max-hits ::fuzzy ::fallback-fuzzy ::query
                                             ::show-fsn? ::inactive-concepts? ::inactive-descriptions?
                                             ::properties ::concept-refsets]))

(s/fdef hermes/get-concept
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))

(s/fdef hermes/get-extended-concept
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))

(s/fdef hermes/get-descriptions
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))

(s/fdef hermes/get-synonyms
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))

(s/fdef hermes/get-component-refset-items
  :args (s/cat :svc ::svc :component-id ::component-id :refset-id (s/? :info.snomed.Concept/id)))

(s/fdef hermes/get-component-refset-ids
  :args (s/cat :svc ::svc :component-id ::component-id))

(s/fdef hermes/with-historical
  :args (s/cat :svc ::svc
               :concept-ids (s/coll-of :info.snomed.Concept/id)
               :refset-ids (s/? (s/coll-of :info.snomed.Concept/id))))

(s/fdef hermes/reverse-map
  :args (s/cat :svc ::svc :refset-id :info.snomed.Concept/id :code ::non-blank-string))

(s/fdef hermes/reverse-map-range
  :args (s/alt :prefix (s/cat :svc ::svc :refset-id :info.snomed.Concept/id :prefix ::non-blank-string)
               :range (s/cat :svc ::svc :refset-id :info.snomed.Concept/id :lower-bound ::non-blank-string :upper-bound ::non-blank-string)))

(s/fdef hermes/get-preferred-synonym
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :language-range (s/? ::non-blank-string)))

(s/fdef hermes/get-fully-specified-name
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))

(s/fdef hermes/search
  :args (s/cat :svc ::svc :params ::search-params)
  :ret (s/coll-of ::result))

(s/fdef hermes/expand-ecl
  :args (s/cat :svc ::svc :ecl ::non-blank-string)
  :ret (s/coll-of ::result))

(s/fdef hermes/all-transitive-synonyms
  :args (s/cat :svc ::svc
               :params (s/alt :by-ecl string? :by-search ::search-params ::by-concept-ids (s/coll-of :info.snomed.Concept/id))))

(s/fdef hermes/ecl-contains?
  :args (s/cat :svc ::svc
               :concept-ids (s/coll-of :info.snomed.Concept/id)
               :ecl ::non-blank-string))

(s/fdef map-into
  :args (s/cat :svc ::svc :source-concept-ids (s/coll-of :info.snomed.Concept/id)
               :target (s/alt :ecl string?
                              :refset-id :info.snomed.Concept/id
                              :concepts (s/coll-of :info.snomed.Concept/id))))

(s/fdef snomed/parse-using-pattern
  :args (s/cat :pattern ::refset-filename-pattern
               :values (s/coll-of string?)))

;;
;; Internal (private) API specifications
;;

(s/fdef store/get-concept
  :args (s/cat :store ::store :concept-id :info.snomed.Concept/id))

(s/fdef search/do-search
  :args (s/cat :searcher ::searcher :parans ::search-params-impl))