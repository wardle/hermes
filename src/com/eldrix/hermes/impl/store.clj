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
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.impl.ser :as ser]
            [com.eldrix.hermes.snomed :as snomed])
  (:import [java.io FileNotFoundException Closeable]
           (org.mapdb Serializer BTreeMap DB DBMaker DataOutput2)
           (org.mapdb.serializer SerializerArrayTuple GroupSerializerObjectArray)
           (java.util NavigableSet UUID)
           (java.time LocalDate)
           (com.eldrix.hermes.snomed Concept ExtendedConcept)))

(set! *warn-on-reflection* true)

(def ConceptSerializer
  (proxy [GroupSerializerObjectArray] []
    (serialize [^DataOutput2 out o]
      (ser/write-concept out o))
    (deserialize [in _i]
      (ser/read-concept in))))

(def DescriptionSerializer
  (proxy [GroupSerializerObjectArray] []
    (serialize [^DataOutput2 out o]
      (ser/write-description out o))
    (deserialize [in _i]
      (ser/read-description in))))

(def RelationshipSerializer
  (proxy [GroupSerializerObjectArray] []
    (serialize [^DataOutput2 out o]
      (ser/write-relationship out o))
    (deserialize [in _i]
      (ser/read-relationship in))))

(def RefsetItemSerializer
  (proxy [GroupSerializerObjectArray] []
    (serialize [^DataOutput2 out o]
      (ser/write-refset-item out o))
    (deserialize [in _i]
      (ser/read-refset-item in))))

(deftype MapDBStore [^DB db
                     ^BTreeMap concepts                     ;; conceptId -- concept
                     ^NavigableSet descriptionsConcept      ;; descriptionId -- conceptId
                     ^BTreeMap relationships                ;; relationshipId - relationship
                     ^BTreeMap refsets                      ;; refset-item-id -- refset-item
                     ^BTreeMap descriptions                 ;; conceptId--id--description
                     ^NavigableSet conceptParentRelationships ;; sourceId -- typeId -- group -- destinationId
                     ^NavigableSet conceptChildRelationships ;; destinationId -- typeId -- group -- sourceId
                     ^NavigableSet installedRefsets         ;; refsetId
                     ^NavigableSet componentRefsets         ;; referencedComponentId -- refsetId -- id
                     ^NavigableSet mapTargetComponent       ;; refsetId -- mapTarget -- id
                     ^NavigableSet associations             ;; targetComponentId -- refsetId -- referencedComponentId - id
                     ]
  Closeable
  (close [_] (.close db)))

(defn- ^DB open-database
  "Open a file-based key-value database from the file specified, optionally read
  only. Use in a `with-open` block or manually (.close) when done"
  [filename {:keys [read-only? skip-check?]}]
  (when (and read-only? (not (.exists (io/as-file filename))))
    (throw (FileNotFoundException. (str "file `" filename "` opened read-only but not found"))))
  (.make (cond-> (-> (DBMaker/fileDB ^String filename)
                     (.fileMmapEnableIfSupported)
                     (.closeOnJvmShutdown))
                 skip-check? (.checksumHeaderBypass)
                 read-only? (.readOnly))))

(defn- ^DB open-temp-database []
  (-> (DBMaker/tempFileDB)
      (.fileMmapEnable)
      (.closeOnJvmShutdown)
      (.make)))

(defn- ^BTreeMap open-bucket
  "Create or open the named b-tree map with the specified value and key
  serializers.
  Parameters:
  - db               - mapdb database (org.mapdb.DB)
  - key-serializer   - serializer for key
  - value-serializer - serializer for value."
  [^DB db ^String nm ^Serializer key-serializer ^Serializer value-serializer]
  (.createOrOpen (.treeMap db nm key-serializer value-serializer)))

(defn- ^NavigableSet open-index
  "Create or open the named set, by default, made up of an array of java long primitives.
  - db         : mapdb database
  - nm         : name of index
  - serializer : serializer to use, by default LONG_ARRAY"
  ([^DB db ^String nm]
   (open-index db nm Serializer/LONG_ARRAY))
  ([^DB db ^String nm ^Serializer serializer]
   (.createOrOpen (.treeSet db nm serializer))))

