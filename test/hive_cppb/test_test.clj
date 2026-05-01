;; Copyright 2026 BuddhiLW
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0

(ns hive-cppb.test-test
  "Self-test for the CPPB auto-test helpers.

  Exercises `promoter-laws`, `boundary-contract`, and `pipeline-shape`
  against the example fixtures defined in `core-test.clj` (add-totals,
  format-greeting, capture-report!, build-report).

  Also covers a few negative paths:
  - calling promoter-laws on a non-promoter var throws
  - boundary-contract detects a boundary that fails to throw on non-Result
    (we cannot easily synthesise one without modifying defboundary, so we
    instead assert the happy-path discriminator works).
  - the opt-in idempotence law via `:cppb/idempotent` metadata."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-dsl.result :as r]
            [hive-cppb.core :as cppb
             :refer [defcollector defpromoter defpipeline defboundary]]
            [hive-cppb.test :as cppb-test]
            ;; pull in the existing example fixtures so we don't redefine them
            [hive-cppb.core-test :as core-test]))

;; -----------------------------------------------------------------------------
;; Generators tailored to the core-test fixtures
;; -----------------------------------------------------------------------------

(def gen-numbers-data
  "Generator for the accumulator shape add-totals expects."
  (gen/hash-map :fetch-numbers (gen/vector gen/small-integer 0 8)))

(def gen-user-data
  "Generator for the accumulator shape format-greeting expects."
  (gen/hash-map :fetch-user
                (gen/hash-map :id   gen/small-integer
                              :name (gen/not-empty gen/string-alphanumeric))))

(def gen-seed
  "Generator for build-report's seed map."
  (gen/hash-map :user-id   gen/small-integer
                :user-name (gen/not-empty gen/string-alphanumeric)))

;; -----------------------------------------------------------------------------
;; Local fixtures unique to this ns
;; -----------------------------------------------------------------------------

(defpromoter ^:cppb/idempotent ensure-greeting
  "Idempotent promoter: assoc :greeting only if missing. Used to verify
  the opt-in idempotence law fires when the metadata flag is present."
  [data]
  (r/ok (update data :greeting (fnil identity "hi"))))

(defboundary swallow! [_result]
  :on-success nil
  :on-failure nil)

;; -----------------------------------------------------------------------------
;; Tests
;; -----------------------------------------------------------------------------

(deftest promoter-laws-exercises-add-totals
  (testing "add-totals satisfies result-shape + determinism"
    (cppb-test/promoter-laws #'core-test/add-totals gen-numbers-data
                             :num-tests 20)))

(deftest promoter-laws-exercises-format-greeting
  (testing "format-greeting satisfies result-shape + determinism"
    (cppb-test/promoter-laws #'core-test/format-greeting gen-user-data
                             :num-tests 20)))

(deftest promoter-laws-idempotence-via-metadata
  (testing "opt-in idempotence law fires when :cppb/idempotent meta is set"
    (cppb-test/promoter-laws #'ensure-greeting
                             (gen/hash-map :greeting gen/string)
                             :num-tests 20)))

(deftest promoter-laws-idempotence-via-option
  (testing "opt-in idempotence law fires when :idempotent? option is true"
    ;; ensure-greeting happens to also satisfy idempotence; reuse it
    (cppb-test/promoter-laws #'ensure-greeting
                             (gen/hash-map :greeting gen/string)
                             :idempotent? true
                             :num-tests 20)))

(deftest promoter-laws-rejects-wrong-role
  (testing "calling promoter-laws on a non-promoter var throws ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cppb-test/promoter-laws #'core-test/fetch-numbers
                                          gen-numbers-data
                                          :num-tests 1)))))

(deftest boundary-contract-exercises-capture-report
  (testing "capture-report! handles ok, err, and rejects non-Result"
    ;; capture-report! mutates an atom; reset to keep the suite hermetic
    (cppb-test/boundary-contract #'core-test/capture-report!
                                 :num-tests 10)))

(deftest boundary-contract-exercises-swallow
  (testing "boundary-contract works on a no-op boundary too"
    (cppb-test/boundary-contract #'swallow! :num-tests 10)))

(deftest boundary-contract-rejects-wrong-role
  (testing "calling boundary-contract on a non-boundary var throws ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cppb-test/boundary-contract #'core-test/add-totals
                                              :num-tests 1)))))

(deftest pipeline-shape-exercises-build-report
  (testing "build-report returns a Result for any seed and is deterministic"
    (cppb-test/pipeline-shape #'core-test/build-report gen-seed
                              :num-tests 20)))

(deftest pipeline-shape-rejects-wrong-role
  (testing "calling pipeline-shape on a non-pipeline var throws ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cppb-test/pipeline-shape #'core-test/add-totals gen-seed
                                           :num-tests 1)))))
