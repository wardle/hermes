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
           (org.apache.lucene.search CollectionTerminatedException CollectorManager IndexSearcher BooleanClause$Occur BooleanQuery$Builder Query
                                     MatchAllDocsQuery BooleanQuery BooleanClause Collector LeafCollector Scorable ScoreMode)))

(set! *warn-on-reflection* true)

;; A Lucene results collector that collects *all* results into the mutable
;; java collection 'coll'.
(deftype IntoArrayCollector [^List coll]
  Collector
  (getLeafCollector [_ ctx]
    (let [base-id (.-docBase ctx)]
      (reify LeafCollector
        (^void setScorer [_ ^Scorable _scorer])                             ;; NOP
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
        (^void setScorer [_ ^Scorable _scorer])                             ;; NOP
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
       (= BooleanClause$Occur/MUST_NOT (.getOccur ^BooleanClause (first (.clauses ^BooleanQuery q))))))

(defn- rewrite-single-must-not
  "Rewrite a single 'must-not' query."
  [^BooleanQuery q]
  (-> (BooleanQuery$Builder.)
      (.add (MatchAllDocsQuery.) BooleanClause$Occur/SHOULD)
      (.add (.getQuery ^BooleanClause (first (.clauses q))) BooleanClause$Occur/MUST_NOT)
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
          (.add builder ^Query (.getQuery ^BooleanClause (first (.clauses ^BooleanQuery query))) BooleanClause$Occur/MUST_NOT)
          (.add builder ^Query query BooleanClause$Occur/MUST)))
      (.build builder))))

(defn q-not
  "Returns the logical query of q1 NOT q2"
  [^Query q1 ^Query q2]
  (-> (BooleanQuery$Builder.)
      (.add q1 BooleanClause$Occur/MUST)
      (.add q2 BooleanClause$Occur/MUST_NOT)
      (.build)))
