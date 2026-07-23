(ns hive-cppb.hooks.macros
  "clj-kondo hooks for CPPB macros with anaphoric bindings."
  (:require [clj-kondo.hooks-api :as api]))

(defn- option-node [nodes k]
  (some (fn [[key-node value-node]]
          (when (= k (api/sexpr key-node)) value-node))
        (partition 2 nodes)))

(defn defboundary
  "Model defboundary as a defn whose branch bodies bind value and error."
  [{:keys [node]}]
  (let [[_ name-node args-node & options] (:children node)
        success-node (option-node options :on-success)
        failure-node (option-node options :on-failure)
        nil-node     (api/token-node nil)
        body-node    (api/list-node
                      (list* (api/token-node 'do)
                             (remove nil? [success-node failure-node])))
        let-node     (api/list-node
                      [(api/token-node 'let)
                       (api/vector-node
                        [(api/token-node 'value) nil-node
                         (api/token-node 'error) nil-node])
                       body-node])]
    {:node (api/list-node
            [(api/token-node 'defn) name-node args-node let-node])}))
