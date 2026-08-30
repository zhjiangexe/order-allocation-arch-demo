# Implementation inventory

This file records the pre-move dependency surface for `align-inventory-ddd-boundaries`. It is an implementation aid, not a change to
the public architecture contract.

## Working-tree rule

The repository already contains a large, intentional set of tracked and untracked changes from the preceding Inventory redesign.
Implementation is restricted to the files listed below and their direct import/wiring/test callers. Unrelated changes must not be
deleted, reset, overwritten or reformatted merely because they are present in the working tree.

## Scoped production source

### Immutable stock view

- `inventory-context/.../balance/application/query/StockQuantViewRepository.java`
- `inventory-context/.../balance/application/usecase/GetStockQuantUsecase.java`
- `inventory-context/.../balance/infrastructure/query/JdbcStockQuantViewRepository.java`
- `inventory-context/.../balance/infrastructure/query/StockQuantRowMapper.java`
- `inventory-context/.../balance/entrypoint/rest/StockQuantController.java`
- `inventory-context/.../balance/entrypoint/rest/StockQuantResponse.java`

### Allocation assignment and order intake

- `inventory-context/.../allocation/domain/valueobject/{AssignmentQueueKey,StockOperationPredecessor}.java`
- `inventory-context/.../allocation/application/result/StockOperationAssignmentResult.java`
- `inventory-context/.../allocation/application/query/StockOperationAssignmentBacklogRepository.java`
- `inventory-context/.../allocation/application/service/assignment/*.java`
- `inventory-context/.../allocation/application/{command/AllocateOrderCommand,usecase/AllocateOrderUsecase}.java`
- `inventory-context/.../movement/application/source/order/OrderStockMovementSource.java`
- `inventory-context/.../allocation/infrastructure/{query,repository,retry}/**/*.java`
- `inventory-context/.../allocation/entrypoint/{messaging,scheduler}/**/*.java`
- `inventory-context/.../allocation/infrastructure/source/order/OrderStockMovementAdapter.java`

### Remaining Application features

- `inventory-context/.../allocation/application/{command,result,service/cancellation,usecase}/**/*.java`
- `inventory-context/.../allocation/application/service/lifecycle/*.java`
- `inventory-context/.../balance/application/{command,receipt,usecase}/**/*.java`
- `inventory-context/.../balance/application/{InboundReceiptCompleter.java}`
- `inventory-context/.../movement/application/{command,query,result}/**/*.java`
- `inventory-context/.../movement/application/{InboundReceiptRegistrar,StockOperationRegistrar}.java`

### Movement records and persistence ports

- `inventory-context/.../movement/domain/{aggregate,entity,type,valueobject}/**/*.java`
- `inventory-context/.../movement/domain/repository/{StockOperationRepository,StockMoveRepository}.java`
- `inventory-context/.../movement/infrastructure/{entity,mapper,query,repository}/**/*.java`
- direct Allocation, Balance, REST, Temporal, monolith seed and test-fixture callers of those types

### Messaging and framework boundaries

- `inventory-context/.../allocation/application/event/*.java`
- `StockAllocationCommitter`, cancellation transactions, release/completion use cases and `ConfirmStockReceiptUsecase`
- Inventory messaging adapters and entrypoint subscription identities
- `inventory-context/.../allocation/domain/service/MovementAssignmentPlanner.java`
- `inventory-context/.../movement/application/query/StockOperationView.java`
- monolith fulfillment cancellation process code currently under `bootstrap/fulfillment/cancellation`
- `inventory-context/.../InventoryBoundaryArchitectureTest.java`

## Path-sensitive wiring that must move with source

- `allocation/infrastructure/retry/AssignmentRetryConflictTranslator.java` has an AspectJ expression naming the current assignment
  package.
- `AllocationConcurrencyEndToEndIntegrationTest` and `AllocationHotSkuConcurrencyIntegrationTest` have test AspectJ expressions
  naming `StockAllocationCommitter.commit(..)` by package.
- `InventoryBoundaryArchitectureTest` resolves current Java source paths explicitly.
- Assignment consumers, the backlog scheduler, monolith seed/SIT configuration and Temporal adapters import concrete classes by their
  current packages.
- Inventory integration tests live under `deployments/monolith/src/sit`; there is no independent `inventory-context/src/sit` source
  set.

## Behavior baseline

Before package moves, the focused unit baseline passed with:

```text
./gradlew :inventory-context:test \
  --tests '*StockQuantControllerTest' \
  --tests '*ConfirmStockReceiptUsecaseTest' \
  --tests '*StockAllocationCommitterTest' \
  --tests '*ReleaseStockOperationUsecaseTest' \
  --tests '*CompleteOutboundMovementsUsecaseTest' \
  --tests '*StockOperationCancellationTransactionsTest'
```

The pinned stock-view baseline covers the existing JSON field names, empty `200` response, expired batches, available-to-promise
values, legacy `stockPoolId`, and deterministic SKU/batch order. Publication tests pin payload plus aggregate and target references.
`StockOperationAssignmentRollbackIntegrationTest` parameterizes persistence and publication failures and verifies operation/move
state, quant reservations, move lines and Outbox rows all roll back together.

## Boundary implementation evidence

- Assignment, lifecycle and availability application transactions now call feature-owned publisher ports synchronously. Infrastructure
  adapters alone construct versioned events, aggregate references and publication targets; no Domain Event dispatcher was introduced.
- Focused adapter tests pin the existing V3 order-assignment, V2 stock-operation lifecycle and V1 stock-availability payload/routing.
  `StockOperationAssignmentRollbackIntegrationTest` still passes through the adapter and proves an Outbox failure rolls back the stock
  mutation transaction.
- `MovementAssignmentPlanner` is framework-free and is composed by `StockAllocationDomainConfiguration` in infrastructure.
- `StockOperationView` is transport-neutral; `StockOperationResponse` owns the legacy `batches` JSON field at the REST boundary.
- The fulfillment cancellation coordinators and their process request/result/status types now live under the explicit
  `process/fulfillment/cancellation` package rather than generic bootstrap.
- `StockOperationDemandFactory` had no production caller. It is now a test fixture under
  `inventory/allocation/testsupport`, and the obsolete production source is gone.
- Empty legacy application/domain package directories left by the moves were removed only after compilation and architecture tests
  proved that no Java source remained in them.

## Final verification evidence

- `spotlessCheck`, the complete backend `test` task and all 110 Inventory monolith SIT tests pass.
- The complete Karate E2E suite passes all 17 scenarios across catalog/idempotency, Events allocation and cancellation, Debezium
  catch-up, shipment cancellation, Temporal fulfillment and Temporal cancellation.
- `openspec validate align-inventory-ddd-boundaries --strict` reports the change as valid, and `git diff --check` reports no whitespace
  errors.
- Inventory Application source has no integration-contract, messaging-envelope or Jackson dependency. Architecture tests enforce the
  same boundary so it cannot silently regress.
- This boundary-alignment change did not edit a database migration, persisted enum/string value, or integration-contract source or
  fixture. Such files already present in the dirty working tree belong to preceding Inventory redesign work and remain outside this
  change's scope.
- Existing external shapes remain pinned at their boundaries: REST tests cover the stock view and the compatibility `batches` field;
  publisher-adapter and integration-contract tests cover the existing V3 assignment, V2 lifecycle and V1 availability events. The
  full persistence/SIT and E2E runs additionally prove those mappings against the running application and database.
