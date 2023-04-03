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
  "Store provides a store of SNOMED CT data with appropriate indices to permit
  fast lookup. It is currently implemented using two LMDB key value stores on
  the local filesystem."
  (:require [clojure.core.async :as a]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.impl.lmdb :as kv]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (com.eldrix.hermes.snomed Concept ExtendedConcept SimpleRefsetItem)
           (java.io Closeable)))

(s/def ::store any?)

;; this is a temporary approach to permit parallel evaluation of the
;; backing key stores

(def concept kv/concept)
(def concept-descriptions kv/concept-descriptions)
(def concrete-values kv/concrete-values)
(def installed-reference-sets kv/installed-reference-sets)
(def component-refset-items kv/component-refset-items)
(def stream-all-refset-items kv/stream-all-refset-items)
(def refset-field-names kv/refset-field-names)
(def refset-item kv/refset-item)
(def status kv/status)
(def component-refset-ids kv/component-refset-ids)
(def stream-all-concepts kv/stream-all-concepts)
(def description kv/description)
(def relationship kv/relationship)
(def compact kv/compact)
(def source-association-referenced-components kv/source-association-referenced-components)
(def source-associations kv/source-associations)
(defn open-store
  (^Closeable [] (kv/open-store))
  (^Closeable [f] (kv/open-store f))
  (^Closeable [f opts] (kv/open-store f opts)))

(def close kv/close)

