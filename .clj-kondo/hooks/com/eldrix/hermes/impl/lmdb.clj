(ns hooks.com.eldrix.hermes.impl.lmdb
  (:require [clj-kondo.hooks-api :as api]))

(defn with-txn
  [{:keys [node]}]
  (let [[binding-vec & body] (rest (:children node))
        [sym store _env] (:children binding-vec)]
    {:node (api/list-node
             (list* (api/token-node 'let)
                    (api/vector-node [sym store])
                    body))}))

(defn with-cursor
  [{:keys [node]}]
  (let [[binding-vec & body] (rest (:children node))
        [sym dbi _txn] (:children binding-vec)]
    {:node (api/list-node
             (list* (api/token-node 'let)
                    (api/vector-node [sym dbi])
                    body))}))
