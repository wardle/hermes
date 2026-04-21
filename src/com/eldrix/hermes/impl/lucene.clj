; Copyright (c) 2020-2023 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns ^:no-doc com.eldrix.hermes.impl.lucene
  (:require [clojure.core.async :as a])
  (:import (java.util List ArrayList)
           (org.apache.lucene.index LeafReaderContext NumericDocValues StoredFields)
           (org.apache.lucene.internal.hppc LongHashSet)
           (org.apache.lucene.util Version)
           (org.apache.lucene.search CollectionTerminatedException CollectorManager IndexSearcher BooleanClause$Occur BooleanQuery$Builder Query
                                     MatchAllDocsQuery BooleanQuery BooleanClause Collector LeafCollector Scorable ScoreMode)))

;;
;; Lucene 10 renames BooleanClause accessors (getQuery/getOccur -> query/occur)
;; because it became a Java record. We select the right accessor at load time
;; using `eval` so that only the live branch is compiled, avoiding AOT issues
;; where dead-branch `defn` class files overwrite the live ones.
;;

(def query-for-boolean-clause
  (eval (if (>= (.major Version/LATEST) 10)
          '(fn [^org.apache.lucene.search.BooleanClause clause] (.query clause))
          '(fn [^org.apache.lucene.search.BooleanClause clause] (.getQuery clause)))))

(def occur-for-boolean-clause
  (eval (if (>= (.major Version/LATEST) 10)
          '(fn [^org.apache.lucene.search.BooleanClause clause] (.occur clause))
          '(fn [^org.apache.lucene.search.BooleanClause clause] (.getOccur clause)))))

;;
;;
;;
;;

(set! *warn-on-reflection* true)

;; A Lucene results collector that collects *all* results into the mutable
;; java collection 'coll'.
(deftype IntoArrayCollector [^List coll]
  Collector
  (getLeafCollector [_ ctx]
    (let [base-id (.-docBase ctx)]
      (reify LeafCollector
        (^void setScorer [_ ^Scorable _scorer])             ;; NOP
        (^void collect [_ ^int doc-id]
          (.add coll (+ base-id doc-id))))))
  (scoreMode [_] ScoreMode/COMPLETE_NO_SCORES))

;; A Lucene CollectorManager that can collect results in parallel and then
;; create a lazy concatenation of the results once search is complete
(deftype IntoSequenceCollectorManager []
  CollectorManager
  (newCollector [_]
    (IntoArrayCollector. (ArrayList.)))
  (reduce [_ collectors]
    (mapcat #(.-coll ^IntoArrayCollector %) collectors)))

;; A Lucene Collector that puts search results onto a core.async channel
(deftype IntoChannelCollector [ch]
  Collector
  (getLeafCollector [_ ctx]
    (let [base-id (.-docBase ctx)]
      (reify LeafCollector
        (^void setScorer [_ ^Scorable _scorer])             ;; NOP
        (^void collect [_ ^int doc-id]
          (when-not (a/>!! ch (+ base-id doc-id))           ;; put the document on the channel, but if channel closed...
            (throw (CollectionTerminatedException.)))))))   ;; ... then prematurely terminate collection of the current leaf
  (scoreMode [_] ScoreMode/COMPLETE_NO_SCORES))

;; A Lucene CollectorManager that can collect results in parallel putting
;; results onto a channel, optionally closing when done.
(deftype IntoChannelCollectorManager [ch close?]
  CollectorManager
  (newCollector [_]
    (IntoChannelCollector. ch))
  (reduce [_ _]
    (when close? (a/close! ch))))

;; A Lucene Collector that collects a deduped set of `long` values read from
;; a named numeric field, one value per matching document. Per segment, reads
;; via NumericDocValues (column-oriented, fast) when the field has a DocValues
;; column, falling back to StoredFields decompression when it does not — so
;; indexes built before the NumericDocValuesField was written still work.
;; Backing store is a primitive `long[]`-backed set (Lucene's HPPC
;; `LongHashSet`), avoiding `Long` autoboxing per match. The HPPC package is
;; marked `internal` in Lucene; if a future Lucene release moves it, drop in
;; an equivalent primitive long set here.
(deftype IntoLongSetCollector [^String field ^LongHashSet s]
  Collector
  (getLeafCollector [_ ctx]
    (let [leaf (.reader ^LeafReaderContext ctx)]
      (if-let [^NumericDocValues ndv (.getNumericDocValues leaf field)]
        (reify LeafCollector
          (^void setScorer [_ ^Scorable _scorer])
          (^void collect [_ ^int doc-id]
            (.advance ndv doc-id)
            (.add s (.longValue ndv))))
        (let [^StoredFields sf (.storedFields leaf)
              ^java.util.Set field-set #{field}]
          (reify LeafCollector
            (^void setScorer [_ ^Scorable _scorer])
            (^void collect [_ ^int doc-id]
              (.add s (long (.numericValue (.getField (.document sf doc-id field-set) field))))))))))
  (scoreMode [_] ScoreMode/COMPLETE_NO_SCORES))

(deftype IntoLongSetCollectorManager [^String field]
  CollectorManager
  (newCollector [_] (IntoLongSetCollector. field (LongHashSet.)))
  (reduce [_ colls]
    (let [out (LongHashSet.)]
      (doseq [^IntoLongSetCollector c colls]
        (.addAll out ^LongHashSet (.-s c)))
      out)))

(defn long-hashset->set
  "Copy a Lucene `LongHashSet` into an immutable Clojure set of boxed longs.
  Iterates a `long[]` to a transient, so each value is boxed exactly once."
  [^LongHashSet s]
  (let [^longs arr (.toArray s)
        n (alength arr)]
    (loop [i 0, out (transient #{})]
      (if (< i n)
        (recur (unchecked-inc i) (conj! out (aget arr i)))
        (persistent! out)))))

(defn do-query-for-long-set
  "Run `q`, returning the distinct `long` values of `field` across matching
  documents as an immutable Clojure set. Reads via NumericDocValues when the
  field has a DocValues column, else via StoredFields."
  [^IndexSearcher searcher ^Query q ^String field]
  (long-hashset->set (.search searcher q (IntoLongSetCollectorManager. field))))

(defn ^:deprecated search-all*
  "Search a Lucene index and return *all* results matching query specified.
  Results are returned as a sequence of Lucene document ids."
  [^IndexSearcher searcher ^Query q]
  (let [coll (ArrayList.)]
    (.search searcher q (IntoArrayCollector. coll))
    (seq coll)))

(defn search-all
  "Search a Lucene index and return *all* results matching query specified.
  Results are returned as a sequence of Lucene document ids."
  [^IndexSearcher searcher ^Query q]
  (.search searcher q (IntoSequenceCollectorManager.)))

(defn count-unique-long-field
  "Return the count of distinct `long` values of `field` across documents
  matching `q`. Reads from NumericDocValues when available, else StoredFields."
  [^IndexSearcher searcher ^Query q ^String field]
  (.size ^LongHashSet (.search searcher q (IntoLongSetCollectorManager. field))))

(defn ^:deprecated stream-all*
  "Search a Lucene index and return *all* results on the channel specified.
  Results are returned as Lucene document ids."
  ([^IndexSearcher searcher ^Query q ch]
   (stream-all* searcher q ch true))
  ([^IndexSearcher searcher ^Query q ch close?]
   (.search searcher q (IntoChannelCollector. ch))
   (when close? (a/close! ch))))

(defn stream-all
  "Search a Lucene index and return *all* results on the channel specified.
  Results are returned as Lucene document ids."
  ([^IndexSearcher searcher ^Query q ch]
   (stream-all searcher q ch true))
  ([^IndexSearcher searcher ^Query q ch close?]
   (.search searcher q (IntoChannelCollectorManager. ch close?))))

(defn- single-must-not-clause?
  "Checks that a boolean query isn't simply a single 'must-not' clause.
  Such a query will fail to return any results if used alone."
  [^Query q]
  (and (instance? BooleanQuery q)
       (= (count (.clauses ^BooleanQuery q)) 1)
       (= BooleanClause$Occur/MUST_NOT (occur-for-boolean-clause (first (.clauses ^BooleanQuery q))))))

(defn- rewrite-single-must-not
  "Rewrite a single 'must-not' query."
  [^BooleanQuery q]
  (-> (BooleanQuery$Builder.)
      (.add (MatchAllDocsQuery.) BooleanClause$Occur/SHOULD)
      (.add (query-for-boolean-clause (first (.clauses q))) BooleanClause$Occur/MUST_NOT)
      (.build)))

(defn q-or
  "Generate a logical disjunction of the queries.
  If there is more than one query, and one of those queries contains a single
  'must-not' clause, it is flattened and re-written into the new query.
  As this is an 'or' operation, that means it will be combined with a
  'match-all-documents'."
  [queries]
  (case (count queries)
    0 nil
    1 (first queries)                                       ;; deliberately *do not* rewrite a MUST_NOT query here
    (let [builder (BooleanQuery$Builder.)]
      (doseq [^Query query queries]
        (if (single-must-not-clause? query)
          (.add builder (rewrite-single-must-not query) BooleanClause$Occur/SHOULD)
          (.add builder query BooleanClause$Occur/SHOULD)))
      (.build builder))))

(defn q-and
  "Generate a logical conjunction of the queries.
  If there is more than one query, and one of those queries contains a single
  'must-not' clause, it is flattened into the new query."
  [queries]
  (case (count queries)
    0 nil
    1 (first queries)                                       ;; deliberately *do not* rewrite a MUST_NOT query here
    (let [builder (BooleanQuery$Builder.)]
      (doseq [query queries]
        (if (single-must-not-clause? query)
          (.add builder ^Query (query-for-boolean-clause (first (.clauses ^BooleanQuery query))) BooleanClause$Occur/MUST_NOT)
          (.add builder ^Query query BooleanClause$Occur/MUST)))
      (.build builder))))

(defn q-not
  "Returns the logical query of q1 NOT q2"
  [^Query q1 ^Query q2]
  (-> (BooleanQuery$Builder.)
      (.add q1 BooleanClause$Occur/MUST)
      (.add q2 BooleanClause$Occur/MUST_NOT)
      (.build)))
