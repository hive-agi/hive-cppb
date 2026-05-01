# hive-cppb

**Collect → Promote → Pipeline → Boundary**: a tiny Clojure macro layer
that codifies a stratified architecture for data-enrichment workflows.
Pure data flows down; effects live only at the boundary.

License: Apache 2.0.

## The four roles

| Role        | Macro            | Purity                | Returns |
| ----------- | ---------------- | --------------------- | ------- |
| Collector   | `defcollector`   | Pure-or-effectful-leaf | Result  |
| Promoter    | `defpromoter`    | Pure                  | Result  |
| Pipeline    | `defpipeline`    | Pure orchestration    | Result  |
| Boundary    | `defboundary`    | Effectful edge        | Effect  |

Invariants (enforced by convention, not the compiler):

1. Collectors are independent — none depends on another's output.
2. Promoters are composable — order may vary; `data → enriched data`.
3. Pipeline is pure orchestration; no I/O.
4. Boundary is thin — one fn, `Result → response`-or-`side effect`.
5. Data flows DOWN; effects live ONLY at the boundary.

## Install

```clojure
;; deps.edn
io.github.hive-agi/hive-cppb {:git/tag "v0.1.0" :git/sha "<sha>"}
```

## Example

```clojure
(require '[hive-cppb.core :as cppb
           :refer [defcollector defpromoter defpipeline defboundary]]
         '[hive-dsl.result :as r])

(defcollector fetch-numbers [_]   (r/ok [1 2 3 4 5]))
(defcollector fetch-user    [seed] (r/ok {:id (:user-id seed) :name "alice"}))

(defpromoter add-totals [data]
  (r/ok (assoc data :total (reduce + (:fetch-numbers data)))))

(defpipeline build-report [seed]
  :collectors [fetch-numbers fetch-user]
  :promoters  [add-totals])

(defboundary print-report! [result]
  :on-success (println "OK:" value)
  :on-failure (println "ERR:" (:error error)))

(-> (build-report {:user-id 7}) print-report!)
;; OK: {:fetch-numbers [1 2 3 4 5] :fetch-user {:id 7 :name "alice"} :total 15}
```

## Property-based test scaffolding

`hive-cppb.test` ships law-checks for each role:

```clojure
(require '[hive-cppb.test :as cppb-test]
         '[clojure.test.check.generators :as gen])

(deftest add-totals-laws
  (cppb-test/promoter-laws #'add-totals
    (gen/hash-map :fetch-numbers (gen/vector gen/small-integer))))
```

## Run the tests

```
clj -M:test
```
