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
(ns ^:no-doc com.eldrix.hermes.impl.search
  "Search creates a Lucene search index for descriptions."
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.impl.language :as lang]
            [com.eldrix.hermes.impl.lucene :as lucene]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.rf2]
            [com.eldrix.hermes.snomed :as snomed])

  (:import (org.apache.lucene.analysis.standard StandardAnalyzer)
           (org.apache.lucene.analysis.tokenattributes CharTermAttribute)
           (org.apache.lucene.analysis Analyzer)
           (org.apache.lucene.document Document DoubleField TextField Field$Store StoredField LongPoint StringField DoubleDocValuesField IntPoint)
           (org.apache.lucene.index Term IndexWriter IndexWriterConfig DirectoryReader IndexWriterConfig$OpenMode IndexReader)
           (org.apache.lucene.search IndexSearcher MatchNoDocsQuery TermQuery FuzzyQuery BooleanClause$Occur PrefixQuery
                                     BooleanQuery$Builder DoubleValuesSource Query ScoreDoc WildcardQuery
                                     MatchAllDocsQuery BooleanQuery BooleanClause)
           (org.apache.lucene.store FSDirectory)
           (org.apache.lucene.queries.function FunctionScoreQuery)
           (java.util Collection)))

(set! *warn-on-reflection* true)

