(ns com.eldrix.hermes.store
  "Store provides access to a key value store."
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.snomed :as snomed])
  (:import [java.io FileNotFoundException Closeable]
           (org.mapdb Serializer)
           (org.mapdb.serializer SerializerArrayTuple)
           (java.util NavigableSet)
           (com.eldrix.hermes.snomed Description)))

(set! *warn-on-reflection* true)

(deftype MapDBStore [^org.mapdb.DB db
                     ^org.mapdb.BTreeMap concepts
                     ^org.mapdb.BTreeMap descriptions
                     ^org.mapdb.BTreeMap relationships
                     ^org.mapdb.BTreeMap refsets
                     ^org.mapdb.BTreeMap cache
                     indexes]
  Closeable
  (close [_] (.close db)))

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
  "Create or open the named set, by default, made up of an array of java long primitives.
  - db         : mapdb database
  - nm         : name of index
  - serializer : serializer to use, by default LONG_ARRAY"
  ([^org.mapdb.DB db ^String nm]
   (open-index db nm Serializer/LONG_ARRAY))
  ([^org.mapdb.DB db ^String nm ^Serializer serializer]
   (.createOrOpen (.treeSet db nm serializer))))

(defn- write-object
  "Write an object into a bucket.
  Correctly handles atomicity and only writes the object if the effectiveTime of
  the entity is newer than the version that already exists in that bucket."
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
  Removes a key from the set if not active."
  [^java.util.NavigableSet bucket k active?]
  (if active?
    (.add bucket k)
    (.remove bucket k)))

(defn stream-all-concepts
  [^MapDBStore store ch]
  (let [concepts (iterator-seq (.valueIterator ^org.mapdb.BTreeMap (.concepts store)))]
    (async/onto-chan! ch concepts)))

(defn build-description-index
  [^MapDBStore store]
  (let [bucket ^org.mapdb.BTreeMap (:concept-descriptions (.indexes store))
        all-descriptions (iterator-seq (.valueIterator ^org.mapdb.BTreeMap (.descriptions store)))]
    (doall (pmap #(.put bucket
                        (long-array [(:conceptId %) (:id %)]) %)
                 all-descriptions))))

(defn build-relationship-indices
  "Build the relationship indices.
   Populates the child and parent relationship indices."
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

(defn build-refset-indices
  "Build indices relating to the refsets.
  Processes each refset item, recording an index to allow lookup of items on the following keys
  ------------------------------------------------------------------------
  | bucket                 | compound key                                |
  | :component-refsets     | referencedComponentId -- refsetId -- itemId |
  | :map-target-component  | refsetId -- mapTarget -- itemId             |
  ------------------------------------------------------------------------
  "
  [^MapDBStore store]
  (let [installed ^java.util.NavigableSet (:installed-refsets (.indexes store))
        components ^java.util.NavigableSet (:component-refsets (.indexes store))
        reverse-map ^java.util.NavigableSet (:map-target-component (.indexes store))
        all-refsets (iterator-seq (.valueIterator ^org.mapdb.BTreeMap (.refsets store)))]
    (dorun (pmap #(let [{:keys [id referencedComponentId refsetId active mapTarget]} %]
                    (.add installed refsetId)
                    (write-index-entry components
                                       (to-array [referencedComponentId refsetId id])
                                       active)
                    (when mapTarget
                      (write-index-entry reverse-map
                                         (to-array [refsetId mapTarget id])
                                         active)))
                 all-refsets))))

