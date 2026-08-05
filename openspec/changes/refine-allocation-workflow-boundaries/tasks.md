## 1. Pin the Existing Boundaries

- [x] 1.1 Extend `StockOperationRecorderTest` to assert that one order-driven outbound call creates exactly one non-null picking shared by all of that order's moves, while different orders never share a picking.
- [x] 1.2 Extend movement model and persistence tests to prove that a generic `StockMove` with no `pickingId` remains valid and round-trips without a schema change.
- [x] 1.3 Extend waiting-movement query tests to prove that standalone moves and inbound picking groups are excluded while order-driven outbound groups retain FIFO and whole-order eligibility.
- [x] 1.4 Add focused allocation tests that pin exactly one initial outcome, exactly one completion per successful wake assignment, no duplicate backorder on an unsuccessful wake, and continuation only after a full productive round.

## 2. Move Allocation Outcome Policy to the Flow Owners

- [x] 2.1 Refactor `MovementAssigner.assign(...)` and `assignWaitingBatch(...)` to return applied allocation outcomes without publishing `OrderAllocationCompleted`, while retaining batch loading, allocation, stock write ordering, move assignment, and persistence in the component.
- [x] 2.2 Update `MovementAssignerTest` to verify assignment results and persisted stock/move state independently of event publication.
- [x] 2.3 Keep `AllocateOrderUsecase` as a void transactional boundary while publishing exactly one `OrderAllocationCompleted` for an allocated initial attempt or exactly one `OrderBackorderRecorded` for an insufficient initial attempt in its existing transaction.
- [x] 2.4 Update `AllocateOrderUsecaseTest` to cover allocated, insufficient, missing-demand, and duplicate-inbox paths with exact outcome-event counts and no unused direct result contract.

## 3. Extract One Bounded Backorder Wake Round

- [x] 3.1 Introduce a transactional, transport-neutral `AllocateWaitingDemandUsecase` that performs the allocatable-stock precheck, reads one FIFO-bounded waiting page, invokes `MovementAssigner.assignWaitingBatch(...)`, and publishes one completion per allocated order without scheduling the next round.
- [x] 3.2 Add focused `AllocateWaitingDemandUsecaseTest` coverage for Inbox deduplication, no allocatable stock, an empty queue, blocked and partially productive rounds, and exact completion counts.
- [x] 3.3 Keep stock receipt independent by publishing availability after commit instead of invoking waiting-demand allocation inside the inbound transaction.
- [x] 3.4 Let `AllocateWaitingDemandUsecase` claim availability messages through the Inbox while allowing scheduled reconciliation to invoke the same transaction boundary without transport metadata.
- [x] 3.5 Rewire `BackorderWakeRequestedIntegrationEventHandler` and its tests to invoke `AllocateWaitingDemandUsecase` through the existing retry boundary while preserving topic, metadata, facility-to-location translation, and Integration Event contracts.

## 4. Verify Atomicity and Allocation Semantics

- [x] 4.1 Add or update a transaction integration test proving that applying available stock, the first wake round's assignments, and their Outbox outcomes commit together and all roll back when that transaction fails.
- [x] 4.2 Update availability-increase and continuation use-case tests to prove that the first wake round is a direct same-transaction call, while a continuation claims its own message and never repeats the stock increase.
- [x] 4.3 Extend allocation architecture tests to confirm commands, wake-round results, and transactional use cases import neither Kafka event classes nor Temporal SDK types; initial and wake paths share `MovementAssigner` directly without invoking each other's use cases or introducing speculative factory/creator classes.
- [x] 4.4 Run the FIFO availability-increase batch, FIFO guarantee scope, hot-SKU concurrency, allocation concurrency, workflow, retry-transaction, Inbox, and Outbox integration suites to verify preserved FIFO/FEFO, idempotency, locking, and event behavior.

## 5. Document and Validate the Refined Design

- [x] 5.1 Update Javadoc on `MovementAssigner`, `StockOperationRecorder`, `ConfirmStockReceiptUsecase`, and `AllocateWaitingDemandUsecase` so transaction ownership, outcome ownership, and optional-versus-required picking rules match the implementation.
- [x] 5.2 Update `docs/stock-reservation-design.md` and `docs/dom-stock-movement-scope.md` with the implemented allocation/wake composition, movement/picking policy, Integration Event versus future Temporal adapter mapping, and the durable-result-replay prerequisite; leave archived OpenSpec changes and `docs/done` untouched.
- [x] 5.3 Run the complete `order-promising` unit test suite, compile the SIT source set, and run `openspec validate refine-allocation-workflow-boundaries --strict` with no validation failures.

## 6. Unify the Facility Vocabulary

