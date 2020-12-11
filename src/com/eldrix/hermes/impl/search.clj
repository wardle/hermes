(ns com.eldrix.hermes.impl.search
  (:require
    [clojure.core.async :as async]
    [clojure.string :as str]
    [clojure.tools.logging.readable :as log]
    [com.eldrix.hermes.impl.language :as lang]
    [com.eldrix.hermes.impl.store :as store]
    [com.eldrix.hermes.snomed :as snomed])
  (:import (org.apache.lucene.index Term IndexWriter IndexWriterConfig DirectoryReader IndexWriterConfig$OpenMode IndexReader)
           (org.apache.lucene.store FSDirectory)
           (org.apache.lucene.document Document TextField Field$Store StoredField LongPoint StringField DoubleDocValuesField IntPoint)
           (org.apache.lucene.search IndexSearcher TermQuery FuzzyQuery BooleanClause$Occur PrefixQuery BooleanQuery$Builder DoubleValuesSource Query ScoreDoc TopDocs)
           (org.apache.lucene.queries.function FunctionScoreQuery)
           (org.apache.lucene.analysis.standard StandardAnalyzer)
           (java.util Collection)
           (java.nio.file Paths)
           (com.eldrix.hermes.impl.store MapDBStore)))

(set! *warn-on-reflection* true)

(defn make-extended-descriptions
  [^MapDBStore store language-refset-ids concept]
  (let [ec (store/make-extended-concept store concept)
        ec' (dissoc ec :descriptions)
        preferred (store/get-preferred-synonym store (:id concept) language-refset-ids)]
    (when-not preferred
      (log/warn "could not determine preferred synonym for " (:id concept) " using refsets: " language-refset-ids))
    ;; turn concept inside out to focus on description instead
    (map #(assoc % :concept (merge (dissoc ec' :concept) (:concept ec'))
                   :preferred-term (:term preferred))
         (:descriptions ec))))

(defn extended-description->document
  "Turn an extended description into a Lucene document."
  [ed]
  (let [doc (doto (Document.)
              (.add (TextField. "term" (:term ed) Field$Store/YES))
              (.add (DoubleDocValuesField. "length-boost" (/ 1.0 (Math/sqrt (count (:term ed)))))) ;; add a penalty for longer terms
              (.add (LongPoint. "module-id" (long-array [(:moduleId ed)])))
              (.add (StringField. "concept-active" (str (get-in ed [:concept :active])) Field$Store/NO))
              (.add (StringField. "description-active" (str (:active ed)) Field$Store/NO))
              (.add (LongPoint. "type-id" (long-array [(:typeId ed)])))
              (.add (LongPoint. "description-id" (long-array [(:id ed)]))) ;; for indexing and search
              (.add (StoredField. "id" ^long (:id ed)))     ;; stored field of same
              (.add (StoredField. "concept-id" ^long (get-in ed [:concept :id])))
              (.add (LongPoint. "concept-id" (long-array [(get-in ed [:concept :id])])))
              (.add (StoredField. "preferred-term" (str (:preferred-term ed)))))]
    (doseq [[rel concept-ids] (get-in ed [:concept :parent-relationships])]
      (let [relationship (str rel)]                         ;; encode parent relationships as relationship type concept id
        (doseq [concept-id concept-ids]                     ;; and use a transitive closure table for the defining relationship
          (.add doc (LongPoint. relationship (long-array [concept-id]))))))
    (doseq [[rel concept-ids] (get-in ed [:concept :direct-parent-relationships])]
      (.add doc (IntPoint. (str "c" rel) (int-array [(count concept-ids)]))) ;; encode count of direct parent relationships by type as ("c" + relationship type = count)
      (let [relationship (str "d" rel)]                     ;; encode direct parent relationships as ("d" + relationship type = concept id)
        (doseq [concept-id concept-ids]
          (.add doc (LongPoint. relationship (long-array [concept-id]))))))
    (doseq [preferred-in (:preferred-in ed)]
      (.add doc (LongPoint. "preferred-in" (long-array [preferred-in]))))
    (doseq [acceptable-in (:acceptable-in ed)]
      (.add doc (LongPoint. "acceptable-in" (long-array [acceptable-in]))))
    (doseq [refset (get-in ed [:concept :refsets])]
      (.add doc (LongPoint. "concept-refsets" (long-array [refset]))))
    (doseq [refset (get-in ed [:refsets])]
      (.add doc (LongPoint. "description-refsets" (long-array [refset]))))
    doc))

