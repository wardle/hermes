; Copyright (c) 2020-2023 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns ^:no-doc com.eldrix.hermes.impl.lmdb
  "A backing key value store implemented using LMDB.

  LMDB has very fast read access, which makes it highly suitable as hermes
  operates principally in read-only mode. We use netty's direct buffers, and a
  shared pool because of allocation overhead compared to on-heap buffers.

  We use key value stores in one of two ways. The first is to store entities.
  These are usually keyed by the identifier, except for descriptions, which are
  keyed by a tuple of concept identifier and description identifier. That
  optimises the common fetch of all descriptions for a given concept. The second
  is to store null values as part of an index, with compound keys. This means
  we can rapidly iterate across a range of keys, which are always sorted, and
  stored big-endian. The compound key structures are defined below.

  It would be possible to create a generic key-value protocol, but this instead
  contains domain-optimised code."
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [com.eldrix.hermes.impl.ser :as ser])
  (:import [org.lmdbjava Env EnvFlags DbiFlags Dbi ByteBufProxy PutFlags Txn GetOp CopyFlags]
           (java.nio.file.attribute FileAttribute)
           (io.netty.buffer PooledByteBufAllocator ByteBuf)
           (java.nio.charset StandardCharsets)
           (java.time LocalDate)
           (com.eldrix.hermes.snomed Concept Description Relationship ConcreteValue)
           (java.util UUID)
           (java.io Closeable File)
           (java.nio.file Files Path CopyOption StandardCopyOption)))

(set! *warn-on-reflection* true)

(s/def ::store any?)

(deftype LmdbStore
         [^Path rootPath
   ;;;; core env
          ^Env coreEnv
   ;; core stores - simple or compound keys and values
          ^Dbi concepts                                            ;; conceptId = concept
          ^Dbi conceptDescriptions                                 ;; conceptId-descriptionId = description
          ^Dbi relationships                                       ;; relationshipId = relationship
          ^Dbi concreteValues                                      ;; conceptId-relationshipId = concreteValue

   ;; core indices - compound keys with empty values
          ^Dbi descriptionConcept                                  ;; descriptionId - conceptId
          ^Dbi conceptParentRelationships                          ;; sourceId - typeId - group - destinationId
          ^Dbi conceptChildRelationships                           ;; destinationId - typeId - group - sourceId
          ^Dbi componentRefsets                                    ;; referencedComponentId - refsetId - msb - lsb
          ^Dbi associations                                        ;; targetComponentId - refsetId - referencedComponentId - msb - lsb
   ;;;; refset env
          ^Env refsetsEnv
          ^Dbi refsetItems                                         ;; refset-item-id = refset-item
          ^Dbi refsetFieldNames]                                   ;; refset-id = field-names]
  Closeable
  (close [_]
    (when-not (.isReadOnly coreEnv)
      (.sync coreEnv true)                                  ;; as we're not using synchronous or asynchronous flushes, manually flush on close
      (.sync refsetsEnv true))
    (.close ^Env coreEnv)
    (.close ^Env refsetsEnv)))

(def ^:private rw-env-flags
  [EnvFlags/MDB_NOSUBDIR EnvFlags/MDB_NOTLS EnvFlags/MDB_WRITEMAP EnvFlags/MDB_NORDAHEAD EnvFlags/MDB_NOSYNC EnvFlags/MDB_NOMETASYNC])

(def ^:private ro-env-flags
  [EnvFlags/MDB_NOSUBDIR EnvFlags/MDB_NOTLS EnvFlags/MDB_NOLOCK EnvFlags/MDB_RDONLY_ENV])

(defn make-dbi-flags
  ^"[Lorg.lmdbjava.DbiFlags;" [read-only? & flags]
  (into-array DbiFlags (if read-only? flags (conj flags DbiFlags/MDB_CREATE))))

(def ^:private default-map-size (* 5 1024 1024 1024))

