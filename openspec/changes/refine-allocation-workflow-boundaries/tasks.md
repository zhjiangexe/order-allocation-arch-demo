## 1. Pin the Existing Boundaries

- [ ] 1.1 Extend `MovementRecorderTest` to assert that one order-driven outbound call creates exactly one non-null picking shared by all of that order's moves, while different orders never share a picking.
- [ ] 1.2 Extend movement model and persistence tests to prove that a generic `StockMove` with no `pickingId` remains valid and round-trips without a schema change.
- [ ] 1.3 Extend waiting-movement query tests to prove that standalone moves and inbound picking groups are excluded while order-driven outbound groups retain FIFO and whole-order eligibility.
- [ ] 1.4 Add focused allocation tests that pin exactly one initial outcome, exactly one completion per successful wake assignment, no duplicate backorder on an unsuccessful wake, and continuation only after a full productive round.

## 2. Move Allocation Outcome Policy to the Flow Owners

- [ ] 2.1 Refactor `MovementAssigner.assign(...)` and `assignAll(...)` to return applied allocation outcomes without publishing `OrderAllocationCompleted`, while retaining batch loading, allocation, stock write ordering, move assignment, and persistence in the component.
- [ ] 2.2 Update `MovementAssignerTest` to verify assignment results and persisted stock/move state independently of event publication.
- [ ] 2.3 Add a transport-neutral `AllocationAttemptResult` and update `AllocateOrderUsecase` to return it while publishing exactly one `OrderAllocationCompleted` for an allocated initial attempt or exactly one `OrderBackorderRecorded` for an insufficient initial attempt in its existing transaction.
- [ ] 2.4 Update `AllocateOrderUsecaseTest` to cover allocated, insufficient, missing-demand, and duplicate-inbox paths with exact returned outcomes and outcome-event counts.

## 3. Extract One Bounded Backorder Wake Round

- [ ] 3.1 Introduce a transport-neutral `WakeRoundResult` and a non-transactional `BackorderWaker` that performs the allocatable-stock precheck, reads one FIFO-bounded waiting page, invokes `MovementAssigner.assignAll(...)`, publishes one completion per allocated order, and returns the allocated order ids plus its continuation decision without scheduling the next round.
- [ ] 3.2 Add focused `BackorderWakerTest` coverage for no allocatable stock, an empty queue, a blocked or partially productive round, a fully productive bounded round, exact completion counts, and returned continuation decisions.
- [ ] 3.3 Refactor `ReplenishmentUsecase.handle(...)` to record and complete inbound stock, directly invoke `BackorderWaker`, publish continuation from `WakeRoundResult` before the existing transaction commits, return the result, and remove its continuation-message entry point and private wake policy.
- [ ] 3.4 Introduce transactional `WakeBackordersUsecase` to claim continuation messages through the Inbox, invoke `BackorderWaker` without recording inbound stock, publish continuation from `WakeRoundResult` in the same transaction, and return the result.
- [ ] 3.5 Rewire `BackorderWakeRequestedIntegrationEventHandler` and its tests to invoke `WakeBackordersUsecase` through the existing retry boundary while preserving topic, metadata, warehouse-to-location translation, and Integration Event contracts.

## 4. Verify Atomicity and Allocation Semantics

- [ ] 4.1 Add or update a transaction integration test proving that inbound completion, the first wake round's assignments, and their Outbox outcomes commit together and all roll back when that transaction fails.
- [ ] 4.2 Update replenishment and continuation use-case tests to prove that the first wake round is a direct same-transaction call, while a continuation claims its own message and never repeats the stock increase.
- [ ] 4.3 Extend allocation architecture tests to confirm commands, results, and transactional use cases import neither Kafka event classes nor Temporal SDK types; initial and wake paths share `MovementAssigner` directly without invoking each other's use cases or introducing speculative factory/creator classes.
- [ ] 4.4 Run the FIFO replenishment batch, FIFO guarantee scope, hot-SKU concurrency, allocation concurrency, workflow, retry-transaction, Inbox, and Outbox integration suites to verify preserved FIFO/FEFO, idempotency, locking, and event behavior.

## 5. Document and Validate the Refined Design

- [ ] 5.1 Update Javadoc on `MovementAssigner`, `MovementRecorder`, `ReplenishmentUsecase`, `BackorderWaker`, and `WakeBackordersUsecase` so transaction ownership, outcome ownership, and optional-versus-required picking rules match the implementation.
- [ ] 5.2 Update `docs/stock-reservation-design.md` and `docs/dom-stock-movement-scope.md` with the implemented allocation/wake composition, movement/picking policy, Integration Event versus future Temporal adapter mapping, and the durable-result-replay prerequisite; leave archived OpenSpec changes and `docs/done` untouched.
- [ ] 5.3 Run the complete `order-promising` unit test suite, compile the SIT source set, and run `openspec validate refine-allocation-workflow-boundaries --strict` with no validation failures.
