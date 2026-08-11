;; The shipped artifact is what runs. This test is the only thing that keeps it
;; equal to the source it claims to be compiled from.
;;
;; Without it the failure is silent and one-directional: editing a .kotoba and
;; forgetting `clojure -M:test:gen` leaves production executing the OLD
;; decision while every other test — which compiles from source — passes. The
;; other authority tests cannot see this, by construction.

(ns kotoba.selfhost.oracle-authority-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [kotoba.selfhost.analyzer :as analyzer]
            [kotoba.selfhost.oracle :as oracle]))

(deftest shipped-artifacts-match-their-source
  (doseq [[id source-path] oracle/oracles]
    (testing (str id " <- " source-path)
      (let [fresh (:kir (compiler/compile-source (slurp source-path)
                                                 :wasm32-kotoba-v1 {}))]
        (is (= fresh (oracle/kir id))
            (str "resources/" (oracle/resource-path id)
                 " is stale — run `clojure -M:test:gen`"))))))

(deftest every-source-ships-and-every-artifact-has-a-source
  ;; a .kotoba added without an oracles entry would never ship
  (let [on-disk (->> (file-seq (clojure.java.io/file "kotoba"))
                     (filter #(.isFile %))
                     (filter #(clojure.string/ends-with? (.getName %) ".kotoba"))
                     (map #(.getPath %))
                     set)]
    (is (= on-disk (set (vals oracle/oracles)))
        "kotoba/*.kotoba and kotoba.selfhost.oracle/oracles disagree")))

(deftest the-artifact-runs-without-the-compiler
  ;; `oracle/call` reaches the resource, not a freshly compiled copy. If this
  ;; ever needed the compiler the runtime dependency split would be a fiction.
  (is (= 63 (oracle/call :capability-admission "known-effect-mask" [])))
  (is (true? (oracle/call :safe-analyzer "non-executable-form?" ["ns"]))))

;; ── the consumer ─────────────────────────────────────────────────────

(deftest classification-is-a-set-because-classes-overlap
  (is (= #{:effect :numeric-result :user-call-excluded}
         (analyzer/classify "has-capability?")))
  (is (= #{:non-executable-form} (analyzer/classify "ns")))
  (is (= #{:numeric-result :user-call-excluded} (analyzer/classify "+")))
  (is (= #{} (analyzer/classify "my-fn"))))

(deftest user-call-detection
  (is (analyzer/user-call? "my-fn"))
  (is (not (analyzer/user-call? "let")))
  (is (not (analyzer/user-call? "ns")))
  (is (not (analyzer/user-call? "kgraph-query"))))

(deftest inference-folds-without-deciding
  (testing "empty program has no effects"
    (is (zero? (analyzer/infer-effects []))))
  (testing "non-effect ops contribute nothing"
    (is (zero? (analyzer/infer-effects ["+" "let" "my-fn" "ns"]))))
  (testing "the fold agrees with the artifact's own union, in any order"
    (let [ops ["kgraph-query" "llm-infer" "+" "kgraph-query"]
          expected (ir/execute (oracle/kir :capability-admission) 'effect-union
                               [(analyzer/effect-bit "kgraph-query")
                                (analyzer/effect-bit "llm-infer")])]
      (is (= expected (analyzer/infer-effects ops)))
      (is (= expected (analyzer/infer-effects (reverse ops))))))
  (testing "idempotent — reaching an op twice is the same set"
    (is (= (analyzer/infer-effects ["llm-infer"])
           (analyzer/infer-effects (repeat 5 "llm-infer"))))))

(deftest check-reports-declaration-gaps
  (let [ops ["kgraph-query" "llm-infer"]
        inferred (analyzer/infer-effects ops)]
    (testing "exact declaration"
      (let [r (analyzer/check ops inferred)]
        (is (:satisfied? r))
        (is (zero? (:undeclared r)))
        (is (zero? (:unused r)))
        (is (= inferred (:minimal-policy r)))))
    (testing "under-declared is caught"
      (let [r (analyzer/check ops (analyzer/effect-bit "llm-infer"))]
        (is (not (:satisfied? r)))
        (is (= (analyzer/effect-bit "kgraph-query") (:undeclared r)))))
    (testing "over-declared is legal but linted"
      (let [r (analyzer/check ops (analyzer/known-effect-mask))]
        (is (:satisfied? r))
        (is (pos? (:unused r)))))))

(deftest admission-separates-allowed-from-sufficient
  (let [ops ["kgraph-query" "llm-infer"]
        needs (analyzer/infer-effects ops)
        grant (fn [d p] {:delegated d :policy p :now 0 :expires 9})]
    (testing "no grant is denied even though the program is well declared"
      (let [r (analyzer/analyze ops needs (grant 0 (analyzer/known-effect-mask)))]
        (is (:satisfied? r) "declaration is fine")
        (is (= :missing-grant (get-in r [:admission :outcome])))))
    (testing "expired grant reports expiry"
      (is (= :expired-grant
             (:outcome (analyzer/admit needs {:delegated needs :policy needs
                                              :now 10 :expires 9})))))
    (testing "disjoint grant is an empty intersection"
      (is (= :empty-intersection
             (:outcome (analyzer/admit (analyzer/effect-bit "kgraph-query")
                                       (grant (analyzer/effect-bit "llm-infer")
                                              (analyzer/known-effect-mask)))))))
    (testing "partial grant ADMITS, and is not sufficient"
      (let [partial-p (analyzer/effect-bit "llm-infer")
            r (analyzer/admit needs (grant needs partial-p))]
        (is (= :admit (:outcome r)))
        (is (not (:sufficient? r)))
        (is (= (analyzer/effect-bit "kgraph-query") (:shortfall r)))))
    (testing "minimal policy admits and suffices"
      (let [r (analyzer/admit needs (grant needs needs))]
        (is (= :admit (:outcome r)))
        (is (:sufficient? r))
        (is (zero? (:shortfall r)))))))

(deftest delegation-and-production-policy
  (let [all (analyzer/known-effect-mask)
        one (analyzer/effect-bit "llm-infer")]
    (is (not (analyzer/attenuation-violation? all one)) "narrowing is fine")
    (is (analyzer/attenuation-violation? one all) "widening is not")
    (is (analyzer/production-policy-ok? one))
    (is (not (analyzer/production-policy-ok? all)) "wildcard policy is malformed")))

(deftest denial-codes-come-from-the-artifact
  ;; not a host copy of the ladder
  (is (= 5 (count @analyzer/denial-codes)))
  (is (= :admit (get @analyzer/denial-codes 0))))
