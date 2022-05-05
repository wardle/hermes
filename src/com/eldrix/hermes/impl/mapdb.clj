; Copyright 2022 Mark Wardle and Eldrix Ltd
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
(ns com.eldrix.hermes.impl.mapdb
  "A store implemented using mapdb"
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.impl.ser :as ser]
            [com.eldrix.hermes.snomed :as snomed])
  (:import [java.io FileNotFoundException Closeable]
           (org.mapdb Serializer BTreeMap DB DBMaker DataOutput2)
           (org.mapdb.serializer GroupSerializerObjectArray)
           (java.util NavigableSet UUID)
           (java.time LocalDate)))

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

(def RefsetFieldNamesSerializer
  (proxy [GroupSerializerObjectArray] []
    (serialize [^DataOutput2 out o]
      (ser/write-field-names out o))
    (deserialize [in _i]
      (ser/read-field-names in))))

(deftype MapDBStore
  [^DB db
   ^BTreeMap concepts                                       ;; conceptId -- concept
   ^NavigableSet descriptionsConcept                        ;; descriptionId -- conceptId
   ^BTreeMap relationships                                  ;; relationshipId - relationship
   ^BTreeMap refsets                                        ;; refset-item-id -- refset-item   ;; TODO: rename to refsetItems
   ^BTreeMap refsetFieldNames                               ;; refset-id -- field-names
   ^BTreeMap descriptions                                   ;; conceptId--id--description
   ^NavigableSet conceptParentRelationships                 ;; sourceId -- typeId -- group -- destinationId
   ^NavigableSet conceptChildRelationships                  ;; destinationId -- typeId -- group -- sourceId
   ^NavigableSet componentRefsets                           ;; referencedComponentId -- refsetId -- msb -- lsb
   ^NavigableSet associations]                              ;; targetComponentId -- refsetId -- referencedComponentId - id

  Closeable
  (close [_] (.close db)))

(s/def ::store #(instance? MapDBStore %))

(defn- open-database
  "Open a file-based key-value database from the file specified, optionally read
  only. Use in a `with-open` block or manually (.close) when done"
  ^DB [filename {:keys [read-only? skip-check?]}]
  (when (and read-only? (not (.exists (io/as-file filename))))
    (throw (FileNotFoundException. (str "file `" filename "` opened read-only but not found"))))
  (.make (cond-> (-> (DBMaker/fileDB ^String filename)
                     (.fileMmapEnableIfSupported)
                     (.closeOnJvmShutdown))
                 skip-check? (.checksumHeaderBypass)
                 read-only? (.readOnly))))

(defn- open-temp-database
  ^DB []
  (-> (DBMaker/tempFileDB)
      (.fileMmapEnable)
      (.closeOnJvmShutdown)
      (.make)))

(defn open-bucket
  "Create or open the named b-tree map with the specified value and key
  serializers.

  Parameters:
  - db               - mapdb database (org.mapdb.DB)
  - key-serializer   - serializer for key
  - value-serializer - serializer for value."
  ^BTreeMap [^DB db ^String nm ^Serializer key-serializer ^Serializer value-serializer]
  (.createOrOpen (.treeMap db nm key-serializer value-serializer)))

(defn open-index
  "Create or open the named set, by default, made up of an array of java long primitives.
  - db         : mapdb database
  - nm         : name of index
  - serializer : serializer to use, by default LONG_ARRAY"
  ^NavigableSet
  ([^DB db ^String nm]
   (open-index db nm Serializer/LONG_ARRAY))
  ([^DB db ^String nm ^Serializer serializer]
   (.createOrOpen (.treeSet db nm serializer))))

(defn write-object
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
     (async/onto-chan!! ch concepts close?))))

(defn compact [^MapDBStore store]
  (.compact (.getStore ^BTreeMap (.concepts store))))

(defn get-installed-reference-sets
  "Returns a set of identifiers representing installed reference sets.

  While it is possible to use the SNOMED ontology to find all reference sets:
    ```
    (get-leaves store (get-all-children store 900000000000455006))
    ```
  That might return reference sets with no actual members in the installed
  edition. Instead, we keep track of installed reference sets as we import
  reference set items, thus ensuring we have a list that contains only
  reference sets with members."
  [^MapDBStore store]
  (set (.keySet ^BTreeMap (.-refsetFieldNames store))))

(s/fdef get-concept
  :args (s/cat :store ::store :concept-id :info.snomed.Concept/id))
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

(defn get-raw-parent-relationships
  "Return the parent relationships of the given concept.
  Returns a list of tuples (from--type--group--to)."
  ([^MapDBStore store concept-id] (get-raw-parent-relationships store concept-id 0))
  ([^MapDBStore store concept-id type-id]
   (map seq (.subSet ^NavigableSet (.conceptParentRelationships store)
                     (long-array [concept-id type-id 0 0])
                     (long-array [concept-id (if (pos? type-id) type-id Long/MAX_VALUE) Long/MAX_VALUE Long/MAX_VALUE])))))

(defn get-raw-child-relationships
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

(defn get-component-refset-ids
  "Return the refset-ids to which this component belongs."
  [^MapDBStore store component-id]
  (->> (.subSet ^NavigableSet (.componentRefsets store)
                (long-array [component-id])                 ;; lower limit = first element of composite key
                (long-array [component-id Long/MAX_VALUE Long/MAX_VALUE Long/MAX_VALUE Long/MAX_VALUE]))
       (map seq)
       (map second)
       set))

(defn stream-all-refset-items
  ([^MapDBStore store ch]
   (stream-all-refset-items store ch true))
  ([^MapDBStore store ch close?]
   (let [items (->> (.-componentRefsets store)
                    (map seq)
                    (map #(let [[_component-id _refset-id uuid-msb uuid-lsb] %]
                            (get-refset-item store uuid-msb uuid-lsb))))]
     (async/onto-chan!! ch items close?))))

