(ns com.eldrix.hermes.impl.lmdb
  "A backing key value store implemented using LMDB.

  LMDB has very fast read access, which makes it highly suitable as hermes
  operates principally in read-only mode. We use netty's direct buffers, and a
  shared pool because of allocation overhead compared to on-heap buffers.

  We use the key value store in one of two ways. The first is to store entities.
  These are usually keyed by the identifier, except for descriptions, which are
  keyed by a tuple of concept identifier and description identifier. That
  optimises the common fetch of all descriptions for a given concept. The second
  is to store null values as part of an index, with compound keys. This means
  we can rapidly iterate across a range of keys, which are always sorted, and
  stored big-endian. The compound key structures are defined below.

  It would be possible to create a generic key-value protocol, but this instead
  contains domain-optimised code."
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [com.eldrix.hermes.impl.ser :as ser])
  (:import [org.lmdbjava Env EnvFlags DbiFlags Dbi ByteBufProxy PutFlags Txn GetOp]
           (java.nio.file.attribute FileAttribute)
           (io.netty.buffer ByteBufInputStream PooledByteBufAllocator ByteBufOutputStream ByteBuf)
           (java.time LocalDate)
           (com.eldrix.hermes.snomed Description Relationship Concept)
           (java.util UUID)
           (java.io Closeable File)
           (java.nio.file Files)))

(set! *warn-on-reflection* true)

(s/def ::store any?)

(deftype LmdbStore
  [;;;; core env
   ^Env coreEnv
   ;; core stores - simple or compound keys and values
   ^Dbi concepts                                            ;; conceptId = concept
   ^Dbi conceptDescriptions                                 ;; conceptId-descriptionId = description
   ^Dbi relationships                                       ;; relationshipId = relationship
   ;; core indices - compound keys with empty values
   ^Dbi descriptionConcept                                  ;; descriptionId - conceptId
   ^Dbi conceptParentRelationships                          ;; sourceId - typeId - group - destinationId
   ^Dbi conceptChildRelationships                           ;; destinationId - typeId - group - sourceId
   ^Dbi componentRefsets                                    ;; referencedComponentId - refsetId - msb - lsb
   ^Dbi associations                                       ;; targetComponentId - refsetId - referencedComponentId - msb - lsb
   ;;;; refset env
   ^Env refsetsEnv
   ^Dbi refsetItems                                         ;; refset-item-id = refset-item
   ^Dbi refsetFieldNames]                                    ;; refset-id = field-names]
  Closeable
  (close [_]
    (.close ^Env coreEnv)
    (.close ^Env refsetsEnv)))

(def ^:private rw-env-flags [EnvFlags/MDB_NOSUBDIR EnvFlags/MDB_NOTLS EnvFlags/MDB_WRITEMAP EnvFlags/MDB_MAPASYNC EnvFlags/MDB_NOMETASYNC EnvFlags/MDB_NORDAHEAD])
(def ^:private ro-env-flags [EnvFlags/MDB_NOSUBDIR EnvFlags/MDB_NOTLS EnvFlags/MDB_NOLOCK EnvFlags/MDB_RDONLY_ENV])
(defn make-dbi-flags
  ^"[Lorg.lmdbjava.DbiFlags;" [read-only? & flags]
  (into-array DbiFlags (if read-only? flags (conj flags DbiFlags/MDB_CREATE))))

(def ^:private default-map-size (* 10 1024 1024 1024))

