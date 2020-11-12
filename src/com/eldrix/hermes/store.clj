(ns com.eldrix.hermes.store
  "Store provides access to a key value store."
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async])
  (:import [java.io FileNotFoundException Closeable]
           (org.mapdb Serializer)
           (org.mapdb.serializer SerializerArrayTuple)
           (java.util NavigableSet)))

(set! *warn-on-reflection* true)

(deftype MapDBStore [db concepts descriptions relationships refsets cache indexes]
  Closeable
  (close [_] db))

(defn- ^org.mapdb.DB open-database
  "Open a file-based key-value database from the file specified, optionally read only. Use in a with-open
  block or manually (.close) when done"
  [filename {:keys [read-only? skip-check?]}]
  (when (and read-only? (not (.exists (io/as-file filename))))
    (throw (FileNotFoundException. (str "file `" filename "` opened read-only but not found"))))
  (.make (cond-> (-> (org.mapdb.DBMaker/fileDB ^String filename)
                     (.fileMmapEnable)
                     (.closeOnJvmShutdown))
                 skip-check? (.checksumHeaderBypass)
                 read-only? (.readOnly))))

(defn- ^org.mapdb.DB open-temp-database []
  (-> (org.mapdb.DBMaker/tempFileDB)
      (.fileMmapEnable)
      (.closeOnJvmShutdown)
      (.make)))

(defn- ^org.mapdb.BTreeMap open-bucket
  "Create or open the named b-tree map with the specified value and key
  serializers.
  Parameters:
  - db               - mapdb database (org.mapdb.DB)
  - key-serializer   - serializer for key
  - value-serializer - serializer for value."
  [^org.mapdb.DB db ^String nm ^Serializer key-serializer ^Serializer value-serializer]
  (.createOrOpen (.treeMap db nm key-serializer value-serializer)))

(defn- ^NavigableSet open-index
  "Create or open the named set, made up of an array of java long primitives."
  [^org.mapdb.DB db ^String nm]
  (.createOrOpen (.treeSet db nm Serializer/LONG_ARRAY)))

(defn- write-object
  "Write an object into a bucket.
  Correctly handling atomicity and only writes the object if the
  effectiveTime of the entity is newer than the version that already exists in
  that bucket."
  ([^org.mapdb.MapExtra bucket o] (write-object bucket o 0))
  ([^org.mapdb.MapExtra bucket o attempt]
   (when-not (.putIfAbsentBoolean bucket (:id o) o)
     (let [old (.get bucket (:id o))]
       (when (nil? old)
         (throw (ex-info "entity disappeared" {:entity o})))
       (when (.isAfter ^java.time.LocalDate (:effectiveTime o) (:effectiveTime old))
         ;atomically replace old value with new value, returning false if old value has been changed in meantime
         (let [success (.replace bucket (:id o) old o)]
           (when-not success
             (println "attempted to update " (:id o) " but its value changed: trying again attempt:#" (inc attempt))
             (if (< attempt 10)
               (write-object bucket o (inc attempt))
               (throw (ex-info "failed to write entity." {:attempts attempt :entity o}))))))))))

(defn- write-index-entry
  "Write an index entry `k` into the `bucket` specified.
  Adds a key to the set, if active.
  Removes a key to the set if not active."
  [^java.util.NavigableSet bucket k active?]
  (if active?
    (.add bucket k)
    (.remove bucket k)))

(defn build-description-index
  [^MapDBStore store]
  (let [bucket ^org.mapdb.BTreeMap (:concept-descriptions (.indexes store))]
    (doall (pmap #(.put bucket
                        (long-array [(:conceptId %) (:id %)]) %)
                 (iterator-seq (.valueIterator ^org.mapdb.BTreeMap (.descriptions store)))))))

