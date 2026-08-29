## 1. Establish boundary and behavior baselines

- [x] 1.1 Inventory current production callers, Spring wiring, AspectJ/source-path checks and tests for every type that will move; record
  the scoped file list without deleting or overwriting unrelated dirty-worktree changes.
- [x] 1.2 Add stock-location read tests that pin the existing REST JSON fields, successful empty result, expired-stock visibility,
  available-to-promise values and deterministic SKU/batch ordering.
- [x] 1.3 Pin assignment, lifecycle and stock-availability publication tests to the existing event payload, Outbox target/aggregate
  references and atomic rollback behavior before introducing publisher ports.

## 2. Make the stock-location read path immutable

- [x] 2.1 Add the immutable `StockQuantView` application projection under the `balance/application/stockview/model` feature package,
  including validated fields and pure available-to-promise/expiry derivation.
- [x] 2.2 Move `StockQuantViewRepository` to `balance/application/stockview/port` and change it to return an ordered immutable list of
  `StockQuantView` rather than a map containing mutable `StockQuant` aggregates.
- [x] 2.3 Update `JdbcStockQuantViewRepository` to select only projection fields and map rows directly to `StockQuantView` in the existing
  one-query order; remove the obsolete mutable-aggregate row mapper if it has no other caller.
- [x] 2.4 Move `GetStockQuantUsecase` into the stock-view feature and update `StockQuantResponse` to group the ordered projections while
  preserving every external response field and empty/expired behavior.
- [x] 2.5 Update unit and JDBC integration tests to prove projection immutability, direct mapping, one-query execution and unchanged REST
  ordering and payload.

## 3. Align the assignment feature and its ports

- [x] 3.1 Move `AssignmentQueueKey`, `StockOperationPredecessor`, the candidate model and `StockOperationAssignmentResult` into
  `allocation/application/assignment/model`; preserve validation and immutable collection copies.
- [x] 3.2 Move `StockOperationAssignmentCandidateRepository`, `StockOperationAssignmentBacklogRepository` and
  `StockAllocationSupplyFinder` into `allocation/application/assignment/port` with full type-derived dependency property names.
- [x] 3.3 Move `StockOperationAssignmentCoordinator`, `StockAllocationCommitter`, `ReconcileStockOperationBacklogUsecase`, result assembly and
  assignment working models under the assignment feature without changing the select-plan-commit method sequence.
- [x] 3.4 Update JDBC candidate, backlog and supply adapters, retry handling, scheduler entrypoints, monolith wiring and tests for the new
  assignment model and port packages.
- [x] 3.5 Move `AllocateOrderUsecase`, its command model and `OrderStockMovementStore` port into
  `allocation/application/intake/order/{model,port}`, while retaining `StockOperationRegistrar` under source-neutral
  `movement/application/registration`.
- [x] 3.6 Run assignment unit and targeted integration tests to verify SHIP_COMPLETE, FEFO, FIFO predecessor blocking, replay, stale-
  proposal rejection and canonical lock order remain unchanged.

## 4. Organize remaining Application code by feature

- [x] 4.1 Consolidate cancellation commands, results, statuses, checkpoints, coordinator ports, transactions and use cases under
  `allocation/application/cancellation/{model,port}` and its feature root.
- [x] 4.2 Consolidate release/completion commands, results, snapshots, working models and use cases under
  `allocation/application/lifecycle/{model,port}` and its feature root.
- [x] 4.3 Consolidate receipt commands, results, repositories and services under `balance/application/receipt/{model,port}` without
  changing inbound stock behavior.
- [x] 4.4 Consolidate movement registration and stock-operation view commands, results and repositories under explicit
  `movement/application/registration` and `movement/application/stockoperationview` features.
- [x] 4.5 Relocate application-only enums and workflow models with their owning features and remove emptied global `command`, `query`,
  `result`, `service`, `dto`, `enum` or `type` containers only after all callers compile.
- [x] 4.6 Update feature-level unit tests and monolith composition imports, then compile `inventory-context` before changing movement
  domain packages.

