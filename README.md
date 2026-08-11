# kotoba-selfhost-contracts

EDN authority for Kotoba selfhost contracts.

This repo owns shipped selfhost seed values such as provider catalogs, runtime
contracts, SDK/release contracts, updater contracts, and native host contracts.
Launchers and adapters read these resources; they do not own the values.

Run:

```sh
clojure -M:test
```

## `kotoba/` — decisions, not seeds

Most of this repo is values that someone else decides with. `kotoba/` is the
exception: it holds the decisions that belong to a seed, authored in Kotoba.

- `kotoba/safe_analyzer_core.kotoba` — the safe analyzer's op classification
  over `resources/kotoba/selfhost/safe_analyzer_facts.edn`.
- `kotoba/capability_admission_core.kotoba` — effect algebra, minimal policy,
  policy check and admission over the same seed. Effect sets are six bits of an
  `:i64`, so the whole algebra is integer ops and stays word-typed.

`effect-bit` assigns **bit i to index i of `:effect-ops`**, so reordering that
vector is a wire change, not a formatting change. The authority test fails on
it; nothing else would notice.

Admission and sufficiency are different questions. `admission-code` denies only
an *empty* intersection, so a partial policy is admitted with a narrowed scope —
ask `authority-sufficient?` before treating code 0 as "has what it needs".

The seed keeps owning *which* ops are in a class; the `.kotoba` owns *what it
means* to be in one, and `test/kotoba/selfhost/safe_analyzer_core_test.clj`
refuses to let either move alone. The compiler is a test-only dependency
(ADR-reliability-t63): nothing in `src/` requires it.

## What ships, and what runs it

`resources/kotoba/selfhost/oracle/*.kir.edn` is the compiled artifact.
`kotoba.selfhost.oracle` loads it; `kotoba.selfhost.analyzer` is the host
traversal that calls it. The compiler is **not** a runtime dependency — it
produced the artifacts under `:test` and is not on this classpath. The KIR
interpreter is.

```clojure
(require '[kotoba.selfhost.analyzer :as a])
(a/analyze ["kgraph-query" "llm-infer" "+"]      ; ops the caller's walk found
           declared-mask
           {:delegated g :policy p :now t :expires e})
;; => {:inferred … :undeclared … :unused … :minimal-policy …
;;     :admission {:outcome :admit :sufficient? false :shortfall …}}
```

`:outcome :admit` does **not** mean the program has what it needs — a partial
policy is admitted with a narrowed scope. Check `:sufficient?`.

After editing any `kotoba/*.kotoba`:

```sh
clojure -M:test:gen     # regenerate the shipped artifact
clojure -M:test         # the drift test fails if you forget
```

Cross-target run (`:jvm-kir`, `:js`, `:wasm`), from an `amu` checkout:

```sh
clojure -M:run test  <path>/kotoba/safe_analyzer_core.kotoba
clojure -M:run check <path>/kotoba/safe_analyzer_core.kotoba --profile pure-product
```
