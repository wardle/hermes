; Copyright 2020 Mark Wardle and Eldrix Ltd
;
;   Licensed under the Apache License, Version 2.0 (the "License");
;   you may not use this file except in compliance with the License.
;   You may obtain a copy of the License at
;
;       http://www.apache.org/licenses/LICENSE-2.0
;
;   Unless required by applicable law or agreed to in writing, software
;   distributed under the License is distributed on an "AS IS" BASIS,
;   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;   See the License for the specific language governing permissions and
;   limitations under the License.
;;;;
(ns com.eldrix.hermes.impl.store
  "Store provides access to a key value store."
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.impl.lmdb :as kv]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (com.eldrix.hermes.snomed Concept ExtendedConcept SimpleRefsetItem)
           (java.io Closeable)))

(s/def ::store any?)

;; this is a temporary approach to permit parallel evaluation of the
;; key stores

(def get-concept kv/get-concept)
(def get-concept-descriptions kv/get-concept-descriptions)
(def get-installed-reference-sets kv/get-installed-reference-sets)
(def get-component-refset-items kv/get-component-refset-items)
(def stream-all-refset-items kv/stream-all-refset-items)
(def get-refset-field-names kv/get-refset-field-names)
(def get-refset-item kv/get-refset-item)
(def status kv/status)
(def get-component-refset-ids kv/get-component-refset-ids)
(def stream-all-concepts kv/stream-all-concepts)
(def get-description kv/get-description)
(def get-relationship kv/get-relationship)
(def compact kv/compact)
(def get-raw-child-relationships kv/get-raw-child-relationships)
(def get-raw-parent-relationships kv/get-raw-parent-relationships)
(def get-source-association-referenced-components kv/get-source-association-referenced-components)
(def source-associations kv/get-source-associations)
(defn open-store
  (^Closeable [] (kv/open-store))
  (^Closeable [f] (kv/open-store f))
  (^Closeable [f opts] (kv/open-store f opts)))

(def close kv/close)