(defn- write-object
  "Write an object into a bucket.
  Parameters:
  - bucket : bucket to which to write
  - o      : object
  - k      : (optional; the key to use).
  Correctly handles atomicity and only writes the object if the effectiveTime of
  the entity is newer than the version that already exists in that bucket.
  Returns true if object was written."
  ([^BTreeMap bucket o] (write-object bucket o (:id o)))
  ([^BTreeMap bucket o k] (write-object bucket o k 0))
  ([^BTreeMap bucket o k attempt]
   (if-not (.putIfAbsent bucket k o)
     true
     (let [old (.get bucket k)]
       (when (nil? old)
         (throw (ex-info "entity disappeared" {:entity o})))
       (when (.isAfter ^LocalDate (:effectiveTime o) (:effectiveTime old))
         ;atomically replace old value with new value, returning false if old value has been changed in meantime
         (if (.replace bucket k old o)
           true
           (do
             (log/warn "attempted to update " k " but its value changed: trying again attempt:#" (inc attempt))
             (if (< attempt 10)
               (write-object bucket o k (inc attempt))
               (throw (ex-info "failed to write entity." {:attempts attempt :entity o}))))))))))

(defn- write-index-entry
  "Write an index entry `k` into the `bucket` specified.
  Adds a key to the set, if active.
  Removes a key from the set if not active."
  [^NavigableSet bucket k active?]
  (if active?
    (.add bucket k)
    (.remove bucket k)))

(defn stream-all-concepts
  "Asynchronously stream all concepts to the channel specified, and, by default,
  closing the channel when done unless specified.
  Returns a channel which, by default, will be closed when done."
  ([^MapDBStore store ch]
   (stream-all-concepts store ch true))
  ([^MapDBStore store ch close?]
   (let [concepts (iterator-seq (.valueIterator ^BTreeMap (.concepts store)))]
     (async/onto-chan! ch concepts close?))))

(defn compact [^MapDBStore store]
  (.compact (.getStore ^BTreeMap (.concepts store))))