- [x] 6.1 Rename the catalog model, repositories, REST surface, Integration Event payloads, commands, domain events, and Java identifiers from `FulfillmentNode` / `nodeId` / `warehouseId` to `Facility` / `facilityId`, retaining `StockLocation` / `locationId` as a separate concept.
- [x] 6.2 Rename unreleased database tables, columns, constraints, frontend contracts, E2E inputs, fixtures, and living documentation without adding compatibility aliases; leave archived OpenSpec changes and `docs/done` untouched.
- [x] 6.3 Run backend unit tests, SIT compilation and selected schema/transaction integration tests, frontend tests, strict OpenSpec validation, and a residual terminology scan.

## 7. Align Stock Availability with the External WMS Boundary

- [x] 7.1 Rename `StockReplenishedIntegrationEvent`, `ReplenishStockCommand`, their Kafka handler, and `ReplenishmentUsecase` to the availability-increase vocabulary, including the dev probe and frontend contracts.
- [x] 7.2 Make `ConfirmStockReceiptUsecase` apply a positive batch delta directly to `StockPool` under Inbox idempotency, then run the first bounded wake round and publish continuation in the same transaction.
- [x] 7.3 Remove the no-longer-used inbound recording/completion path and update movement, architecture, unit, and transaction tests to prove availability changes create no inbound picking, move, or move line.
- [x] 7.4 Update living documentation and run backend unit/SIT, frontend, strict OpenSpec, diff, and residual terminology validation.

## 8. Restore Local Synchronous Stock Receipt

- [x] 8.1 Correct the artifacts to treat `StockPool` as the stock context's physical source of truth and define a synchronous one-step receipt that creates inbound warehouse execution before changing quantity.
- [x] 8.2 Restore facility-aware `StockOperationRecorder.recordInbound(...)`, `MovementCompleter`, and `StockPool.receive(...)`, then replace the availability-increase facade with transactional `ConfirmStockReceiptUsecase` plus focused unit and architecture tests.
- [x] 8.3 Replace the dev Kafka stock probe with a production stock REST controller that invokes receipt confirmation synchronously, update frontend contracts and integration tests, and remove the unused availability-input event/handler path.
- [x] 8.4 Update living documentation and run backend unit/SIT, frontend, strict OpenSpec, diff, and residual-boundary validation.

## 9. Keep the REST Adapter Thin

- [x] 9.1 Move facility-to-internal-location resolution from `StockReceiptController` into `ConfirmStockReceiptUsecase`, remove `locationId` from the receipt command boundary, add an architecture guard, and run focused unit/SIT plus strict OpenSpec validation.

## 10. Decouple Receipt Confirmation from Backorder Allocation

- [x] 10.1 Update allocation artifacts to replace the receipt-and-first-wake atomic boundary with an Outbox-backed availability fact, a shared transactional wake use case, and periodic scheduler reconciliation.
- [x] 10.2 Make `ConfirmStockReceiptUsecase` complete only inbound execution and publish `StockAvailabilityIncreased`; translate it to a keyed Integration Event and handle it through `AllocateWaitingDemandUsecase` after commit.
- [x] 10.3 Add `AllocationReconciliationScheduler` and a bounded waiting-scope query so scheduled reconciliation invokes the same transactional `AllocateWaitingDemandUsecase` without transport metadata.
- [x] 10.4 Update unit, architecture, transaction, Kafka, FIFO, and living-documentation coverage for asynchronous first wake, duplicate-safe event/scheduler overlap, and eventual convergence.
- [x] 10.5 Run the complete backend unit suite, compile SIT, run focused receipt/allocation integration suites, and validate the OpenSpec change strictly.

## 11. Remove Backorder Continuation Events

- [x] 11.1 Update allocation artifacts and living documentation so availability provides the prompt first wake while Scheduler reconciliation owns any later bounded rounds.
- [x] 11.2 Remove the continuation Domain Event, Integration Event, translator branch, Kafka handler, and their focused tests; keep `AllocateWaitingDemandUsecase` shared by availability and Scheduler without publishing orchestration control.
- [x] 11.3 Update FIFO batch and test-support coverage to drive later rounds through the Scheduler and prove no continuation Outbox event is required for convergence.
- [x] 11.4 Run backend unit/SIT validation, strict OpenSpec validation, a residual continuation-event scan, and `git diff --check`.

## 12. Support Multiple Receipt Locations per Facility

- [x] 12.1 Add `locationId` to the synchronous receipt contract, validate that it is an internal location of the stated facility, and use it consistently for inbound picking, move, physical stock, and availability publication.
- [x] 12.2 Expose internal locations through a read-only catalog use case and let the stock UI select a location before querying inventory or confirming receipt; remove the one-internal-location schema assumption.
- [x] 12.3 Update focused backend/frontend tests and living documentation, then run backend unit/SIT compilation, frontend tests, strict OpenSpec validation, and `git diff --check`.