(defn get-all-parents
  "Returns all parent concepts for the concept, including self.
   Parameters:
   - `store`
   - `concept-id`
   - `type-id`, defaults to 'IS-A' (116680003)."
  ([store concept-id]
   (get-all-parents store concept-id snomed/IsA))
  ([store concept-id type-id]
   (loop [work #{concept-id}
          result (transient #{})]
     (if-not (seq work)
       (persistent! result)
       (let [id (first work)
             done-already? (contains? result id)
             parent-ids (if done-already? () (map last (kv/get-raw-parent-relationships store id type-id)))]
         (recur (apply conj (rest work) parent-ids)
                (conj! result id)))))))

(defn get-parent-relationships
  "Returns a map of the parent relationships, with each value a set of
  identifiers representing the targets of each relationship.
  Returns a map:
  key: concept-id of the relationship type (e.g. identifier representing finding site)
  value: a set of concept identifiers for that property.

  See `get-parent-relationships-expanded` to get each target expanded via
  transitive closure tables."
  [store concept-id]
  (->> (kv/get-raw-parent-relationships store concept-id)
       (reduce (fn [acc v]
                 (update acc (v 1) (fnil conj #{}) (v 3))) {}))) ;; tuple [concept-id type-id group destination-id] so return indices 1+3

(defn get-parent-relationships-of-type
  "Returns a set of identifiers representing the parent relationships of the
  specified type of the specified concept."
  [store concept-id type-concept-id]
  (->> (kv/get-raw-parent-relationships store concept-id type-concept-id)
       (reduce (fn [acc v] (conj acc (v 3))) #{})))

(defn get-parent-relationships-of-types
  [store concept-id type-concept-ids]
  (set (mapcat #(get-parent-relationships-of-type store concept-id %) type-concept-ids)))

(defn get-parent-relationships-expanded
  "Returns a map of the parent relationships, with each value a set of
  identifiers representing the targets and their transitive closure tables. This
  makes it trivial to build queries that find all concepts with, for example, a
  common finding site at any level of granularity."
  ([store concept-id]
   (->> (kv/get-raw-parent-relationships store concept-id)
        (map (fn [[_source-id type-id _group target-id]] (hash-map type-id (get-all-parents store target-id))))
        (apply merge-with into)))
  ([store concept-id type-id]
   (->> (kv/get-raw-parent-relationships store concept-id type-id)
        (map (fn [[_source-id type-id _group target-id]] (hash-map type-id (get-all-parents store target-id))))
        (apply merge-with into))))

(defn get-child-relationships-of-type
  "Returns a set of identifiers representing the parent relationships of the
  specified type of the specified concept."
  [store concept-id type-concept-id]
  (->> (kv/get-raw-child-relationships store concept-id type-concept-id)
       (reduce (fn [acc v] (conj acc (v 3))) #{})))

(defn paths-to-root
  "Return a sequence of paths from the concept to root node.
  Each path is a sequence of identifiers, starting with the concept itself
  and ending with the root node.
  e.g.
  ```
    (sort-by count (paths-to-root store 24700007))
  ```
  result (truncated):
  ```
    ((24700007 414029004 64572001 404684003 138875005)
    (24700007 6118003 80690008 362975008 64572001 404684003 138875005)
    (24700007 39367000 23853001 246556002 118234003 404684003 138875005)
    (24700007 6118003 80690008 23853001 246556002 118234003 404684003 138875005))
  ```"
  [store concept-id]
  (loop [parent-ids (map last (kv/get-raw-parent-relationships store concept-id snomed/IsA))
         results []]
    (let [parent (first parent-ids)]
      (if-not parent
        (if (seq results) (map #(conj % concept-id) results) (list (list concept-id)))
        (recur (rest parent-ids)
               (concat results (paths-to-root store parent)))))))

(defn get-all-children
  "Returns all child concepts for the concept.
  It takes 3500 milliseconds on my 2013 laptop to return all child concepts
  of the root SNOMED CT concept, so this should only be used for more granular
  concepts generally, or used asynchronously / via streaming.
   
   Parameters:
   - store
   - `concept-id`
   - `type-id`, defaults to 'IS-A' (116680003)."
  ([store concept-id] (get-all-children store concept-id snomed/IsA))
  ([store concept-id type-id]
   (loop [work #{concept-id}
          result #{}]
     (if-not (seq work)
       result
       (let [id (first work)
             done-already? (contains? result id)
             children (if done-already? () (map last (kv/get-raw-child-relationships store id type-id)))]
         (recur (apply conj (rest work) children)
                (conj result id)))))))

(defn get-grouped-properties
  "Return a concept's properties as a collection of maps, each map representing
  related properties in a 'relationshipGroup'. By default, all groups are
  returned, but this can optionally be limited to those containing a specific
  relationship type."
  ([store concept-id]
   (->> (kv/get-raw-parent-relationships store concept-id)  ;; tuples concept--type--group--destination
        (map rest)                                          ;; turn into tuple of type--group--destination
        (group-by second)                                   ;; now group by 'group'
        (reduce-kv                                          ;; and turn each into a map of type--destination, still grouped by group
          (fn [m k v] (assoc m k (into {} (map #(hash-map (first %) (last %)) v))))
          {})
        vals))
  ([store concept-id type-id]
   (->> (get-grouped-properties store concept-id)
        (filter #(contains? % type-id)))))

(defn get-leaves
  "Returns the subset of the specified `concept-ids` such that no member of the subset is subsumed by another member.
  
   Parameters:
  - concept-ids  : a collection of concept identifiers"
  [store concept-ids]
  (set/difference (set concept-ids) (into #{} (mapcat #(disj (get-all-parents store %) %) concept-ids))))

(defn transitive-synonyms
  "Returns all synonyms of the specified concept, including those of its
  descendants."
  ([store concept-id] (transitive-synonyms store concept-id {}))
  ([store concept-id {:keys [include-inactive?]}]
   (let [concepts (conj (get-all-children store concept-id) concept-id)
         ds (mapcat (partial kv/get-concept-descriptions store) concepts)
         ds' (if include-inactive? ds (filter :active ds))]
     (filter #(= snomed/Synonym (:typeId %)) ds'))))


(defn get-description-refsets
  "Get the refsets and language applicability for a description.
  
   Returns a map containing:
  - refsets       : a set of refsets to which this description is a member
  - preferredIn  : refsets for which this description is preferred
  - acceptableIn : refsets for which this description is acceptable.

  Example:
  ``` 
  (map #(merge % (get-description-refsets store (:id %)))
       (get-concept-descriptions store 24700007))
  ```"
  [store description-id]
  (let [refset-items (kv/get-component-refset-items store description-id)
        refsets (into #{} (map :refsetId refset-items))
        preferred-in (into #{} (map :refsetId (filter #(= snomed/Preferred (:acceptabilityId %)) refset-items)))
        acceptable-in (into #{} (map :refsetId (filter #(= snomed/Acceptable (:acceptabilityId %)) refset-items)))]
    {:refsets      refsets
     :preferredIn  preferred-in
     :acceptableIn acceptable-in}))

(s/fdef get-preferred-description
  :args (s/cat :store ::store :concept-id :info.snomed.Concept/id :description-type-id :info.snomed.Concept/id :language-refset-id :info.snomed.Concept/id)
  :ret (s/nilable :info.snomed/Description))
(defn get-preferred-description
  "Return the preferred description for the concept specified as defined by
  the language reference set specified for the description type.
  - store               :
  - concept-id          :
  - description-type-id : type of description (e.g. synonym or FSN)
  - language-refset-id  : language reference set

  Possible description-type-ids:
  ```
   900000000000013009: synonym (core metadata concept)
   900000000000003001: fully specified name
  ```
  Example language-refset-ids:
  ``` 
   900000000000509007: US English language reference set
   999001261000000100: UK English (clinical) language reference set
  ```"
  [store concept-id description-type-id language-refset-id]
  (let [descriptions (->> (kv/get-concept-descriptions store concept-id)
                          (filter :active)
                          (filter #(= description-type-id (:typeId %))))
        refset-item (->> (mapcat #(kv/get-component-refset-items store (:id %) language-refset-id) descriptions)
                         (filter #(= snomed/Preferred (:acceptabilityId %))) ;; only PREFERRED
                         (first))
        preferred (:referencedComponentId refset-item)]
    (when preferred (kv/get-description store preferred))))

(defn get-preferred-synonym
  "Returns the preferred synonym for the concept specified, looking in the language reference sets
  specified in order, returning the first 'preferred' value. The ordering of the reference
  set identifiers is therefore significant.
  - store               : store
  - concept-id          : concept-id
  - language-refset-ids : an ordered list of language reference set identifiers

  Notes:
  We have a frustrating situation in that there are three language reference sets in the UK that
  might give a 'good' answer to the 'preferred' description for any given concept.
  900000000000508004 : Great Britain English language reference set
  999001261000000100 : NHS realm language (clinical part)
  999000691000001104 : NHS Realm language (pharmacy part)  (supercedes the old dm+d realm subset 30001000001134).
  This means that we need to be able to submit *multiple* language reference set identifiers."
  [store concept-id language-refset-ids]
  (some identity (map (partial get-preferred-description store concept-id snomed/Synonym) language-refset-ids)))

(s/fdef get-preferred-fully-specified-name
  :args (s/cat :store ::store
               :concept-id :info.snomed.Concept/id
               :language-refset-ids (s/coll-of :info.snomed.Concept/id))
  :ret (s/nilable :info.snomed/Description))
(defn get-preferred-fully-specified-name [store concept-id language-refset-ids]
  (some identity (map (partial get-preferred-description store concept-id snomed/FullySpecifiedName) language-refset-ids)))

(s/fdef get-fully-specified-name
  :args (s/alt
          :default (s/cat :store ::store :concept-id :info.snomed.Concept/id)
          :specified (s/cat :store ::store :concept-id :info.snomed.Concept/id
                            :language-refset-ids (s/coll-of :info.snomed.Concept/id) :fallback? boolean?))
  :ret (s/nilable :info.snomed/Description))
(defn get-fully-specified-name
  "Return the fully specified name for the concept specified. If no language preferences are provided the first
  description of type FSN will be returned. If language preferences are provided, but there is no
  match *and* `fallback?` is true, then the first description of type FSN will be returned."
  ([store concept-id]
   (get-fully-specified-name store concept-id [] true))
  ([store concept-id language-refset-ids fallback?]
   (if-not (seq language-refset-ids)
     (first (filter snomed/is-fully-specified-name? (kv/get-concept-descriptions store concept-id)))
     (let [preferred (get-preferred-fully-specified-name store concept-id language-refset-ids)]
       (if (and fallback? (nil? preferred))
         (get-fully-specified-name store concept-id)
         preferred)))))

(s/fdef make-extended-concept
  :args (s/cat :store ::store :concept :info.snomed/Concept)
  :ret (s/nilable #(instance? ExtendedConcept %)))
(defn make-extended-concept [store concept]
  (when-not (map? concept)
    (throw (IllegalArgumentException. "invalid concept")))
  (let [concept-id (:id concept)
        descriptions (map #(merge % (get-description-refsets store (:id %)))
                          (kv/get-concept-descriptions store concept-id))
        parent-relationships (get-parent-relationships-expanded store concept-id)
        direct-parent-relationships (get-parent-relationships store concept-id)
        refsets (kv/get-component-refset-ids store concept-id)]
    (snomed/->ExtendedConcept
      concept
      descriptions
      parent-relationships
      direct-parent-relationships
      refsets)))

(s/fdef get-extended-concept
  :args (s/cat :store ::store :concept-id :info.snomed.Concept/id))
(defn get-extended-concept
  "Get an extended concept for the concept specified."
  [store concept-id]
  (make-extended-concept store (kv/get-concept store concept-id)))

(defn get-release-information
  "Returns descriptions representing the installed distributions.
  Ordering will be by date except that the description for the 'core' module
  will always be first.
  See https://confluence.ihtsdotools.org/display/DOCTIG/4.1.+Root+and+top-level+Concepts"
  [st]
  (let [root-synonyms (sort-by :effectiveTime (filter :active (kv/get-concept-descriptions st snomed/Root)))
        ;; get core date by looking for descriptions in 'CORE' module and get the latest
        core (last (filter #(= snomed/CoreModule (:moduleId %)) root-synonyms))
        others (filter #(not= snomed/CoreModule (:moduleId %)) root-synonyms)]
    (cons core others)))

(s/fdef history-profile
  :args (s/cat :store ::store :profile (s/? (s/nilable #{:HISTORY-MIN :HISTORY-MOD :HISTORY-MAX})))
  :ret (s/coll-of :info.snomed.Concept/id))
(defn history-profile
  "Return a sequence of reference set identifiers representing the history
  profile requested, or HISTORY-MAX, if not specified.
  See https://confluence.ihtsdotools.org/display/DOCECL/6.11+History+Supplements"
  ([st] (history-profile st :HISTORY-MAX))
  ([st profile]
   (case (or profile :HISTORY-MAX)
     :HISTORY-MIN [snomed/SameAsReferenceSet]
     :HISTORY-MOD [snomed/SameAsReferenceSet snomed/ReplacedByReferenceSet snomed/WasAReferenceSet snomed/PartiallyEquivalentToReferenceSet]
     :HISTORY-MAX (get-all-children st snomed/HistoricalAssociationReferenceSet))))

(defn source-historical
  "Return the requested historical associations for the component of types as
  defined by refset-ids, or all association refsets if omitted."
  ([st component-id]
   (source-historical st component-id (get-all-children st snomed/HistoricalAssociationReferenceSet)))
  ([st component-id refset-ids]
   (mapcat #(kv/get-source-association-referenced-components st component-id %) refset-ids)))

(s/fdef with-historical
  :args (s/cat :st ::store
               :concept-ids (s/coll-of :info.snomed.Concept/id)
               :refset-ids (s/? (s/coll-of :info.snomed.Concept/id))))
(defn with-historical
  "For a given sequence of concept identifiers, expand to include historical
  associations both backwards and forwards in time.

  For a currently active concept, this will return historic inactivated concepts
  in which it is the target. For a now inactive concept, this will return the
  active associations and their historic associations.

  By default, all types of historical associations except MoveTo and MovedFrom
  are included, but this is configurable. "
  ([st concept-ids]
   (with-historical st concept-ids
                    (disj (get-all-children st snomed/HistoricalAssociationReferenceSet) snomed/MovedToReferenceSet snomed/MovedFromReferenceSet)))
  ([st concept-ids historical-refset-ids]
   (let [refset-ids (set historical-refset-ids)
         future-ids (map :targetComponentId (filter #(refset-ids (:refsetId %)) (mapcat #(kv/get-component-refset-items st %) concept-ids)))
         modern-ids (set/union (set concept-ids) (set future-ids))
         historic-ids (set (mapcat #(source-historical st % refset-ids) modern-ids))]
     (set/union modern-ids historic-ids))))


(defn get-refset-descriptors
  [store refset-id]
  (->> (kv/get-component-refset-items store refset-id 900000000000456007)
       (sort-by :attributeOrder)))

(defn get-refset-descriptor-attribute-ids
  "Return a vector of attribute description concept ids for the given reference
  set."
  [store refset-id]
  (->> (kv/get-component-refset-items store refset-id 900000000000456007)
       (sort-by :attributeOrder)
       (mapv :attributeDescriptionId)))

(defn reify-refset-item
  "Reifies a refset item when possible, turning it into a concrete class.
  Suitable for use at import as long as refset descriptor refsets have already
  been imported."
  ([store item]
   (if (and (:active item) (seq (:fields item)) (instance? SimpleRefsetItem item))
     (let [attr-ids (get-refset-descriptor-attribute-ids store (:refsetId item))
           reifier (snomed/refset-reifier attr-ids)]
       (reifier item))
     item)))

(defn extended-refset-item
  "Merges a map of extended attributes to the specified reference set item.
  The attributes will be keyed based on information from the reference set
  descriptor information and known field names."
  [store {:keys [refsetId] :as item} & {:keys [attr-ids?] :or {attr-ids? true}}]
  (let [attr-ids (when attr-ids? (get-refset-descriptor-attribute-ids store refsetId))
        refset-field-names (or (kv/get-refset-field-names store refsetId) (throw (ex-info "No field names for reference set" {:refsetId refsetId})))
        field-names (map keyword (subvec refset-field-names 5))
        fields (subvec (snomed/->vec item) 5)]              ;; every reference set has 5 core attributes and then additional fields
    (merge
      (zipmap field-names fields)
      (when attr-ids? (zipmap attr-ids fields))
      (dissoc item :fields))))

(defn refset-counts
  "Returns a map of reference set counts keyed by type.
  This simply iterates over all stored items
  Example results from the UK distribution:
  ```
  {com.eldrix.hermes.snomed.SimpleMapRefsetItem        508618,      ;; ~4 %
   com.eldrix.hermes.snomed.LanguageRefsetItem         5829820,     ;; ~45 %
   com.eldrix.hermes.snomed.ExtendedMapRefsetItem      1858024,     ;; ~14 %
   com.eldrix.hermes.snomed.SimpleRefsetItem           1972073,     ;; ~15 %
   com.eldrix.hermes.snomed.AttributeValueRefsetItem   1261587,     ;; ~10 %
   com.eldrix.hermes.snomed.AssociationRefsetItem      1263064,     ;; ~10 %
   com.eldrix.hermes.snomed.RefsetDescriptorRefsetItem 1131}        ;; 0.01 %
   ```."
  [store]
  (let [ch (async/chan)]
    (stream-all-refset-items store ch)
    (loop [results {}]
      (if-let [item (async/<!! ch)]
        (recur (update results (type item) (fnil inc 0)))
        results))))

(defmulti write-batch
  "Write a batch of SNOMED components to the store. Returns nil.
  Parameters:
  - batch - a map containing :type and :data keys
  - store - SNOMED CT store implementation"
  :type)
(defmethod write-batch :info.snomed/Concept [batch store]
  (kv/write-concepts store (:data batch)))
(defmethod write-batch :info.snomed/Description [batch store]
  (kv/write-descriptions store (:data batch)))
(defmethod write-batch :info.snomed/Relationship [batch store]
  (kv/write-relationships store (:data batch)))
(defmethod write-batch :info.snomed/Refset [{:keys [headings data]} store]
  (let [items (map #(reify-refset-item store %) data)]
    (kv/write-refset-items store headings items)))

(defn write-batch-one-by-one
  "Write out a batch one item at a time. "
  [batch store]
  (doseq [b (map #(assoc batch :data [%]) (:data batch))]
    (try
      (write-batch b store)
      (catch Exception e
        (log/error "import error: failed to import data: " b)
        (throw (ex-info "Import error" {:batch (dissoc batch :data)
                                        :data  b :exception (Throwable->map e)}))))))

(defn write-batch-with-fallback [batch store]
  (try
    (write-batch batch store)
    (catch Exception _
      (write-batch-one-by-one batch store))))


(defn index [store]
  (kv/drop-relationships-index store)
  (kv/drop-refset-indices store)
  (kv/index-relationships store)
  (kv/index-refsets store))

(defmulti is-a? (fn [_store concept _parent-id] (class concept)))

(defmethod is-a? Long [store concept-id parent-id]
  (contains? (get-all-parents store concept-id) parent-id))

(defmethod is-a? Concept [store concept parent-id]
  (contains? (get-all-parents store (:id concept)) parent-id))

(defmethod is-a? ExtendedConcept [_ extended-concept parent-id]
  (contains? (get-in extended-concept [:parentRelationships snomed/IsA]) parent-id))

(defmulti has-property? (fn [_store concept _property-id _value-id] (class concept)))

(defmethod has-property? Long [store concept-id property-id value-id]
  (contains? (get-parent-relationships-of-type store concept-id property-id) value-id))

(defmethod has-property? Concept [store concept property-id value-id]
  (contains? (get-parent-relationships-of-type store (:id concept) property-id) value-id))

(defmethod has-property? ExtendedConcept [_ extended-concept property-id value-id]
  (contains? (get-in extended-concept [:parentRelationships property-id]) value-id))