(defn dbi-names
  "Returns an environment's DBI names as a set."
  [^Env env]
  (set (map #(String. ^bytes % StandardCharsets/UTF_8) (.getDbiNames env))))

(defn- open*
  "Open a store at the path specified.
  f          : path of directory, anything coercible by clojure.io/as-file
  read-only? : whether to open read-only; default true
  map-size   : size in bytes, default 5gb"
  [f & {:keys [read-only? map-size] :or {read-only? true map-size default-map-size}}]
  (let [f' ^File (io/file f)]
    (when-not (.exists f')
      (if read-only?
        (throw (ex-info "Store not found and opening in read-only mode" {:f f}))
        (Files/createDirectory (.toPath f') (make-array FileAttribute 0))))
    (let [root-path (.toPath f')
          core-f (.toFile (.resolve root-path "core.db"))
          refsets-f (.toFile (.resolve root-path "refsets.db"))
          ^Env core-env (-> (Env/create ByteBufProxy/PROXY_NETTY)
                            (.setMapSize map-size) (.setMaxDbs 9)
                            (.open core-f (into-array EnvFlags (if read-only? ro-env-flags rw-env-flags))))
          ^Env refsets-env (-> (Env/create ByteBufProxy/PROXY_NETTY)
                               (.setMapSize map-size) (.setMaxDbs 2)
                               (.open refsets-f (into-array EnvFlags (if read-only? ro-env-flags rw-env-flags))))
          base-flags (make-dbi-flags read-only?)
          ;; core env
          concepts (.openDbi core-env "c" base-flags)
          conceptDescriptions (.openDbi core-env "d" base-flags)
          relationships (.openDbi core-env "r" base-flags)
          concreteValues (.openDbi core-env "cv" base-flags)
          descriptionConcept (.openDbi core-env "dc" base-flags)
          conceptParentRelationships (.openDbi core-env "cpr" base-flags)
          conceptChildRelationships (.openDbi core-env "ccr" base-flags)
          componentRefsets (.openDbi core-env "cr" base-flags)
          associations (.openDbi core-env "a" base-flags)
          ;; refsets env
          refsetItems (.openDbi refsets-env "rs" base-flags)
          refsetFieldNames (.openDbi refsets-env "rs-n" base-flags)]
      (->LmdbStore root-path
                   core-env
                   concepts conceptDescriptions relationships concreteValues
                   descriptionConcept conceptParentRelationships
                   conceptChildRelationships componentRefsets associations
                   refsets-env
                   refsetItems refsetFieldNames))))

(defn open-store
  (^Closeable []
   (open-store (.toFile (Files/createTempDirectory "hermes-lmdb-" (make-array FileAttribute 0))) {:read-only? false}))
  (^Closeable [f]
   (open-store f {}))
  (^Closeable [f opts]
   (open* f opts)))

(defn compact-and-close
  [^LmdbStore store]
  (let [^Path path (.-rootPath store)
        core (.resolve path "core.db")
        core' (.resolve path "core2.db")
        refsets (.resolve path "refsets.db")
        refsets' (.resolve path "refsets2.db")]
    (Files/deleteIfExists core')
    (.copy ^Env (.-coreEnv store) (.toFile core') (into-array ^CopyFlags [CopyFlags/MDB_CP_COMPACT]))
    (Files/deleteIfExists refsets')
    (.copy ^Env (.-refsetsEnv store) (.toFile refsets') (into-array ^CopyFlags [CopyFlags/MDB_CP_COMPACT]))
    (.close store)
    (Files/move core' core (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
    (Files/move refsets' refsets (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))))

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
    (or (not existing) (.isAfter effectiveTime (ser/read-effective-time existing read-offset)))))

(defn write-concepts
  "Each concept is stored as an entity in the 'concepts' db keyed by identifier."
  [^LmdbStore store concepts]
  (with-open [txn (.txnWrite ^Env (.-coreEnv store))]
    (let [^Dbi db (.-concepts store)
          kb (.directBuffer PooledByteBufAllocator/DEFAULT 8)
          vb (.directBuffer PooledByteBufAllocator/DEFAULT 512)]
      (try (doseq [^Concept concept concepts]
             (doto kb .clear (.writeLong (.-id concept)))
             (when (should-write-object? db txn kb 8 (.-effectiveTime concept))
               (.clear vb)
               (ser/write-concept vb concept)
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
    (let [^Dbi db (.-conceptDescriptions store)             ;; concept-id - description-id = description
          ^Dbi idx (.-descriptionConcept store)             ;; description-id - concept-id = nil
          kb (.directBuffer PooledByteBufAllocator/DEFAULT 16)
          vb (.directBuffer PooledByteBufAllocator/DEFAULT 512)
          idx-key (.directBuffer PooledByteBufAllocator/DEFAULT 16)
          idx-val (.directBuffer PooledByteBufAllocator/DEFAULT 0)]
      (try (doseq [^Description description descriptions]
             (doto kb
               .clear
               (.writeLong (.-conceptId description))
               (.writeLong (.-id description)))
             (doto idx-key
               .clear
               (.writeLong (.-id description))
               (.writeLong (.-conceptId description)))
             (when (should-write-object? db txn kb 8 (.-effectiveTime description))
               (.clear vb)
               (ser/write-description vb description)
               (.put db txn kb vb put-flags)
               (.put idx txn idx-key idx-val put-flags)))
           (.commit txn)
           (finally (.release kb) (.release vb) (.release idx-key) (.release idx-val))))))

(defn write-relationships
  "Each relationship is stored as an entity in the 'relationships' db, keyed
  by a relationship-id."
  [^LmdbStore store relationships]
  (with-open [txn (.txnWrite ^Env (.-coreEnv store))]
    (let [^Dbi db (.-relationships store)
          kb (.directBuffer PooledByteBufAllocator/DEFAULT 8) ;; relationship id
          vb (.directBuffer PooledByteBufAllocator/DEFAULT 64)] ;; relationship entity
      (try (doseq [^Relationship relationship relationships]
             (doto kb
               .clear
               (.writeLong (.-id relationship)))
             (when (should-write-object? db txn kb 8 (.-effectiveTime relationship)) ;; skip an 8 byte id (relationship-id) in the value
               (.clear vb)
               (ser/write-relationship vb relationship)
               (.put db txn kb vb put-flags)))
           (.commit txn)
           (finally (.release kb) (.release vb))))))

(defn write-concrete-values
  "Write concrete values to the store. Only active concrete values are stored.
  Inactive concrete values are managed carefully so that if appropriate,
  previously stored concrete values are deleted."
  [^LmdbStore store concrete-values]
  (with-open [txn (.txnWrite ^Env (.-coreEnv store))]
    (let [^Dbi db (.-concreteValues store)
          kb (.directBuffer PooledByteBufAllocator/DEFAULT 16) ;; conceptId-relationshipId  (compound key)
          vb (.directBuffer PooledByteBufAllocator/DEFAULT 4096)] ;; concrete value entity
      (try (doseq [^ConcreteValue cv concrete-values]
             (doto kb
               .clear
               (.writeLong (.-sourceId cv))
               (.writeLong (.-id cv)))
             (when (should-write-object? db txn kb 8 (.-effectiveTime cv)) ;; skip an 8 byte id (relationship-id) in the value
               (if (:active cv)
                 (do (.clear vb)
                     (ser/write-concrete-value vb cv)
                     (.put db txn kb vb put-flags))
                 (.delete db txn kb))))
           (.commit txn)
           (finally (.release kb) (.release vb))))))

(defn index-relationships
  "Iterates all active relationships and rebuilds parent and child indices.
  Each *active* relationship is referenced in the 'conceptParentRelationships'
  and 'conceptChildRelationships' indices."
  [^LmdbStore store]
  (with-open [^Txn write-txn (.txnWrite ^Env (.-coreEnv store))
              ^Txn read-txn (.txnRead ^Env (.-coreEnv store))
              cursor (.openCursor ^Dbi (.-relationships store) read-txn)]
    (let [^Dbi parent-idx (.-conceptParentRelationships store)
          ^Dbi child-idx (.-conceptChildRelationships store)
          parent-idx-key (.directBuffer PooledByteBufAllocator/DEFAULT 32) ;; sourceId -- typeId -- group -- destinationId
          child-idx-key (.directBuffer PooledByteBufAllocator/DEFAULT 32) ;; destinationId -- typeId -- group -- sourceId
          idx-val (.directBuffer PooledByteBufAllocator/DEFAULT 0)] ;; empty value
      (try
        (.drop parent-idx write-txn) ;; delete all parent and child indices
        (.drop child-idx write-txn)
        (loop [continue? (.first cursor)]
          (when continue?
            (let [^Relationship relationship (ser/read-relationship (.val cursor))]
              (when (.-active relationship)
                (doto parent-idx-key .clear
                      (.writeLong (.-sourceId relationship))
                      (.writeLong (.-typeId relationship))
                      (.writeLong (.-relationshipGroup relationship))
                      (.writeLong (.-destinationId relationship)))
                (doto child-idx-key .clear
                      (.writeLong (.-destinationId relationship))
                      (.writeLong (.-typeId relationship))
                      (.writeLong (.-relationshipGroup relationship))
                      (.writeLong (.-sourceId relationship)))
                (.put parent-idx write-txn parent-idx-key idx-val put-flags)
                (.put child-idx write-txn child-idx-key idx-val put-flags)))
            (.resetReaderIndex ^ByteBuf (.val cursor))      ;; reset position in value otherwise .next will throw an exception on second item
            (recur (.next cursor))))
        (.commit write-txn)
        (finally (.release parent-idx-key) (.release child-idx-key) (.release idx-val))))))

(defn- write-refset-headings
  [^LmdbStore store ^Txn txn refset-id headings]
  (when refset-id
    (let [^Dbi headings-db (.-refsetFieldNames store)
          kb (.directBuffer PooledByteBufAllocator/DEFAULT 8)
          vb (.directBuffer PooledByteBufAllocator/DEFAULT 512)]
      (try (.writeLong kb refset-id)
           (ser/write-field-names vb (or headings []))
           (.put headings-db txn kb vb put-flags)
           (finally (.release kb)
                    (.release vb))))))

(defn write-refset-items
  "Each reference set item is stored as an entity in the 'refsetItems' db, keyed
  by the UUID, a tuple of msb and lsb.

  During import, a record of refset field names is created:
  - refsetFieldNames  : refset-id -- field-names (an array of strings)"
  [^LmdbStore store headings items]
  (with-open [refsets-txn (.txnWrite ^Env (.-refsetsEnv store))]
    (let [^Dbi items-db (.-refsetItems store)
          item-kb (.directBuffer PooledByteBufAllocator/DEFAULT 16) ;; a UUID - 16 bytes
          vb (.directBuffer PooledByteBufAllocator/DEFAULT 512)]
      (try
        (loop [items' items, refset-ids #{}]
          (when-let [item (first items')]
            (when-not (contains? refset-ids (:refsetId item))
              (write-refset-headings store refsets-txn (:refsetId item) headings))
            (let [msb (.getMostSignificantBits ^UUID (:id item))
                  lsb (.getLeastSignificantBits ^UUID (:id item))]
              (doto item-kb
                .clear
                (.writeLong msb)
                (.writeLong lsb))
              (when (should-write-object? items-db refsets-txn item-kb 17 (:effectiveTime item)) ;; skip a 17 byte key (type-msb-lsb; type = 1 byte, msb = 8 bytes, lsb = 8 bytes)
                (.clear vb)
                (ser/write-refset-item vb item)
                (.put items-db refsets-txn item-kb vb put-flags)))
            (recur (next items') (conj refset-ids (:refsetId item)))))
        (.commit refsets-txn)
        (finally (.release item-kb) (.release vb))))))

(defn index-refsets
  "Iterates all active reference set items and rebuilds indices.
  Each *active* item is indexed:
  - componentRefsets  : referencedComponentId -- refsetId -- msb -- lsb
  - associations      : targetComponentId -- refsetId -- referencedComponentId - msb - lsb
  We delete all indexes "
  [^LmdbStore store]
  (with-open [^Txn write-txn (.txnWrite ^Env (.-coreEnv store))
              ^Txn read-txn (.txnRead ^Env (.-refsetsEnv store))
              cursor (.openCursor ^Dbi (.-refsetItems store) read-txn)]
    (let [^Dbi components-db (.-componentRefsets store)
          ^Dbi assocs-db (.-associations store)
          component-kb (.directBuffer PooledByteBufAllocator/DEFAULT 32) ;; referencedComponentId -- refsetId -- msb -- lsb
          assoc-kb (.directBuffer PooledByteBufAllocator/DEFAULT 40) ;; targetComponentId -- refsetId -- referencedComponentId - msb - lsb
          idx-val (.directBuffer PooledByteBufAllocator/DEFAULT 0)]
      (try
        (.drop components-db write-txn)   ;; delete all existing indices prior to re-indexing
        (.drop assocs-db write-txn)
        (loop [continue? (.first cursor)]
          (when continue?
            (let [item (ser/read-refset-item (.val cursor))
                  msb (.getMostSignificantBits ^UUID (:id item))
                  lsb (.getLeastSignificantBits ^UUID (:id item))
                  target-id (:targetComponentId item)]
              (when (:active item)
                (doto component-kb
                  .clear
                  (.writeLong (:referencedComponentId item))
                  (.writeLong (:refsetId item))
                  (.writeLong msb)
                  (.writeLong lsb))
                (.put components-db write-txn component-kb idx-val put-flags)
                (when target-id
                  (doto assoc-kb
                    .clear
                    (.writeLong target-id)
                    (.writeLong (:refsetId item))
                    (.writeLong (:referencedComponentId item))
                    (.writeLong msb)
                    (.writeLong lsb))
                  (.put assocs-db write-txn assoc-kb idx-val put-flags))))
            (.resetReaderIndex ^ByteBuf (.val cursor))
            (recur (.next cursor))))
        (.commit write-txn)
        (finally (.release component-kb) (.release assoc-kb) (.release idx-val))))))

(defn stream-all
  "Blocking; stream all values from the specified dbi to the channel specified.
  It would usually be appropriate to run in a background thread."
  ([^Env env ^Dbi dbi ch read-fn]
   (stream-all env dbi ch read-fn true))
  ([^Env env ^Dbi dbi ch read-fn close?]
   (with-open [^Txn txn (.txnRead ^Env env)
               cursor (.openCursor ^Dbi dbi txn)]
     (loop [continue? (.first cursor)]
       (if continue?
         (when (a/>!! ch (read-fn (.val cursor)))           ;; >!! will return true unless channel closed
           (.resetReaderIndex ^ByteBuf (.val cursor))       ;; reset position in value otherwise .next will throw an exception on second item
           (recur (.next cursor)))
         (when close? (a/close! ch)))))))

(defn get-object [^Env env ^Dbi dbi ^long id read-fn]
  (with-open [txn (.txnRead env)]
    (let [kb (.directBuffer PooledByteBufAllocator/DEFAULT 8)]
      (try
        (.writeLong kb id)
        (when-let [rb (.get dbi txn kb)]
          (read-fn rb))
        (finally (.release kb))))))

(defn concept
  [^LmdbStore store ^long concept-id]
  (get-object (.-coreEnv store) (.-concepts store) concept-id ser/read-concept))

(defn description
  "Return the description with the given `concept-id` and `description-id`.

  If no concept-id is given, this uses the descriptionId-conceptId index to
  first determine the concept-id, as all descriptions are actually stored by
  conceptId-descriptionId-concept because that's a more common operation than
  finding a description by identifier alone."
  ([^LmdbStore store ^long description-id]
   (let [kb (.directBuffer PooledByteBufAllocator/DEFAULT 16)]
     (try
       (doto kb (.writeLong description-id) (.writeLong 0))
       (with-open [txn ^Txn (.txnRead ^Env (.-coreEnv store))
                   cursor (.openCursor ^Dbi (.-descriptionConcept store) txn)]
         (when (.get cursor kb GetOp/MDB_SET_RANGE)         ;; put cursor on first entry with this description identifier
           (let [^ByteBuf kb' (.key cursor)
                 did (.readLong kb')
                 concept-id (.readLong kb')]
             (when (= description-id did)
               (doto kb
                 .clear
                 (.writeLong concept-id)
                 (.writeLong description-id))
               (when-let [vb (.get ^Dbi (.-conceptDescriptions store) txn kb)]
                 (ser/read-description vb))))))
       (finally (.release kb)))))
  ([^LmdbStore store ^long concept-id ^long description-id]
   (with-open [txn (.txnRead ^Env (.-coreEnv store))]
     (let [kb (.directBuffer PooledByteBufAllocator/DEFAULT 16)]
       (try
         (.writeLong kb concept-id)
         (.writeLong kb description-id)
         (when-let [rb (.get ^Dbi (.-conceptDescriptions store) txn kb)]
           (ser/read-description rb))
         (finally (.release kb)))))))

(defn map-keys-in-range*
  "Returns a vector consisting of the result of applying f to the keys in the
  inclusive range between start-key and end-key. 'f' is called with a ByteBuf
  representing a key within the range."
  [^Env env ^Dbi dbi ^ByteBuf start-kb ^ByteBuf end-kb f]
  (with-open [^Txn txn (.txnRead env)
              cursor (.openCursor dbi txn)]
    (when (.get cursor start-kb GetOp/MDB_SET_RANGE)
      (loop [results (transient []), continue? true]
        (let [^ByteBuf kb (.key cursor)]
          (if (and continue? (>= (.compareTo kb start-kb) 0) (>= (.compareTo end-kb kb) 0))
            (recur (conj! results (f kb)) (.next cursor))
            (persistent! results)))))))

(defmacro write-longs
  [buf xs]
  (cons 'do (for [x xs] `(.writeLong ~buf ~x))))

(defmacro map-keys-in-range
  "Returns a vector consisting of the result of applying f to the keys in the
  inclusive range between start-key and end-key. 'f' is called with a ByteBuf
  representing a key within the range.
  While each key should be a vector of longs, comparison is character-wise. As
  such,minimum long is 0 and maximum long is -1 (FFFF...) and not Long/MIN_VALUE
   and Long/MAX_VALUE as might be expected."
  [^Env env ^Dbi dbi start-key end-key f]
  (assert (vector? start-key) "start-key must be a vector")
  (assert (vector? end-key) "end-key must be a vector")
  (assert (= (count start-key) (count end-key)) "start-key and end-key must be same length")
  (let [n (* 8 (count start-key))]
    `(let [start-kb# (.directBuffer PooledByteBufAllocator/DEFAULT ~n)
           end-kb# (.directBuffer PooledByteBufAllocator/DEFAULT ~n)]
       (try
         (write-longs start-kb# ~start-key)
         (write-longs end-kb# ~end-key)
         (map-keys-in-range* ~env ~dbi start-kb# end-kb# ~f)
         (finally (.release start-kb#) (.release end-kb#))))))

(defn concept-descriptions
  "Returns a vector of descriptions for the given concept."
  [^LmdbStore store ^long concept-id]
  (let [start-kb (.directBuffer PooledByteBufAllocator/DEFAULT 16)]
    (try
      (doto start-kb (.writeLong concept-id) (.writeLong 0))
      (with-open [^Txn txn (.txnRead ^Env (.-coreEnv store))
                  cursor (.openCursor ^Dbi (.-conceptDescriptions store) txn)]
        (when (.get cursor start-kb GetOp/MDB_SET_RANGE)    ;; get cursor to first key greater than or equal to specified key.
          (loop [results (transient []) continue? true]
            (if-not (and continue? (= concept-id (.getLong ^ByteBuf (.key cursor) 0)))
              (persistent! results)
              (let [d (ser/read-description (.val cursor))]
                (.resetReaderIndex ^ByteBuf (.val cursor))  ;; reset position in value otherwise .next will throw an exception on second item
                (recur (conj! results d) (.next cursor)))))))
      (finally (.release start-kb)))))

(defn relationship
  [^LmdbStore store ^long relationship-id]
  (get-object (.-coreEnv store) (.-relationships store) relationship-id ser/read-relationship))

(defn concrete-values
  "Return concrete values for the specified concept, if the underlying store
  supports concrete values. Only active concrete values are returned."
  [^LmdbStore store ^long concept-id]
  (let [start-kb (.directBuffer PooledByteBufAllocator/DEFAULT 16)]
    (try
      (doto start-kb (.writeLong concept-id) (.writeLong 0))
      (with-open [^Txn txn (.txnRead ^Env (.-coreEnv store))
                  cursor (.openCursor ^Dbi (.-concreteValues store) txn)]
        (when (.get cursor start-kb GetOp/MDB_SET_RANGE)    ;; get cursor to first key greater than or equal to specified key.
          (loop [results (transient []) continue? true]
            (if-not (and continue? (= concept-id (.getLong ^ByteBuf (.key cursor) 0)))
              (persistent! results)
              (let [d (ser/read-concrete-value (.val cursor))]
                (.resetReaderIndex ^ByteBuf (.val cursor))  ;; reset position in value otherwise .next will throw an exception on second item
                (recur (conj! results d) (.next cursor)))))))
      (finally (.release start-kb)))))

(defn refset-item
  "Get the specified refset item.
  Parameters:
  - store
  - UUID  : the UUID of the refset item to fetch
  - msb/lsb : the most and least significant 64-bit longs representing the UUID."
  ([^LmdbStore store ^UUID uuid]
   (refset-item store (.getMostSignificantBits uuid) (.getLeastSignificantBits uuid)))
  ([^LmdbStore store ^long msb ^long lsb]
   (with-open [txn (.txnRead ^Env (.-refsetsEnv store))]
     (let [kb (.directBuffer PooledByteBufAllocator/DEFAULT 16)]
       (try
         (doto kb (.writeLong msb) (.writeLong lsb))
         (when-let [vb (.get ^Dbi (.-refsetItems store) txn kb)]
           (ser/read-refset-item vb))
         (finally (.release kb)))))))

(defn raw-parent-relationships
  "Return either all parent relationships of the given concept or only those
  relationships of the given type.
  Returns a vector of tuples [from--type--group--to]."
  ([^LmdbStore store ^long concept-id]
   (map-keys-in-range (.-coreEnv store) (.-conceptParentRelationships store)
                      [concept-id 0 0 0]
                      [concept-id -1 -1 -1]
                      (fn [^ByteBuf b] (vector (.readLong b) (.readLong b) (.readLong b) (.readLong b)))))
  ([^LmdbStore store ^long concept-id ^long type-id]
   (map-keys-in-range (.-coreEnv store) (.-conceptParentRelationships store)
                      [concept-id type-id 0 0]
                      [concept-id type-id -1 -1]
                      (fn [^ByteBuf b] (vector (.readLong b) (.readLong b) (.readLong b) (.readLong b))))))

(defn raw-child-relationships
  "Return the child relationships of the given concept.
  Returns a vector of tuples [from--type--group--to]."
  ([^LmdbStore store ^long concept-id]
   (map-keys-in-range (.-coreEnv store) (.-conceptChildRelationships store)
                      [concept-id 0 0 0]
                      [concept-id -1 -1 -1]
                      (fn [^ByteBuf b] (vector (.readLong b) (.readLong b) (.readLong b) (.readLong b)))))
  ([^LmdbStore store ^long concept-id ^long type-id]
   (map-keys-in-range (.-coreEnv store) (.-conceptChildRelationships store)
                      [concept-id type-id 0 0]
                      [concept-id type-id -1 -1]
                      (fn [^ByteBuf b] (vector (.readLong b) (.readLong b) (.readLong b) (.readLong b))))))

(defn component-refset-items
  "Get the refset items for the given component, optionally
   limited to the refset specified.
   - store
   - component-id : id of the component (e.g concept-id or description-id)
   - refset-id    : (optional) - limit to this refset."
  ([^LmdbStore store ^long component-id]
   (map-keys-in-range (.-coreEnv store) (.-componentRefsets store)
                      [component-id 0 0 0]
                      [component-id -1 -1 -1]
                      (fn [^ByteBuf b] (refset-item store (.getLong b 16) (.getLong b 24)))))
  ([^LmdbStore store ^long component-id ^long refset-id]
   (map-keys-in-range (.-coreEnv store) (.-componentRefsets store)
                      [component-id refset-id 0 0]
                      [component-id refset-id -1 -1]
                      (fn [^ByteBuf b] (refset-item store (.getLong b 16) (.getLong b 24))))))

(defn component-refset-ids
  "Return a set of refset-ids to which this component belongs."
  [^LmdbStore store ^long component-id]
  (set (map-keys-in-range (.-coreEnv store) (.-componentRefsets store)
                          [component-id 0 0 0]
                          [component-id -1 -1 -1]
                          (fn [^ByteBuf b] (.getLong b 8)))))

(defn component-in-refset?
  "Is the component in the reference set specified?"
  [^LmdbStore store ^long component-id ^long refset-id]
  (some true? (map-keys-in-range (.-coreEnv store) (.-componentRefsets store)
                                 [component-id refset-id 0 0]
                                 [component-id refset-id -1 -1]
                                 (fn [_] true))))

(defn stream-all-concepts
  "Streams all concepts to the channel specified, and, by default, closes the
  channel when done, unless specified."
  ([^LmdbStore store ch]
   (stream-all-concepts store ch true))
  ([^LmdbStore store ch close?]
   (stream-all (.-coreEnv store) (.-concepts store) ch ser/read-concept close?)))

(defn stream-all-refset-items
  ([^LmdbStore store ch]
   (stream-all-refset-items store ch true))
  ([^LmdbStore store ch close?]
   (stream-all (.-refsetsEnv store) (.-refsetItems store) ch ser/read-refset-item close?)))

(defn refset-field-names
  "Returns the field names for the given reference set.

  The reference set descriptors provide a human-readable description and a type
  for each column in a reference set, but do not include the camel-cased column
  identifier in the original source file. On import, we store those column names
  and provide the lookup here."
  [^LmdbStore store ^long refset-id]
  (get-object (.-refsetsEnv store) (.-refsetFieldNames store) refset-id ser/read-field-names))

(defn installed-reference-sets
  "Returns a set of identifiers representing installed reference sets.

  While it is possible to use the SNOMED ontology to find all reference sets:
    ```
    (get-leaves store (all-children store 900000000000455006))
    ```
  That might return reference sets with no actual members in the installed
  edition. Instead, we keep track of installed reference sets as we import
  reference set items, thus ensuring we have a list that contains only
  reference sets with members."
  [^LmdbStore store]
  (with-open [^Txn txn (.txnRead ^Env (.-refsetsEnv store))
              cursor (.openCursor ^Dbi (.-refsetFieldNames store) txn)]
    (loop [results (transient #{})
           continue? (.first cursor)]
      (if continue?
        (recur (conj! results (.readLong ^ByteBuf (.key cursor))) (.next cursor))
        (persistent! results)))))

(defn source-associations
  "Returns the associations in which this component is the target.
  targetComponentId -- refsetId -- referencedComponentId - itemId1 - itemId2"
  ([^LmdbStore store ^long component-id]
   (map-keys-in-range (.-coreEnv store) (.-associations store)
                      [component-id 0 0 0 0]
                      [component-id -1 -1 -1 -1]
                      (fn [^ByteBuf b] (refset-item store (.getLong b 24) (.getLong b 32)))))
  ([^LmdbStore store ^long component-id ^long refset-id]
   (map-keys-in-range (.-coreEnv store) (.-associations store)
                      [component-id refset-id 0 0 0]
                      [component-id refset-id -1 -1 -1]
                      (fn [^ByteBuf b] (refset-item store (.getLong b 24) (.getLong b 32))))))

(defn source-association-referenced-components
  "Returns a sequence of component identifiers that reference the specified
  component in the specified association reference set."
  [^LmdbStore store ^long component-id ^long refset-id]
  (set (map-keys-in-range (.-coreEnv store) (.-associations store)
                          [component-id refset-id 0 0 0]
                          [component-id refset-id -1 -1 -1]
                          (fn [^ByteBuf b] (.getLong b 16)))))

(defn status
  [^LmdbStore store]
  (with-open [^Txn core-txn (.txnRead ^Env (.-coreEnv store))
              ^Txn refsets-txn (.txnRead ^Env (.-refsetsEnv store))]
    {:concepts        (.entries (.stat ^Dbi (.-concepts store) core-txn))
     :descriptions    (.entries (.stat ^Dbi (.-conceptDescriptions store) core-txn))
     :relationships   (.entries (.stat ^Dbi (.-relationships store) core-txn))
     :concrete-values (.entries (.stat ^Dbi (.-concreteValues store) core-txn))
     :refsets         (.entries (.stat ^Dbi (.-refsetFieldNames store) refsets-txn))
     :refset-items    (.entries (.stat ^Dbi (.-refsetItems store) refsets-txn))
     :indices         {:descriptions-concept         (.entries (.stat ^Dbi (.-descriptionConcept store) core-txn))
                       :concept-parent-relationships (.entries (.stat ^Dbi (.-conceptParentRelationships store) core-txn))
                       :concept-child-relationships  (.entries (.stat ^Dbi (.-conceptChildRelationships store) core-txn))
                       :component-refsets            (.entries (.stat ^Dbi (.-componentRefsets store) core-txn))
                       :associations                 (.entries (.stat ^Dbi (.-associations store) core-txn))}}))

(defn- dbi-stat
  "Return internal statistics regarding a DBI. Total size is calculated from the
   total number of pages and page size, with a mean size per entry."
  [^Dbi dbi ^Txn txn]
  (let [stat (.stat dbi txn)                                ;; stat is an immutable object
        size (* (.pageSize stat) (+ (.branchPages stat) (.leafPages stat) (.overflowPages stat)))]
    {:db            (String. (.getName dbi) StandardCharsets/UTF_8)
     :entries       (.entries stat)
     :depth         (.depth stat)
     :pageSize      (.pageSize stat)
     :branchPages   (.branchPages stat)
     :leafPages     (.leafPages stat)
     :overflowPages (.-overflowPages stat)
     :size          size
     :mean          (when (pos? (.entries stat)) (int (/ size (.entries stat))))}))

(defn statistics
  "Internal statistics for the store. Returns a sequence with an item per
  environment"
  [^LmdbStore store]
  (with-open [^Txn core-txn (.txnRead ^Env (.-coreEnv store))
              ^Txn refsets-txn (.txnRead ^Env (.-refsetsEnv store))]
    (->> [{:env      :core
           :map-size (.mapSize (.info ^Env (.coreEnv store)))
           :dbs      [(dbi-stat (.-concepts store) core-txn)
                      (dbi-stat (.-conceptDescriptions store) core-txn)
                      (dbi-stat (.-relationships store) core-txn)
                      (dbi-stat (.-concreteValues store) core-txn)
                      (dbi-stat (.-descriptionConcept store) core-txn)
                      (dbi-stat (.-conceptParentRelationships store) core-txn)
                      (dbi-stat (.-conceptChildRelationships store) core-txn)
                      (dbi-stat (.-componentRefsets store) core-txn)
                      (dbi-stat (.-associations store) core-txn)]}
          {:env      :refsets
           :map-size (.mapSize (.info ^Env (.-refsetsEnv store)))
           :dbs      [(dbi-stat (.-refsetFieldNames store) refsets-txn)
                      (dbi-stat (.-refsetItems store) refsets-txn)]}]
         (mapv #(assoc % :total-size (->> % :dbs (map :size) (reduce +))))
         (mapv #(assoc % :map-used (float (/ (:total-size %) (:map-size %))))))))