(defn- open*
  "Open a store at the path specified.
  f          : path of directory, anything coercible by clojure.io/as-file
  read-only? : whether to open read-only; default true
  map-size   : size in bytes, default 10gb"
  [f & {:keys [read-only? map-size] :or {read-only? true map-size default-map-size}}]
  (let [f' ^File (io/file f)]
    (when (not (.exists f'))
      (if read-only?
        (throw (ex-info "Store not found and opening in read-only mode" {:f f}))
        (Files/createDirectory (.toPath f') (make-array FileAttribute 0))))
    (let [root-path (.toPath f')
          core-f (.toFile (.resolve root-path "core.db"))
          refsets-f (.toFile (.resolve root-path "refsets.db"))
          core-env (-> (Env/create ByteBufProxy/PROXY_NETTY)
                       (.setMapSize map-size) (.setMaxDbs 8)
                       (.open core-f (into-array EnvFlags (if read-only? ro-env-flags rw-env-flags))))
          refsets-env (-> (Env/create ByteBufProxy/PROXY_NETTY)
                          (.setMapSize map-size) (.setMaxDbs 2)
                          (.open refsets-f (into-array EnvFlags (if read-only? ro-env-flags rw-env-flags))))
          base-flags (make-dbi-flags read-only?)
          ;; core env
          concepts (.openDbi ^Env core-env "c" (make-dbi-flags read-only? DbiFlags/MDB_INTEGERKEY))
          conceptDescriptions (.openDbi ^Env core-env "d" (make-dbi-flags read-only?))
          relationships (.openDbi ^Env core-env "r" (make-dbi-flags read-only? DbiFlags/MDB_INTEGERKEY))
          descriptionConcept (.openDbi ^Env core-env "dc" base-flags)
          conceptParentRelationships (.openDbi ^Env core-env "cpr" base-flags)
          conceptChildRelationships (.openDbi ^Env core-env "ccr" base-flags)
          componentRefsets (.openDbi ^Env core-env "cr" base-flags)
          associations (.openDbi ^Env core-env "a" base-flags)
          ;; refsets env
          refsetItems (.openDbi ^Env refsets-env "rs" base-flags)
          refsetFieldNames (.openDbi ^Env refsets-env "rs-n" (make-dbi-flags read-only? DbiFlags/MDB_INTEGERKEY))]

      (->LmdbStore core-env
                   concepts conceptDescriptions relationships descriptionConcept
                   conceptParentRelationships conceptChildRelationships componentRefsets associations
                   refsets-env
                   refsetItems refsetFieldNames))))

(defn open-store
  (^Closeable [] (open-store (.toFile (Files/createTempDirectory "hermes-lmdb-" (make-array FileAttribute 0))) {:read-only? false}))
  (^Closeable [f] (open-store f {}))
  (^Closeable [f opts] (open* f opts)))

(defn close
  [^LmdbStore store]
  (.close store))

(def put-flags (make-array PutFlags 0))

(defn- should-write-object?
  "Determine whether to write an object.
  This only writes the object if the effectiveTime of the entity is newer than
  the version that already exists. LMDB writes are single-threaded.
  Optimised so avoids deserialising anything except the effective time from
  the stored component, if found."
  [^Dbi dbi ^Txn txn ^ByteBuf kb read-offset ^LocalDate effectiveTime]
  (let [existing (.get dbi txn kb)]
    (or (not existing) (.isAfter effectiveTime (ser/read-effective-time (ByteBufInputStream. existing) read-offset)))))

(defn write-concepts
  "Each concept is stored as an entity in the 'concepts' db keyed by identifier."
  [^LmdbStore store concepts]
  (with-open [txn (.txnWrite ^Env (.-coreEnv store))]
    (let [db ^Dbi (.-concepts store)
          kb (.directBuffer (PooledByteBufAllocator/DEFAULT) 8)
          vb (.directBuffer (PooledByteBufAllocator/DEFAULT) 512)
          out (ByteBufOutputStream. vb)]
      (try (doseq [^Concept concept concepts]
             (doto kb .clear (.writeLong (.-id concept)))
             (when (should-write-object? db txn kb 8 (.-effectiveTime concept))
               (.clear vb)
               (ser/write-concept out concept)
               (.put db txn kb vb put-flags)))
           (.commit txn)
           (finally (.release kb) (.release vb))))))

(defn write-descriptions
  "Each description is stored as an entity in the 'descriptions' db, keyed
  by a tuple of concept-id--description--id.

  Each description is referenced in the 'descriptionConcept' index,
  keyed by description-id--concept-id."
  [^LmdbStore store descriptions]
  (with-open [txn (.txnWrite ^Env (.-coreEnv store))]
    (let [db ^Dbi (.-conceptDescriptions store)             ;; concept-id - description-id = description
          idx ^Dbi (.-descriptionConcept store)             ;; description-id - concept-id = nil
          kb (.directBuffer (PooledByteBufAllocator/DEFAULT) 16)
          vb (.directBuffer (PooledByteBufAllocator/DEFAULT) 512)
          idx-key (.directBuffer (PooledByteBufAllocator/DEFAULT) 16)
          idx-val (.directBuffer (PooledByteBufAllocator/DEFAULT) 0)
          out (ByteBufOutputStream. vb)]
      (try (doseq [^Description description descriptions]
             (doto kb .clear (.writeLong (.-conceptId description)) (.writeLong (.-id description)))
             (doto idx-key .clear (.writeLong (.-id description)) (.writeLong (.-conceptId description)))
             (when (should-write-object? db txn kb 8 (.-effectiveTime description))
               (.clear vb)
               (ser/write-description out description)
               (.put db txn kb vb put-flags)
               (.put idx txn idx-key idx-val put-flags)))
           (.commit txn)
           (finally (.release kb) (.release vb) (.release idx-key) (.release idx-val))))))

(defn write-relationships
  "Each relationship is stored as an entity in the 'relationships' db, keyed
  by a relationship-id.

  Each *active* relationship is referenced in the 'conceptParentRelationships'
   and 'conceptChildRelationships' indices,"
  [^LmdbStore store relationships]
  (with-open [txn (.txnWrite ^Env (.-coreEnv store))]
    (let [db ^Dbi (.-relationships store)
          parent-idx ^Dbi (.-conceptParentRelationships store)
          child-idx ^Dbi (.-conceptChildRelationships store)
          kb (.directBuffer (PooledByteBufAllocator/DEFAULT) 8) ;; relationship id
          vb (.directBuffer (PooledByteBufAllocator/DEFAULT) 64) ;; relationship entity
          parent-idx-key (.directBuffer (PooledByteBufAllocator/DEFAULT) 32) ;; sourceId -- typeId -- group -- destinationId
          child-idx-key (.directBuffer (PooledByteBufAllocator/DEFAULT) 32) ;; destinationId -- typeId -- group -- sourceId
          idx-val (.directBuffer (PooledByteBufAllocator/DEFAULT) 0)
          out (ByteBufOutputStream. vb)]
      (try (doseq [^Relationship relationship relationships]
             (doto kb .clear (.writeLong (.-id relationship)))
             (doto parent-idx-key .clear (.writeLong (.-sourceId relationship)) (.writeLong (.-typeId relationship)) (.writeLong (.-relationshipGroup relationship)) (.writeLong (.-destinationId relationship)))
             (doto child-idx-key .clear (.writeLong (.-destinationId relationship)) (.writeLong (.-typeId relationship)) (.writeLong (.-relationshipGroup relationship)) (.writeLong (.-sourceId relationship)))
             (when (should-write-object? db txn kb 8 (.-effectiveTime relationship)) ;; skip a 8 byte key (relationship-id)
               (ser/write-relationship out relationship)
               (.put db txn kb vb put-flags)
               (if (.-active relationship)
                 (do (.put parent-idx txn parent-idx-key idx-val put-flags)
                     (.put child-idx txn child-idx-key idx-val put-flags))
                 (do (.delete parent-idx txn parent-idx-key) ;; if its inactive, we're careful to delete any existing indices
                     (.delete child-idx txn child-idx-key))) ;; so that update-in-place does work
               (.clear vb)))
           (.commit txn)
           (finally (.release kb) (.release vb) (.release parent-idx-key) (.release child-idx-key) (.release idx-val))))))

(defn- write-refset-headings
  [^LmdbStore store ^Txn txn refset-id headings]
  (when refset-id
    (let [headings-db ^Dbi (.-refsetFieldNames store)
          kb (.directBuffer (PooledByteBufAllocator/DEFAULT) 8)
          vb (.directBuffer (PooledByteBufAllocator/DEFAULT) 512)]
      (try (.writeLong kb refset-id)
           (ser/write-field-names (ByteBufOutputStream. vb) (or headings []))
           (.put headings-db txn kb vb put-flags)
           (finally (.release kb)
                    (.release vb))))))

(defn write-refset-items
  "Each reference set item is stored as an entity in the 'refsetItems' db, keyed
  by the UUID, a tuple of msb and lsb.

  Each *active* item is indexed:
  - refsetFieldNames  : refset-id -- field-names (an array of strings)
  - componentRefsets  : referencedComponentId -- refsetId -- msb -- lsb
  - associations      : targetComponentId -- refsetId -- referencedComponentId - msb - lsb"
  [^LmdbStore store headings items]
  (with-open [core-txn (.txnWrite ^Env (.-coreEnv store))
              refsets-txn (.txnWrite ^Env (.-refsetsEnv store))]
    (let [items-db ^Dbi (.-refsetItems store)
          components-db ^Dbi (.-componentRefsets store)
          assocs-db ^Dbi (.-associations store)
          item-kb (.directBuffer (PooledByteBufAllocator/DEFAULT) 16) ;; a UUID - 16 bytes
          vb (.directBuffer (PooledByteBufAllocator/DEFAULT) 512)
          component-kb (.directBuffer (PooledByteBufAllocator/DEFAULT) 32) ;; referencedComponentId -- refsetId -- msb -- lsb
          assoc-kb (.directBuffer (PooledByteBufAllocator/DEFAULT) 40) ;; targetComponentId -- refsetId -- referencedComponentId - msb - lsb
          idx-val (.directBuffer (PooledByteBufAllocator/DEFAULT) 0)]
      (try
        (loop [items' items refset-ids #{}]
          (when-let [item (first items')]
            (when-not (contains? refset-ids (:refsetId item))
              (write-refset-headings store refsets-txn (:refsetId item) headings))
            (let [msb (.getMostSignificantBits ^UUID (:id item))
                  lsb (.getLeastSignificantBits ^UUID (:id item))
                  target-id (:targetComponentId item)]
              (doto item-kb .clear (.writeLong msb) (.writeLong lsb))
              (doto component-kb .clear (.writeLong (:referencedComponentId item)) (.writeLong (:refsetId item)) (.writeLong msb) (.writeLong lsb))
              (when (should-write-object? items-db refsets-txn item-kb 17 (:effectiveTime item)) ;; skip a 17 byte key (type-msb-lsb; type = 1 byte, msb = 8 bytes, lsb = 8 bytes)
                (.clear vb)
                (ser/write-refset-item (ByteBufOutputStream. vb) item)
                (.put items-db refsets-txn item-kb vb put-flags)
                (when target-id
                  (doto assoc-kb .clear (.writeLong target-id) (.writeLong (:refsetId item)) (.writeLong (:referencedComponentId item)) (.writeLong msb) (.writeLong lsb)))
                (if (:active item)
                  (do (.put components-db core-txn component-kb idx-val put-flags)
                      (when target-id (.put assocs-db core-txn assoc-kb idx-val put-flags)))
                  (do (.delete components-db core-txn component-kb)
                      (when target-id (.delete assocs-db core-txn assoc-kb))))))
            (recur (next items') (conj refset-ids (:refsetId item)))))
        (.commit core-txn)
        (.commit refsets-txn)
        (finally (.release item-kb) (.release vb) (.release component-kb) (.release assoc-kb) (.release idx-val))))))

(defn stream-all
  "Stream all values to the channel specified. "
  ([^Env env ^Dbi dbi ch read-fn]
   (stream-all env dbi ch read-fn true))
  ([^Env env ^Dbi dbi ch read-fn close?]
   (async/thread
     (with-open [txn ^Txn (.txnRead ^Env env)
                 cursor (.openCursor ^Dbi dbi txn)]
       (loop [continue? (.first cursor)]
         (if continue?
           (do (async/>!! ch (read-fn (ByteBufInputStream. (.val cursor))))
               (.resetReaderIndex ^ByteBuf (.val cursor))   ;; reset position in value otherwise .next will throw an exception on second item
               (recur (.next cursor)))
           (when close? (clojure.core.async/close! ch))))))))


(defn get-object [^Env env ^Dbi dbi ^long id read-fn]
  (with-open [txn (.txnRead env)]
    (let [kb (.directBuffer (PooledByteBufAllocator/DEFAULT) 8)]
      (try
        (.writeLong kb id)
        (when-let [rb (.get dbi txn kb)]
          (read-fn (ByteBufInputStream. rb)))
        (finally (.release kb))))))

(defn get-concept
  [^LmdbStore store ^long id]
  (get-object (.-coreEnv store) (.-concepts store) id ser/read-concept))

(defn get-description
  "Return the description with the given `description-id`.
  This uses the descriptionId-conceptId index to determine the concept-id,
  as all descriptions are actually stored by conceptId-descriptionId-concept because
  that's a more common operation that finding a description by identifier alone."
  [^LmdbStore store description-id]
  (let [kb (.directBuffer (PooledByteBufAllocator/DEFAULT) 16)]
    (try
      (doto kb (.writeLong description-id) (.writeLong 0))
      (with-open [txn ^Txn (.txnRead ^Env (.-coreEnv store))
                  cursor (.openCursor ^Dbi (.-descriptionConcept store) txn)]
        (when (.get cursor kb GetOp/MDB_SET_RANGE)          ;; put cursor on first entry with this description identifier
          (let [kb' ^ByteBuf (.key cursor)
                did (.readLong kb')
                concept-id (.readLong kb')]
            (when (= description-id did)
              (doto kb .clear (.writeLong concept-id) (.writeLong description-id))
              (when-let [vb (.get ^Dbi (.-conceptDescriptions store) txn kb)]
                (ser/read-description (ByteBufInputStream. vb)))))))
      (finally (.release kb)))))

(defn map-keys-in-range
  "Returns a vector consisting of the result of applying f to the keys in the
  inclusive range between start-key and end-key. 'f' is called with a ByteBuf
  representing a key within the range.
  While each key should be a vector of longs, comparison is character-wise. As
  such,minimum long is 0 and maximum long is -1 (FFFF...) and not Long/MIN_VALUE
   and Long/MAX_VALUE as might be expected."
  [^Env env ^Dbi dbi start-key end-key f]
  (let [n (* 8 (count start-key))
        start-kb (.directBuffer (PooledByteBufAllocator/DEFAULT) n)
        end-kb (.directBuffer (PooledByteBufAllocator/DEFAULT) n)]
    (try
      (doseq [k start-key] (.writeLong start-kb k))
      (doseq [k end-key] (.writeLong end-kb k))
      (with-open [txn ^Txn (.txnRead env)
                  cursor (.openCursor dbi txn)]
        (when (.get cursor start-kb GetOp/MDB_SET_RANGE)
          (loop [results (transient []) continue? true]
            (let [kb ^ByteBuf (.key cursor)]
              (if (and continue? (>= (.compareTo kb start-kb) 0) (>= (.compareTo end-kb kb) 0))
                (recur (conj! results (f kb)) (.next cursor))
                (persistent! results))))))
      (finally (.release start-kb) (.release end-kb)))))

(defn get-concept-descriptions
  "Returns a vector of descriptions for the given concept."
  [^LmdbStore store ^long concept-id]
  (let [start-kb (.directBuffer (PooledByteBufAllocator/DEFAULT) 16)]
    (try
      (doto start-kb (.writeLong concept-id) (.writeLong 0))
      (with-open [txn ^Txn (.txnRead ^Env (.-coreEnv store))
                  cursor (.openCursor ^Dbi (.-conceptDescriptions store) txn)]
        (when (.get cursor start-kb GetOp/MDB_SET_RANGE)    ;; get cursor to first key greater than or equal to specified key.
          (loop [results (transient []) continue? true]
            (if-not (and continue? (= concept-id (.getLong ^ByteBuf (.key cursor) 0)))
              (persistent! results)
              (let [d (ser/read-description (ByteBufInputStream. (.val cursor)))]
                (.resetReaderIndex ^ByteBuf (.val cursor))  ;; reset position in value otherwise .next will throw an exception on second item
                (recur (conj! results d) (.next cursor)))))))
      (finally (.release start-kb)))))

(defn get-relationship
  [^LmdbStore store ^long relationship-id]
  (get-object (.-coreEnv store) (.-relationships store) relationship-id ser/read-relationship))

(defn get-refset-item
  "Get the specified refset item.
  Parameters:
  - store
  - UUID  : the UUID of the refset item to fetch
  - msb/lsb : the most and least significant 64-bit longs representing the UUID."
  ([^LmdbStore store ^UUID uuid]
   (get-refset-item store (.getMostSignificantBits uuid) (.getLeastSignificantBits uuid)))
  ([^LmdbStore store ^long msb ^long lsb]
   (with-open [txn (.txnRead ^Env (.-refsetsEnv store))]
     (let [kb (.directBuffer (PooledByteBufAllocator/DEFAULT) 16)]
       (try
         (doto kb (.writeLong msb) (.writeLong lsb))
         (when-let [vb (.get ^Dbi (.-refsetItems store) txn kb)]
           (ser/read-refset-item (ByteBufInputStream. vb)))
         (finally (.release kb)))))))


(defn get-raw-parent-relationships
  "Return the parent relationships of the given concept.
  Returns a list of tuples (from--type--group--to)."
  ([^LmdbStore store concept-id]
   (map-keys-in-range (.-coreEnv store) (.-conceptParentRelationships store)
                      [concept-id 0 0 0]
                      [concept-id -1 -1 -1]
                      (fn [^ByteBuf b] (vector (.readLong b) (.readLong b) (.readLong b) (.readLong b)))))
  ([^LmdbStore store concept-id type-id]
   (map-keys-in-range (.-coreEnv store) (.-conceptParentRelationships store)
                      [concept-id type-id 0 0]
                      [concept-id type-id -1 -1]
                      (fn [^ByteBuf b] (vector (.readLong b) (.readLong b) (.readLong b) (.readLong b))))))

(defn get-raw-child-relationships
  "Return the child relationships of the given concept.
  Returns a list of tuples (from--type--group--to)."
  ([^LmdbStore store concept-id]
   (map-keys-in-range (.-coreEnv store) (.-conceptChildRelationships store)
                      [concept-id 0 0 0]
                      [concept-id -1 -1 -1]
                      (fn [^ByteBuf b] (vector (.readLong b) (.readLong b) (.readLong b) (.readLong b)))))
  ([^LmdbStore store concept-id type-id]
   (map-keys-in-range (.-coreEnv store) (.-conceptChildRelationships store)
                      [concept-id type-id 0 0]
                      [concept-id type-id -1 -1]
                      (fn [^ByteBuf b] (vector (.readLong b) (.readLong b) (.readLong b) (.readLong b))))))

(defn get-component-refset-items
  "Get the refset items for the given component, optionally
   limited to the refset specified.
   - store
   - component-id : id of the component (e.g concept-id or description-id)
   - refset-id    : (optional) - limit to this refset."
  ([^LmdbStore store component-id]
   (map-keys-in-range (.-coreEnv store) (.-componentRefsets store)
                      [component-id 0 0 0]
                      [component-id -1 -1 -1]
                      (fn [^ByteBuf b] (get-refset-item store (.getLong b 16) (.getLong b 24)))))
  ([^LmdbStore store component-id refset-id]
   (map-keys-in-range (.-coreEnv store) (.-componentRefsets store)
                      [component-id refset-id 0 0]
                      [component-id refset-id -1 -1]
                      (fn [^ByteBuf b] (get-refset-item store (.getLong b 16) (.getLong b 24))))))

(defn get-component-refset-ids
  "Return a set of refset-ids to which this component belongs."
  [^LmdbStore store component-id]
  (set (map-keys-in-range (.-coreEnv store) (.-componentRefsets store)
                          [component-id 0 0 0]
                          [component-id -1 -1 -1]
                          (fn [^ByteBuf b] (.getLong b 8)))))

(defn stream-all-concepts
  "Asynchronously stream all concepts to the channel specified, and, by default,
  closing the channel when done unless specified.
  Returns a channel which, by default, will be closed when done."
  ([^LmdbStore store ch]
   (stream-all-concepts store ch true))
  ([^LmdbStore store ch close?]
   (stream-all (.-coreEnv store) (.-concepts store) ch ser/read-concept close?)))

(defn stream-all-refset-items
  ([^LmdbStore store ch]
   (stream-all-refset-items store ch true))
  ([^LmdbStore store ch close?]
   (stream-all (.-refsetsEnv store) (.-refsetItems store) ch ser/read-refset-item close?)))

(defn get-refset-field-names
  "Returns the field names for the given reference set.

  The reference set descriptors provide a human-readable description and a type
  for each column in a reference set, but do not include the camel-cased column
  identifier in the original source file. On import, we store those column names
  and provide the lookup here."
  [^LmdbStore store refset-id]
  (get-object (.-refsetsEnv store) (.-refsetFieldNames store) refset-id ser/read-field-names))

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
  [^LmdbStore store]
  (with-open [txn ^Txn (.txnRead ^Env (.-refsetsEnv store))
              cursor (.openCursor ^Dbi (.-refsetFieldNames store) txn)]
    (loop [results (transient #{})
           continue? (.first cursor)]
      (if continue?
        (recur (conj! results (.readLong ^ByteBuf (.key cursor))) (.next cursor))
        (persistent! results)))))

(defn get-source-associations
  "Returns the associations in which this component is the target.
  targetComponentId -- refsetId -- referencedComponentId - itemId1 - itemId2"
  ([^LmdbStore store component-id]
   (map-keys-in-range (.-coreEnv store) (.-associations store)
                      [component-id 0 0 0 0]
                      [component-id -1 -1 -1 -1]
                      (fn [^ByteBuf b] (get-refset-item store (.getLong b 24) (.getLong b 32)))))
  ([^LmdbStore store component-id refset-id]
   (map-keys-in-range (.-coreEnv store) (.-associations store)
                      [component-id refset-id 0 0 0]
                      [component-id refset-id -1 -1 -1]
                      (fn [^ByteBuf b] (get-refset-item store (.getLong b 24) (.getLong b 32))))))

(defn get-source-association-referenced-components
  "Returns a sequence of component identifiers that reference the specified
  component in the specified association reference set."
  [^LmdbStore store component-id refset-id]
  (set (map-keys-in-range (.-coreEnv store) (.-associations store)
                          [component-id refset-id 0 0 0]
                          [component-id refset-id -1 -1 -1]
                          (fn [^ByteBuf b] (.getLong b 16)))))

(defn compact [^LmdbStore store])
;;;NOP

(defn status
  [^LmdbStore store]
  (with-open [^Txn core-txn (.txnRead ^Env (.-coreEnv store))
              ^Txn refsets-txn (.txnRead ^Env (.-refsetsEnv store))]
    {:concepts      (.entries (.stat ^Dbi (.-concepts store) core-txn))
     :descriptions  (.entries (.stat ^Dbi (.-conceptDescriptions store) core-txn))
     :relationships (.entries (.stat ^Dbi (.-relationships store) core-txn))
     :refsets       (.entries (.stat ^Dbi (.-refsetFieldNames store) refsets-txn))
     :refset-items  (.entries (.stat ^Dbi (.-refsetItems store) refsets-txn))
     :indices       {:descriptions-concept         (.entries (.stat ^Dbi (.-descriptionConcept store) core-txn))
                     :concept-parent-relationships (.entries (.stat ^Dbi (.conceptParentRelationships store) core-txn))
                     :concept-child-relationships  (.entries (.stat ^Dbi (.conceptChildRelationships store) core-txn))
                     :component-refsets            (.entries (.stat ^Dbi (.componentRefsets store) core-txn))
                     :associations                 (.entries (.stat ^Dbi (.associations store) core-txn))}}))

