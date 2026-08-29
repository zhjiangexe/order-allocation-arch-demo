## 1. Inventory package-sensitive surface and baseline

- [x] 1.1 Record every production/test source under the Domain taxonomy, Application `model`, and movement `shared/port` packages, plus
  direct import, AspectJ, source-path, reflection, and documentation references.
- [x] 1.2 Run the current Inventory architecture/unit tests and representative Inventory SIT tests before moving packages.
- [x] 1.3 Record the scoped worktree rule and prove that migrations, integration contracts, SQL, public schemas, and unrelated dirty-tree
  files are outside this change.

## 2. Replace implementation-shape architecture assertions

- [x] 2.1 Retain generic Inventory rules for bounded-context/table isolation, capability dependency direction, framework-free Domain,
  contract/transport-free Application, port ownership, and package conventions.
- [x] 2.2 Remove exact production file lists, method-body fragments, variable-name rules, class-modifier rules, living-document wording,
  and retired implementation-name assertions from Inventory architecture tests.
- [x] 2.3 Use ArchUnit for compiled type dependency rules and source scanning only for boundaries unavailable in bytecode, then run the
  focused architecture tests.

## 3. Flatten capability Domain packages

- [x] 3.1 Move all Allocation Domain aggregates, repositories, services, states, and values directly to `allocation/domain`, updating
  production, test, fixture, SIT, configuration, AspectJ, and documentation references.
- [x] 3.2 Move `StockQuant`, `StockQuantRepository`, and `StockWriteOrder` directly to `balance/domain` and update all callers.
- [x] 3.3 Move operation and move model types directly to `movement/domain` and update all callers.
- [x] 3.4 Move Warehouse Domain aggregates, repositories, and types directly to `warehouse/domain` and update all callers.
- [x] 3.5 Compile and run focused Domain/unit tests, then prove no retired Domain taxonomy declaration or import remains.

## 4. Flatten feature-local Application packages

- [x] 4.1 Move Assignment, Cancellation, Lifecycle, and Order Intake values from feature-local `model` packages into their owning
  Application feature packages while retaining each feature's `port` package.
- [x] 4.2 Move Receipt, Stock View, Movement Registration, and Stock Operation View values from feature-local `model` packages into their
  owning Application feature packages while retaining each feature's `port` package.
- [x] 4.3 Move movement persistence ports from `movement/application/shared/port` to `movement/application/port` and update all adapters,
  transactions, configuration, fixtures, and tests.
- [x] 4.4 Remove only empty retired `model`, `shared`, and Domain taxonomy directories, then prove no old package reference remains.
- [x] 4.5 Compile Inventory and monolith test/SIT source sets and run focused Application, adapter, and architecture tests.

## 5. Format and verify behavior preservation

- [x] 5.1 Run `cd backend && ./gradlew spotlessApply`, inspect non-import diffs for accidental logic changes, and run `spotlessCheck`.
- [x] 5.2 Run all Inventory unit/architecture tests and all Inventory monolith SIT tests.
- [x] 5.3 Run the complete backend test suite and resolve all cross-module package regressions.
- [x] 5.4 Run the complete project E2E suite and verify Events, Temporal, cancellation, receipt, allocation, and stock-view flows.
- [x] 5.5 Run strict OpenSpec validation and confirm no database migration, persisted representation, REST schema, integration-event
  schema, external identifier, SQL, transaction, or business-algorithm change was introduced.

## 6. Amend Allocation organization decision

- [x] 6.1 Map every Allocation Domain, Application, infrastructure, entrypoint, test, fixture, AspectJ, configuration, and documentation
  reference to its owning assignment, cancellation, intake, lifecycle, or narrowly scoped cross-slice concern.
- [x] 6.2 Update proposal, specification, and design so Allocation is business-capability-first and layers are created only where a
  slice owns code, without treating slices as bounded contexts.

## 7. Reorganize Allocation into vertical capability slices

- [x] 7.1 Co-locate assignment Domain rules, Application orchestration and ports, persistence/publication adapters, retry translation,
  configuration, availability entrypoint, and backlog scheduler below `allocation/assignment`.