(defn concept->documents
  [store language-refset-ids concept]
  (->> (make-extended-descriptions store language-refset-ids concept)
       (map extended-description->document)))

(defn write-concept! [store ^IndexWriter writer language-refset-ids concept]
  (let [docs (concept->documents store language-refset-ids concept)]
    (doseq [doc docs]
      (.addDocument writer doc))))

(defn write-batch! [store ^IndexWriter writer language-refset-ids concepts]
  (dorun (map (partial write-concept! store writer language-refset-ids) concepts))
  (.commit writer))

(defn ^IndexWriter open-index-writer
  [filename]
  (let [analyzer (StandardAnalyzer.)
        directory (FSDirectory/open (Paths/get filename (into-array String [])))
        writer-config (doto (IndexWriterConfig. analyzer)
                        (.setOpenMode IndexWriterConfig$OpenMode/CREATE_OR_APPEND))]
    (IndexWriter. directory writer-config)))

(defn ^IndexReader open-index-reader
  [filename]
  (let [directory (FSDirectory/open (Paths/get filename (into-array String [])))]
    (DirectoryReader/open directory)))

(defn build-search-index
  "Build a search index using the SNOMED CT store at `store-filename`."
  [store-filename search-filename language-priority-list]
  (let [ch (async/chan 1 (partition-all 1000))]             ;; chunk concepts into batches
    (with-open [store (store/open-store store-filename)
                writer (open-index-writer search-filename)]
      (let [langs (lang/match store language-priority-list)]
        (when-not (seq langs) (throw (ex-info "No language refset for any locale listed in priority list"
                                              {:priority-list language-priority-list :store-filename store-filename})))
        (store/stream-all-concepts store ch)                ;; start streaming all concepts
        (async/<!!                                          ;; block until pipeline complete
          (async/pipeline                                   ;; pipeline for side-effects
            (.availableProcessors (Runtime/getRuntime))     ;; Parallelism factor
            (doto (async/chan) (async/close!))              ;; Output channel - /dev/null
            (map (partial write-batch! store writer langs))
            ch))
        (.forceMerge writer 1)))))

(defn- create-test-search
  ([store-filename search-filename] (create-test-search store-filename search-filename [73211009 46635009 195353004 232369001 711158005]))
  ([store-filename search-filename concept-ids]
   ;;(clojure.java.shell/sh "rm" "-rf" search-filename)
   ;; let's create a really small index for testing
   (with-open [store (store/open-store store-filename)
               writer (open-index-writer search-filename)]
     (let [concepts (map (partial store/get-concept store) concept-ids)]
       (write-batch! store writer [] concepts)))))

(defn- make-token-query
  [^String token fuzzy]
  (let [len (count token)
        term (Term. "term" token)
        tq (TermQuery. term)]
    (if (> len 2)
      (let [builder (BooleanQuery$Builder.)]
        (.add builder (PrefixQuery. term) BooleanClause$Occur/SHOULD)
        (if (and fuzzy (> fuzzy 0)) (.add builder (FuzzyQuery. term (min 2 fuzzy)) BooleanClause$Occur/SHOULD)
                                    (.add builder tq BooleanClause$Occur/SHOULD))
        (.setMinimumNumberShouldMatch builder 1)
        (.build builder))
      tq)))