(defn all-parents
  "Returns all parent concepts for the concept(s), including that concept or
  those concepts, by design.
   Parameters:
   - `store`
   - `concept-id-or-ids` a concept identifier, or collection of identifiers
   - `type-id`, defaults to 'IS-A' (116680003)."
  ([store concept-id-or-ids]
   (all-parents store concept-id-or-ids snomed/IsA))
  ([store concept-id-or-ids type-id]
   (loop [work (if (number? concept-id-or-ids) #{concept-id-or-ids} (set concept-id-or-ids))
          result (transient #{})]
     (if-not (seq work)
       (persistent! result)
       (let [id (first work)
             done-already? (contains? result id)
             parent-ids (if done-already? () (map last (kv/raw-parent-relationships store id type-id)))]
         (recur (apply conj (rest work) parent-ids)
                (conj! result id)))))))

(defn parent-relationships
  "Returns a map of the parent relationships, with each value a set of
  identifiers representing the targets of each relationship.
  Returns a map:
  key: concept-id of the relationship type (e.g. identifier representing finding site)
  value: a set of concept identifiers for that property.

  See `get-parent-relationships-expanded` to get each target expanded via
  transitive closure tables."
  [store concept-id]
  (->> (kv/raw-parent-relationships store concept-id)
       (reduce (fn [acc v]
                 (update acc (v 1) (fnil conj #{}) (v 3))) {}))) ;; tuple [concept-id type-id group destination-id] so return indices 1+3

(defn parent-relationships-of-type
  "Returns a set of identifiers representing the parent relationships of the
  specified type of the specified concept."
  [store concept-id type-concept-id]
  (->> (kv/raw-parent-relationships store concept-id type-concept-id)
       (reduce (fn [acc v] (conj acc (v 3))) #{})))

(defn parent-relationships-of-types
  [store concept-id type-concept-ids]
  (set (mapcat #(parent-relationships-of-type store concept-id %) type-concept-ids)))

(defn parent-relationships-expanded
  "Returns a map of the parent relationships, with each value a set of
  identifiers representing the targets and their transitive closure tables. This
  makes it trivial to build queries that find all concepts with, for example, a
  common finding site at any level of granularity."
  ([store concept-id]
   (->> (kv/raw-parent-relationships store concept-id)
        (reduce (fn [acc [_source-id type-id _group target-id]]
                  (update acc type-id conj target-id)) {})
        (reduce-kv (fn [acc k v]
                     (assoc acc k (all-parents store v))) {})))
  ([store concept-id type-id]
   (->> (kv/raw-parent-relationships store concept-id type-id)
        (reduce (fn [acc [_source-id type-id _group target-id]]
                  (update acc type-id conj target-id)) {})
        (reduce-kv (fn [acc k v]
                     (assoc acc k (all-parents store v))) {}))))

(defn proximal-parent-ids
  "Returns a sequence of identifiers for the proximal parents of the given type,
  defaulting to the 'IS-A' relationship if no type is given."
  ([store concept-id type-concept-id]
   (map peek (kv/raw-parent-relationships store concept-id type-concept-id)))
  ([store concept-id]
   (map peek (kv/raw-parent-relationships store concept-id snomed/IsA))))

(defn child-relationships-of-type
  "Returns a set of identifiers representing the parent relationships of the
  specified type of the specified concept."
  [store concept-id type-concept-id]
  (->> (kv/raw-child-relationships store concept-id type-concept-id)
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
  (loop [parent-ids (map last (kv/raw-parent-relationships store concept-id snomed/IsA))
         results []]
    (let [parent (first parent-ids)]
      (if-not parent
        (if (seq results) (map #(conj % concept-id) results) (list (list concept-id)))
        (recur (rest parent-ids)
               (concat results (paths-to-root store parent)))))))

(defn all-children
  "Returns all child concepts for the concept.
  It takes 3500 milliseconds on my 2013 laptop to return all child concepts
  of the root SNOMED CT concept, so this should only be used for more granular
  concepts generally, or used asynchronously / via streaming.
   
   Parameters:
   - store
   - `concept-id`
   - `type-id`, defaults to 'IS-A' (116680003)."
  ([store concept-id] (all-children store concept-id snomed/IsA))
  ([store concept-id type-id]
   (loop [work #{concept-id}
          result #{}]
     (if-not (seq work)
       result
       (let [id (first work)
             done-already? (contains? result id)
             children (if done-already? () (map last (kv/raw-child-relationships store id type-id)))]
         (recur (apply conj (rest work) children)
                (conj result id)))))))

(defn properties-by-group
  "Returns a concept's properties as a map of group-id to a map of type-id
  to a set of target identifiers.
  e.g.
  ```
  (properties-by-group store 24700007)
  =>
  {0 {116680003 #{6118003 414029004 39367000}},
   1 {116676008 #{32693004}, 363698007 #{21483005}, 370135005 #{769247005}},
   2 {116676008 #{409774005}, 363698007 #{21483005}, 370135005 #{769247005}}}
  ```"
  [store concept-id]
  (->> (kv/raw-parent-relationships store concept-id)
       (reduce (fn [acc [_ type-id group-id target-id]]
                 (update-in acc [group-id type-id] (fnil conj #{}) target-id)) {})))

(defn properties-by-type
  "Return a concept's properties as a map of type-id to a map of group-id to a
  set of target-ids.
  e.g.
  ```
  (properties-by-type store 24700007)
  =>
  {116676008 {1 #{32693004}, 2 #{409774005}},
   116680003 {0 #{6118003 414029004 39367000}},
   363698007 {1 #{21483005}, 2 #{21483005}},
   370135005 {1 #{769247005}, 2 #{769247005}}}
  ```"
  [store concept-id]
  (->> (kv/raw-parent-relationships store concept-id)
       (reduce (fn [acc [_ type-id group-id target-id]]
                 (update-in acc [type-id group-id] (fnil conj #{}) target-id)) {})))

(defn leaves
  "Returns the subset of the specified `concept-ids` such that no member of the
  subset is subsumed by another member.
  
   Parameters:
  - concept-ids  : a collection of concept identifiers"
  [store concept-ids]
  (set/difference (set concept-ids) (into #{} (mapcat #(disj (all-parents store %) %) concept-ids))))

(defn transitive-synonyms
  "Returns all synonyms of the specified concept, including those of its
  descendants."
  ([store concept-id] (transitive-synonyms store concept-id {}))
  ([store concept-id {:keys [include-inactive?]}]
   (let [concepts (conj (all-children store concept-id) concept-id)
         ds (mapcat (partial kv/concept-descriptions store) concepts)
         ds' (if include-inactive? ds (filter :active ds))]
     (filter #(= snomed/Synonym (:typeId %)) ds'))))


(defn description-refsets
  "Get the refsets and language applicability for a description.
  
   Returns a map containing:
  - refsets       : a set of refsets to which this description is a member
  - preferredIn  : refsets for which this description is preferred
  - acceptableIn : refsets for which this description is acceptable.

  Example:
  ``` 
  (map #(merge % (description-refsets store (:id %)))
       (concept-descriptions store 24700007))
  ```"
  [store description-id]
  (let [refset-items (kv/component-refset-items store description-id)
        refsets (into #{} (map :refsetId) refset-items)
        preferred-in (into #{}
                           (comp (filter #(= snomed/Preferred (:acceptabilityId %))) (map :refsetId))
                           refset-items)
        acceptable-in (into #{}
                            (comp (filter #(= snomed/Acceptable (:acceptabilityId %))) (map :refsetId))
                            refset-items)]
    {:refsets      refsets
     :preferredIn  preferred-in
     :acceptableIn acceptable-in}))

(s/fdef preferred-description
  :args (s/cat :store ::store :concept-id :info.snomed.Concept/id :description-type-id :info.snomed.Concept/id :language-refset-id :info.snomed.Concept/id)
  :ret (s/nilable :info.snomed/Description))
(defn preferred-description
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
  (let [descriptions (->> (kv/concept-descriptions store concept-id)
                          (filter #(and (:active %) (= description-type-id (:typeId %)))))
        item (->> (mapcat #(kv/component-refset-items store (:id %) language-refset-id) descriptions)
                  (filter #(= snomed/Preferred (:acceptabilityId %))) ;; only PREFERRED
                  (first))
        preferred (:referencedComponentId item)]
    (when preferred (kv/description store concept-id preferred))))

(defn preferred-synonym
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
  ([store concept-id language-refset-ids]
   (when-let [refset-id (first language-refset-ids)]
     (let [d (preferred-description store concept-id snomed/Synonym refset-id)]
       (or d (recur store concept-id (rest language-refset-ids)))))))

(s/fdef preferred-fully-specified-name
  :args (s/cat :store ::store
               :concept-id :info.snomed.Concept/id
               :language-refset-ids (s/coll-of :info.snomed.Concept/id))
  :ret (s/nilable :info.snomed/Description))
(defn preferred-fully-specified-name [store concept-id language-refset-ids]
  (when-let [refset-id (first language-refset-ids)]
    (let [d (preferred-description store concept-id snomed/FullySpecifiedName refset-id)]
      (or d (recur store concept-id (rest language-refset-ids))))))

(s/fdef fully-specified-name
  :args (s/alt
          :default (s/cat :store ::store :concept-id :info.snomed.Concept/id)
          :specified (s/cat :store ::store :concept-id :info.snomed.Concept/id
                            :language-refset-ids (s/coll-of :info.snomed.Concept/id) :fallback? boolean?))
  :ret (s/nilable :info.snomed/Description))
(defn fully-specified-name
  "Return the fully specified name for the concept specified. If no language preferences are provided the first
  description of type FSN will be returned. If language preferences are provided, but there is no
  match *and* `fallback?` is true, then the first description of type FSN will be returned."
  ([store concept-id]
   (fully-specified-name store concept-id [] true))
  ([store concept-id language-refset-ids fallback?]
   (if-not (seq language-refset-ids)
     (first (filter snomed/fully-specified-name? (kv/concept-descriptions store concept-id)))
     (let [preferred (preferred-fully-specified-name store concept-id language-refset-ids)]
       (if (and fallback? (nil? preferred))
         (fully-specified-name store concept-id)
         preferred)))))

(s/fdef make-extended-concept
  :args (s/cat :store ::store :concept :info.snomed/Concept)
  :ret (s/nilable #(instance? ExtendedConcept %)))
(defn make-extended-concept [store {concept-id :id :as c}]
  (let [descriptions (map #(merge % (description-refsets store (:id %)))
                          (kv/concept-descriptions store concept-id))
        parent-rels (parent-relationships-expanded store concept-id)
        direct-parent-rels (parent-relationships store concept-id)
        concrete (concrete-values store concept-id)
        refsets (kv/component-refset-ids store concept-id)]
    (snomed/->ExtendedConcept c descriptions parent-rels direct-parent-rels concrete refsets)))

(s/fdef extended-concept
  :args (s/cat :store ::store :concept-id :info.snomed.Concept/id))
(defn extended-concept
  "Get an extended concept for the concept specified."
  [store concept-id]
  (make-extended-concept store (kv/concept store concept-id)))

(defn release-information
  "Returns descriptions representing the installed distributions.
  Ordering will be by date except that the description for the 'core' module
  will always be first.
  See https://confluence.ihtsdotools.org/display/DOCTIG/4.1.+Root+and+top-level+Concepts"
  [st]
  (let [root-synonyms (sort-by :effectiveTime (filter :active (kv/concept-descriptions st snomed/Root)))
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
     :HISTORY-MAX (all-children st snomed/HistoricalAssociationReferenceSet))))

(defn source-historical
  "Return the requested historical associations for the component of types as
  defined by refset-ids, or all association refsets if omitted."
  ([st component-id]
   (source-historical st component-id (all-children st snomed/HistoricalAssociationReferenceSet)))
  ([st component-id refset-ids]
   (mapcat #(kv/source-association-referenced-components st component-id %) refset-ids)))

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

  By default, all active types of historical associations except MoveTo and
  MovedFrom are included, but this is configurable. "
  ([st concept-ids]
   (with-historical st concept-ids
                    (disj (all-children st snomed/HistoricalAssociationReferenceSet) snomed/MovedToReferenceSet snomed/MovedFromReferenceSet)))
  ([st concept-ids historical-refset-ids]
   (let [refset-ids (set historical-refset-ids)
         future-ids (map :targetComponentId (filter #(refset-ids (:refsetId %)) (mapcat #(kv/component-refset-items st %) concept-ids)))
         modern-ids (set/union (set concept-ids) (set future-ids))
         historic-ids (set (mapcat #(source-historical st % refset-ids) modern-ids))]
     (set/union modern-ids historic-ids))))

(defn refset-descriptors
  [store refset-id]
  (->> (kv/component-refset-items store refset-id 900000000000456007)
       (sort-by :attributeOrder)))

(defn refset-descriptor-attribute-ids
  "Return a vector of attribute description concept ids for the given reference
  set."
  [store refset-id]
  (->> (kv/component-refset-items store refset-id 900000000000456007)
       (sort-by :attributeOrder)
       (mapv :attributeDescriptionId)))

(defn reify-refset-item
  "Reifies a refset item when possible, turning it into a concrete class.
  Suitable for use at import as long as refset descriptor refsets have already
  been imported."
  ([store item]
   (if (and (:active item) (seq (:fields item)) (instance? SimpleRefsetItem item))
     (let [attr-ids (refset-descriptor-attribute-ids store (:refsetId item))
           reifier (snomed/refset-reifier attr-ids)]
       (reifier item))
     item)))

(defn extended-refset-item
  "Merges a map of extended attributes to the specified reference set item.
  The attributes will be keyed based on information from the reference set
  descriptor information and known field names."
  [store {:keys [refsetId] :as item} & {:keys [attr-ids?] :or {attr-ids? true}}]
  (let [attr-ids (when attr-ids? (refset-descriptor-attribute-ids store refsetId))
        all-field-names (or (refset-field-names store refsetId) (throw (ex-info "No field names for reference set" {:refsetId refsetId})))
        field-names (mapv keyword (subvec all-field-names 5))
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
  (let [ch (a/chan)]
    (stream-all-refset-items store ch)
    (loop [results {}]
      (if-let [item (a/<!! ch)]
        (recur (update results (type item) (fnil inc 0)))
        results))))

(defmulti write-batch
  "Write a batch of SNOMED components to the store. Returns nil.
  Parameters:
  - store - SNOMED CT store implementation
  - batch - a map containing :type, :headings and :data keys.
  The implementation will be chosen via the :type of the batch."
  (fn [_store batch] (:type batch)))
(defmethod write-batch :info.snomed/Concept [store {data :data}]
  (kv/write-concepts store data))
(defmethod write-batch :info.snomed/Description [store {data :data}]
  (kv/write-descriptions store data))
(defmethod write-batch :info.snomed/Relationship [store {data :data}]
  (kv/write-relationships store data))
(defmethod write-batch :info.snomed/ConcreteValue [store {data :data}]
  (kv/write-concrete-values store data))
(defmethod write-batch :info.snomed/Refset [store {:keys [headings data]}]
  (let [items (map #(reify-refset-item store %) data)]
    (kv/write-refset-items store headings items)))

(defn write-batch-one-by-one
  "Write out a batch one item at a time. "
  [store batch]
  (doseq [b (map #(assoc batch :data [%]) (:data batch))]
    (try
      (write-batch store b)
      (catch Exception e
        (log/error "import error: failed to import data: " b)
        (throw (ex-info "Import error" {:batch (dissoc batch :data)
                                        :data  b :exception (Throwable->map e)}))))))

(defn write-batch-with-fallback
  "Write a batch of data to the store. If there is an error, the write is
  retried one-by-one so that the parsing error can be identified down to an
  individual item."
  [store batch]
  (try
    (write-batch store batch)
    (catch Exception _
      (write-batch-one-by-one store batch))))

(defn index [store]
  (kv/drop-relationships-index store)
  (kv/drop-refset-indices store)
  (kv/index-relationships store)
  (kv/index-refsets store))

(defmulti is-a? (fn [_store x _parent-id] (class x)))

(defmethod is-a? Long [store concept-id parent-id]
  (contains? (all-parents store concept-id) parent-id))

(defmethod is-a? Concept [store {concept-id :id} parent-id]
  (contains? (all-parents store concept-id) parent-id))

(defmethod is-a? ExtendedConcept [_ ec parent-id]
  (contains? (get-in ec [:parentRelationships snomed/IsA]) parent-id))

(defmulti has-property? (fn [_store x _property-id _value-id] (class x)))

(defmethod has-property? Long [store concept-id property-id value-id]
  (contains? (parent-relationships-of-type store concept-id property-id) value-id))

(defmethod has-property? Concept [store {concept-id :id} property-id value-id]
  (contains? (parent-relationships-of-type store concept-id property-id) value-id))

(defmethod has-property? ExtendedConcept [_ ec property-id value-id]
  (contains? (get-in ec [:parentRelationships property-id]) value-id))





