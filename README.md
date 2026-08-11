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

The seed keeps owning *which* ops are in a class; the `.kotoba` owns *what it
means* to be in one, and `test/kotoba/selfhost/safe_analyzer_core_test.clj`
refuses to let either move alone. The compiler is a test-only dependency
(ADR-reliability-t63): nothing in `src/` requires it.

Cross-target run (`:jvm-kir`, `:js`, `:wasm`), from an `amu` checkout:

```sh
clojure -M:run test  <path>/kotoba/safe_analyzer_core.kotoba
clojure -M:run check <path>/kotoba/safe_analyzer_core.kotoba --profile pure-product
```