(defn build-relationship-indices
  "Build the relationship indices.
   Populating the child and parent relationship indices."
  [^MapDBStore store]
  (let [all-relationships (iterator-seq (.valueIterator ^org.mapdb.BTreeMap (.relationships store)))
        child-bucket (:concept-child-relationships (.indexes store))
        parent-bucket (:concept-parent-relationships (.indexes store))]
    (dorun (pmap #(let [active? (:active %)]
                    (write-index-entry parent-bucket
                                       (long-array [(:sourceId %) (:typeId %) (:destinationId %)])
                                       active?)
                    (write-index-entry child-bucket
                                       (long-array [(:destinationId %) (:typeId %) (:sourceId %)])
                                       active?))
                 all-relationships))))

(defn get-concept
  [^MapDBStore store concept-id]
  (.get ^org.mapdb.MapExtra (.concepts store) concept-id))

(defn get-description
  [^MapDBStore store description-id]
  (.get ^org.mapdb.MapExtra (.descriptions store) description-id))

(defn get-descriptions
  [^MapDBStore store description-ids]
  (let [m (.descriptions store)]
    (map #(.get ^org.mapdb.MapExtra m %) description-ids)))

(defn get-concept-descriptions
  "Return the descriptions for the concept specified."
  [^MapDBStore store concept-id]
  (vals (.prefixSubMap ^org.mapdb.BTreeMap (:concept-descriptions (.indexes store)) (long-array [concept-id]))))

(defn get-fully-specified-name
  [^MapDBStore store concept-id]
  (first (filter #(= 900000000000003001 (:typeId %)) (get-concept-descriptions store concept-id))))

(defn get-parent-relationships
  "Return the parent relationships of the given concept"
  ([^MapDBStore store concept-id] (get-parent-relationships store concept-id 0))
  ([^MapDBStore store concept-id type-id]
   (map seq (.subSet ^java.util.NavigableSet (:concept-parent-relationships (.indexes store))
                     (long-array [concept-id type-id 0])
                     (long-array [concept-id (if (pos? type-id) type-id Long/MAX_VALUE) Long/MAX_VALUE])))))

(defn get-child-relationships
  "Return the child relationships of the given concept"
  ([^MapDBStore store concept-id] (get-child-relationships store concept-id 0))
  ([^MapDBStore store concept-id type-id]
   (map seq (.subSet ^java.util.NavigableSet (:concept-child-relationships (.indexes store))
                     (long-array [concept-id type-id 0])
                     (long-array [concept-id (if (pos? type-id) type-id Long/MAX_VALUE) Long/MAX_VALUE])))))

(defn get-all-parents
  "Returns all parent concepts for the concept.
   Parameters:
   - `concept-id`
   - `type-id`, defaults to 'IS-A' (116680003)."
  ([^MapDBStore store concept-id] (get-all-parents store concept-id 116680003))
  ([^MapDBStore store concept-id type-id]
   (loop [work #{concept-id}
          result #{}]
     (if-not (seq work)
       result
       (let [id (first work)
             done-already? (contains? result id)
             parents (if done-already? () (map last (get-parent-relationships store id type-id)))]
         (recur (apply conj (rest work) parents)
                (conj result id)))))))

(defn get-all-children
  "Returns all child concepts for the concept.
  It takes 3500 milliseconds on my 2013 laptop to return all child concepts
  of the root SNOMED CT concept, so this should only be used for more granular
  concepts generally.
   Parameters:
   - store
   - `concept-id`
   - `type-id`, defaults to 'IS-A' (116680003)."
  ([^MapDBStore store concept-id] (get-all-children store concept-id 116680003))
  ([^MapDBStore store concept-id type-id]
   (loop [work #{concept-id}
          result #{}]
     (if-not (seq work)
       result
       (let [id (first work)
             done-already? (contains? result id)
             children (if done-already? () (map last (get-child-relationships store id type-id)))]
         (recur (apply conj (rest work) children)
                (conj result id)))))))

(defn is-a?
  "Is `child` a type of `parent`?"
  [^MapDBStore store child parent]
  (contains? (get-all-parents store child) parent))

(defn get-child-relationships
  "Return the child relationships of the given concept"
  ([^MapDBStore store concept-id] (get-child-relationships store concept-id 0))
  ([^MapDBStore store concept-id type-id]
   (map seq (.subSet ^java.util.NavigableSet (:concept-child-relationships (.indexes store))
                     (long-array [concept-id type-id 0])
                     (long-array [concept-id (if (pos? type-id) type-id Long/MAX_VALUE) Long/MAX_VALUE])))))

(defn- write-concepts [^MapDBStore store objects]
  (doseq [o objects]
    (write-object (.concepts store) o)))

(defn- write-descriptions [^MapDBStore store objects]
  (doseq [o objects]
    (write-object (.descriptions store) o)))

(defn- write-relationships [^MapDBStore store objects]
  (doseq [o objects]
    (write-object (.relationships store) o)))

(defn- write-refset-items [^MapDBStore store objects]
  (doseq [o objects]
    (write-object (.refsets store) o)))

(defn get-relationship [^MapDBStore store relationship-id]
  (.get ^org.mapdb.MapExtra (.relationships store) relationship-id))

(defn get-refset-item [^MapDBStore store uid]
  (.get ^org.mapdb.MapExtra (.refsets store) uid))

(defn get-extended-concept [^MapDBStore store concept-id]
  (.get ^org.mapdb.MapExtra (.cache store) concept-id))

(defn close [^MapDBStore store]
  (.close ^org.mapdb.DB (.db store)))

(def default-opts
  {:read-only?  true
   :skip-check? false})

(defn open-store
  ([] (open-store nil nil))
  ([filename opts]
   (let [db
         (if filename
           (open-database filename (merge default-opts opts))
           (open-temp-database))
         concepts (open-bucket db "c" Serializer/LONG Serializer/JAVA)
         descriptions (open-bucket db "d" Serializer/LONG Serializer/JAVA)
         relationships (open-bucket db "r" Serializer/LONG Serializer/JAVA)
         refsets (open-bucket db "rs" Serializer/STRING Serializer/JAVA)
         cache (open-bucket db "cache" Serializer/LONG Serializer/JAVA)
         indexes {
                  ;; as fetching a list of descriptions [including content] common, store tuple (concept-id description-id)=d
                  :concept-descriptions
                  (open-bucket db "c-ds" Serializer/LONG_ARRAY Serializer/JAVA)

                  ;; for relationships we store a triple [concept-id type-id destination-id] of *only* active relationships
                  ;; this optimises for the get IS-A relationships of xxx, for example
                  :concept-parent-relationships
                  (open-index db "c-pr")
                  :concept-child-relationships
                  (open-index db "c-cr")

                  ;; for refsets, we store a key [sctId--refsetId--itemId] = refset item to optimise
                  ;; a) determining whether a component is part of a refset, and
                  ;; b) returning the items for a given component from a specific refset.
                  :component-refsets
                  (open-bucket db "c-rs"
                               (SerializerArrayTuple. (into-array Serializer [Serializer/LONG Serializer/LONG Serializer/STRING]))
                               Serializer/JAVA)}]

     (->MapDBStore db concepts descriptions relationships refsets cache indexes))))

(defmulti write-batch :type)
(defmethod write-batch :info.snomed/Concept [batch store]
  (write-concepts store (:data batch)))
(defmethod write-batch :info.snomed/Description [batch store]
  (write-descriptions store (:data batch)))
(defmethod write-batch :info.snomed/Relationship [batch store]
  (write-relationships store (:data batch)))
(defmethod write-batch :info.snomed/Refset [batch store]
  (write-refset-items store (:data batch)))


(defn write-batch-worker
  "Write a batch from the channel 'c' specified into the backing store."
  [store c]
  (loop [batch (async/<!! c)]
    (when-not (nil? batch)
      (write-batch batch store)
      (recur (async/<!! c)))))

(comment
  (set! *warn-on-reflection* true)
  (use 'clojure.repl)
  (require '[clojure.java.javadoc :as javadoc])
  (def db (open-store "snomed.db" {:read-only? false :skip-check? false}))
  (close db)
  (.close (.db db))

  (do
    (java.time.LocalDateTime/now)
    (com.eldrix.hermes.core/import-snomed "/Users/mark/Downloads/uk_sct2cl_30.0.0_20200805000001/SnomedCT_InternationalRF2_PRODUCTION_20190731T120000Z"
                                          "snomed.db")
    (java.time.LocalDateTime/now))

  (def db (open-store "snomed.db" {:read-only? true :skip-check? false}))
  (def all-descriptions)
  (def all-relationships (iterator-seq (.valueIterator (.relationships db))))

  ;; build concept-description index

  ;; get descriptions for MS concept
  (vals (.prefixSubMap (:concept-descriptions (.indexes db)) (long-array [24700007])))
  (map :term (filter :active (vals (.prefixSubMap (:concept-descriptions (.indexes db)) (long-array [24700007])))))

  ;; build parentl relationships index
  (first all-relationships)

  (def all-refsets (iterator-seq (.valueIterator (.refsets db))))
  (first all-refsets)
  (defn refsetitem->key [item]
    (into-array :referencedComponentId :refsetId :id %
                ))
  ;; write an index of source->type->destination for each relationship
  (let [bucket (:concept-parent-relationships (.indexes db))]
    (dorun (pmap #(write-index-entry
                    bucket
                    (long-array [(:sourceId %) (:typeId %) (:destinationId %)])
                    (:active %))
                 all-relationships)))

  (do (println "started: " (java.time.LocalDateTime/now))
      (build-relationship-indices db)
      (println "finished: " (java.time.LocalDateTime/now)))

  ;; get all parent relationships of MS
  (map seq (.subSet (:concept-parent-relationships (.indexes db)) (long-array [24700007 0 0]) (long-array [24700007 Long/MAX_VALUE Long/MAX_VALUE])))
  ;; get list of all direct child concepts of MS
  (->> (.subSet (:concept-child-relationships (.indexes db))
                (long-array [24700007 116680003 0])
                (long-array [24700007 116680003 Long/MAX_VALUE]))
       (map seq)
       (map last))

  (defn get-parent-relationships
    "Return the parent relationships of the given concept"
    ([db concept-id] (get-parent-relationships db concept-id 0))
    ([db concept-id type-id]
     (map seq (.subSet (:concept-parent-relationships (.indexes db))
                       (long-array [concept-id type-id 0])
                       (long-array [24700007 (if (pos? type-id) type-id Long/MAX_VALUE) Long/MAX_VALUE])))))

  ;; get all direct parents (IS-A) for multiple sclerosis
  (map last (get-parent-relationships db 24700007 116680003))
  (map last (get-child-relationships db 24700007 116680003))

  (with-open [db (open-store "snomed.db" {:read-only? false})]
    (.compact (.getStore (.concepts db))))

  (with-open [db (open-store "snomed.db" {:read-only? false})]
    (.getAllNames (.db db)))

  (with-open [db (open-store "snomed.db" {:read-only? false})]
    (println "store cd" (.getStore (:concept-descriptions (.indexes db))))
    (println "store c" (.getStore (.concepts db))))

  (do
    (def result (with-open [db (open-store "snomed.db" {:read-only? false})]
                  (let [test-set (.createOrOpen (.treeSet (.db db) "test3" Serializer/LONG_ARRAY))]
                    (.add test-set (long-array [1 2 3]))
                    (.add test-set (long-array [1 2 4]))
                    (.add test-set (long-array [1 3 1]))
                    (.contains test-set (long-array [1 2 3]))
                    (seq (.subSet test-set (long-array [1 2]) (long-array [1 2 Long/MAX_VALUE]))))))
    (map seq result))


  (close db)
  (.close (.db db))

  )