(defn get-installed-reference-sets
  "Returns the installed reference sets.
  While it is possible to use the SNOMED ontology to find all reference sets:
    ```
    (get-leaves store (get-all-children store 900000000000455006))
    ```
  This will return reference sets with no actual members in the installed
  edition. Instead, we keep track of installed reference sets as we import
  reference set items, thus ensuring we have a list that contains only
  reference sets with members."
  [^MapDBStore store]
  (into #{} (.installedRefsets store)))

(defn get-concept
  "Returns the concept specified."
  [^MapDBStore store concept-id]
  (.get ^BTreeMap (.concepts store) concept-id))

(defn get-description
  "Return the description with the given `description-id`.
  This uses the descriptionId-conceptId index to determine the concept-id,
  as all descriptions are actually stored by conceptId-descriptionId-concept because
  that's a more common operation that finding a description by identifier alone."
  [^MapDBStore store description-id]
  (when-let [concept-id (first (map second (.subSet ^NavigableSet (.descriptionsConcept store)
                                                    (long-array [description-id 0])
                                                    (long-array [description-id Long/MAX_VALUE]))))]
    (first (vals (.prefixSubMap ^BTreeMap (.descriptions store) (long-array [concept-id description-id]))))))

(defn get-concept-descriptions
  "Return the descriptions for the concept specified."
  [^MapDBStore store concept-id]
  (vals (.prefixSubMap ^BTreeMap (.descriptions store) (long-array [concept-id]))))

(defn get-relationship [^MapDBStore store relationship-id]
  (.get ^BTreeMap (.relationships store) relationship-id))

(defn get-refset-item
  "Get the specified refset item.
  Parameters:
  - store : MapDBStore
  - UUID  : the UUID of the refset item to fetch
  - msb/lsb : the most and least significant 64-bit longs representing the UUID."
  ([^MapDBStore store ^long msb ^long lsb]
   (.get ^BTreeMap (.refsets store) (long-array [msb lsb])))
  ([^MapDBStore store ^UUID uuid]
   (.get ^BTreeMap (.refsets store) (long-array [(.getMostSignificantBits uuid) (.getLeastSignificantBits uuid)]))))

(defn- get-raw-parent-relationships
  "Return the parent relationships of the given concept.
  Returns a list of tuples (from--type--group--to)."
  ([^MapDBStore store concept-id] (get-raw-parent-relationships store concept-id 0))
  ([^MapDBStore store concept-id type-id]
   (map seq (.subSet ^NavigableSet (.conceptParentRelationships store)
                     (long-array [concept-id type-id 0 0])
                     (long-array [concept-id (if (pos? type-id) type-id Long/MAX_VALUE) Long/MAX_VALUE Long/MAX_VALUE])))))

(defn- get-raw-child-relationships
  "Return the child relationships of the given concept.
  Returns a list of tuples (from--type--group--to)."
  ([^MapDBStore store concept-id] (get-raw-child-relationships store concept-id 0))
  ([^MapDBStore store concept-id type-id]
   (map seq (.subSet ^NavigableSet (.conceptChildRelationships store)
                     (long-array [concept-id type-id 0 0])
                     (long-array [concept-id (if (pos? type-id) type-id Long/MAX_VALUE) Long/MAX_VALUE Long/MAX_VALUE])))))

(defn get-component-refset-items
  "Get the refset items for the given component, optionally
   limited to the refset specified.
   - store        : MapDBStore
   - component-id : id of the component (e.g concept-id or description-id)
   - refset-id    : (optional) - limit to this refset."
  ([^MapDBStore store component-id]
   (get-component-refset-items store component-id 0))
  ([^MapDBStore store component-id refset-id]
   (->> (.subSet ^NavigableSet (.componentRefsets store)
                 (long-array [component-id refset-id])      ;; lower limit = first two elements of composite key
                 (long-array [component-id (if (zero? refset-id) Long/MAX_VALUE refset-id) Long/MAX_VALUE Long/MAX_VALUE])) ;; upper limit = the four elements
        (map seq)
        (map #(let [[_component-id _refset-id item-msb item-lsb] %] (get-refset-item store item-msb item-lsb))))))

(defn get-component-refsets
  "Return the refset-id's to which this component belongs."
  [^MapDBStore store component-id]
  (->> (.subSet ^NavigableSet (.componentRefsets store)
                (long-array [component-id])                 ;; lower limit = first element of composite key
                (long-array [component-id Long/MAX_VALUE Long/MAX_VALUE]))
       (map seq)
       (map second)))

(defn get-reverse-map
  "Returns the reverse mapping from the reference set and mapTarget specified."
  [^MapDBStore store refset-id s]
  (->> (map seq (.subSet ^NavigableSet (.-mapTargetComponent store)
                         (to-array [refset-id s])
                         (to-array [refset-id s nil])))
       (map #(let [[_refset-id _map-target uuid-msb uuid-lsb] %] (get-refset-item store uuid-msb uuid-lsb)))))

(defn prefix-upper-bound
  "Given a string, generate an upper bound suitable for a prefix search.
  For example, 123 should give 124."
  [s]
  (when-not (str/blank? s) (str (apply str (butlast s)) (char (unchecked-inc (int (last s)))))))

(defn get-reverse-map-range
  "Returns the reverse mapping from the reference set specified, performing
  what is essentially a prefix search using the parameters.
  For example (get-reverse-map-range store \"D86.\") will return all items
  with a map target with prefix 'D86.'

  Parameters:
  - store        : a MapDBStore
  - refset-id    : the reference set in which to search
  - lower-bound  : lower bound prefix, inclusive
  - upper-bound  : upper bound prefix, exclusive
  - prefix       : prefix; lower and upper bounds derived automatically

  Prefixes are case sensitive, with natural string sorting used."
  ([^MapDBStore store refset-id prefix]
   (get-reverse-map-range store refset-id prefix nil))
  ([^MapDBStore store refset-id lower-bound upper-bound]
   (let [index ^NavigableSet (.-mapTargetComponent store)
         least (.ceiling index (to-array [refset-id lower-bound]))
         greatest (.floor index (to-array [refset-id (if upper-bound upper-bound (prefix-upper-bound lower-bound))]))
         invalid? (or (str/blank? lower-bound) (and least greatest (pos? (.compare (.comparator index) least greatest))))]
     (when-not invalid?
       (->> (.subSet index least greatest)
            (map seq)
            (map #(let [[_refset-id _map-target uuid-msb uuid-lsb] %] (get-refset-item store uuid-msb uuid-lsb))))))))

(defn get-source-associations
  "Returns the associations in which this component is the target.
  targetComponentId - refsetId - itemId1 -itemId2."
  ([^MapDBStore store component-id] (get-source-associations store component-id nil))
  ([^MapDBStore store component-id refset-id]
   (->> (map seq (.subSet ^NavigableSet (.-associations store)
                          (long-array [component-id (if refset-id refset-id 0) 0 0 0])
                          (long-array [component-id (if refset-id refset-id Long/MAX_VALUE) Long/MAX_VALUE Long/MAX_VALUE Long/MAX_VALUE])))
        (map #(let [[_target-id _refset-id _referenced-id item-id-1 item-id-2] %] (UUID. item-id-1 item-id-2)))
        (map (partial get-refset-item store)))))

(defn get-source-association-referenced-components
  "Returns a sequence of component identifiers that reference the specified
  component in the specified association reference set."
  [^MapDBStore store component-id refset-id]
  (->> (.subSet ^NavigableSet (.-associations store)
                (long-array [component-id refset-id 0 0 0])
                (long-array [component-id refset-id Long/MAX_VALUE Long/MAX_VALUE Long/MAX_VALUE]))
       (map #(aget ^"[J" % 2))))

(defn- write-concepts [^MapDBStore store objects]
  (let [bucket (.concepts store)]
    (doseq [o objects]
      (write-object bucket o))))

(defn- write-descriptions
  "Write a batch of descriptions, updating indices when appropriate.

  Note unlike other indices, we write in both active and inactive.
    -------------------------------------------------------
  | index                   | compound key                 |
  | .descriptionsConcept    | descriptionId -- conceptId   |
  ---------------------------------------------------------"
  [^MapDBStore store objects]
  (let [d-bucket (.descriptions store)
        dc-index ^NavigableSet (.descriptionsConcept store)]
    (doseq [o objects]
      (let [id (:id o) conceptId (:conceptId o)]
        (when (write-object d-bucket o (long-array [conceptId id]))
          (write-index-entry dc-index (long-array [id conceptId]) true))))))

(defn- write-relationships
  "Write a batch of relationships, updating indices when appropriate.

   This populates the child and parent relationship indices.
   ----------------------------------------------------------------------------
  | index                       | compound key                                 |
  | .conceptChildRelationships  | destinationId -- typeId -- group -- sourceId |
  | .conceptParentRelationships | sourceId -- typeId -- group -- destinationId |
  -----------------------------------------------------------------------------"
  [^MapDBStore store objects]
  (let [rel-bucket (.relationships store)
        child-bucket (.conceptChildRelationships store)
        parent-bucket (.conceptParentRelationships store)]
    (doseq [o objects]
      (when (write-object rel-bucket o)
        (let [{:keys [sourceId typeId active relationshipGroup destinationId]} o]
          (write-index-entry parent-bucket (long-array [sourceId typeId relationshipGroup destinationId]) active)
          (write-index-entry child-bucket (long-array [destinationId typeId relationshipGroup sourceId]) active))))))

(defn- write-refset-items
  "Write a batch of refset items, updating indices when appropriate.

  Processes each refset item, recording an index to allow lookup of items in the
  following buckets using the defined keys:
  -----------------------------------------------------------------------------
  | index               | simple or compound key                               |
  | .componentRefsets   | referencedComponentId - refsetId - itemId1 - itemId2 |
  | .mapTargetComponent | refsetId -- mapTarget - itemId1 - itemId2            |
  | .installedRefsets   | refsetId                                             |
  | .associations       | targetComponentId - refsetId -                       |
  |                     |  - referencedComponentId - itemId1 -itemId2          |
  -----------------------------------------------------------------------------
  An itemID is a 128 bit integer (UUID) so is stored as two longs (itemId1
  itemId2)."
  [^MapDBStore store objects]
  (let [refsets (.refsets store)
        installed ^NavigableSet (.installedRefsets store)
        components ^NavigableSet (.componentRefsets store)
        reverse-map ^NavigableSet (.mapTargetComponent store)
        associations ^NavigableSet (.associations store)]
    (doseq [o objects]
      (let [{:keys [^UUID id referencedComponentId refsetId active mapTarget targetComponentId]} o
            uuid-msb (.getMostSignificantBits id)
            uuid-lsb (.getLeastSignificantBits id)]
        (when (write-object refsets o (long-array [uuid-msb uuid-lsb]))
          (.add installed refsetId)
          (write-index-entry components (long-array [referencedComponentId refsetId uuid-msb uuid-lsb]) active)
          (when mapTarget
            (write-index-entry reverse-map (to-array [refsetId mapTarget uuid-msb uuid-lsb]) active))
          (when targetComponentId
            (write-index-entry associations (long-array [targetComponentId refsetId referencedComponentId uuid-msb uuid-lsb]) active)))))))

(defn close [^MapDBStore store]
  (.close ^DB (.db store)))

(defn status
  [^MapDBStore store]
  (let [concepts (future (.size ^BTreeMap (.concepts store)))
        descriptions (future (.size ^BTreeMap (.descriptions store)))
        relationships (future (.size ^BTreeMap (.relationships store)))
        refsets (future (.size ^BTreeMap (.refsets store)))
        indices (future {:descriptions-concept         (.size ^NavigableSet (.descriptionsConcept store))
                         :concept-parent-relationships (.size ^NavigableSet (.conceptParentRelationships store))
                         :concept-child-relationships  (.size ^NavigableSet (.conceptChildRelationships store))
                         :installed-refsets            (.size ^NavigableSet (.installedRefsets store))
                         :component-refsets            (.size ^NavigableSet (.componentRefsets store))
                         :map-target-component         (.size ^NavigableSet (.mapTargetComponent store))
                         :associations                 (.size ^NavigableSet (.associations store))})]
    {:concepts      @concepts
     :descriptions  @descriptions
     :relationships @relationships
     :refsets       @refsets
     :indices       @indices}))

(def default-opts
  {:read-only?  true
   :skip-check? false})

(defn ^Closeable open-store
  ([] (open-store nil nil))
  ([filename] (open-store filename nil))
  ([filename opts]
   (let [db (if filename
              (open-database filename (merge default-opts opts))
              (open-temp-database))
         ;; core components - concepts, descriptions, relationships and refset items
         concepts (open-bucket db "c" Serializer/LONG ConceptSerializer)
         descriptions-concept (open-index db "dc")
         relationships (open-bucket db "r" Serializer/LONG RelationshipSerializer)
         refsets (open-bucket db "rs" Serializer/LONG_ARRAY RefsetItemSerializer)

         ;; as fetching a list of descriptions [including content] common, store tuple (concept-id description-id)=d
         concept-descriptions (open-bucket db "c-ds" Serializer/LONG_ARRAY DescriptionSerializer)

         ;; for relationships we store a tuple [concept-id type-id group destination-id] of *only* active relationships
         ;; this optimises for the get IS-A relationships of xxx, for example
         concept-parent-relationships (open-index db "c-pr")
         concept-child-relationships (open-index db "c-cr")

         ;; for refsets, we store a key [sctId--refsetId--itemId'-itemId''] = refset item to optimise
         ;; a) determining whether a component is part of a refset, and
         ;; b) returning the items for a given component from a specific refset.
         component-refsets (open-index db "c-rs" Serializer/LONG_ARRAY)

         ;; cache a set of installed reference sets
         installed-refsets (open-index db "installed-rs" Serializer/LONG)

         ;; for any map refsets, we store (refsetid--mapTarget--itemId'--itemId'') to
         ;; allow reverse mapping from, e.g. Read code to SNOMED-CT
         map-target-component (open-index db "r-ti" (SerializerArrayTuple. (into-array Serializer [Serializer/LONG Serializer/STRING Serializer/LONG Serializer/LONG])))

         ;; for association reference sets, we store an index to permit search from
         ;; target to source components - e.g. find components that this target has replaced
         ;; targetComponentId - refsetId - referencedComponentId - itemId1 -itemId2
         associations (open-index db "r-as" Serializer/LONG_ARRAY)]
     (->MapDBStore db
                   concepts
                   descriptions-concept
                   relationships
                   refsets
                   concept-descriptions
                   concept-parent-relationships
                   concept-child-relationships
                   installed-refsets
                   component-refsets
                   map-target-component
                   associations))))

(defmulti write-batch :type)
(defmethod write-batch :info.snomed/Concept [batch store]
  (write-concepts store (:data batch)))
(defmethod write-batch :info.snomed/Description [batch store]
  (write-descriptions store (:data batch)))
(defmethod write-batch :info.snomed/Relationship [batch store]
  (write-relationships store (:data batch)))
(defmethod write-batch :info.snomed/Refset [batch store]
  (write-refset-items store (:data batch)))

(defn write-batch-one-by-one
  "Write out a batch one item at a time. "
  [batch store]
  (loop [batches (map #(assoc batch :data [%]) (:data batch))]
    (when-let [b (first batches)]
      (try
        (write-batch b store)
        (catch Exception e
          (log/error "import error: failed to import row: " b)
          (throw e)))
      (recur (next batches)))))

(defn write-batch-worker
  "Write a batch from the channel 'c' specified into the backing store."
  [store c]
  (loop [batch (async/<!! c)]
    (when-not (nil? batch)
      (try
        (write-batch batch store)
        (catch Exception _
          (write-batch-one-by-one batch store)))
      (recur (async/<!! c)))))


;;;;
;;;;
;;;;

(defn get-all-parents
  "Returns all parent concepts for the concept, including self.
   Parameters:
   - `store` the MapDBStore
   - `concept-id`
   - `type-id`, defaults to 'IS-A' (116680003)."
  ([^MapDBStore store concept-id] (get-all-parents store concept-id snomed/IsA))
  ([^MapDBStore store concept-id type-id]
   (loop [work #{concept-id}
          result #{}]
     (if-not (seq work)
       result
       (let [id (first work)
             done-already? (contains? result id)
             parents (if done-already? () (map last (get-raw-parent-relationships store id type-id)))]
         (recur (apply conj (rest work) parents)
                (conj result id)))))))

(defn get-parent-relationships
  "Returns the parent relationships of the specified concept.
  Returns a map
  key: concept-id of the relationship type (e.g. identifier representing finding site)
  value: a set of concept identifiers for that property.

  See get-parent-relationships-expanded to get each target expanded via
  transitive closure tables."
  [store concept-id]
  (->> (get-raw-parent-relationships store concept-id)
       (map #(hash-map (second %) #{(last %)}))             ;; tuple [concept-id type-id group destination-id] so return indices 1+3
       (apply merge-with into)))

(defn get-parent-relationships-of-type
  "Returns a collection of identifiers representing the parent relationships of
  the specified type of the specified concept."
  [store concept-id type-concept-id]
  (set (map last (get-raw-parent-relationships store concept-id type-concept-id))))

(defn get-parent-relationships-of-types
  [store concept-id type-concept-ids]
  (set (mapcat (partial get-parent-relationships-of-type store concept-id) type-concept-ids)))

(defn get-parent-relationships-expanded
  "Returns all of the parent relationships, expanded to
  include each target's transitive closure table.
  This makes it trivial to build queries that find all concepts
  with, for example, a common finding site at any level of granularity."
  [store concept-id]
  (->> (get-raw-parent-relationships store concept-id)
       (map #(hash-map (second %) (get-all-parents store (last %))))
       (apply merge-with into)))

(defn get-child-relationships-of-type
  "Returns a collection of identifiers representing the parent relationships of
  the specified type of the specified concept."
  [store concept-id type-concept-id]
  (set (map last (get-raw-child-relationships store concept-id type-concept-id))))

(defn paths-to-root
  "Return a sequence of paths from the concept to root node.
  Each path is a sequence of identifiers, starting with the concept itself
  and ending with the root node.
  e.g.
  (sort-by count (paths-to-root store 24700007))
  result (truncated):
  ((24700007 414029004 64572001 404684003 138875005)
   (24700007 6118003 80690008 362975008 64572001 404684003 138875005)
   (24700007 39367000 23853001 246556002 118234003 404684003 138875005)
   (24700007 6118003 80690008 23853001 246556002 118234003 404684003 138875005))"
  [store concept-id]
  (loop [parents (map last (get-raw-parent-relationships store concept-id snomed/IsA))
         results []]
    (let [parent (first parents)]
      (if-not parent
        (if (seq results) (map #(conj % concept-id) results) (list (list concept-id)))
        (recur (rest parents)
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
  ([^MapDBStore store concept-id] (get-all-children store concept-id snomed/IsA))
  ([^MapDBStore store concept-id type-id]
   (loop [work #{concept-id}
          result #{}]
     (if-not (seq work)
       result
       (let [id (first work)
             done-already? (contains? result id)
             children (if done-already? () (map last (get-raw-child-relationships store id type-id)))]
         (recur (apply conj (rest work) children)
                (conj result id)))))))

(defn get-grouped-properties
  "Return a concept's properties as a collection of maps, each map representing
  related properties in a 'relationshipGroup'. By default, all groups are
  returned, but this can optionally be limited to those containing a specific
  relationship type."
  ([store concept-id]
   (->> (get-raw-parent-relationships store concept-id)     ;; tuples concept--type--group--destination
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
  [^MapDBStore store concept-ids]
  (set/difference (set concept-ids) (into #{} (mapcat #(disj (get-all-parents store %) %) concept-ids))))

(defn all-transitive-synonyms
  "Returns all of the synonyms of the specified concept, including those
   of its descendants."
  ([store concept-id] (all-transitive-synonyms store concept-id {}))
  ([store concept-id {:keys [include-inactive?]}]
   (let [concepts (conj (get-all-children store concept-id) concept-id)
         ds (mapcat (partial get-concept-descriptions store) concepts)
         ds' (if include-inactive? ds (filter :active ds))]
     (filter #(= snomed/Synonym (:typeId %)) ds'))))


(defn get-description-refsets
  "Get the refsets and language applicability for a description.
  Returns a map containing:
  - refsets       : a set of refsets to which this description is a member
  - preferredIn  : refsets for which this description is preferred
  - acceptableIn : refsets for which this description is acceptable.

  Example:
  (map #(merge % (get-description-refsets store (:id %)))
       (get-concept-descriptions store 24700007))"
  [store description-id]
  (let [refset-items (get-component-refset-items store description-id)
        refsets (into #{} (map :refsetId refset-items))
        preferred-in (into #{} (map :refsetId (filter #(= snomed/Preferred (:acceptabilityId %)) refset-items)))
        acceptable-in (into #{} (map :refsetId (filter #(= snomed/Acceptable (:acceptabilityId %)) refset-items)))]
    {:refsets      refsets
     :preferredIn  preferred-in
     :acceptableIn acceptable-in}))

(defn get-preferred-description
  "Return the preferred description for the concept specified as defined by
  the language reference set specified for the description type.
  - store               :
  - concept-id          :
  - description-type-id : type of description (e.g. synonym or FSN)
  - language-refset-id  : language reference set

  Possible description-type-ids:
   900000000000013009: synonym (core metadata concept)
   900000000000003001: fully specified name

  Example language-refset-ids:
   900000000000509007: US English language reference set
   999001261000000100: UK English (clinical) language reference set."
  [store concept-id description-type-id language-refset-id]
  (let [descriptions (->> (get-concept-descriptions store concept-id)
                          (filter :active)
                          (filter #(= description-type-id (:typeId %))))
        refset-item (->> (mapcat #(get-component-refset-items store (:id %) language-refset-id) descriptions)
                         (filter #(= snomed/Preferred (:acceptabilityId %))) ;; only PREFERRED
                         (first))
        preferred (:referencedComponentId refset-item)]
    (when preferred (get-description store preferred))))

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

(defn get-preferred-fully-specified-name [store concept-id language-refset-ids]
  (some identity (map (partial get-preferred-description store concept-id snomed/FullySpecifiedName) language-refset-ids)))

(defn get-fully-specified-name
  "Return the fully specified name for the concept specified. If no language preferences are provided the first
  description of type FSN will be returned. If language preferences are provided, but there is no
  match *and* `fallback?` is true, then the first description of type FSN will be returned."
  ([^MapDBStore store concept-id] (get-fully-specified-name store concept-id nil true))
  ([^MapDBStore store concept-id language-refset-ids fallback?]
   (if (nil? language-refset-ids)
     (first (filter snomed/is-fully-specified-name? (get-concept-descriptions store concept-id)))
     (let [preferred (get-preferred-fully-specified-name store concept-id language-refset-ids)]
       (if (and fallback? (nil? preferred))
         (get-fully-specified-name store concept-id)
         preferred)))))

(defn make-extended-concept [^MapDBStore store concept]
  (when-not (map? concept)
    (throw (IllegalArgumentException. "invalid concept")))
  (let [concept-id (:id concept)
        descriptions (map #(merge % (get-description-refsets store (:id %)))
                          (get-concept-descriptions store concept-id))
        parent-relationships (get-parent-relationships-expanded store concept-id)
        direct-parent-relationships (get-parent-relationships store concept-id)
        refsets (into #{} (get-component-refsets store concept-id))]
    (snomed/->ExtendedConcept
      concept
      descriptions
      parent-relationships
      direct-parent-relationships
      refsets)))

(defn get-extended-concept [^MapDBStore store concept-id]
  (make-extended-concept store (get-concept store concept-id)))

(defn get-release-information
  "Returns descriptions representing the installed distributions.
  Ordering will be by date except that the description for the 'core' module
  will always be first.
  See https://confluence.ihtsdotools.org/display/DOCTIG/4.1.+Root+and+top-level+Concepts"
  [^MapDBStore st]
  (let [root-synonyms (sort-by :effectiveTime (filter :active (get-concept-descriptions st snomed/Root)))
        ;; get core date by looking for descriptions in 'CORE' module and get the latest
        core (last (filter #(= snomed/CoreModule (:moduleId %)) root-synonyms))
        others (filter #(not= snomed/CoreModule (:moduleId %)) root-synonyms)]
    (cons core others)))

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

(comment
  (set! *warn-on-reflection* true)
  (def filename "snomed.db/store.db")
  (def store (open-store filename {:read-only? true :skip-check? false}))
  (close store)

  (with-open [st (open-store filename {:read-only? false :skip-check? false})]
    (.compact (.getStore (.concepts st))))

  (with-open [st (open-store filename {:read-only? false :skip-check? true})]
    (.compact (.getStore (.concepts st))))

  (with-open [db (open-store filename {:read-only? true :skip-check? false})]
    (status db))

  (with-open [st (open-store filename {:read-only? true :skip-check? false})]
    (->> (get-concept-descriptions st 24700007)
         (filter :active)
         (remove snomed/is-fully-specified-name?)
         (map :term)
         (sort-by :term)))

  (with-open [st (open-store filename {:read-only? true :skip-check? false})]
    (get-component-refset-items st 24700007 900000000000497000))

  (with-open [st (open-store filename {:read-only? true :skip-check? false})]
    (get-reverse-map st 900000000000497000 "F20.."))

  (with-open [db (open-store filename {:read-only? false})]
    (.getAllNames (.db db)))

  (def ch (async/chan))
  (async/thread (with-open [st (open-store filename {:read-only? true})]
                  (async/<!! (stream-all-concepts st ch))))

  (def store (open-store "snomed.db/store.db" {:read-only? false}))
  (.close store)
  (require '[com.eldrix.hermes.importer :as imp])
  (imp/importable-files "/Users/mark/Downloads/snomed-2021-01")
  (imp/all-metadata "/Users/mark/Downloads/snomed-2021-01")
  (def ch (imp/load-snomed "/Users/mark/Downloads/snomed-2021-01"))
  (loop [batch (async/<!! ch)]
    (when batch
      (write-batch batch store)
      (recur (async/<!! ch))))

  (def store (open-store "snomed.db/store.db" {:read-only? true}))
  (get-concept store 24700007)
  (get-raw-child-relationships store 24700007)
  (get-child-relationships-of-type store 24700007 116680003)
  (time (get-concept-descriptions store 24700007))
  (make-extended-concept store (get-concept store 49723003))
  (get-preferred-synonym store 24700007 [999001261000000100 999000691000001104 900000000000508004 999001261000000100])

  (get-description store 82816014)
  (map #(get-preferred-synonym store % [999000691000001104 900000000000508004 999001261000000100]) (map :refsetId (get-component-refset-items store 82816014)))

  (def refsets (get-all-children store 900000000000455006))
  (count refsets)
  (count (get-leaves store refsets))
  (count (get-installed-reference-sets store))
  (clojure.set/difference (set refsets) (set (get-installed-reference-sets store)))

  )

