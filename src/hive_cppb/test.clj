;; Copyright 2026 BuddhiLW
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0

(ns hive-cppb.test
  "Reusable property-based test scaffolding for CPPB roles.

  These helpers consume the `:cppb/role` metadata stamped onto vars by
  `defcollector` / `defpromoter` / `defpipeline` / `defboundary` (see
  `hive-cppb.core`) and produce `clojure.test` assertions that
  exercise the canonical laws each role is expected to obey.

  Helpers are designed to be called from a caller's own test ns:

    (require '[hive-cppb.test :as cppb-test])
    (require '[clojure.test.check.generators :as gen])

    (deftest add-totals-laws
      (cppb-test/promoter-laws #'add-totals
        (gen/hash-map :fetch-numbers (gen/vector gen/small-integer))))

  Each helper is a regular function that runs `clojure.test/is` assertions
  inline, so it can be dropped into any `deftest`. They throw via the
  underlying assertion machinery if a law is violated.

  Laws codify the CPPB principle: pure data flow with effects pushed to
  the boundary."
  (:require [clojure.test :as t]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-dsl.result :as r]
            [hive-cppb.core :as cppb]))

;; =============================================================================
;; Internal helpers
;; =============================================================================

(def ^:private default-num-tests
  "How many random inputs each property runs by default. Modest because
  these are smoke-style laws meant to run inside CI on every commit."
  50)

(defn- result?
  "True iff x is a hive-dsl Result (ok or err)."
  [x]
  (or (r/ok? x) (r/err? x)))

(defn- assert-role!
  "Throw a clear ex-info if `v` is not a var carrying the expected role."
  [v expected-role]
  (when-not (var? v)
    (throw (ex-info "CPPB test helper expects a var (use #'name)"
                    {:given v :expected-role expected-role})))
  (let [role (cppb/cppb-role v)]
    (when-not (= expected-role role)
      (throw (ex-info "Var is not tagged with the expected :cppb/role"
                      {:var v :expected expected-role :actual role})))))

(defn- run-prop
  "Run a test.check property and report via clojure.test. Returns true iff
  the property held; calls (is ...) so failures show up as normal test
  failures with the shrunk counterexample."
  [property num-tests label]
  (let [{:keys [pass? shrunk fail seed num-tests]
         :as   report} (tc/quick-check num-tests property)]
    (t/is pass?
          (str label
               " — failed after " num-tests " runs."
               (when fail (str " counterexample=" (pr-str fail)))
               (when shrunk (str " shrunk=" (pr-str (:smallest shrunk))))
               " seed=" seed))
    pass?))

;; =============================================================================
;; promoter-laws
;; =============================================================================

(defn promoter-laws
  "Assert the canonical laws of a CPPB promoter via property-based tests.

  Args:
    promoter-var  — a var #'foo where foo was defined via defpromoter
    input-gen     — a test.check generator producing `data` values that the
                    promoter accepts (i.e. the unwrapped accumulator map)

  Options (kw-args, all optional):
    :num-tests   — how many random inputs to sample (default 50)
    :idempotent? — if truthy (or if the var carries `:cppb/idempotent`
                   metadata), assert (promoter (promoter data)) ≡
                   (promoter data) when both calls are :ok

  Laws asserted:

  1. Result-shape: promoter always returns a hive-dsl Result.
  2. Determinism (purity): same input → same output across two invocations.
     Promoters are required by CPPB to be pure, so this is unconditional.
  3. Idempotence (opt-in): when enabled, applying the promoter to its own
     output yields the same Result.

  Identity-on-err law (caller responsibility): when the *pipeline* sees an
  err Result it short-circuits before reaching the next promoter. Promoters
  themselves never receive err input, so we deliberately do not test that
  here — it is enforced by `r/bind` in `defpipeline`'s expansion."
  [promoter-var input-gen & {:keys [num-tests idempotent?]
                             :or   {num-tests default-num-tests}}]
  (assert-role! promoter-var :promoter)
  (let [f               (deref promoter-var)
        meta-idempotent (:cppb/idempotent (meta promoter-var))
        check-idem?     (or idempotent? meta-idempotent)]

    (run-prop
     (prop/for-all [data input-gen]
       (result? (f data)))
     num-tests
     (str promoter-var " result-shape law"))

    (run-prop
     (prop/for-all [data input-gen]
       (= (f data) (f data)))
     num-tests
     (str promoter-var " determinism law"))

    (when check-idem?
      (run-prop
       (prop/for-all [data input-gen]
         (let [once (f data)]
           (if (r/ok? once)
             (= once (f (:ok once)))
             ;; err on first call → law vacuously holds
             true)))
       num-tests
       (str promoter-var " idempotence law")))

    :ok))

;; =============================================================================
;; boundary-contract
;; =============================================================================

(defn boundary-contract
  "Assert the contract of a CPPB boundary.

  Args:
    boundary-var  — a var #'foo where foo was defined via defboundary

  Options:
    :ok-gen   — generator producing values to wrap in (r/ok ...) (default any)
    :err-gen  — generator producing data maps for (r/err :test/x data)
                (default a small map)
    :num-tests — how many random inputs to sample (default 50)

  Contract asserted:

  1. Boundary handles `(r/ok value)` without throwing.
  2. Boundary handles `(r/err category data)` without throwing.
  3. Boundary throws `ex-info` for non-Result inputs (defboundary's :else
     branch). We assert the throw and that the ex-data carries
     `:cppb/role :boundary` so callers can recognise it."
  [boundary-var & {:keys [ok-gen err-gen num-tests]
                   :or   {ok-gen     gen/any-printable-equatable
                          err-gen    (gen/map gen/keyword gen/small-integer)
                          num-tests  default-num-tests}}]
  (assert-role! boundary-var :boundary)
  (let [f (deref boundary-var)]

    (run-prop
     (prop/for-all [v ok-gen]
       (try (f (r/ok v)) true
            (catch Throwable _ false)))
     num-tests
     (str boundary-var " handles ok results"))

    (run-prop
     (prop/for-all [data err-gen]
       (try (f (r/err :test/boundary-contract data)) true
            (catch Throwable _ false)))
     num-tests
     (str boundary-var " handles err results"))

    ;; non-Result rejection — single deterministic case is enough, but we
    ;; phrase it as `is` so it folds into the surrounding deftest's count.
    (let [thrown (try (f {:not :a-result}) ::no-throw
                      (catch clojure.lang.ExceptionInfo e e)
                      (catch Throwable _ ::wrong-type))]
      (t/is (instance? clojure.lang.ExceptionInfo thrown)
            (str boundary-var " must throw ex-info on non-Result input"))
      (when (instance? clojure.lang.ExceptionInfo thrown)
        (t/is (= :boundary (:cppb/role (ex-data thrown)))
              (str boundary-var " ex-data must carry :cppb/role :boundary"))))

    :ok))

;; =============================================================================
;; pipeline-shape
;; =============================================================================

(defn pipeline-shape
  "Assert structural laws of a CPPB pipeline.

  Args:
    pipeline-var — a var #'foo where foo was defined via defpipeline
    seed-gen     — a test.check generator producing seed inputs

  Options:
    :num-tests — how many random seeds to sample (default 50)

  Laws asserted:

  1. Result-shape: pipeline returns a hive-dsl Result for any seed.
  2. Determinism: same seed → same Result (assumes underlying collectors
     and promoters are pure, per CPPB invariant).

  Short-circuit-on-error law (NOT asserted here — needs DI):

  Asserting that the pipeline aborts on the *first* failing collector
  requires injecting a stub collector into the pipeline definition, which
  the current `defpipeline` does not support (collectors are resolved as
  closed-over symbols at macro-expansion time). The smoke test in
  `core_test.clj` covers this with hand-written failing fixtures."
  [pipeline-var seed-gen & {:keys [num-tests]
                            :or   {num-tests default-num-tests}}]
  (assert-role! pipeline-var :pipeline)
  (let [f (deref pipeline-var)]

    (run-prop
     (prop/for-all [seed seed-gen]
       (result? (f seed)))
     num-tests
     (str pipeline-var " result-shape law"))

    (run-prop
     (prop/for-all [seed seed-gen]
       (= (f seed) (f seed)))
     num-tests
     (str pipeline-var " determinism law"))

    :ok))
