; Copyright (c) 2020-2023 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns ^:no-doc com.eldrix.hermes.impl.store
  "Store provides a store of SNOMED CT data with appropriate indices to permit
  fast lookup. It is currently implemented using two LMDB key value stores on
  the local filesystem."
  (:require [clojure.core.async :as a]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.impl.lmdb :as lmdb]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (com.eldrix.hermes.impl.lmdb LmdbStore)
           (com.eldrix.hermes.snomed Concept Description ExtendedConcept SimpleRefsetItem)
           (java.io Closeable)
           (java.util Collection)))

(s/def ::store any?)

;; Functions that delegate directly to lmdb (transaction managed internally)
(def concept lmdb/concept)
(def installed-reference-sets lmdb/installed-reference-sets)
(def stream-all-refset-items lmdb/stream-all-refset-items)
(def refset-field-names lmdb/refset-field-names)
(def refset-item lmdb/refset-item)
(def status lmdb/status)
(def stream-all-concepts lmdb/stream-all-concepts)
(def description lmdb/description)
(def relationship lmdb/relationship)
(def compact-and-close lmdb/compact-and-close)
(defn open-store
  (^Closeable [] (lmdb/open-store))
  (^Closeable [f] (lmdb/open-store f))
  (^Closeable [f opts] (lmdb/open-store f opts)))
(def close lmdb/close)


(defn concept-descriptions
  [^LmdbStore store concept-id]
  (lmdb/with-txn [txn store :core]
    (lmdb/concept-descriptions* store txn concept-id)))

(defn concrete-values
  [^LmdbStore store concept-id]
  (lmdb/with-txn [txn store :core]
    (lmdb/concrete-values* store txn concept-id)))

(defn component-refset-items
  "Get the refset items for the given component, optionally limited to the
  refset specified."
  ([^LmdbStore store component-id]
   (lmdb/with-txn [core-txn store :core]
     (lmdb/with-txn [refsets-txn store :refsets]
       (lmdb/component-refset-items* store core-txn refsets-txn component-id))))
  ([^LmdbStore store component-id refset-id]
   (lmdb/with-txn [core-txn store :core]
     (lmdb/with-txn [refsets-txn store :refsets]
       (lmdb/component-refset-items* store core-txn refsets-txn component-id refset-id)))))

(defn component-refset-ids
  "Return a set of refset-ids to which this component belongs."
  [^LmdbStore store component-id]
  (lmdb/with-txn [txn store :core]
    (lmdb/component-refset-ids* store txn component-id)))

(defn component-in-refsets?
  "Is the given component a member of the reference sets specified?"
  [^LmdbStore store component-id refset-ids]
  (lmdb/with-txn [txn store :core]
    (lmdb/component-in-refsets?* store txn component-id refset-ids)))

(defn source-association-referenced-components
  [^LmdbStore store component-id refset-id]
  (lmdb/with-txn [txn store :core]
    (lmdb/source-association-referenced-components* store txn component-id refset-id)))