(s/def ::store any?)
(s/def ::searcher #(instance? IndexSearcher %))
(s/def ::writer #(instance? IndexWriter %))

;; Specification for search parameters
(s/def ::s string?)
(s/def ::max-hits pos-int?)
(s/def ::fuzzy (s/int-in 0 3))
(s/def ::fallback-fuzzy (s/int-in 0 3))
(s/def ::query #(instance? Query %))
(s/def ::show-fsn? boolean?)
(s/def ::inactive-concepts? boolean?)
(s/def ::inactive-descriptions? boolean?)
(s/def ::remove-duplicates? boolean?)
(s/def ::properties (s/map-of int? int?))
(s/def ::concept-refsets (s/coll-of :info.snomed.Concept/id))
(s/def ::search-params (s/keys :req-un [::s]
                               :opt-un [::max-hits ::fuzzy ::fallback-fuzzy ::query
                                        ::show-fsn? ::inactive-concepts? ::inactive-descriptions?
                                        ::properties ::concept-refsets]))
;; Specification for search results
(s/def ::id :info.snomed.Description/id)
(s/def ::conceptId :info.snomed.Concept/id)
(s/def ::term string?)
(s/def ::preferredTerm string?)
(s/def ::result (s/keys :req-un [::id ::conceptId ::term ::preferredTerm]))
(defn gen-result
  ([] (gen/fmap snomed/map->Result (s/gen ::result)))
  ([result] (gen/fmap #(merge % result) (gen-result))))

(defn remove-duplicates
  "Returns a lazy sequence removing consecutive duplicates in coll with
  duplicates determined by equality function `equality-fn`.
  Returns a transducer when no collection is provided.
  Also see [[clojure.core/dedupe]]."
  ([equality-fn]
   (fn [rf]
     (let [pv (volatile! ::none)]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input]
          (let [prior @pv]
            (vreset! pv input)
            (if (equality-fn prior input)
              result
              (rf result input))))))))
  ([equality-fn coll] (sequence (remove-duplicates equality-fn) coll)))

(defn duplicate-result?
  "Are the given results, in effect, duplicates?
  This matches a result if they have the same conceptId and same term."
  [{a-concept-id :conceptId a-term :term} {b-concept-id :conceptId b-term :term}]
  (and (= a-concept-id b-concept-id) (= a-term b-term)))

(defn make-extended-descriptions
  [store language-refset-ids concept]
  (let [ec (store/make-extended-concept store concept)
        ec' (dissoc ec :descriptions)
        preferred (store/preferred-synonym store (:id concept) language-refset-ids)]
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
    (doseq [[rel concept-ids] (get-in ed [:concept :parentRelationships])]
      (let [relationship (str rel)]                         ;; encode parent relationships as relationship type concept id
        (doseq [concept-id concept-ids]                     ;; and use a transitive closure table for the defining relationship
          (.add doc (LongPoint. relationship (long-array [concept-id]))))))
    (doseq [[rel concept-ids] (get-in ed [:concept :directParentRelationships])]
      (.add doc (IntPoint. (str "c" rel) (int-array [(count concept-ids)]))) ;; encode count of direct parent relationships by type as ("c" + relationship type = count)
      (let [relationship (str "d" rel)]                     ;; encode direct parent relationships as ("d" + relationship type = concept id)
        (doseq [concept-id concept-ids]
          (.add doc (LongPoint. relationship (long-array [concept-id]))))))
    (doseq [{typeId :typeId, ^String v :value} (get-in ed [:concept :concreteValues])]
      (let [k (str "v" typeId)]
        (case (.charAt v 0)
          \# (.add doc (DoubleField. k ^double (Double/parseDouble (subs v 1)) Field$Store/NO)) ;; parse numbers into double
          \" (.add doc (StringField. k ^String (subs v 1 (unchecked-dec (.length v))) Field$Store/NO)) ;; unwrap string from quotes for search index
          (.add doc (StringField. k v Field$Store/NO)))))  ;; store booleans as strings
    (doseq [preferred-in (:preferredIn ed)]
      (.add doc (LongPoint. "preferred-in" (long-array [preferred-in]))))
    (doseq [acceptable-in (:acceptableIn ed)]
      (.add doc (LongPoint. "acceptable-in" (long-array [acceptable-in]))))
    (doseq [refset (get-in ed [:concept :refsets])]
      (.add doc (LongPoint. "concept-refsets" (long-array [refset]))))
    (doseq [refset (get ed :refsets)]
      (.add doc (LongPoint. "description-refsets" (long-array [refset]))))
    doc))

(defn concept->documents
  [store language-refset-ids concept]
  (->> (make-extended-descriptions store language-refset-ids concept)
       (map extended-description->document)))

(defn open-index-writer
  ^IndexWriter [f]
  (let [analyzer (StandardAnalyzer.)
        directory (FSDirectory/open (.toPath (io/file f)))
        writer-config (doto (IndexWriterConfig. analyzer)
                        (.setOpenMode IndexWriterConfig$OpenMode/CREATE))]
    (IndexWriter. directory writer-config)))

(defn open-index-reader
  ^IndexReader [f]
  (let [directory (FSDirectory/open (.toPath (io/file f)))]
    (DirectoryReader/open directory)))

(defn build-search-index
  "Build a search index using the SNOMED CT store at `store-filename`."
  [store-filename search-filename language-priority-list]
  (let [nthreads (.availableProcessors (Runtime/getRuntime))
        ch (a/chan 50)]
    (with-open [store (store/open-store store-filename)
                writer (open-index-writer search-filename)]
      (let [langs (lang/match store language-priority-list)
            langs' (if (seq langs)
                     langs
                     (do (log/warn "No language refset for any locale in requested priority list" {:priority-list language-priority-list :store-filename store-filename})
                         (log/warn "Falling back to default of 'en-US'")
                         (lang/match store "en-US")))]
        (when-not (seq langs') (throw (ex-info "No language refset for any locale listed in priority list"
                                               {:priority-list language-priority-list :store-filename store-filename})))
        (a/thread (store/stream-all-concepts store ch))     ;; start streaming all concepts
        (a/<!! (a/pipeline
                 nthreads                                   ;; Parallelism factor
                 (doto (a/chan) (a/close!))
                 (comp (map #(concept->documents store langs' %))
                       (map #(.addDocuments writer %)))
                 ch true (fn ex-handler [ex] (log/error ex) (a/close! ch) nil))))
      (.forceMerge writer 1))))

(defn- make-token-query
  [^String token fuzzy]
  (let [term (Term. "term" token)
        tq (TermQuery. term)
        builder (BooleanQuery$Builder.)]
    (.add builder (PrefixQuery. term) BooleanClause$Occur/SHOULD)
    (if (and fuzzy (pos? fuzzy)) (.add builder (FuzzyQuery. term (min 2 fuzzy)) BooleanClause$Occur/SHOULD)
                                 (.add builder tq BooleanClause$Occur/SHOULD))
    (.setMinimumNumberShouldMatch builder 1)
    (.build builder)))

(defn tokenize
  "Tokenize the string 's' according the 'analyzer' and field specified."
  [^Analyzer analyzer ^String field-name ^String s]
  (with-open [tokenStream (.tokenStream analyzer field-name s)]
    (let [termAtt (.addAttribute tokenStream CharTermAttribute)]
      (.reset tokenStream)
      (loop [has-more (.incrementToken tokenStream)
             result []]
        (if-not has-more
          result
          (let [term (.toString termAtt)]
            (recur (.incrementToken tokenStream) (conj result term))))))))

(defn- make-tokens-query
  ([s] (make-tokens-query s 0))
  ([s fuzzy]
   (with-open [analyzer (StandardAnalyzer.)]
     (when s
       (let [qs (map #(make-token-query % fuzzy) (tokenize analyzer "term" s))]
         (if (> (count qs) 1)
           (let [builder (BooleanQuery$Builder.)]
             (doseq [q qs]
               (.add builder q BooleanClause$Occur/MUST))
             (.build builder))
           (first qs)))))))

(defn q-or [queries]
  (lucene/q-or queries))

(defn q-and [queries]
  (lucene/q-and queries))

(defn q-not
  "Returns the logical query of q1 NOT q2"
  [^Query q1 ^Query q2]
  (lucene/q-not q1 q2))

(defn q-fsn
  []
  (LongPoint/newExactQuery "type-id" snomed/FullySpecifiedName))

(defn q-synonym
  []
  (LongPoint/newExactQuery "type-id" snomed/Synonym))

(defn q-concept-active
  [active?]
  (TermQuery. (Term. "concept-active" (str active?))))

(defn q-description-active
  [active?]
  (TermQuery. (Term. "description-active" (str active?))))

(defn boost-length-query
  "Returns a new query with scores boosted by the inverse of the length"
  [^Query q]
  (FunctionScoreQuery. q (DoubleValuesSource/fromDoubleField "length-boost")))

(defn- make-search-query
  ^Query
  [{:keys [s fuzzy show-fsn? inactive-concepts? inactive-descriptions? concept-refsets properties]
    :or   {show-fsn? false inactive-concepts? false inactive-descriptions? true}}]
  (let [query (cond-> (BooleanQuery$Builder.)
                      s
                      (.add (make-tokens-query s fuzzy) BooleanClause$Occur/MUST)

                      (not inactive-concepts?)
                      (.add (TermQuery. (Term. "concept-active" "true")) BooleanClause$Occur/FILTER)

                      (not inactive-descriptions?)
                      (.add (TermQuery. (Term. "description-active" "true")) BooleanClause$Occur/FILTER)

                      (not show-fsn?)
                      (.add (q-fsn) BooleanClause$Occur/MUST_NOT)

                      (seq concept-refsets)
                      (.add (LongPoint/newSetQuery "concept-refsets" ^Collection concept-refsets) BooleanClause$Occur/FILTER))]
    (doseq [[k v] properties]
      (let [^Collection vv (if (instance? Collection v) v [v])]
        (.add query
              (LongPoint/newSetQuery (str k) vv)
              BooleanClause$Occur/FILTER)))
    (.build query)))

(defn doc->result [^Document doc]
  (snomed/->Result (.numericValue (.getField doc "id"))
                   (.numericValue (.getField doc "concept-id"))
                   (.get doc "term")
                   (.get doc "preferred-term")))

(defn xf-doc-id->-result
  "Returns a transducer that maps a Lucene document id into a search result."
  [^IndexSearcher searcher]
  (let [stored-fields (.storedFields searcher)]
    (map (fn [^long doc-id] (doc->result (.document stored-fields doc-id))))))

(defn xf-doc-id->concept-id
  "Returns a transducer that maps a Lucene document id into a concept identifier."
  [^IndexSearcher searcher]
  (let [stored-fields (.storedFields searcher)]
    (map (fn [^long doc-id] (.numericValue (.getField (.document stored-fields doc-id #{"concept-id"}) "concept-id"))))))

(defn xf-scoredoc->concept-id
  "Returns a transducer that maps a Lucene ScoreDoc to a concept identifier"
  [^IndexSearcher searcher]
  (let [stored-fields (.storedFields searcher)]
    (map (fn [^ScoreDoc score-doc] (.numericValue (.getField (.document stored-fields (.-doc score-doc) #{"concept-id"}) "concept-id"))))))

(defn do-query-for-results
  "Perform a search using query 'q' returning results as a sequence of Result
items."
  ([^IndexSearcher searcher ^Query q]
   (let [stored-fields (.storedFields searcher)]
     (->> (lucene/search-all searcher q)
          (map #(doc->result (.document stored-fields %))))))
  ([^IndexSearcher searcher ^Query q max-hits]
   (let [stored-fields (.storedFields searcher)]
     (->> (seq (.-scoreDocs (.search searcher q (int max-hits))))
          (map #(doc->result (.document stored-fields (.-doc ^ScoreDoc %))))))))

(defn do-query-for-concept-ids
  "Perform the query, returning results as a set of concept identifiers"
  ([^IndexSearcher searcher ^Query query]
   (into #{}
         (xf-doc-id->concept-id searcher)
         (lucene/search-all searcher query)))
  ([^IndexSearcher searcher ^Query query max-hits]
   (into #{}
         (xf-scoredoc->concept-id searcher)
         (seq (.-scoreDocs (.search searcher query ^int max-hits))))))

(s/fdef do-search
  :args (s/cat :searcher ::searcher :params ::search-params))
(defn do-search
  "Perform a search against the index.
  Parameters:
  - searcher : the IndexSearcher to use
  - params   : a map of search parameters, which are:

  | keyword                 | description (default)                             |
  |---------------------    |---------------------------------------------------|
  | :s                      | search string to use                              |
  | :max-hits               | maximum hits (if omitted returns unlimited but    |
  |                         | *unsorted* results)                               |
  | :fuzzy                  | fuzziness (0-2, default 0)                        |
  | :fallback-fuzzy         | if no results, try fuzzy search (0-2, default 0). |
  | :query                  | additional ^Query to apply                        |
  | :show-fsn?              | show FSNs in results? (default, false)            |
  | :inactive-concepts?     | search descriptions of inactive concepts? (false) |
  | :inactive-descriptions? | search inactive descriptions? (default, true)     |
  | :remove-duplicates?     | remove duplicate results (default, false)         |
  | :properties             | a map of properties and their possible values.    |
  | :concept-refsets        | a collection of refset ids to limit search        |

  The properties map contains keys for a property and then either a single
  identifier or vector of identifiers to limit search.

  Example: to search for neurologist as an occupation ('IS-A' '14679004')
  ```
  (do-search searcher {:s \"neurologist\"  :properties {snomed/IsA [14679004]}})
  ```
  A FSN is a fully-specified name and should generally be left out of search."
  [^IndexSearcher searcher {:keys [max-hits fuzzy fallback-fuzzy remove-duplicates?] :as params}]
  (let [q1 (make-search-query params)
        q2 (if-let [q (:query params)] (q-and [q1 q]) q1)
        q3 (boost-length-query q2)
        results (if max-hits
                  (do-query-for-results searcher q3 (int max-hits))
                  (do-query-for-results searcher q3))]
    (if (seq results)
      (if remove-duplicates?
        (remove-duplicates duplicate-result? results)
        results)
      (let [fuzzy (or fuzzy 0)
            fallback (or fallback-fuzzy 0)]
        (when (and (zero? fuzzy) (pos? fallback))           ; only fallback to fuzzy search if no fuzziness requested first time
          (do-search searcher (assoc params :fuzzy fallback)))))))

(defn q-self
  "Returns a query that will only return documents for the concept specified."
  [concept-id]
  (LongPoint/newExactQuery "concept-id" concept-id))

(defn q-match-none []
  (MatchNoDocsQuery.))

(defn q-match-all []
  (MatchAllDocsQuery.))

(defn q-match-all?
  "Does the query match all documents?"
  [q]
  (instance? MatchAllDocsQuery q))

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
  (let [^Collection parent-ids (disj (store/all-parents store concept-id) concept-id)]
    (LongPoint/newSetQuery "concept-id" parent-ids)))

(defn q-ancestorOfAny
  [store ^Collection concept-ids]
  (let [^Collection parent-ids (set/difference (store/all-parents store concept-ids) (set concept-ids))]
    (LongPoint/newSetQuery "concept-id" parent-ids)))

(defn q-ancestorOrSelfOf
  "A query for concepts that are ancestors of the specified concept plus the concept itself."
  [store concept-id]
  (let [^Collection parent-ids (store/all-parents store concept-id)]
    (LongPoint/newSetQuery "concept-id" parent-ids)))

(defn q-ancestorOrSelfOfAny
  [store ^Collection concept-ids]
  (LongPoint/newSetQuery "concept-id" ^Collection (store/all-parents store concept-ids)))

(defn q-parentOf
  "A query for parents (immediate supertypes) of the specified concept excluding
  the concept itself."
  [store concept-id]
  (let [^Collection parent-ids (store/proximal-parent-ids store concept-id)]
    (LongPoint/newSetQuery "concept-id" parent-ids)))

(defn q-parentOfAny
  "A query for parents (immediate supertypes) of the specified concepts."
  [store ^Collection concept-ids]
  (let [^Collection all-parents (into #{} (mapcat #(store/proximal-parent-ids store %)) concept-ids)]
    (LongPoint/newSetQuery "concept-id" all-parents)))

(defn q-parentOrSelfOf
  "A query for parents (immediate supertypes) of the specified concept including
  the concept itself."
  [store concept-id]
  (let [^Collection parent-ids (conj (store/proximal-parent-ids store concept-id) concept-id)]
    (LongPoint/newSetQuery "concept-id" parent-ids)))

(defn q-parentOrSelfOfAny
  "A query for parents (immediate supertypes) of the specified concepts
  including the concepts themselves."
  [store ^Collection concept-ids]
  (let [^Collection parent-ids (into (set concept-ids) (mapcat #(store/proximal-parent-ids store %)) concept-ids)]
    (LongPoint/newSetQuery "concept-id" parent-ids)))

(defn q-memberOf
  "A query for concepts that are referenced by the given reference set."
  [refset-id]
  (LongPoint/newExactQuery "concept-refsets" refset-id))

(defn q-memberOfAny
  "A query for concepts that are referenced by the given reference sets."
  [^Collection refset-ids]
  (LongPoint/newSetQuery "concept-refsets" refset-ids))

(defn q-description-memberOf
  [refset-id]
  (LongPoint/newExactQuery "description-refsets" refset-id))

(defn q-description-memberOfAny
  [^Collection refset-ids]
  (LongPoint/newSetQuery "description-refsets" refset-ids))


(defn q-memberOfInstalledReferenceSet
  "A query for concepts that are a member of any reference set."
  [store]
  (LongPoint/newSetQuery "concept-refsets" ^Collection (store/installed-reference-sets store)))

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
  (when-not (zero? (count coll))
    (LongPoint/newSetQuery (str "d" property) ^Collection coll)))

(defn q-attribute-count
  "A query for documents for a count direct properties (parent relationships) of
  the type specified.
  Parameters
  - property    : concept-id of the attribute
  - minimum     : minimum count
  - maximum     : maximum count (use Integer/MAX_VALUE for half-open range)

  For example, get concepts with 4 or more active ingredients:
  ```
  (q-attribute-count 127489000 4 Integer/MAX_VALUE)
  ```"
  [property minimum maximum]
  (let [field (str "c" property)]
    (cond
      (< maximum minimum)
      (throw (ex-info "Invalid range." {:property property :minimum minimum :maximum maximum}))

      (and (pos? minimum) (= minimum maximum))
      (IntPoint/newExactQuery field (int minimum))

      (pos? minimum)
      (IntPoint/newRangeQuery field (int minimum) (int maximum))

      (and (zero? minimum) (zero? maximum))
      (q-not (MatchAllDocsQuery.) (IntPoint/newRangeQuery field 1 Integer/MAX_VALUE))

      (and (zero? minimum) (= Integer/MAX_VALUE maximum)) ;;A cardinality of [0..*] should therefore never be used as this indicates that the given attribute is not being constrained in any way, and is therefore a redundant part of the expression constraint.
      (MatchAllDocsQuery.)

      (and (zero? minimum) (pos? maximum))
      (q-not (MatchAllDocsQuery.) (IntPoint/newRangeQuery field (int (inc maximum)) Integer/MAX_VALUE)))))

(defn q-term [s] (make-tokens-query s))

(defn q-wildcard [s]
  (WildcardQuery. (Term. "term" ^String s)))

(defn q-type [type-id]
  (LongPoint/newExactQuery "type-id" type-id))

(defn q-typeAny
  [^Collection types]
  (LongPoint/newSetQuery "type-id" types))

(defn q-acceptability
  [accept refset-id]
  (case accept
    :preferred-in (LongPoint/newExactQuery "preferred-in" refset-id)
    :acceptable-in (LongPoint/newExactQuery "acceptable-in" refset-id)
    (throw (IllegalArgumentException. (str "unknown acceptability '" accept "'")))))

(defn q-acceptabilityAny
  [accept ^Collection refset-ids]
  (case accept
    :preferred-in (LongPoint/newSetQuery "preferred-in" refset-ids)
    :acceptable-in (LongPoint/newSetQuery "acceptable-in" refset-ids)
    (throw (IllegalArgumentException. (str "unknown acceptability '" accept "'")))))

(defn q-concrete= [type-id n] (DoubleField/newExactQuery (str "v" type-id) n))
(defn q-concrete> [type-id n] (DoubleField/newRangeQuery (str "v" type-id) (Math/nextUp (double n)) Double/POSITIVE_INFINITY))
(defn q-concrete>= [type-id n] (DoubleField/newRangeQuery (str "v" type-id) n Double/POSITIVE_INFINITY))
(defn q-concrete< [type-id n] (DoubleField/newRangeQuery (str "v" type-id) Double/NEGATIVE_INFINITY (Math/nextDown (double n))))
(defn q-concrete<= [type-id n] (DoubleField/newRangeQuery (str "v" type-id) Double/NEGATIVE_INFINITY n))
(defn q-concrete!= [type-id n] (q-and [(q-concrete< type-id n) (q-concrete> type-id n)]))

(defn rewrite-query
  "Rewrites a query separating out any top-level 'inclusions' from 'exclusions'.
  Returns a vector of two queries inclusions and the exclusions.
  Exclusions will be rewritten from MUST_NOT to MUST.
  Useful in a situation where exclusions need to be applied independently
  to a substrate and the NOT will be specified in a parent clause."
  [^Query query]
  (if-not (instance? BooleanQuery query)
    (vector query nil)
    (let [clauses (.clauses ^BooleanQuery query)
          incl (seq (filter #(not= (.getOccur ^BooleanClause %) BooleanClause$Occur/MUST_NOT) clauses))
          excl (seq (filter #(= (.getOccur ^BooleanClause %) BooleanClause$Occur/MUST_NOT) clauses))]
      (vector
        ;; build the inclusive clauses directly into a new query
        (when incl
          (let [builder (BooleanQuery$Builder.)]
            (doseq [^BooleanClause clause incl]
              (.add builder clause))
            (.build builder)))
        ;; extract the exclusive queries from each clause but rewrite
        (when excl
          (let [builder (BooleanQuery$Builder.)]
            (doseq [^BooleanClause clause excl]
              (.add builder (.getQuery clause) BooleanClause$Occur/MUST))
            (.build builder)))))))

(defn test-query [store ^IndexSearcher searcher ^Query q ^long max-hits]
  (when q
    (->> (do-query-for-concept-ids searcher q max-hits)
         (map (partial store/fully-specified-name store))
         (map #(select-keys % [:conceptId :term])))))

(comment
  (build-search-index "snomed.db/store.db" "snomed.db/search.db" "en-GB")

  (def reader (open-index-reader "snomed.db/search.db"))
  (def searcher (IndexSearcher. reader))
  (do-search searcher {:s "abdom p" :properties {snomed/IsA 404684003}})
  (count (do-search searcher {:properties {snomed/IsA 24700007} :inactive-concepts? true}))
  (do-query-for-results searcher (make-search-query {:properties {snomed/IsA 24700007} :inactive-concepts? true}))
  (q-or [(make-search-query {:inactive-concepts? true})])
  (do-query-for-concept-ids searcher (q-or [(make-search-query {:inactive-concepts? true})]))
  (.clauses (make-search-query {:inactive-concepts? true}))
  (do-search searcher {:s "bendroflumethiatide" :fuzzy 3})
  (do-query-for-results searcher (q-attribute-count snomed/HasActiveIngredient 0 0)))
