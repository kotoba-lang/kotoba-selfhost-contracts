# kotoba-selfhost-contracts

EDN authority for Kotoba selfhost contracts.

This repo owns shipped selfhost seed values such as provider catalogs, runtime
contracts, SDK/release contracts, updater contracts, and native host contracts.
Launchers and adapters read these resources; they do not own the values.

Run:

```sh
clojure -M:test
```