(defn all-parents*
  "Returns all parent concepts for the concept(s), including that concept or
  those concepts, by design. Uses an existing core read transaction."
  [store txn concept-id-or-ids type-id]
  (loop [work (if (number? concept-id-or-ids) #{concept-id-or-ids} (set concept-id-or-ids))
         result (transient #{})]
    (if-not (seq work)
      (persistent! result)
      (let [id (first work)
            done-already? (contains? result id)
            parent-ids (if done-already? () (map last (lmdb/raw-parent-relationships* store txn id type-id)))]
        (recur (apply conj (rest work) parent-ids)
               (conj! result id))))))

(defn all-parents
  "Returns all parent concepts for the concept(s), including that concept or
  those concepts, by design.
   Parameters:
   - `store`
   - `concept-id-or-ids` a concept identifier, or collection of identifiers
   - `type-id`, defaults to 'IS-A' (116680003)."
  ([store concept-id-or-ids]
   (all-parents store concept-id-or-ids snomed/IsA))
  ([^LmdbStore store concept-id-or-ids type-id]
   (lmdb/with-txn [txn store :core]
     (all-parents* store txn concept-id-or-ids type-id))))

(defn parent-relationships*
  "Returns a map of the parent relationships using an existing core read
  transaction. See [[parent-relationships]]."
  [store txn concept-id]
  (->> (lmdb/raw-parent-relationships* store txn concept-id)
       (reduce (fn [acc v]
                 (update acc (v 1) (fnil conj #{}) (v 3))) {}))) ;; tuple [concept-id type-id group destination-id] so return indices 1+3

(defn parent-relationships
  "Returns a map of the parent relationships, with each value a set of
  identifiers representing the targets of each relationship.
  Returns a map:
  key: concept-id of the relationship type (e.g. identifier representing finding site)
  value: a set of concept identifiers for that property.

  See `get-parent-relationships-expanded` to get each target expanded via
  transitive closure tables."
  [^LmdbStore store concept-id]
  (lmdb/with-txn [txn store :core]
    (parent-relationships* store txn concept-id)))

(defn parent-relationships-of-type*
  "Returns a set of identifiers representing the parent relationships of the
  specified type, using an existing core read transaction."
  [store txn concept-id type-concept-id]
  (->> (lmdb/raw-parent-relationships* store txn concept-id type-concept-id)
       (reduce (fn [acc v] (conj acc (v 3))) #{})))

(defn parent-relationships-of-type
  "Returns a set of identifiers representing the parent relationships of the
  specified type of the specified concept."
  [^LmdbStore store concept-id type-concept-id]
  (lmdb/with-txn [txn store :core]
    (parent-relationships-of-type* store txn concept-id type-concept-id)))

(defn parent-relationships-of-types
  [^LmdbStore store concept-id type-concept-ids]
  (lmdb/with-txn [txn store :core]
    (into #{} (mapcat #(parent-relationships-of-type* store txn concept-id %)) type-concept-ids)))

(defn parent-relationships-expanded*
  "Returns a map of the parent relationships expanded via transitive closure,
  using an existing core read transaction. See [[parent-relationships-expanded]]."
  ([store txn concept-id]
   (->> (lmdb/raw-parent-relationships* store txn concept-id)
        (reduce (fn [acc [_source-id type-id _group target-id]]
                  (update acc type-id conj target-id)) {})
        (reduce-kv (fn [acc k v]
                     (assoc acc k (all-parents* store txn v snomed/IsA))) {})))
  ([store txn concept-id type-id]
   (->> (lmdb/raw-parent-relationships* store txn concept-id type-id)
        (reduce (fn [acc [_source-id type-id _group target-id]]
                  (update acc type-id conj target-id)) {})
        (reduce-kv (fn [acc k v]
                     (assoc acc k (all-parents* store txn v snomed/IsA))) {}))))

(defn parent-relationships-expanded
  "Returns a map of the parent relationships, with each value a set of
  identifiers representing the targets and their transitive closure tables. This
  makes it trivial to build queries that find all concepts with, for example, a
  common finding site at any level of granularity."
  ([^LmdbStore store concept-id]
   (lmdb/with-txn [txn store :core]
     (parent-relationships-expanded* store txn concept-id)))
  ([^LmdbStore store concept-id type-id]
   (lmdb/with-txn [txn store :core]
     (parent-relationships-expanded* store txn concept-id type-id))))

(defn proximal-parent-ids
  "Returns a sequence of identifiers for the proximal parents of the given type,
  defaulting to the 'IS-A' relationship if no type is given."
  ([^LmdbStore store concept-id type-concept-id]
   (lmdb/with-txn [txn store :core]
     (map peek (lmdb/raw-parent-relationships* store txn concept-id type-concept-id))))
  ([store concept-id]
   (proximal-parent-ids store concept-id snomed/IsA)))

(defn child-relationships-of-types*
  "Returns a set of source concept-ids for child relationships of the given
  destination concept(s), using an existing core read transaction.
  `concept-id-or-ids` is a single concept-id (destination) or a collection of
  destinations. `type-concept-ids` is a set or predicate; a type filter is
  required — callers must explicitly decide which relationship types to
  include."
  [^LmdbStore store txn concept-id-or-ids type-concept-ids]
  (let [ids (if (number? concept-id-or-ids) [concept-id-or-ids] concept-id-or-ids)]
    (into #{}
          (comp (mapcat #(lmdb/raw-child-relationships* store txn %))
                (filter #(type-concept-ids (% 1)))
                (map #(% 3)))
          ids)))

(defn child-relationships-of-types
  "A version of [[child-relationships-of-types*]] that manages transactions."
  [^LmdbStore store concept-id-or-ids type-concept-ids]
  (lmdb/with-txn [txn store :core]
    (child-relationships-of-types* store txn concept-id-or-ids type-concept-ids)))

(defn child-relationships-of-type
  "Returns a set of identifiers representing the child relationships of the
  specified type of the specified concept."
  [^LmdbStore store concept-id type-concept-id]
  (lmdb/with-txn [txn store :core]
    (->> (lmdb/raw-child-relationships* store txn concept-id type-concept-id)
         (reduce (fn [acc v] (conj acc (v 3))) #{}))))

(defn paths-to-root*
  "Return paths from concept to root using an existing core read transaction.
  See [[paths-to-root]]."
  [store txn concept-id]
  (loop [parent-ids (map last (lmdb/raw-parent-relationships* store txn concept-id snomed/IsA))
         results []]
    (let [parent (first parent-ids)]
      (if-not parent
        (if (seq results) (map #(conj % concept-id) results) (list (list concept-id)))
        (recur (rest parent-ids)
               (into results (paths-to-root* store txn parent)))))))

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
  [^LmdbStore store concept-id]
  (lmdb/with-txn [txn store :core]
    (paths-to-root* store txn concept-id)))

(defn all-children*
  "Returns all child concepts using an existing core read transaction.
  See [[all-children]]."
  [store txn concept-id type-id]
  (loop [work #{concept-id}
         result #{}]
    (if-not (seq work)
      result
      (let [id (first work)
            done-already? (contains? result id)
            children (if done-already? () (map last (lmdb/raw-child-relationships* store txn id type-id)))]
        (recur (apply conj (rest work) children)
               (conj result id))))))

(defn all-children
  "Returns all child concepts for the concept.
  It takes 2300 milliseconds on my 2021 M1 Pro laptop to return all child concepts
  of the root SNOMED CT concept, so this should only be used for more granular
  concepts generally, or used asynchronously / via streaming.

   Parameters:
   - store
   - `concept-id`
   - `type-id`, defaults to 'IS-A' (116680003)."
  ([store concept-id] (all-children store concept-id snomed/IsA))
  ([^LmdbStore store concept-id type-id]
   (lmdb/with-txn [txn store :core]
     (all-children* store txn concept-id type-id))))

(defn properties-by-group*
  "Returns a concept's properties grouped by group-id, using an existing core
  read transaction. See [[properties-by-group]]."
  [store txn concept-id]
  (->> (lmdb/raw-parent-relationships* store txn concept-id)
       (reduce (fn [acc [_ type-id group-id target-id]]
                 (update-in acc [group-id type-id] (fnil conj #{}) target-id)) {})))

(defn properties-by-group
  "Returns a concept's properties as a map of group-id to a map of type-id
  to a set of target identifiers. Results do not include concrete values.
  e.g.
  ```
  (properties-by-group store 24700007)
  =>
  {0 {116680003 #{6118003 414029004 39367000}},
   1 {116676008 #{32693004}, 363698007 #{21483005}, 370135005 #{769247005}},
   2 {116676008 #{409774005}, 363698007 #{21483005}, 370135005 #{769247005}}}
  ```"
  [^LmdbStore store concept-id]
  (lmdb/with-txn [txn store :core]
    (properties-by-group* store txn concept-id)))

(defn properties-by-group-expanded*
  "Returns properties grouped by group-id with values expanded via transitive
  closure, using an existing core read transaction."
  [store txn concept-id]
  (update-vals (properties-by-group* store txn concept-id)
               (fn [group] (update-vals group #(all-parents* store txn % snomed/IsA)))))

(defn properties-by-group-expanded
  [^LmdbStore store concept-id]
  (lmdb/with-txn [txn store :core]
    (properties-by-group-expanded* store txn concept-id)))

(defn properties
  "Returns a concept's properties, including concrete values. Ungrouped
  properties are returned under key '0', with other groups returned with
  non-zero keys. There is no other intrinsic meaning to the group identifier.

  e.g. for lamotrigine:
  ```
  (properties store 1231295007)
  =>
  {0 {116680003 #{779653004}, 411116001 #{385060002}, 763032000 #{732936001},
      766939001 #{773862006}, 1142139005 #{\"#1\"}},
   1 {732943007 #{387562000}, 732945000 #{258684004}, 732947008 #{732936001},
      762949000 #{387562000}, 1142135004 #{\"#250\"}, 1142136003 #{\"#1\"}}}
  ```
  See https://confluence.ihtsdotools.org/display/DOCRELFMT/4.2.3+Relationship+File+Specification
    \"The relationshipGroup field is used to group relationships with the same
    sourceId field into one or more logical sets. A relationship with a
    relationshipGroup field value of '0' is considered not to be grouped. All
    relationships with the same sourceId and non-zero relationshipGroup are
    considered to be logically grouped.\".

  Note: All values are returned as sets. It is only through access to the MRCM
  that cardinality rules could be applied safely, but that requires support for
  ECL, which is not available at this low level of the library."
  [^LmdbStore store concept-id]
  (lmdb/with-txn [txn store :core]
    (reduce (fn [acc {:keys [typeId relationshipGroup value]}]
              (update-in acc [relationshipGroup typeId] (fnil conj #{}) value))
            (properties-by-group* store txn concept-id)
            (lmdb/concrete-values* store txn concept-id))))

(defn properties-expanded
  "Return all properties grouped by relationship group, with results containing
  concept identifiers expanded to include transitive relationships."
  [^LmdbStore store concept-id]
  (lmdb/with-txn [txn store :core]
    (reduce (fn [acc {:keys [typeId relationshipGroup value]}]
              (update-in acc [relationshipGroup typeId] (fnil conj #{}) value))
            (properties-by-group-expanded* store txn concept-id)
            (lmdb/concrete-values* store txn concept-id))))

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
  [^LmdbStore store concept-id]
  (lmdb/with-txn [txn store :core]
    (->> (lmdb/raw-parent-relationships* store txn concept-id)
         (reduce (fn [acc [_ type-id group-id target-id]]
                   (update-in acc [type-id group-id] (fnil conj #{}) target-id)) {}))))

(defn leaves
  "Returns the subset of the specified `concept-ids` such that no member of the
  subset is subsumed by another member, choosing the most *specific* concepts. 
  As such, the result will be concepts that have no descendants within that 
  result. If the SNOMED hierarchy is displayed as a tree with the root at the
  top, then this returns the 'bottom'-most concepts within the set.
  
  Also see [[top-leaves]] although that is not performant and is only used for
  testing. 

  This is essentially implementing 'bottom-of-set' as per https://confluence.ihtsdotools.org/display/DOCECL/6.12+Top+and+Bottom.

  Parameters:
  - concept-ids  : a collection of concept identifiers."
  ^Collection [^LmdbStore store concept-ids]
  (lmdb/with-txn [txn store :core]
    (set/difference                                         ;; remove all parents of the concepts from the set
     (set concept-ids)
     (into #{} (mapcat #(disj (all-parents* store txn % snomed/IsA) %)) concept-ids))))

(defn top-leaves
  "Returns the subset of the specified `concept-ids` such that no member of the
  subset is subsumed by another member, choosing the most *general* concepts.
  As such, the result will be concepts that have no ancestors within that 
  result. If the SNOMED hierarchy is drawn as a tree with the root at the top,
  then this returns the 'top'-most concepts within the set.

  Also see [[leaves]] which does the same but returns the most specific
  concepts ('bottom' if the SNOMED CT hierarchy is displayed as a tree with 
  the root at the top).
  
  This is essentially implementing 'top-of-set' as per https://confluence.ihtsdotools.org/display/DOCECL/6.12+Top+and+Bottom
 
  WARNING: this implementation is slow and simply the reverse of `leaves`. 
  It is much better (3x faster) to implement within Lucene, but this is made
  available for validation purposes.

  Parameters:
  - concept-ids : a collection of concept identifiers."
  ^Collection [^LmdbStore store concept-ids]
  (lmdb/with-txn [txn store :core]
    (set/difference                                         ;; remove all children of the concepts from the set
     (set concept-ids)
     (into #{} (mapcat #(disj (all-children* store txn % snomed/IsA) %)) concept-ids))))

(defn transitive-synonyms
  "Returns all synonyms of the specified concept, including those of its
  descendants."
  ([store concept-id] (transitive-synonyms store concept-id {}))
  ([^LmdbStore store concept-id {:keys [include-inactive?]}]
   (lmdb/with-txn [txn store :core]
     (let [concepts (conj (all-children* store txn concept-id snomed/IsA) concept-id)
           ds (mapcat #(lmdb/concept-descriptions* store txn %) concepts)
           ds' (if include-inactive? ds (filter :active ds))]
       (filterv #(= snomed/Synonym (:typeId %)) ds')))))

(defn description-refsets*
  "Get the refsets and language applicability for a description using existing
  transactions. See [[description-refsets]]."
  [store core-txn refsets-txn description-id]
  (let [refset-items (lmdb/component-refset-items* store core-txn refsets-txn description-id)
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
  [^LmdbStore store description-id]
  (lmdb/with-txn [core-txn store :core]
    (lmdb/with-txn [refsets-txn store :refsets]
      (description-refsets* store core-txn refsets-txn description-id))))


(s/fdef language-synonyms
  :args (s/cat :store ::store :concept-id int? :language-refset-ids (s/coll-of int?))
  :ret (s/coll-of :info.snomed/Description))
(defn language-synonyms
  "Return synonyms for the concept that are active and present in the language
  reference sets specified. This means that they are either preferred or
  acceptable in those languages."
  [^LmdbStore store concept-id language-refset-ids]
  (lmdb/with-txn [txn store :core]
    (filterv #(and (:active %)
                   (= snomed/Synonym (:typeId %))
                   (lmdb/component-in-refsets?* store txn (:id %) language-refset-ids))
             (lmdb/concept-descriptions* store txn concept-id))))

(defn- preferred-description*
  "Return the preferred description for the concept specified as defined by
  the language reference set specified for the description type, using existing
  transactions."
  ^Description [^LmdbStore store core-txn refsets-txn concept-id description-type-id language-refset-id]
  (loop [ds (lmdb/concept-descriptions* store core-txn concept-id)]
    (when-let [d (first ds)]
      (if (and (:active d)
               (= description-type-id (:typeId d))
               (some #(= snomed/Preferred (:acceptabilityId %))
                     (lmdb/component-refset-items* store core-txn refsets-txn (:id d) language-refset-id)))
        d
        (recur (rest ds))))))

(s/fdef preferred-description
  :args (s/cat :store ::store :concept-id int? :description-type-id int? :language-refset-id int?)
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
  ^Description [^LmdbStore store concept-id description-type-id language-refset-id]
  (lmdb/with-txn [core-txn store :core]
    (lmdb/with-txn [refsets-txn store :refsets]
      (preferred-description* store core-txn refsets-txn concept-id description-type-id language-refset-id))))

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
  ([^LmdbStore store concept-id language-refset-ids]
   (lmdb/with-txn [core-txn store :core]
     (lmdb/with-txn [refsets-txn store :refsets]
       (loop [refset-ids language-refset-ids]
         (when-let [refset-id (first refset-ids)]
           (or (preferred-description* store core-txn refsets-txn concept-id snomed/Synonym refset-id)
               (recur (rest refset-ids)))))))))

(s/fdef preferred-fully-specified-name
  :args (s/cat :store ::store :concept-id int? :language-refset-ids (s/coll-of int?))
  :ret (s/nilable :info.snomed/Description))
(defn preferred-fully-specified-name [^LmdbStore store concept-id language-refset-ids]
  (lmdb/with-txn [core-txn store :core]
    (lmdb/with-txn [refsets-txn store :refsets]
      (loop [refset-ids language-refset-ids]
        (when-let [refset-id (first refset-ids)]
          (or (preferred-description* store core-txn refsets-txn concept-id snomed/FullySpecifiedName refset-id)
              (recur (rest refset-ids))))))))

(s/fdef fully-specified-name
  :args (s/alt
         :default (s/cat :store ::store :concept-id int?)
         :specified (s/cat :store ::store :concept-id int?
                           :language-refset-ids (s/coll-of int?) :fallback? boolean?))
  :ret (s/nilable :info.snomed/Description))
(defn ^:deprecated fully-specified-name
  "DEPRECATED: Use [[preferred-fully-specified-name]] instead.

  Return the fully specified name for the concept specified. If no language preferences are provided the first
  description of type FSN will be returned. If language preferences are provided, but there is no
  match *and* `fallback?` is true, then the first description of type FSN will be returned."
  ([store concept-id]
   (fully-specified-name store concept-id [] true))
  ([^LmdbStore store concept-id language-refset-ids fallback?]
   (if-not (seq language-refset-ids)
     (lmdb/with-txn [txn store :core]
       (first (filter snomed/fully-specified-name? (lmdb/concept-descriptions* store txn concept-id))))
     (let [preferred (preferred-fully-specified-name store concept-id language-refset-ids)]
       (if (and fallback? (nil? preferred))
         (fully-specified-name store concept-id)
         preferred)))))

(defn- make-extended-concept*
  "Build an ExtendedConcept using existing transactions."
  [store core-txn refsets-txn {concept-id :id :as c}]
  (let [descriptions (mapv #(merge % (description-refsets* store core-txn refsets-txn (:id %)))
                           (lmdb/concept-descriptions* store core-txn concept-id))
        parent-rels (parent-relationships-expanded* store core-txn concept-id)
        direct-parent-rels (parent-relationships* store core-txn concept-id)
        concrete (lmdb/concrete-values* store core-txn concept-id)
        refsets (lmdb/component-refset-ids* store core-txn concept-id)]
    (snomed/->ExtendedConcept c descriptions parent-rels direct-parent-rels concrete refsets)))

(s/fdef make-extended-concept
  :args (s/cat :store ::store :concept :info.snomed/Concept)
  :ret (s/nilable #(instance? ExtendedConcept %)))
(defn make-extended-concept [^LmdbStore store {concept-id :id :as c}]
  (lmdb/with-txn [core-txn store :core]
    (lmdb/with-txn [refsets-txn store :refsets]
      (make-extended-concept* store core-txn refsets-txn c))))

(s/fdef extended-concept
  :args (s/cat :store ::store :concept-id int?))
(defn extended-concept
  "Get an extended concept for the concept specified."
  [^LmdbStore store concept-id]
  (lmdb/with-txn [core-txn store :core]
    (when-let [c (lmdb/concept* store core-txn concept-id)]
      (lmdb/with-txn [refsets-txn store :refsets]
        (make-extended-concept* store core-txn refsets-txn c)))))

(defn installed-language-reference-sets
  "Returns a set of identifiers representing installed language reference sets
  with at least one installed member."
  [store]
  (set/intersection (all-children store snomed/LanguageTypeReferenceSet)
                    (installed-reference-sets store)))

(defn release-information
  "Returns descriptions representing the installed distributions.
  Ordering will be by date except that the description for the 'core' module
  will always be first.
  See https://confluence.ihtsdotools.org/display/DOCTIG/4.1.+Root+and+top-level+Concepts"
  [^LmdbStore st]
  (lmdb/with-txn [txn st :core]
    (let [root-synonyms (sort-by :effectiveTime (filter :active (lmdb/concept-descriptions* st txn snomed/Root)))
          ;; get core date by looking for descriptions in 'CORE' module and get the latest
          core (last (filter #(= snomed/CoreModule (:moduleId %)) root-synonyms))
          others (filter #(not= snomed/CoreModule (:moduleId %)) root-synonyms)]
      (cons core others))))

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
     :HISTORY-MAX (all-children st snomed/HistoricalAssociationReferenceSet snomed/IsA))))

(defn source-historical*
  "Return the requested historical associations using an existing core read
  transaction. See [[source-historical]]."
  [store txn component-id refset-ids]
  (into #{} (mapcat #(lmdb/source-association-referenced-components* store txn component-id %)) refset-ids))

(defn source-historical
  "Return the requested historical associations for the component of types as
  defined by refset-ids, or all association refsets if omitted."
  ([^LmdbStore st component-id]
   (lmdb/with-txn [txn st :core]
     (source-historical* st txn component-id (all-children* st txn snomed/HistoricalAssociationReferenceSet snomed/IsA))))
  ([^LmdbStore st component-id refset-ids]
   (lmdb/with-txn [txn st :core]
     (source-historical* st txn component-id refset-ids))))

(s/fdef with-historical
  :args (s/cat :st ::store :concept-ids (s/coll-of int?) :refset-ids (s/? (s/coll-of int?))))
(defn with-historical
  "For a given sequence of concept identifiers, expand to include historical
  associations both backwards and forwards in time.

  For a currently active concept, this will return historic inactivated concepts
  in which it is the target. For a now inactive concept, this will return the
  active associations and their historic associations.

  By default, all active types of historical associations except MoveTo and
  MovedFrom are included, but this is configurable. "
  ([^LmdbStore st concept-ids]
   (lmdb/with-txn [core-txn st :core]
     (let [refset-ids (disj (all-children* st core-txn snomed/HistoricalAssociationReferenceSet snomed/IsA)
                            snomed/MovedToReferenceSet snomed/MovedFromReferenceSet)]
       (lmdb/with-txn [refsets-txn st :refsets]
         (let [future-ids (into []
                                (comp (mapcat #(lmdb/component-refset-items* st core-txn refsets-txn %))
                                      (filter #(refset-ids (:refsetId %)))
                                      (map :targetComponentId))
                                concept-ids)
               modern-ids (set/union (set concept-ids) (set future-ids))
               historic-ids (into #{} (mapcat #(source-historical* st core-txn % refset-ids)) modern-ids)]
           (set/union modern-ids historic-ids))))))
  ([^LmdbStore st concept-ids historical-refset-ids]
   (lmdb/with-txn [core-txn st :core]
     (lmdb/with-txn [refsets-txn st :refsets]
       (let [refset-ids (set historical-refset-ids)
             future-ids (into []
                              (comp (mapcat #(lmdb/component-refset-items* st core-txn refsets-txn %))
                                    (filter #(refset-ids (:refsetId %)))
                                    (map :targetComponentId))
                              concept-ids)
             modern-ids (set/union (set concept-ids) (set future-ids))
             historic-ids (into #{} (mapcat #(source-historical* st core-txn % refset-ids)) modern-ids)]
         (set/union modern-ids historic-ids))))))

(defn refset-descriptors
  [^LmdbStore store refset-id]
  (lmdb/with-txn [core-txn store :core]
    (lmdb/with-txn [refsets-txn store :refsets]
      (->> (lmdb/component-refset-items* store core-txn refsets-txn refset-id 900000000000456007)
           (sort-by :attributeOrder)))))

(defn refset-descriptor-attribute-ids
  "Return a vector of attribute description concept ids for the given reference
  set."
  [^LmdbStore store refset-id]
  (lmdb/with-txn [core-txn store :core]
    (lmdb/with-txn [refsets-txn store :refsets]
      (->> (lmdb/component-refset-items* store core-txn refsets-txn refset-id 900000000000456007)
           (sort-by :attributeOrder)
           (mapv :attributeDescriptionId)))))

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
  (lmdb/write-concepts store data))
(defmethod write-batch :info.snomed/Description [store {data :data}]
  (lmdb/write-descriptions store data))
(defmethod write-batch :info.snomed/Relationship [store {data :data}]
  (lmdb/write-relationships store data))
(defmethod write-batch :info.snomed/ConcreteValue [store {data :data}]
  (lmdb/write-concrete-values store data))
(defmethod write-batch :info.snomed/Refset [store {:keys [headings data]}]
  (let [items (map #(reify-refset-item store %) data)]
    (lmdb/write-refset-items store headings items)))

(defn write-batch-one-by-one
  "Write out a batch one item at a time. "
  [store batch]
  (doseq [b (map #(assoc batch :data [%]) (:data batch))]
    (try
      (write-batch store b)
      (catch Exception e
        (log/error e "import error: failed to import data: " b)
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
  (lmdb/index-relationships store)
  (lmdb/index-refsets store))

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