- [x] 7.2 Co-locate cancellation Domain state and repository, Application orchestration and port, persistence/warehouse adapters, and
  order-cancellation entrypoint below `allocation/cancellation`.
- [x] 7.3 Co-locate Order intake Application command/use case/port, source projection adapter and persistence, and order-placement
  entrypoint below `allocation/intake`.
- [x] 7.4 Co-locate lifecycle Application orchestration/port, publication adapter, and shipment-handover entrypoint below
  `allocation/lifecycle`, without adding an empty Domain layer.
- [x] 7.5 Update cross-slice subscription/retry support, tests, fixtures, AspectJ expressions, monolith/WMS callers, and living
  documentation; remove only empty retired Allocation layer-first directories and prove no old reference remains.
- [x] 7.6 Add implementation-neutral architecture rules for business-capability-first Allocation placement and inward layer dependency,
  then compile and run focused architecture and Inventory tests.

## 8. Verify the amended structure

- [x] 8.1 Run `spotlessApply`, inspect package-only production changes, run `spotlessCheck`, and run `git diff --check`.
- [x] 8.2 Run all Inventory unit/architecture tests and all Inventory monolith SIT tests.
- [x] 8.3 Run the complete backend test suite and complete project E2E suite.
- [x] 8.4 Run strict OpenSpec validation and reconfirm that no persistence, contract, transaction, or business-algorithm change was
  introduced by the second package migration.

## 9. Extend the vertical-slice decision to every Inventory capability

- [x] 9.1 Map every Balance, Movement, and Warehouse production, test, fixture, SIT, architecture, configuration, and living-document
  reference to an On-hand, Receipt, Visibility, Operation, Registration, Location, or Operation Type owner.
- [x] 9.2 Amend proposal, specification, design, and tasks so every Inventory capability is business-capability-first and each slice
  creates only the layers it owns.

## 10. Reorganize Balance into vertical capability slices

- [x] 10.1 Move the canonical Stock Quant Domain and persistence below `balance/onhand`, updating all callers and fixtures.
- [x] 10.2 Co-locate receipt Application orchestration and ports, REST entrypoint, request persistence, and availability publication
  below `balance/receipt`.
- [x] 10.3 Co-locate the Stock Quant query model, port, JDBC adapter, REST entrypoint, and tests below `balance/visibility`.

## 11. Reorganize Movement into vertical capability slices

- [x] 11.1 Move canonical Stock Operation, Move, and Move Line Domain types, persistence ports, entities, mappers, and repositories below
  `movement/operation`.
- [x] 11.2 Co-locate source-neutral and inbound registration Application behavior below `movement/registration`.
- [x] 11.3 Co-locate operation query, reconciliation, health, JDBC adapters, REST entrypoint, and tests below
  `movement/visibility`.

## 12. Reorganize Warehouse and protect all Inventory slices

- [x] 12.1 Co-locate Stock Location Domain, listing use case, REST entrypoint, persistence, and tests below `warehouse/location`.
- [x] 12.2 Co-locate Stock Operation Type and Direction Domain and persistence below `warehouse/operationtype`.
- [x] 12.3 Update fixtures, SIT, monolith/WMS callers, AspectJ/source paths, and living documentation; remove only empty retired
  layer-first directories and prove no retired package reference remains.
- [x] 12.4 Generalize implementation-neutral placement, layer, and sibling-dependency architecture rules to Balance, Movement, and
  Warehouse, then compile and run focused tests.

## 13. Verify the complete Inventory vertical-slice structure

- [x] 13.1 Run `spotlessApply`, inspect production changes for package-only behavior, run `spotlessCheck`, and run `git diff --check`.
- [x] 13.2 Run all Inventory unit/architecture tests and all Inventory monolith SIT tests.
- [x] 13.3 Run the complete backend test suite and complete project E2E suite.
- [x] 13.4 Run strict OpenSpec validation and reconfirm no persistence, contract, transaction, or business-algorithm change was
  introduced by the third package migration.
