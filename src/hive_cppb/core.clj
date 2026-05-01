;; Copyright 2026 BuddhiLW
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0

(ns hive-cppb.core
  "CPPB pattern macros: Collect → Promote → Pipeline → Boundary.

  Codifies an L3 stratified architecture for data-enrichment workflows.
  Reuses hive-dsl Result monad for railway-oriented threading.

  Roles (carried as :cppb/role var metadata):

    :collector — gather raw data from a source. Pure-or-leaf. Returns Result.
    :promoter  — pure data → enriched data. Returns Result.
    :pipeline  — pure orchestration: run collectors, thread through promoters.
    :boundary  — effectful edge: dispatch on Result, fire side effect.

  Invariants enforced by convention (not compiler):
  1. Collectors independent — none depends on another's output
  2. Promoters composable — order may vary; data → enriched data
  3. Pipeline = pure orchestration, no effects
  4. Boundary thin — one fn, Result → response or side effect
  5. Data flows DOWN; effects only at boundary"
  (:require [hive-dsl.result :as r]))

;; =============================================================================
;; defcollector
;; =============================================================================

(defmacro defcollector
  "Define a CPPB Collector — gathers raw data from a source.

  Body should return a hive-dsl Result: (r/ok value) or (r/err category data).
  Pure-or-effectful-leaf; if effectful, wrap I/O in (r/try-effect ...).

  (defcollector fetch-user [seed]
    (r/ok {:id (:user-id seed) :name \"alice\"}))

  (defcollector slurp-config [seed]
    (r/try-effect (slurp (:path seed))))"
  {:arglists '([name docstring? args & body])}
  [name & body]
  (let [[doc body] (if (string? (first body))
                     [(first body) (rest body)]
                     [nil body])
        [args & forms] body]
    `(defn ~(vary-meta name assoc :cppb/role :collector)
       ~@(when doc [doc])
       ~args
       ~@forms)))

;; =============================================================================
;; defpromoter
;; =============================================================================

(defmacro defpromoter
  "Define a CPPB Promoter — pure transformation: data → enriched data.

  Receives the unwrapped pipeline accumulator (the merged collector map, then
  whatever previous promoters returned). Must return a hive-dsl Result.

  Pure: no I/O, no global mutation. Composability invariant — promoters
  should commute when their key sets don't overlap.

  (defpromoter inject-totals [data]
    (r/ok (assoc data :total (reduce + (:numbers data)))))

  (defpromoter validate-budget [data]
    (if (pos? (:budget data))
      (r/ok data)
      (r/err :budget/non-positive {:budget (:budget data)})))"
  {:arglists '([name docstring? args & body])}
  [name & body]
  (let [[doc body] (if (string? (first body))
                     [(first body) (rest body)]
                     [nil body])
        [args & forms] body]
    `(defn ~(vary-meta name assoc :cppb/role :promoter)
       ~@(when doc [doc])
       ~args
       ~@forms)))

;; =============================================================================
;; defpipeline
;; =============================================================================

(defmacro defpipeline
  "Define a CPPB Pipeline — pure orchestration of collectors and promoters.

  (defpipeline build-report [seed]
    :collectors [fetch-user fetch-numbers]
    :promoters  [inject-totals format-greeting])

  Expansion semantics:
  - Each collector is invoked with the pipeline argument (seed). Each must
    return a Result. Results are bound by collector name (keyword form) into
    a single accumulator map. First err short-circuits.
  - The accumulator is wrapped in (r/ok ...) and threaded through promoters
    via r/bind. Each promoter receives the unwrapped accumulator and returns
    a Result. First err short-circuits.

  Pure orchestration only — no effects. Effects live in defboundary."
  {:arglists '([name args & {:keys [doc collectors promoters]}])}
  [name args & {:keys [doc collectors promoters]}]
  (let [seed-sym (first args)
        collector-bindings
        (vec (mapcat (fn [c]
                       [(symbol (clojure.core/name c))
                        `(~c ~seed-sym)])
                     collectors))
        accum-map
        (into {} (map (fn [c]
                        [(keyword (clojure.core/name c))
                         (symbol (clojure.core/name c))])
                      collectors))
        threaded
        (reduce (fn [acc p] `(r/bind ~acc ~p))
                `(r/ok ~accum-map)
                promoters)]
    `(defn ~(vary-meta name assoc
                       :cppb/role :pipeline
                       :cppb/collectors (mapv keyword '~collectors)
                       :cppb/promoters  (mapv keyword '~promoters))
       ~@(when doc [doc])
       ~args
       (r/let-ok ~collector-bindings
         ~threaded))))

;; =============================================================================
;; defboundary
;; =============================================================================

(defmacro defboundary
  "Define a CPPB Boundary — Result → effect.

  Anaphoric bindings inside the branches:
    `value`  — unwrapped :ok value (in :on-success branch)
    `error`  — full error map (in :on-failure branch)

  (defboundary print-report! [result]
    :on-success (println \"OK:\" value)
    :on-failure (println \"ERR:\" (:error error)))

  Boundary is the ONLY place I/O is permitted in CPPB. Keep it thin: dispatch
  on Result, fire one effect or return one response."
  {:arglists '([name args & {:keys [doc on-success on-failure]}])}
  [name args & {:keys [doc on-success on-failure]}]
  (let [result-sym (first args)]
    `(defn ~(vary-meta name assoc :cppb/role :boundary)
       ~@(when doc [doc])
       ~args
       (let [r# ~result-sym]
         (cond
           (r/ok? r#)
           (let [~'value (:ok r#)] ~on-success)

           (r/err? r#)
           (let [~'error r#] ~on-failure)

           :else
           (throw (ex-info "defboundary received non-Result value"
                           {:cppb/role :boundary :value r#})))))))

;; =============================================================================
;; Introspection helpers
;; =============================================================================

(defn cppb-role
  "Return the :cppb/role metadata of a var or symbol."
  [v]
  (cond
    (var? v)    (:cppb/role (meta v))
    (symbol? v) (:cppb/role (meta (resolve v)))))

(defn collector? [v] (= :collector (cppb-role v)))
(defn promoter?  [v] (= :promoter  (cppb-role v)))
(defn pipeline?  [v] (= :pipeline  (cppb-role v)))
(defn boundary?  [v] (= :boundary  (cppb-role v)))
