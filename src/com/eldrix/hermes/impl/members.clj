(ns com.eldrix.hermes.impl.members
  "Members creates a Lucene search index for reference set members."
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [com.eldrix.hermes.impl.lucene :as lucene]
            [com.eldrix.hermes.impl.store :as store])
  (:import (org.apache.lucene.search IndexSearcher TermQuery PrefixQuery Query MatchAllDocsQuery WildcardQuery)
           (org.apache.lucene.document Document Field$Store StringField LongPoint StoredField)
           (java.util UUID Collection)
           (java.time ZoneId LocalDate)
           (org.apache.lucene.store ByteBuffersDirectory FSDirectory)
           (org.apache.lucene.index IndexWriterConfig IndexWriter IndexReader DirectoryReader Term IndexWriterConfig$OpenMode)
           (org.apache.lucene.analysis.standard StandardAnalyzer)))

(set! *warn-on-reflection* true)

(defn localdate->epoch-milli
  "Converts a java.time.LocalDate to the number of milliseconds from the epoch of 1970-01-01T00:00:00Z."
  ^long [^LocalDate date]
  (.toEpochMilli (.toInstant (.atStartOfDay date (ZoneId/of "UTC")))))

(def stored-fields
  "A set of field names for which we store values in the index"
  #{:referencedComponentId})

(defn- make-string-field
  [k v]
  (StringField. (name k) (str v) (if (stored-fields k) Field$Store/YES Field$Store/NO)))

(defmulti make-fields
  "Create Lucene field(s) from the value.
  This could use additional runtime information if it became necessary in the
  future. At the moment, we simply use the class of the value but could use
  the attribute type information from the attribute reference set if required."
  (fn [_k v] (class v)))
(defmethod make-fields Long [k v]
  (if (stored-fields k)
    [(LongPoint. (name k) (long-array [v]))
     (StoredField. (name k) ^long v)]
    [(LongPoint. (name k) (long-array [v]))]))

(defmethod make-fields UUID [k v]
  [(make-string-field k v)])
(defmethod make-fields Boolean [k v]
  [(make-string-field k v)])
(defmethod make-fields String [k v]
  [(make-string-field k v)])
(defmethod make-fields LocalDate [k v]
  (let [nm (name k)
        v' (localdate->epoch-milli v)]
    (if (stored-fields k)
      [(LongPoint. nm (long-array [v']))
       (StoredField. nm ^long v')]
      [(LongPoint. nm (long-array [v']))])))

(defmethod make-fields Integer [k v]
  (if (stored-fields k)
    [(LongPoint. (name k) (long-array [v]))
     (StoredField. (name k) ^long v)]
    [(LongPoint. (name k) (long-array [v]))]))

(defn make-document
  "Create a Lucene document representing the given reference set item.
  Reference set items consist of a core set of keys and values, together with
  a range of other properties for a specific subtype, or some custom properties
  in a given distribution. The meaning of those properties are defined in refset
  descriptors. All values are either integers, component identifiers or strings.

  Currently, we do not use the runtime information made available in the refset
  descriptors, as we can derive type easily by simply looking at the value."
  ^Document [item]
  (let [doc (Document.)]
    (doseq [[k v] (seq item)
            field (make-fields k v)]
      (.add doc field))
    doc))

(defn open-index-writer
  "Creates a Lucene index at 'f' where 'f' is anything coercible by
  [[clojure.java.io/as-file]]."
  (^IndexWriter [f]
   (let [analyzer (StandardAnalyzer.)
         directory (FSDirectory/open (.toPath (io/file f)))
         writer-config (doto (IndexWriterConfig. analyzer)
                         (.setOpenMode IndexWriterConfig$OpenMode/CREATE))]
     (IndexWriter. directory writer-config))))

(defn build-members-index
  "Build a refset members index using the SNOMED CT store at `store-file`."
  [store-file refset-index-filename]
  (let [ch (a/chan 50)]
    (with-open [store (store/open-store store-file)
                writer (open-index-writer refset-index-filename)]
      (a/thread (store/stream-all-refset-items store ch))
      (a/<!!                                                ;; block until pipeline complete
        (a/pipeline-blocking                                ;; pipeline for side-effects
          (.availableProcessors (Runtime/getRuntime))       ;; Parallelism factor
          (doto (a/chan) (a/close!))                        ;; Output channel - /dev/null
          (comp (map #(store/extended-refset-item store % :attr-ids? false))
                (map make-document)
                (map #(.addDocument writer %)))
          ch))
      (.forceMerge writer 1))))

(defn open-index-reader
  ^IndexReader [f]
  (let [directory (FSDirectory/open (.toPath (io/file f)))]
    (DirectoryReader/open directory)))

(defn q-or [queries]
  (lucene/q-or queries))

(defn q-and [queries]
  (lucene/q-and queries))

(defn q-not
  "Returns the logical query of q1 NOT q2"
  [^Query q1 ^Query q2]
  (lucene/q-not q1 q2))

(defn q-all
  "Returns a query to match all documents."
  []
  (MatchAllDocsQuery.))

(defn q-prefix
  "Create a prefix query for the field specified."
  ^Query [^String field-name ^String term]
  (PrefixQuery. (Term. field-name term)))

(defn q-term
  "Create a query for the exact match for the field specified."
  ^Query [^String field-name ^String term]
  (TermQuery. (Term. field-name term)))

(defn q-wildcard
  "Create a wildcard query for the field specified.
  From the Lucene documentation:
    \"Supported wildcards are *, which matches any character sequence (including
     the empty one), and ?, which matches any single character. '\\' is the
     escape character. Note this query can be slow, as it needs to iterate over
     many terms. In order to prevent extremely slow wildcard queries, a term
     should not start with the wildcard *\"."
  ^Query [^String field ^String term]
  (WildcardQuery. (Term. field term)))

(defn q-refset-id
  "Create a query for items belonging to the reference set specified"
  ^Query [refset-id]
  (LongPoint/newExactQuery "refsetId" refset-id))

(defn q-refset-ids
  "Create a query for items belonging to the reference sets specified."
  ^Query [^Collection refset-ids]
  (LongPoint/newSetQuery "refsetId" refset-ids))

(defn q-module-id
  "Create a query for items with the specified module id"
  ^Query [module-id]
  (LongPoint/newExactQuery "moduleId" module-id))

(defn q-module-ids
  "Create a query for items with the specified module ids"
  ^Query [^Collection module-ids]
  (LongPoint/newSetQuery "moduleId" module-ids))

(defn q-time>=
  "Create a query for items with a field, or an effective time, greater or equal
  than 'd'."
  (^Query [d] (q-time>= "effectiveTime" d))
  (^Query [^String field d] (LongPoint/newRangeQuery field (localdate->epoch-milli d) Long/MAX_VALUE)))

(defn q-time>
  "Create a query for items with a field, or an effective time, greater than 'd'."
  (^Query [d] (q-time> "effectiveTime" d))
  (^Query [^String field d] (LongPoint/newRangeQuery field (inc (localdate->epoch-milli d)) Long/MAX_VALUE)))

(defn q-time=
  "Create a query for items with a field, or an effective time, equal to 'd'."
  (^Query [d] (q-time= "effectiveTime" d))
  (^Query [^String field d] (LongPoint/newExactQuery field (localdate->epoch-milli d))))

(defn q-time<
  "Create a query for items with a field, or an effective time, less than 'd'."
  (^Query [d] (q-time< "effectiveTime" d))
  (^Query [^String field d] (LongPoint/newRangeQuery field 0 (dec (localdate->epoch-milli d)))))

(defn q-time<=
  "Create a query for items with a field, or an effective time, less than or
  equal to 'd'."
  (^Query [d] (q-time<= "effectiveTime" d))
  (^Query [^String field d] (LongPoint/newRangeQuery field 0 (localdate->epoch-milli d))))

(defn q-time!=
  "Create a query for items with a field, or an effective time, not equal to 'd'."
  (^Query [d] (q-time!= "effectiveTime" d))
  (^Query [^String field d]
   (let [v (localdate->epoch-milli d)]
     (q-or [(q-time< field (dec v)) (q-time> field (inc v))]))))

(defn q-field=
  "Create a query for items with a field equal to"
  [^String field ^long value]
  (LongPoint/newExactQuery field value))

(defn q-field!=
  "Create a query for items with a field not equal to"
  [^String field ^long value]
  (q-or [(LongPoint/newRangeQuery field Long/MIN_VALUE (dec value))
         (LongPoint/newRangeQuery field (inc value) Long/MAX_VALUE)]))

(defn q-field<
  [^String field ^long value]
  (LongPoint/newRangeQuery field Long/MIN_VALUE (dec value)))

(defn q-field>
  [^String field ^long value]
  (LongPoint/newRangeQuery field (inc value) Long/MAX_VALUE))

(defn q-field<=
  [^String field ^long value]
  (LongPoint/newRangeQuery field Long/MIN_VALUE value))

(defn q-field>=
  [^String field ^long value]
  (LongPoint/newRangeQuery field value Long/MAX_VALUE))

(defn q-field-in
  [^String field ^Collection values]
  (LongPoint/newSetQuery field values))

(defn q-field-boolean
  [^String field x]
  (q-term field (str x)))

(defn q-referenced-component
  "Create a query for items relating to the specified component."
  ^Query [component-id]
  (LongPoint/newExactQuery "referencedComponentId" component-id))

(defn search
  "Performs the search, returning a set of referenced component identifiers."
  [^IndexSearcher searcher query]
  (let [sfields (.storedFields searcher)]
    (into #{}
          (map (fn [^long doc-id] (.numericValue (.getField (.document sfields doc-id #{"referencedComponentId"}) "referencedComponentId"))))
          (lucene/search-all searcher query))))

(defn search*
  "Performs a search and returns whether result not empty.
  For refset membership testing, this is an order of magnitude slower than using
  the 'store' refset function.
  ```
  (time (search-not-empty? searcher (q-and [(q-refset-id 734138000) (q-referenced-component 24700007)])))
  \"Elapsed time: 2.756541 msecs\"
  (time (store/get-component-refset-items store 24700007 734138000))
  \"Elapsed time: 0.18375 msecs\"
  ```
  As such, this should only be used outside of scenarios in which that cannot
  be used, such as more complex or arbitrary field queries."
  [^IndexSearcher searcher ^Query query]
  (seq (.-scoreDocs (.search searcher query 1))))

(comment
  (def store (store/open-store "snomed-2022-04-21.db/store.db"))
  (.refsetFieldNames store)
  (get (.refsetFieldNames store) 30931000001101)
  (store/get-refset-descriptors store 30931000001101)
  (store/get-refset-descriptor-attribute-ids store 30931000001101)
  (store/get-preferred-synonym store 449608002 [999001261000000100])

  (build-members-index "snomed.db/store.db" "snomed.db/members.db")
  (def reader (open-index-reader "snomed.db/members.db"))
  (def searcher (IndexSearcher. reader))
  (search searcher (q-refset-id 447562003))
  (search searcher (q-and [(q-prefix "mapTarget" "G21.0") (q-refset-id 447562003)]))
  (time
    (do (def ids (search searcher (q-and [(q-prefix "mapTarget" "G50") (q-refset-id 447562003)])))
        (map #(store/get-component-refset-items store % 447562003) ids)))
  (def directory (ByteBuffersDirectory.))
  (def config (IndexWriterConfig. (StandardAnalyzer.)))
  (def writer (IndexWriter. directory config))
  (def ch (a/chan 200))
  (a/thread (store/stream-all-refset-items store))
  (def items (a/<!! 200))
  (def docs (->> items
                 (map #(store/extended-refset-item store % :attr-ids? false))
                 (map make-document)))
  (run! #(.addDocument writer %2) docs)
  (.forceMerge writer 1)
  (.close writer)

  (def reader (DirectoryReader/open directory))
  (def searcher (IndexSearcher. reader))
  (first items)

  (->> (seq (.scoreDocs (.search searcher (LongPoint/newExactQuery "referencedComponentId" 100005) 20000)))
       (map #(.doc searcher (.-doc %)))
       (map #(.get % "referencedComponentId")))


  (->> (seq (.scoreDocs (.search searcher (PrefixQuery. (Term. "mapTarget" "XU")) 1000)))
       (map #(.doc searcher (.-doc %)))))
