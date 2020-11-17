(ns com.eldrix.hermes.search
  (:require
    [clojure.core.async :as async]
    [com.eldrix.hermes.store :as store])
  (:import (org.apache.lucene.index Term IndexWriter IndexWriterConfig DirectoryReader IndexWriterConfig$OpenMode IndexReader)
           (org.apache.lucene.store FSDirectory)
           (org.apache.lucene.document Document TextField Field$Store StoredField LongPoint StringField DoubleDocValuesField)
           (org.apache.lucene.search IndexSearcher TermQuery FuzzyQuery BooleanClause$Occur PrefixQuery BooleanQuery$Builder DoubleValuesSource BooleanClause Query ScoreDoc TopDocs)
           (org.apache.lucene.queries.function FunctionScoreQuery)
           (org.apache.lucene.analysis.standard StandardAnalyzer)
           (java.util Collection)))

(set! *warn-on-reflection* true)


(defn make-extended-descriptions
  [store concept]
  (let [ec (store/make-extended-concept store concept)
        ec' (dissoc ec :descriptions)]
    (map #(assoc % :concept (merge (dissoc ec' :concept)    ;; turn concept inside out to focus on description instead
                                   (:concept ec')))
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
              (.add (StoredField. "concept-id" ^long (get-in ed [:concept :id]))))]
    (doseq [[rel concept-ids] (get-in ed [:concept :parent-relationships])]
      (let [relationship (str rel)]                         ;; encode parent relationships as relationship type concept id
        (doseq [concept-id concept-ids]                     ;; and use a transitive closure table for the defining relationship
          (.add doc (LongPoint. relationship (long-array [concept-id]))))))
    (doseq [parent (get-in ed [:concept :direct-parents])]
      (.add doc (LongPoint. "direct-parents" (long-array [parent]))))
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
  [store concept]
  (->> (make-extended-descriptions store concept)
       (map extended-description->document)))

(defn make-token-query
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

(defn make-tokens-query
  ([s] (make-tokens-query s 0))
  ([s fuzzy]
   (when s
     (let [qs (map #(make-token-query % fuzzy) (clojure.string/split (clojure.string/lower-case s) #"\s"))]
       (if (> (count qs) 1)
         (let [builder (BooleanQuery$Builder.)]
           (doseq [q qs]
             (.add builder q BooleanClause$Occur/MUST))
           (.build builder))
         (first qs))))))

(defn write-concept! [store ^IndexWriter writer concept]
  (let [docs (concept->documents store concept)]
    (doseq [doc docs]
      (.addDocument writer doc))))

(defn write-batch! [store ^IndexWriter writer concepts]
  (dorun (map (partial write-concept! store writer) concepts))
  (.commit writer))


(defn ^IndexWriter open-index-writer
  [filename]
  (let [analyzer (StandardAnalyzer.)
        directory (FSDirectory/open (java.nio.file.Paths/get filename (into-array String [])))
        writer-config (doto (IndexWriterConfig. analyzer)
                        (.setOpenMode IndexWriterConfig$OpenMode/CREATE_OR_APPEND))]
    (IndexWriter. directory writer-config)))

(defn ^IndexReader open-index-reader
  [filename]
  (let [directory (FSDirectory/open (java.nio.file.Paths/get filename (into-array String [])))]
    (DirectoryReader/open directory)))

(defn build-search-index
  "Build a search index using the SNOMED CT store at `store-filename`."
  [store-filename search-filename]
  (let [ch (async/chan 1 (partition-all 1000))]             ;; chunk concepts into batches
    (with-open [store (store/open-store store-filename)
                writer (open-index-writer search-filename)]
      (async/thread (store/stream-all-concepts store ch))   ;; start streaming all concepts
      (async/<!!                                            ;; pipeline for side-effects
        (async/pipeline
          (.availableProcessors (Runtime/getRuntime))       ;; Parallelism factor
          (doto (async/chan) (async/close!))                ;; Output channel - /dev/null
          (map (partial write-batch! store writer))
          ch))
      (.forceMerge writer 1))))

(defn create-test-search
  ([store-filename search-filename] (create-test-search store-filename search-filename [73211009 46635009 195353004 232369001 711158005]))
  ([store-filename search-filename concept-ids]
   (clojure.java.shell/sh "rm" "-rf" search-filename)
   ;; let's create a really small index for testing
   (with-open [store (store/open-store store-filename)
               writer (open-index-writer search-filename)]
     (let [concepts (map (partial store/get-concept store) concept-ids)]
       (write-batch! store writer concepts)))))

(def default-search-parameters
  {:s                      nil
   :max-hits               0
   :fuzzy                  0
   :fallback-fuzzy         0
   :show-fsn?              false
   :inactive-concepts?     false
   :inactive-descriptions? false
   :properties             {}})

(defn ^Query make-search-query
  [{:keys [s fuzzy show-fsn? inactive-concepts? inactive-descriptions? properties]}]
  (let [booster (DoubleValuesSource/fromDoubleField "length-boost")
        query (cond-> (org.apache.lucene.search.BooleanQuery$Builder.)
                      s
                      (.add (make-tokens-query s fuzzy) BooleanClause$Occur/MUST)
                      (not inactive-concepts?)
                      (.add (TermQuery. (Term. "concept-active" "true")) BooleanClause$Occur/FILTER)
                      (not inactive-descriptions?)
                      (.add (TermQuery. (Term. "description-active" "true")) BooleanClause$Occur/FILTER)
                      (not show-fsn?)
                      (.add (LongPoint/newExactQuery "type-id" 900000000000003001) BooleanClause$Occur/MUST_NOT))]
    (doseq [[k v] properties]
      (let [^Collection vv (if (vector? v) v [v])]
        (.add query
              (LongPoint/newSetQuery (str k) vv)
              BooleanClause$Occur/FILTER)))
    (FunctionScoreQuery. (.build query) booster)))

(defn do-search [^IndexSearcher searcher params]
  (let [query (make-search-query params)
        hits (seq (.-scoreDocs ^TopDocs (.search searcher query (int (or (:max-hits params) 200)))))]
    (if hits
      (map #(hash-map :id (Long/parseLong (.get ^Document % "id")) :term (.get ^Document % "term") :concept-id (Long/parseLong (.get ^Document % "concept-id")))
           (map #(.doc searcher %) (map #(.-doc ^ScoreDoc %) hits)))
      (let [fuzzy (or (:fuzzy params) 0)
            fallback (or (:fallback-fuzzy params) 0)]
        (when (and (= fuzzy 0) (> fallback 0))
          (do-search searcher (assoc params :fuzzy fallback)))))))

(comment
  (build-search-index "snomed.db" "search.db")
  (def searcher (IndexSearcher. (open-index-reader "search.db")))

  (create-test-search "snomed.db" "test-search.db")
  (def searcher (IndexSearcher. (open-index-reader "test-search.db")))
  (def store (store/open-store "snomed.db"))
  (def langs (store/ordered-language-refsets-from-locale "en-GB" (store/get-installed-reference-sets store)))
  ;; is-a 14679004 = occupations
  (do-search searcher {:s "baker" :fuzzy 0 :fallback-fuzzy 1 :properties {116680003 [14679004]}})
  (do-search searcher {:s "neurol" :fuzzy 0 :fallback-fuzzy 1 :properties {116680003 [14679004]}})

  (map :term (map #(store/get-preferred-synonym store (:concept-id %) langs) (do-search searcher {:s "neurologist" :properties {116680003 [14679004]}})))
  )