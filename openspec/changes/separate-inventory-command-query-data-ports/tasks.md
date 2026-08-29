## 1. Baseline and Corrected Classification

- [x] 1.1 Record every current Inventory custom Repository, Store and Finder definition plus production callers before editing.
- [x] 1.2 Run focused Inventory unit and architecture tests to establish the pre-change behavior baseline.
- [x] 1.3 Correct proposal, design and spec so package ownership and Store/Finder method semantics are independent.

## 2. Former Domain Repository Separation

- [x] 2.1 Split `StockQuant` reads into `StockQuantFinder`; keep only save and explicit lock operations in `StockQuantStore`.
- [x] 2.2 Split operation-type and cancellation pure reads into command Finders while keeping mutations in Stores.
- [x] 2.3 Split location Domain loading from `StockLocationStore` and rename the query projection port to `StockLocationViewFinder`.
- [x] 2.4 Keep all custom Repository interfaces out of Inventory Domain packages.

## 3. Existing Store Separation

- [x] 3.1 Split ordinary reads from `StockOperationStore` and `StockMoveStore`, retaining only save and explicit lock methods in Stores.
- [x] 3.2 Split `StockMoveLineStore` reads into `StockMoveLineFinder` while preserving assignment/release persistence behavior.
- [x] 3.3 Give adapters implementing both narrow ports neutral `*PersistenceAdapter` names and preserve complete port-derived dependency names.

## 4. Allocation Pure-read Ports

- [x] 4.1 Rename allocation candidate, backlog and supply ports from command Stores back to command Finders.
- [x] 4.2 Rename their JDBC adapters and update coordinator, committer, reconciler, tests and integration composition.
- [x] 4.3 Prove allocation planning, strict FIFO, FEFO, stale-proposal, rollback and backlog behavior remains unchanged.

## 5. Architecture and Documentation

- [x] 5.1 Enforce Store mutation/lock-only methods, Finder pure-read-only methods and command/query package ownership with non-vacuous fitness functions.
- [x] 5.2 Update Inventory architecture and allocation-flow documentation to distinguish use-case ownership from method effects.
- [x] 5.3 Search source, tests, fixtures, SIT, docs and active OpenSpec artifacts for retired intermediate names and stale classification language.

## 6. Verification

- [x] 6.1 Run `cd backend && ./gradlew spotlessApply`, `./gradlew spotlessCheck` and `git diff --check`.
- [x] 6.2 Run Inventory unit/architecture tests, Inventory monolith SIT and compile all direct caller modules.
- [x] 6.3 Run the complete backend test suite and project E2E suite.
- [x] 6.4 Sync the corrected main spec, run strict OpenSpec validation and record final interface counts and verification results.