(defn get-refset-field-names
  "Returns the field names for the given reference set.

  The reference set descriptors provide a human-readable description and a type
  for each column in a reference set, but do not include the camel-cased column
  identifier in the original source file. On import, we store those column names
  and provide the lookup here."
  [^MapDBStore store refset-id]
  (.get ^BTreeMap (.-refsetFieldNames store) refset-id))

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

(defn write-concepts [^MapDBStore store concepts]
  (let [bucket (.concepts store)]
    (doseq [o concepts]
      (write-object bucket o))))

(defn write-descriptions
  "Write a batch of descriptions, updating indices when appropriate.

  Note unlike other indices, we write in both active and inactive.
  | Index                   | Compound key                 |
  |:------------------------|:-----------------------------|
  | .descriptionsConcept    | descriptionId -- conceptId   |"
  [^MapDBStore store descriptions]
  (let [d-bucket (.descriptions store)
        dc-index ^NavigableSet (.descriptionsConcept store)]
    (doseq [o descriptions]
      (let [id (:id o) conceptId (:conceptId o)]
        (when (write-object d-bucket o (long-array [conceptId id]))
          (write-index-entry dc-index (long-array [id conceptId]) true))))))

(defn write-relationships
  "Write a batch of relationships, updating indices when appropriate.

   This populates the child and parent relationship indices.
  | Index                       | Compound key                                 |
  |:----------------------------|:---------------------------------------------|
  | .conceptChildRelationships  | destinationId -- typeId -- group -- sourceId |
  | .conceptParentRelationships | sourceId -- typeId -- group -- destinationId |"
  [^MapDBStore store relationships]
  (let [rel-bucket (.relationships store)
        child-bucket (.conceptChildRelationships store)
        parent-bucket (.conceptParentRelationships store)]
    (doseq [o relationships]
      (when (write-object rel-bucket o)
        (let [{:keys [sourceId typeId active relationshipGroup destinationId]} o]
          (write-index-entry parent-bucket (long-array [sourceId typeId relationshipGroup destinationId]) active)
          (write-index-entry child-bucket (long-array [destinationId typeId relationshipGroup sourceId]) active))))))

(defn write-refset-items
  "Write a batch of refset items, updating indices when appropriate.

  Processes each refset item, recording an index to allow lookup of items in the
  following buckets using the defined keys:
  | index               | simple or compound key                               |
  |:--------------------|:-----------------------------------------------------|
  | .componentRefsets   | referencedComponentId - refsetId - itemId1 - itemId2 |
  | .refsetFieldNames   | refsetId -- headings
  | .associations       | targetComponentId - refsetId -                       |
  |                     |  - referencedComponentId - itemId1 -itemId2          |

   An itemID is a 128 bit integer (UUID) so is stored as two longs (itemId1
  itemId2)."
  [^MapDBStore store headings items]
  (let [refsets (.refsets store)
        field-names ^BTreeMap (.-refsetFieldNames store)
        components ^NavigableSet (.componentRefsets store)
        associations ^NavigableSet (.associations store)]
    (doseq [o items]
      (let [{:keys [^UUID id referencedComponentId refsetId active targetComponentId]} o
            uuid-msb (.getMostSignificantBits id)
            uuid-lsb (.getLeastSignificantBits id)]
        (when (write-object refsets o (long-array [uuid-msb uuid-lsb]))
          (.putIfAbsent field-names refsetId (or headings []))
          (write-index-entry components (long-array [referencedComponentId refsetId uuid-msb uuid-lsb]) active)
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
                         :component-refsets            (.size ^NavigableSet (.componentRefsets store))
                         :associations                 (.size ^NavigableSet (.associations store))})]
    {:concepts      @concepts
     :descriptions  @descriptions
     :relationships @relationships
     :refsets       (.size ^BTreeMap (.refsetFieldNames store))
     :refset-items  @refsets
     :indices       @indices}))

(def default-opts
  {:read-only?  true
   :skip-check? false})

(defn open-store
  (^Closeable [] (open-store nil nil))
  (^Closeable [filename] (open-store filename nil))
  (^Closeable [filename opts]
   (let [db (if filename
              (open-database filename (merge default-opts opts))
              (open-temp-database))
         ;; core components - concepts, descriptions, relationships and refset items
         concepts (open-bucket db "c" Serializer/LONG ConceptSerializer)
         descriptions-concept (open-index db "dc")
         relationships (open-bucket db "r" Serializer/LONG RelationshipSerializer)
         refsets (open-bucket db "rs" Serializer/LONG_ARRAY RefsetItemSerializer)

         ;; we store a list of headings for each refset, keyed by refset id
         refset-field-names (open-bucket db "r-fn" Serializer/LONG RefsetFieldNamesSerializer)

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

         ;; for association reference sets, we store an index to permit search from
         ;; target to source components - e.g. find components that this target has replaced
         ;; targetComponentId - refsetId - referencedComponentId - itemId1 -itemId2
         associations (open-index db "r-as" Serializer/LONG_ARRAY)]
     (->MapDBStore db
                   concepts
                   descriptions-concept
                   relationships
                   refsets
                   refset-field-names
                   concept-descriptions
                   concept-parent-relationships
                   concept-child-relationships
                   component-refsets
                   associations))))

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

  (def store (open-store "snomed.db/store.db" {:read-only? true}))
  (time (get-concept store 24700007))
  (get-raw-child-relationships store 24700007)
  (time (get-concept-descriptions store 24700007))

  (get-description store 82816014)

  (count (get-installed-reference-sets store)))