## 5. Reclassify movement records and persistence ownership

- [x] 5.1 Move operation concepts (`StockOperation`, its state/source, assignment policy and source type) into
  `movement/domain/model/operation` without changing validation, idempotency or lifecycle methods.
- [x] 5.2 Move move concepts (`StockMove`, `StockMoveLine` and `MoveState`) into `movement/domain/model/move` without introducing a
  parent aggregate object or independent move-line lifecycle.
- [x] 5.3 Move `StockOperationRepository` and `StockMoveRepository` from Domain to the explicitly shared movement application persistence
  port package; keep `StockQuantRepository` and `StockOperationCancellationRepository` domain-owned.
- [x] 5.4 Update JPA/JDBC implementations, mappers, allocation/balance/movement callers, monolith seed/wiring code, fixtures and tests to
  use the reclassified models and application persistence ports.
- [x] 5.5 Verify registration and all lifecycle transactions still load only the records they require and still enforce operation/move/
  line summaries, quant consistency, optimistic versions and lock order atomically.

## 6. Isolate messaging contracts behind publisher ports

- [x] 6.1 Add assignment, stock-operation lifecycle and stock-availability publisher ports owned by their application features, plus an
  application-owned lifecycle action type and immutable `StockAvailabilityIncrease` publication model; introduce no Domain Event
  types or dispatch.
- [x] 6.2 Move or absorb `OrderStockOperationAssignedPublicationFactory`, `StockOperationLifecyclePublicationFactory` and receipt
  availability-contract construction into infrastructure messaging adapters that translate to the existing versioned contracts and
  publication envelopes.
- [x] 6.3 Replace direct contract/publication calls in commit, cancellation, release, completion and receipt transactions with
  synchronous publisher-port calls while keeping each Outbox write inside the same database transaction.
- [x] 6.4 Move `AllocationEventSubscriptions` and other inbound subscription identities to `entrypoint/messaging`, preserving their exact
  values and consumer behavior.
- [x] 6.5 Update mapping, transaction rollback, consumer and Outbox tests; verify no Inventory application package imports
  `contracts.*`, messaging envelope, channel or external aggregate-reference types.

## 7. Remove remaining framework and ownership leaks

- [x] 7.1 Remove Spring stereotypes from `MovementAssignmentPlanner` and other pure domain services and register their interfaces through
  outer-layer Inventory configuration.
- [x] 7.2 Remove Jackson annotations from application query views, including `StockOperationView`, and map any compatibility field names
  in REST response records instead.
- [x] 7.3 Move fulfillment/Inventory cancellation process logic and its request/result/status models out of generic monolith bootstrap
  into an explicit cross-context orchestration or process-manager package; leave bootstrap with composition only.
- [x] 7.4 Prove whether `StockOperationDemandFactory` has a production caller; retain it with its owning feature, move it to test fixtures,
  or delete it according to that evidence.
- [x] 7.5 Remove only empty directories and source artifacts proven obsolete by the completed moves; preserve unrelated untracked and
  modified files.
- [x] 7.6 Extend `InventoryBoundaryArchitectureTest` to enforce domain/application dependency rules, repository ownership, immutable read
  projections, feature-owned workflow models and contract-independent application publication.

## 8. Format and verify the complete change

- [x] 8.1 Run `cd backend && ./gradlew spotlessApply` and inspect the scoped diff for accidental behavior, SQL, schema or contract
  changes.
- [x] 8.2 Run `cd backend && ./gradlew spotlessCheck` plus Inventory architecture and unit tests.
- [x] 8.3 Run the targeted Inventory assignment, stock-view, persistence, messaging and lifecycle integration/SIT suites.
- [x] 8.4 Run the full backend test suite and resolve all cross-module package or wiring regressions.
- [x] 8.5 Run the project E2E suite and verify order intake, allocation, replenishment wake, cancellation, receipt and stock-view flows.
- [x] 8.6 Run strict OpenSpec validation and confirm no database migration, persisted-value change, REST schema change or integration-event
  schema change was introduced.