(defn get-installed-reference-sets
  "Returns the installed reference sets"
  [^MapDBStore store]
  (into #{} (:installed-refsets (.indexes store))))

(defn get-concept
  [^MapDBStore store concept-id]
  (.get ^org.mapdb.BTreeMap (.concepts store) concept-id))

(defn get-description
  [^MapDBStore store description-id]
  (.get ^org.mapdb.BTreeMap (.descriptions store) description-id))

(defn get-descriptions
  [^MapDBStore store description-ids]
  (let [m (.descriptions store)]
    (map #(.get ^org.mapdb.BTreeMap m %) description-ids)))

(defn get-concept-descriptions
  "Return the descriptions for the concept specified."
  [^MapDBStore store concept-id]
  (vals (.prefixSubMap ^org.mapdb.BTreeMap (:concept-descriptions (.indexes store)) (long-array [concept-id]))))

(defn is-fully-specified-name?
  [^Description d]
  (= 900000000000003001 (:typeId d)))

(defn is-synonym?
  [^Description d]
  (= 900000000000013009 (:typeId d)))

(defn get-fully-specified-name
  [^MapDBStore store concept-id]
  (first (filter is-fully-specified-name? (get-concept-descriptions store concept-id))))

(defn get-relationship [^MapDBStore store relationship-id]
  (.get ^org.mapdb.BTreeMap (.relationships store) relationship-id))

(defn get-refset-item [^MapDBStore store uid]
  (.get ^org.mapdb.BTreeMap (.refsets store) uid))

(defn- get-raw-parent-relationships
  "Return the parent relationships of the given concept.
  Returns a list of tuples (from--type--to)."
  ([^MapDBStore store concept-id] (get-raw-parent-relationships store concept-id 0))
  ([^MapDBStore store concept-id type-id]
   (map seq (.subSet ^java.util.NavigableSet (:concept-parent-relationships (.indexes store))
                     (long-array [concept-id type-id 0])
                     (long-array [concept-id (if (pos? type-id) type-id Long/MAX_VALUE) Long/MAX_VALUE])))))

(defn- get-raw-child-relationships
  "Return the child relationships of the given concept.
  Returns a list of tuples (from--type--to)."
  ([^MapDBStore store concept-id] (get-raw-child-relationships store concept-id 0))
  ([^MapDBStore store concept-id type-id]
   (map seq (.subSet ^java.util.NavigableSet (:concept-child-relationships (.indexes store))
                     (long-array [concept-id type-id 0])
                     (long-array [concept-id (if (pos? type-id) type-id Long/MAX_VALUE) Long/MAX_VALUE])))))

(defn get-all-parents
  "Returns all parent concepts for the concept.
   Parameters:
   - `store` the MapDBStore
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
       (map rest)
       (map #(hash-map (first %) #{(second %)}))
       (apply merge-with into)))

(defn get-parent-relationships-expanded
  "Returns all of the parent relationships, expanded to
  include each target's transitive closure table.
  This makes it trivial to build queries that find all concepts
  with, for example, a common finding site at any level of granularity."
  [store concept-id]
  (->> (get-raw-parent-relationships store concept-id)
       (map rest)
       (map #(hash-map (first %) (get-all-parents store (second %))))
       (apply merge-with into)))

(defn get-all-children
  "Returns all child concepts for the concept.
  It takes 3500 milliseconds on my 2013 laptop to return all child concepts
  of the root SNOMED CT concept, so this should only be used for more granular
  concepts generally, or used asynchronously / via streaming.
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
             children (if done-already? () (map last (get-raw-child-relationships store id type-id)))]
         (recur (apply conj (rest work) children)
                (conj result id)))))))

(defn is-a?
  "Is `child` a type of `parent`?"
  [^MapDBStore store child parent]
  (contains? (get-all-parents store child) parent))

(defn get-component-refset-items
  "Get the refset items for the given component, optionally
   limited to the refset specified.
   - store        : MapDBStore
   - component-id : id of the component (e.g concept-id or description-id)
   - refset-id    : (optional) - limit to this refset."
  ([^MapDBStore store component-id]
   (get-component-refset-items store component-id nil))
  ([^MapDBStore store component-id refset-id]
   (->> (.subSet ^java.util.NavigableSet (:component-refsets (.indexes store))
                 (to-array (if refset-id [component-id refset-id] [component-id])) ;; lower limit = first two elements of composite key
                 (to-array [component-id refset-id nil]))   ;; upper limit = the three elements, with final nil = infinite
        (map seq)
        (map last)
        (map (partial get-refset-item store)))))

(defn get-component-refsets
  "Return the refset-id's to which this component belongs."
  [^MapDBStore store component-id]
  (->> (.subSet ^java.util.NavigableSet (:component-refsets (.indexes store))
                (to-array [component-id])                   ;; lower limit = first two elements of composite key
                (to-array [component-id nil nil]))
       (map seq)
       (map second)))

(defn get-reverse-map
  "Returns the reverse mapping from the reference set and mapTarget specified."
  [^MapDBStore store refset-id s]
  (->> (map seq (.subSet ^java.util.NavigableSet (:map-target-component (.indexes store))
                         (to-array [refset-id s])
                         (to-array [refset-id s nil])))
       (map last)
       (map (partial get-refset-item store))))


(defn get-description-refsets
  "Get the refsets and language applicability for a description.
  Returns a map containing:
  - refsets       : a set of refsets to which this description is a member
  - preferred-in  : refsets for which this description is preferred
  - acceptable-in : refsets for which this description is acceptable.

  Example:
  (map #(merge % (get-description-refsets store (:id %)))
       (get-concept-descriptions store 24700007))"
  [store description-id]
  (let [refset-items (get-component-refset-items store description-id)
        refsets (into #{} (map :refsetId refset-items))
        preferred-in (into #{} (map :refsetId (filter #(= 900000000000548007 (:acceptabilityId %)) refset-items)))
        acceptable-in (into #{} (map :refsetId (filter #(= 900000000000549004 (:acceptabilityId %)) refset-items)))]
    {:refsets       refsets
     :preferred-in  preferred-in
     :acceptable-in acceptable-in}))

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
                         (filter #(= 900000000000548007 (:acceptabilityId %))) ;; only PREFERRED
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
  (some identity (map (partial get-preferred-description store concept-id 900000000000013009) language-refset-ids)))

(defn get-preferred-fully-specified-name [store concept-id language-refset-ids]
  (some identity (map (partial get-preferred-description store concept-id 900000000000003001) language-refset-ids)))

(def language-reference-sets
  "Defines a mapping between ISO language tags and the language reference sets
  (possibly) installed as part of SNOMED CT. These can be used if a client
  does not wish to specify a reference set (or set of reference sets) to be used
  to derive preferred or acceptable terms, but instead wants some sane defaults
  on the basis of locale as defined by IETF BCP 47."
  {"en-GB" [999001261000000100                              ;; NHS realm language (clinical part)
            999000691000001104                              ;; NHS realm language (pharmacy part)
            900000000000508004                              ;; Great Britain English language reference set
            ]
   "en-US" [900000000000509007]})

(defn- select-language-reference-sets
  "Returns the known language reference sets that are in the `installed` set.
  - installed : a set of refset identifiers reference sets."
  [installed]
  (reduce-kv (fn [m k v]
               (if-let [filtered (seq (filter installed v))]
                 (assoc m k filtered)
                 (dissoc m k)))
             {}
             language-reference-sets))

(defn ordered-language-refsets-from-locale
  "Return an ordered list of language reference sets, as determined by
  the priority list from the user and the set of installed reference sets.
  Parameters
  - priority-list     : e.g. en-GB;q=1.0,en-US;q=0.5,fr-FR;q=0.0
  - installed-refsets : e.g. #{900000000000509007 900000000000508004}"
  [^String priority-list installed-refsets]
  (let [installed (select-language-reference-sets installed-refsets)
        priority-list (try (java.util.Locale$LanguageRange/parse priority-list) (catch Exception _ []))
        locales (map #(java.util.Locale/forLanguageTag %) (keys installed))]
    (mapcat #(get installed %) (map #(.toLanguageTag ^java.util.Locale %) (java.util.Locale/filter priority-list locales)))))

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

(defn make-extended-concept [^MapDBStore store concept]
  (let [concept-id (:id concept)
        descriptions (map #(merge % (get-description-refsets store (:id %)))
                          (get-concept-descriptions store concept-id))
        parent-relationships (get-parent-relationships-expanded store concept-id)
        direct-parents (get (get-parent-relationships store concept-id) 116680003)  ;; direct IS-A relationships ONLY
        refsets (into #{} (get-component-refsets store concept-id))]
    (snomed/->ExtendedConcept
      concept
      descriptions
      parent-relationships
      direct-parents
      refsets)))

(defn compact [^MapDBStore store]
  (.compact (.getStore ^org.mapdb.BTreeMap (.concepts store))))

(defn close [^MapDBStore store]
  (.close ^org.mapdb.DB (.db store)))

(defn status
  [^MapDBStore store]
  {:concepts      (.size ^org.mapdb.BTreeMap (.concepts store))
   :descriptions  (.size ^org.mapdb.BTreeMap (.descriptions store))
   :relationships (.size ^org.mapdb.BTreeMap (.relationships store))
   :refsets       (.size ^org.mapdb.BTreeMap (.refsets store))
   :indices       (let [ks (keys (.indexes store))]
                    (zipmap ks (map #(.size (get (.indexes store) %)) ks)))})

(def default-opts
  {:read-only?  true
   :skip-check? false})

(defn open-store
  ([] (open-store nil nil))
  ([filename] (open-store filename nil))
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
                  (open-index db "c-rs"
                              (SerializerArrayTuple. (into-array Serializer [Serializer/LONG Serializer/LONG Serializer/STRING])))

                  ;; cache a set of installed reference sets
                  :installed-refsets
                  (open-index db "installed-rs" Serializer/LONG)

                  ;; for any map refsets, we store (refsetid--mapTarget--itemId) to
                  ;; allow reverse mapping from, e.g. Read code to SNOMED-CT
                  :map-target-component
                  (open-index db "r-ti"
                              (SerializerArrayTuple. (into-array Serializer [Serializer/LONG Serializer/STRING Serializer/STRING])))}]

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

  (def store (open-store "snomed.db" {:read-only? true :skip-check? false}))
  (close store)

  (with-open [st (open-store "snomed.db" {:read-only? false :skip-check? false})]
    (.compact (.getStore (.concepts st))))

  (with-open [st (open-store "snomed.db" {:read-only? false :skip-check? true})]
    (.compact (.getStore (.concepts st))))

  (with-open [db (open-store "snomed.db" {:read-only? true :skip-check? false})]
    (status db))

  (with-open [st (open-store "snomed.db" {:read-only? true :skip-check? false})]
    (->> (get-concept-descriptions st 24700007)
         (filter :active)
         (remove is-fully-specified-name?)
         (map :term)
         (sort-by :term)))

  (with-open [st (open-store "snomed.db" {:read-only? true :skip-check? false})]
    (get-component-refset-items st 24700007 900000000000497000))

  (with-open [st (open-store "snomed.db" {:read-only? true :skip-check? false})]
    (get-reverse-map st 900000000000497000 "F20.."))

  (with-open [db (open-store "snomed.db" {:read-only? false})]
    (.getAllNames (.db db)))

  (def ch (async/chan))
  (async/thread (with-open [st (open-store "snomed.db" {:read-only? true})]
                  (async/<!! (stream-all-concepts st ch))))
  )

