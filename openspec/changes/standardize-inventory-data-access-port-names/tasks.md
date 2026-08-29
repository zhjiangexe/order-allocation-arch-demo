## 1. Baseline and Classification

- [x] 1.1 Record all Inventory custom `Repository`, `Store` and `Finder` interfaces plus their direct callers and classify each as Domain aggregate, Application command persistence, Application read-only access or Spring Data infrastructure.
- [x] 1.2 Run the focused Inventory unit and architecture tests; record the known obsolete
  `allocation.application.store` ownership violation and establish that all other pre-rename tests are green.

## 2. Application Command Stores

- [x] 2.1 Rename `StockOperationRepository`, `StockMoveRepository`, `StockMoveLineRepository` and `StockReceiptRequestRepository` to the approved `*Store` names without changing their methods.
- [x] 2.2 Rename the corresponding custom infrastructure adapters, constructor parameters, fields, imports, tests and Spring composition references with the full `*Store` names.
- [x] 2.3 Prove the Store migration preserves registration, assignment, release, receipt, completion, cancellation, locking and rollback behavior with focused tests.

## 3. Application Read-only Finders

- [x] 3.1 Rename backlog, candidate, Stock Quant view, Stock Operation view and reconciliation repository ports and custom adapters to `*Finder` without changing their queries.
- [x] 3.2 Move the existing `StockAllocationSupplyFinder` to `allocation.planning.application.port` and update all callers and tests.
- [x] 3.3 Prove all Finder interfaces are read-only and preserve planning, visibility, reconciliation, FIFO/FEFO and backlog behavior with focused tests.

## 4. Architecture and Documentation

- [x] 4.1 Add non-vacuous architecture fitness functions that reserve custom `*Repository` for Domain, constrain `*Store`/`*Finder` to Application ports and reject obvious Finder mutation methods while exempting Spring Data `Jpa*Repository`.
- [x] 4.2 Update Inventory architecture and allocation-flow documentation to define the three-suffix vocabulary and remove retired custom port/adapter names.
- [x] 4.3 Search production, tests, fixtures, SIT, other modules, docs and package-sensitive strings to prove no retired custom type or obsolete `allocation.application.store` package remains.

## 5. Verification

- [x] 5.1 Run `cd backend && ./gradlew spotlessApply`, `./gradlew spotlessCheck` and `git diff --check`.
- [x] 5.2 Run all Inventory unit/architecture tests, Inventory monolith SIT tests and compile direct caller modules.
- [x] 5.3 Run the complete backend test suite and project E2E suite.
- [x] 5.4 Run strict OpenSpec validation and confirm migrations, database mappings, SQL behavior, contracts, algorithms, transactions, lock order and externally observable behavior remain unchanged.