(defn- make-tokens-query
  ([s] (make-tokens-query s 0))
  ([s fuzzy]
   (when s
     (let [qs (map #(make-token-query % fuzzy) (str/split (str/lower-case s) #"\s"))]
       (if (> (count qs) 1)
         (let [builder (BooleanQuery$Builder.)]
           (doseq [q qs]
             (.add builder q BooleanClause$Occur/MUST))
           (.build builder))
         (first qs))))))

(defn- ^Query make-search-query
  [{:keys [s fuzzy show-fsn? inactive-concepts? inactive-descriptions? properties]
    :or   {show-fsn? false inactive-concepts? false inactive-descriptions? true}}]
  (let [booster (DoubleValuesSource/fromDoubleField "length-boost")
        query (cond-> (BooleanQuery$Builder.)
                      s
                      (.add (make-tokens-query s fuzzy) BooleanClause$Occur/MUST)
                      (not inactive-concepts?)
                      (.add (TermQuery. (Term. "concept-active" "true")) BooleanClause$Occur/FILTER)
                      (not inactive-descriptions?)
                      (.add (TermQuery. (Term. "description-active" "true")) BooleanClause$Occur/FILTER)
                      (not show-fsn?)
                      (.add (LongPoint/newExactQuery "type-id" snomed/FullySpecifiedName) BooleanClause$Occur/MUST_NOT))]
    (doseq [[k v] properties]
      (let [^Collection vv (if (vector? v) v [v])]
        (.add query
              (LongPoint/newSetQuery (str k) vv)
              BooleanClause$Occur/FILTER)))
    (FunctionScoreQuery. (.build query) booster)))

(defrecord Result
  [^long id
   ^long conceptId
   ^String term
   ^String preferredTerm])

(defn- scoredoc->result
  "Convert a Lucene ScoreDoc (`score-doc`) into a Result."
  [^IndexSearcher searcher ^ScoreDoc score-doc]
  (let [doc (.doc searcher (.-doc score-doc))]
    (->Result (Long/parseLong (.get doc "id"))
              (Long/parseLong (.get doc "concept-id"))
              (.get doc "term")
              (.get doc "preferred-term"))))

(defn- scoredoc->concept-id
  "Convert a Lucene ScoreDoc ('score-doc' into a concept-id."
  [^IndexSearcher searcher ^ScoreDoc score-doc]
  (let [doc (.doc searcher (.-doc score-doc))]
    (Long/parseLong (.get doc "concept-id"))))

(defn do-search
  "Perform a search against the index.
  Parameters:
  - searcher : the IndexSearcher to use
  - params   : a map of search parameters, which are:
    |- :s                  : search string to use
    |- :max-hits           : maximum hits (default, 200)
    |- :fuzzy              : fuzziness (0-2, default 0)
    |- :fallback-fuzzy     : if no results, try again with fuzzy search?
    |- :show-fsn?          : show FSNs in results? (default: false)
    |- :inactive-concepts? : search descriptions of inactive concepts?
    |                      : (default: false).
    |- :inactive-descriptions? : search inactive descriptions? (default, true)
    |- :properties         : a map of properties and their possible values.

  The properties map contains keys for a property and then either a single
  identifier or vector of identifiers to limit search.

  Example: to search for neurologist as an occupation ('IS-A' '14679004')
  (do-search searcher {:s \"neurologist\"  :properties {snomed/IsA [14679004]}})

  A FSN is a fully-specified name and should generally be left out of search."
  [^IndexSearcher searcher params]
  (let [query (make-search-query params)
        hits (seq (.-scoreDocs ^TopDocs (.search searcher query (int (or (:max-hits params) 200)))))]
    (if hits
      (map (partial scoredoc->result searcher) hits)
      (let [fuzzy (or (:fuzzy params) 0)
            fallback (or (:fallback-fuzzy params) 0)]
        (when (and (= fuzzy 0) (> fallback 0))
          (do-search searcher (assoc params :fuzzy fallback)))))))


(defn topdocs->concept-ids
  [searcher ^TopDocs top-docs]
  (->> (seq (.-scoreDocs top-docs))
       (map (partial scoredoc->concept-id searcher))
       (set)))

(defn do-query
  "Perform the query, returning results as a set of concept identifiers"
  [^IndexSearcher searcher ^Query query max-hits]
  (let [topdocs ^TopDocs (.search searcher query (int (or max-hits 200)))]
    (topdocs->concept-ids searcher topdocs)))

(defn q-or
  [queries]
  (case (count queries)
    0 nil
    1 (first queries)
    (let [builder (BooleanQuery$Builder.)]
      (doseq [^Query query queries]
        (.add builder query BooleanClause$Occur/SHOULD))
      (.build builder))))

(defn q-and
  [queries]
  (case (count queries)
    0 nil
    1 (first queries)
    (let [builder (BooleanQuery$Builder.)]
      (doseq [query queries]
        (.add builder ^Query query BooleanClause$Occur/MUST))
      (.build builder))))

(defn q-not
  "Returns the logical query of q1 NOT q2"
  [^Query q1 ^Query q2]
  (-> (BooleanQuery$Builder.)
      (.add q1 BooleanClause$Occur/MUST)
      (.add q2 BooleanClause$Occur/MUST_NOT)
      (.build)))

(defn q-self
  "Returns a query that will only return documents for the concept specified."
  [concept-id]
  (LongPoint/newExactQuery "concept-id" concept-id))

(defn q-concept-ids
  "Returns a query that will return documents for the concepts specified."
  [^Collection concept-ids]
  (LongPoint/newSetQuery "concept-id" concept-ids))

(defn q-descendantOf
  "Returns a query that matches descendants of the specified concept."
  [concept-id]
  (LongPoint/newExactQuery (str snomed/IsA) concept-id))

(defn q-descendantOfAny
  [^Collection concept-ids]
  (LongPoint/newSetQuery (str snomed/IsA) concept-ids))

(defn q-descendantOrSelfOf
  "Returns a query that matches descendants of the specified concept plus the specified concept itself."
  [concept-id]
  (-> (BooleanQuery$Builder.)
      (.add (q-self concept-id) BooleanClause$Occur/SHOULD)
      (.add (q-descendantOf concept-id) BooleanClause$Occur/SHOULD)
      (.build)))

(defn q-descendantOrSelfOfAny
  [concept-ids]
  (-> (BooleanQuery$Builder.)
      (.add (q-concept-ids concept-ids) BooleanClause$Occur/SHOULD)
      (.add (q-descendantOfAny concept-ids) BooleanClause$Occur/SHOULD)
      (.build)))


(defn q-childOf
  "A query for direct (proximal) children of the specified concept."
  [concept-id]
  (LongPoint/newExactQuery (str "d" snomed/IsA) concept-id))

(defn q-childOfAny
  [^Collection concept-ids]
  (LongPoint/newSetQuery (str "d" snomed/IsA) concept-ids))

(defn q-childOrSelfOf
  "A query for direct (proximal) children of the specified concept plus the concept itself."
  [concept-id]
  (-> (BooleanQuery$Builder.)
      (.add (q-self concept-id) BooleanClause$Occur/SHOULD)
      (.add (q-childOf concept-id) BooleanClause$Occur/SHOULD)
      (.build)))

(defn q-childOrSelfOfAny
  [^Collection concept-ids]
  (-> (BooleanQuery$Builder.)
      (.add (q-concept-ids concept-ids) BooleanClause$Occur/SHOULD)
      (.add (q-childOfAny concept-ids) BooleanClause$Occur/SHOULD)
      (.build)))

(defn q-ancestorOf
  "A query for concepts that are ancestors of the specified concept."
  [store concept-id]
  (let [^Collection parents (disj (store/get-all-parents store concept-id) concept-id)]
    (LongPoint/newSetQuery "concept-id" parents)))

(defn q-ancestorOfAny
  [store ^Collection concept-ids]
  (let [^Collection parents (into #{} (mapcat #(disj (store/get-all-parents store %) %) concept-ids))]
    (LongPoint/newSetQuery "concept-id" parents)))

(defn q-ancestorOrSelfOf
  "A query for concepts that are ancestors of the specified concept plus the concept itself."
  [store concept-id]
  (let [^Collection parents (store/get-all-parents store concept-id)]
    (LongPoint/newSetQuery "concept-id" parents)))

(defn q-ancestorOrSelfOfAny
  [store ^Collection concept-ids]
  (let [^Collection all-parents (into #{} (mapcat #(store/get-all-parents store %) concept-ids))]
    (LongPoint/newSetQuery "concept-id" all-parents)))

(defn q-parentOf
  [store concept-id]
  (let [^Collection parents (map last (#'store/get-raw-parent-relationships store concept-id snomed/IsA))]
    (LongPoint/newSetQuery "concept-id" parents)))

(defn q-parentOfAny
  [store ^Collection concept-ids]
  (let [^Collection all-parents (into #{} (mapcat #(map last (#'store/get-raw-parent-relationships store % snomed/IsA)) concept-ids))]
    (LongPoint/newSetQuery "concept-id" all-parents)))

(defn q-parentOrSelfOf
  [store concept-id]
  (let [^Collection parents (conj (map last (#'store/get-raw-parent-relationships store concept-id snomed/IsA)) concept-id)]
    (LongPoint/newSetQuery "concept-id" parents)))

(defn q-parentOrSelfOfAny
  [store ^Collection concept-ids]
  (let [^Collection parents (into #{} (mapcat #(conj (map last (#'store/get-raw-parent-relationships store % snomed/IsA)) %) concept-ids))]
    (LongPoint/newSetQuery "concept-id" parents)))

(defn q-memberOf
  "A query for concepts that are referenced by the given reference set."
  [refset-id]
  (LongPoint/newExactQuery "concept-refsets" refset-id))

(defn q-memberOfAny
  [^Collection refset-ids]
  (LongPoint/newSetQuery "concept-refsets" refset-ids))

(defn q-memberOfInstalledReferenceSet
  "A query for concepts that are a member of any reference set."
  [store]
  (LongPoint/newSetQuery "concept-refsets" ^Collection (store/get-installed-reference-sets store)))

(defn q-any
  "Returns a query that returns 'any' concept."
  []
  (q-descendantOrSelfOf snomed/Root))

(defn q-attribute-descendantOrSelfOf
  "Returns a query constraining to documents with the specified property and value.
  It uses the 'descendantOrSelfOf' constraint."
  [property value]
  (LongPoint/newExactQuery (str property) value))

(defn q-attribute-exactly-equal
  "A query for documents with the property exactly equal to the value.
  Usually, it would be more appropriate to use `q-attribute-descendantOrSelfOf`."
  [property value]
  (LongPoint/newExactQuery (str "d" property) value))

(defn q-attribute-in-set
  [property coll]
  (LongPoint/newSetQuery (str property) ^Collection coll))

(defn q-attribute-count
  "A query for documents for a count direct properties (parent relationships) of
  the type specified.
  Parameters
  - property    : concept-id of the attribute
  - minimum     : minimum count
  - maximum     : maximum count (use Integer/MAX_VALUE for half-open range)
  For example, get concepts with 4 or more active ingredients:
  (q-attribute-count 127489000 4 0)"
  [property minimum maximum]
  (if (and (= 0 minimum) (= maximum 0))
    (throw (ex-info "not yet implemented: cardinality [0..0]" {:property property}))
    (IntPoint/newRangeQuery (str "c" property) (int minimum) (int maximum))))

(defn test-query [store ^IndexSearcher searcher ^Query q ^long max-hits]
  (when q
    (->> (.search searcher q max-hits)
         (topdocs->concept-ids searcher)
         (map (partial store/get-fully-specified-name store))
         (map #(select-keys % [:conceptId :term])))))

(comment
  (build-search-index "snomed.db/store.db" "snomed.db/search.db" "en-GB")
  (q-descendantOf 24700007)
  (q-descendantOrSelfOf 24700007)
  (q-childOrSelfOf 24700007)
  (def store (store/open-store "snomed.db/store.db"))
  (def index-reader (open-index-reader "snomed.db/search.db"))
  (def searcher (org.apache.lucene.search.IndexSearcher. index-reader))
  (q-ancestorOf store 24700007)

  (def testq (comp clojure.pprint/print-table (partial test-query store searcher)))

  (testq (q-ancestorOf store 24700007) 100000)
  (testq (q-ancestorOrSelfOf store 24700007) 100000)
  (testq (q-parentOrSelfOf store 24700007) 10000)
  (testq (q-parentOf store 24700007) 10000)
  (testq (q-memberOf 991411000000109) 10)
  (testq (q-attribute-descendantOrSelfOf 246075003 387517004) 10000)
  (testq (q-attribute-exactly-equal 246075003 387517004) 1000)
  (testq (q-childOrSelfOf 404684003) 1000)
  (testq (com.eldrix.hermes.expression.ecl/parse store "^  991411000000109 |Example problem list concepts reference set|") 1000)
  )