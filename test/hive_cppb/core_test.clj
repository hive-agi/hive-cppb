;; Copyright 2026 BuddhiLW
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0

(ns hive-cppb.core-test
  "Smoke test for CPPB macro layer.

  Exercises a tiny example pipeline end-to-end:
  - 2 collectors (fetch-numbers, fetch-user)
  - 2 promoters (add-totals, format-greeting)
  - 1 pipeline (build-report)
  - 1 boundary (capture-report!)

  Asserts:
  - Each macro tags its var with the right :cppb/role
  - Pipeline threads collector results into the accumulator map
  - Promoters compose via Result bind
  - Boundary dispatches on Result
  - First err short-circuits the pipeline"
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-cppb.core :as cppb
             :refer [defcollector defpromoter defpipeline defboundary]]))

;; -----------------------------------------------------------------------------
;; Example: build a tiny report from seed → numbers + user → totals + greeting
;; -----------------------------------------------------------------------------

(defcollector fetch-numbers
  "Returns a fixed list of numbers, ignoring seed."
  [_seed]
  (r/ok [1 2 3 4 5]))

(defcollector fetch-user
  "Returns a user map keyed off the seed."
  [seed]
  (r/ok {:id (:user-id seed) :name (:user-name seed "anon")}))

(defcollector failing-collector
  "Collector that always fails — used for short-circuit test."
  [_seed]
  (r/err :collector/missing {:reason "intentional"}))

(defpromoter add-totals
  "Sum the :fetch-numbers slot into :total."
  [data]
  (r/ok (assoc data :total (reduce + (:fetch-numbers data)))))

(defpromoter format-greeting
  "Build a greeting string from :fetch-user."
  [data]
  (r/ok (assoc data :greeting (str "Hello, " (get-in data [:fetch-user :name])))))

(defpromoter failing-promoter
  "Promoter that always fails — used for short-circuit test."
  [_data]
  (r/err :promoter/blocked {:reason "intentional"}))

(defpipeline build-report [seed]
  :collectors [fetch-numbers fetch-user]
  :promoters  [add-totals format-greeting])

(defpipeline build-report-with-bad-collector [seed]
  :collectors [fetch-numbers failing-collector]
  :promoters  [add-totals])

(defpipeline build-report-with-bad-promoter [seed]
  :collectors [fetch-numbers fetch-user]
  :promoters  [failing-promoter format-greeting])

;; Boundary captures into an atom so we can assert against it
(def ^:private capture (atom nil))

(defboundary capture-report! [result]
  :on-success (reset! capture {:branch :ok :payload value})
  :on-failure (reset! capture {:branch :err :payload error}))

;; -----------------------------------------------------------------------------
;; Tests
;; -----------------------------------------------------------------------------

(deftest role-metadata
  (testing ":cppb/role metadata is set correctly on each macro form"
    (is (cppb/collector? #'fetch-numbers))
    (is (cppb/collector? #'fetch-user))
    (is (cppb/promoter?  #'add-totals))
    (is (cppb/promoter?  #'format-greeting))
    (is (cppb/pipeline?  #'build-report))
    (is (cppb/boundary?  #'capture-report!))))

(deftest pipeline-happy-path
  (testing "Pipeline gathers, threads, returns ok with combined data"
    (let [result (build-report {:user-id 42 :user-name "alice"})]
      (is (r/ok? result))
      (let [data (:ok result)]
        (is (= [1 2 3 4 5] (:fetch-numbers data)))
        (is (= {:id 42 :name "alice"} (:fetch-user data)))
        (is (= 15 (:total data)))
        (is (= "Hello, alice" (:greeting data)))))))

(deftest pipeline-short-circuits-on-collector-err
  (testing "First failing collector aborts the pipeline"
    (let [result (build-report-with-bad-collector {})]
      (is (r/err? result))
      (is (= :collector/missing (:error result))))))

(deftest pipeline-short-circuits-on-promoter-err
  (testing "First failing promoter aborts the pipeline before downstream promoters"
    (let [result (build-report-with-bad-promoter {:user-id 1})]
      (is (r/err? result))
      (is (= :promoter/blocked (:error result))))))

(deftest boundary-dispatches-on-success
  (reset! capture nil)
  (capture-report! (r/ok {:foo 1}))
  (is (= {:branch :ok :payload {:foo 1}} @capture)))

(deftest boundary-dispatches-on-failure
  (reset! capture nil)
  (capture-report! (r/err :test/boom {:why "smoke"}))
  (is (= :err (:branch @capture)))
  (is (= :test/boom (get-in @capture [:payload :error]))))

(deftest end-to-end-pipeline-plus-boundary
  (testing "Pipeline output flows into boundary, full CPPB cycle"
    (reset! capture nil)
    (-> (build-report {:user-id 7 :user-name "bob"})
        capture-report!)
    (is (= :ok (:branch @capture)))
    (is (= "Hello, bob" (get-in @capture [:payload :greeting])))
    (is (= 15 (get-in @capture [:payload :total])))))
