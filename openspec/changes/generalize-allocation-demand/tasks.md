## 1. Freeze the allocation contract

- [x] 1.1 Confirm the `AllocationDemand` status transitions are limited to `PENDING`, `ALLOCATED`, and `CANCELLED`, with `ALLOCATED -> CANCELLED` allowed only after external execution cancellation is confirmed and local execution remains reversible.
- [x] 1.2 Freeze source allocation-unit identity as `(sourceType, canonical sourceId, allocationUnitKey)`, require canonical source id to be globally unique within its source type, and keep raw external-id namespace mapping in source adapters.
- [x] 1.3 Confirm that one allocation demand contains only one owner, facility, and source location.
- [x] 1.4 Define allocation-unit-key derivation from source-stable business split identities, reject retry-specific or mutable-configuration-derived keys, and use `PRIMARY` for the one-unit-per-order path.
- [x] 1.5 Define immutable accepted demand content across scope, scheduling snapshots, execution intent, and canonical demand lines; persist `acceptedContentVersion = 1`, normalize time/default representations, compare only immutable move/picking intent, exclude mutable execution and allocation-generated values, and avoid an unversioned opaque hash as the sole correctness check.
- [x] 1.6 Add architecture tests preventing allocation code from using source-specific aggregates as its decision input.
- [x] 1.7 Freeze the first-version policy as shared-SKU strict FIFO plus all-or-nothing: a demand must satisfy predecessor order in every required SKU queue, while demands sharing no SKU remain independent; reject adapters requiring partial allocation, precedence bypass, or cross-unit atomic completion.
- [x] 1.8 Document the first-version inventory dimensions as positive integer base-unit quantities for fungible owner/location/SKU stock, use checked same-SKU aggregation, reject totals above the persistence range, and retain no tenant, UOM, lot/serial, quality-constraint, or deallocation support.
- [x] 1.9 Define source-line canonicalization by stable `sourceLineId`, derive an immutable allocation-owned `lineSequence`, ignore transport ordering, and reject duplicate source-line ids within one allocation unit.
- [x] 1.10 Freeze first-version order immutability after acceptance: no amendment or reopen under `ORDER/orderId/PRIMARY`; a replacement uses a new `orderId`, while future revision identities require a separate source change.

## 2. Add allocation-demand persistence and domain model

- [x] 2.1 Add the migration for allocation demand headers, including full source allocation-unit identity, scope, scheduling snapshots, `acceptedContentVersion`, allocation-owned `enqueuedAt`, allocation status, version, and timestamps; do not duplicate execution-intent fields from move/picking records.
- [x] 2.2 Add the migration for allocation demand lines, including allocation-owned line id, source-line reference, SKU, quantity, immutable canonical `lineSequence`, and duplicate source-line protection per allocation unit.
- [x] 2.3 Add the unique constraint for `(sourceType, sourceId, allocationUnitKey)` and indexes for pending scope/FIFO candidate queries.
- [x] 2.4 Implement `AllocationDemand` and `AllocationDemandLine` domain models with the allowed state transitions.
- [x] 2.5 Implement source type and source reference value objects with validation.
- [x] 2.6 Implement persistence entities, mappers, repositories, and optimistic-locking support.
- [x] 2.7 Add unit and persistence tests for creation, canonical line sequence, structural identical retry after mutable execution progress, conflicting retry, duplicate source lines, checked same-SKU overflow, invalid quantities, invalid transitions, cancelled-demand replay/conflict, and cancellation.
- [x] 2.8 Add model/architecture tests proving `AllocationDemand` contains only common allocation data and does not depend on source-specific aggregate status or fulfillment transitions.
- [x] 2.9 Persist cancellation-operation progress by `(allocationDemandId, cancellationOperationId)`, distinguishing started, external-rejected, external-confirmed, and locally completed states so a confirmed operation resumes an incomplete local commit.
- [x] 2.10 Introduce movement demand references through a nullable-add, active-data backfill, anomaly scan, and validated-constraint sequence; enforce paired references for demand movements, no references for inbound/supply-only movements, non-cascading history, and rolling-version compatibility.

## 3. Create source adapters and execution links

- [x] 3.1 Add an application command/use case that atomically commits the allocation-side inbox claim, allocation demand, all demand lines, and all stock-consuming outbound movements and required picking from a source allocation-unit reference; do not span the source-context transaction.
- [x] 3.2 Link each stock-consuming outbound movement through `allocationDemandId` and `allocationDemandLineId`; retain `sourceLineId` for traceability only.
- [x] 3.3 Keep inbound movement creation supply-only and verify that it never creates an allocation demand reference.
- [x] 3.4 Add the order source adapter that uses allocation-unit key `PRIMARY`, captures demand source location from the facility outbound picking type's `defaultFromLocationId`, and creates the `ORDER` demand, outbound picking, and movements in one local acceptance transaction.
- [x] 3.5 Introduce source-provided execution intent for destination location, picking type/direction, and picking creation; refactor `StockPicking` validation to use picking type or direction rather than `orderId` presence.
- [x] 3.6 Enforce that demand location, outbound move source location, reserved stock-pool location, and facility ownership are consistent.
- [x] 3.7 Backfill order lines with no move, `CONFIRMED` waiting moves, and active `ASSIGNED` moves under allocation-unit key `PRIMARY`; use the current outbound default only when no execution exists, otherwise preserve the existing move/picking source location, preserve original enqueue time, and suppress completion publication.
- [x] 3.8 Add source-agnostic contract tests proving transfer/replenishment/manual identities can create test demands without an order id; do not add their production adapters in this change.
- [x] 3.9 Add idempotency tests proving a picking/location configuration change or changed-content replay after first order acceptance returns `SourceDemandConflict` instead of creating, replacing, or reopening a demand; verify a replacement with a new order id can create its own `PRIMARY` demand.
- [x] 3.10 Implement the versioned acceptance structural comparator across demand header, canonical lines, and immutable execution-intent fields; normalize UTC persistence precision and null/default values, exclude mutable move/picking state and allocation-generated values, and treat any normalized V1 content difference as `SourceDemandConflict`.

## 4. Replace candidate discovery with demand-first queries

- [x] 4.1 Add repository queries that return pending allocation-demand candidates with all demand lines, execution references, and the earlier pending predecessors needed to evaluate every required SKU queue even when those predecessors fail the stock-availability prefilter.
- [x] 4.2 Scope each FIFO queue by owner, facility, source location, and SKU; use triggering SKU only to wake candidate discovery, not as permission to bypass another required SKU's predecessor.
- [x] 4.3 Exclude cancelled/completed execution records and inbound movements without using `orderId` as the generic demand predicate.
- [x] 4.4 Ensure candidate limits count allocation demands and preserve `(enqueuedAt, allocationDemandId)` precedence in every shared SKU queue without refreshing enqueue time on retry; commit at most one demand per allocation transaction, reconsider successors in later bounded iterations, and do not let disjoint SKU queues block one another.
- [x] 4.5 Load all candidate SKUs' allocatable stock in a bounded number of queries before planning.
- [x] 4.6 Add repository tests for order and non-order candidates, inbound exclusion, multi-SKU candidates, a later A+B demand behind an earlier B-only predecessor, an available A-only successor behind an unavailable A+B predecessor, independent disjoint-SKU queues, predecessor visibility despite stock prefiltering, scope limits, and location/facility consistency.
- [x] 4.7 Add an anomaly query and isolation behavior for pending demands with missing/duplicate execution references or mismatched demand/move allocation state.

## 5. Separate planning from execution commit

- [x] 5.1 Refactor the allocation decision service to accept source-agnostic demand snapshots and return an immutable allocation plan keyed only by `allocationDemandId` and `allocationDemandLineId`, aggregate same-SKU quantities for sufficiency, distribute FEFO picks by canonical line sequence, and avoid repository access.
- [x] 5.2 Implement `AllocationCommitter` to validate demand/move/stock-pool location consistency, follow the shared demand → globally ordered stock pools → ordered moves/picking lock hierarchy, and apply the plan to demand lines.
- [x] 5.3 Update outbound `StockMove` state and create `StockMoveLine` records from committed batch picks.
- [x] 5.4 Update optional `StockPicking` summaries in the same transaction as move and demand state changes.
- [x] 5.5 Transition `AllocationDemand` to `ALLOCATED` only after every demand line is committed successfully.
- [x] 5.6 Preserve rollback behavior for optimistic-locking failures and ensure no completion fact is published after rollback.
- [x] 5.7 Replace allocation-core `orderId` / `orderLineId` names in `Demand`, `DemandLine`, `BatchPick`, and allocation result types with allocation-owned identities.
- [x] 5.8 Add unit tests for complete allocation, insufficient multi-SKU stock, deterministic same-SKU line-to-batch mapping, FEFO picks, shared-SKU FIFO predecessor selection, disjoint-SKU progress, and empty/no-op batches.
- [x] 5.9 Make ready allocation plans self-validating, remove duplicate SKU data from batch picks, and enforce at most one move per allocation demand line with a database unique index.

## 6. Rewire initial allocation and waiting reconciliation

- [x] 6.1 Refactor initial allocation to use the generic demand creator, planner, and committer without changing its external trigger contract.
- [x] 6.2 Introduce `TransactionalAllocationAttempt` to consume demand-first candidates instead of reconstructing demand from `StockMove`, and share that transaction operation between availability wake-up and reconciliation use cases.
- [x] 6.3 Replace `MovementAssigner.toDemands` with the new candidate/commit boundary and remove redundant movement-to-demand conversion.
- [x] 6.4 Keep availability events and reconciliation scheduler as separate triggers of the same bounded allocation use case; process at most one demand per transaction and let the outer reconciliation loop invoke subsequent iterations.
- [x] 6.5 Ensure duplicate wake-ups for the same demand are safe under optimistic locking and idempotent commit.
- [x] 6.6 Update unit tests for initial allocation, waiting allocation, one-demand transaction boundaries, successor reconsideration, scheduler isolation, duplicate events, concurrent allocation, and deadlock-safe lock ordering across multi-SKU demands.

## 7. Generalize completion and cancellation facts

- [x] 7.1 Define an allocation-context-internal generic completion result with allocation-demand, source, move, source-location, quantity, and optional picking/execution-group references.
- [x] 7.2 Publish one canonical `OrderAllocationCommittedIntegrationEvent` v1 and fan it out to Ordering plus the selected fulfillment driver; keep `allocationId = pickingId` and map `orderLineId` from the order source-line reference.
- [x] 7.3 Add the order completion adapter and verify that order status changes remain outside the allocation transaction.
- [x] 7.4 Implement cancellation of pending demands and unassigned movements using a stable `cancellationOperationId`; use `OrderCancelledIntegrationEvent.eventId` for the order path and treat its workflow sequencing as external cancellation confirmation when required.
- [x] 7.5 Implement allocated-demand cancellation as an idempotent operation: call the coordinator outside the database transaction with the operation id, durably record rejected or confirmed external decisions, and after confirmation follow the shared lock hierarchy to release reservations and complete local cancellation in one transaction.
- [x] 7.6 Return a not-cancellable result when external execution cancellation cannot be confirmed, without changing demand, reservation, movement, or picking state.
- [x] 7.7 Add tests for cancellation/allocation races, crash before external-result persistence, crash after external confirmation but before local commit, exactly-once reservation release, confirmed reversible cancellation, unconfirmed refusal, idempotent retries, and canonical completion publication.
- [x] 7.8 Verify the order adapter preserves existing fulfillment sequencing: WMS `CANCELLED` permits ordering/allocation cancellation, while WMS `REJECTED` leaves the order, demand, and reservation active.
- [x] 7.9 Verify same-operation retries retain their original decision and document that unordered future source adapters must persist cancellation tombstones before activation.

## 8. Prove the source-agnostic boundary without expanding adapter scope

- [x] 8.1 Add test fixtures for `TRANSFER`, `REPLENISHMENT`, `PRODUCTION`, and `MANUAL` source identities without importing source-specific aggregates.
- [x] 8.2 Verify through persistence, candidate-query, and planner contract tests that a non-order demand does not require `orderId` or `orderLineId`.
- [x] 8.3 Verify that supply-only inbound, inventory adjustment, and already-reserved operations do not create allocation demands.
- [x] 8.4 Document that each production non-order adapter requires a separate OpenSpec change covering creation, allocation-unit-key derivation, completion, cancellation, and integration contracts.
- [x] 8.5 Add capability-gate tests rejecting test adapters that require partial allocation, FIFO bypass, decimal/UOM quantities, constrained lot/serial stock, or source-level atomic completion across allocation units.

## 9. Remove legacy order-specific queue paths

- [x] 9.1 Run dual-read comparison between the legacy order/movement candidate query and the new allocation-demand candidate query after normalizing existing execution to its move/picking source location and no-move orders to the configured outbound default; explicitly classify extra legacy location rows and legacy allocations rejected by the new cross-SKU predecessor check.
- [x] 9.2 Keep shadow comparison read-only and prove a feature switch plus paused-consumer verification allows only one allocation committer mode across all instances to write reservations, movement states, and completion events for a source allocation unit.
- [x] 9.3 Verify that FIFO, FEFO, ship-complete, availability wake-up, cancellation, and concurrency integration tests produce equivalent order behavior except for explicitly declared corrections to legacy multi-location expansion and cross-SKU precedence.
- [x] 9.4 Remove the `picking.orderId`-based generic waiting predicate after the order path uses the generic demand-first query and non-order contract tests pass.
- [x] 9.5 Remove obsolete order-specific demand reconstruction and `MovementAssigner` waiting-query dependencies.
- [x] 9.6 Retire obsolete internal event names only after order adapters publish the unchanged v1 integration contracts from the generic fact.
- [x] 9.7 Update living specs, architecture documentation, and migration notes with the final allocation-demand boundary.

## 10. Verification and rollout

- [x] 10.1 Run module unit tests and persistence tests for allocation demand, stock allocation, and stock movement.
- [x] 10.2 Run end-to-end tests for order, inbound receipt, availability wake-up, scheduler reconciliation, confirmed reversible cancellation, and unconfirmed warehouse cancellation refusal; use contract tests rather than production adapters for non-order sources.
- [x] 10.3 Verify database indexes and query plans for pending demand scope queries before removing legacy indexes.
- [x] 10.4 Exercise initial and final idempotent backfill against representative no-move, `CONFIRMED`, and active `ASSIGNED` data; verify enqueue-time preservation, no completion re-publication, no duplicate source allocation-unit identities, paired movement references, and no stranded demand/movement records.
- [x] 10.5 Implement and document the quiesced cutover: pause and drain legacy order-allocation/availability consumers and reconciliation, run final backfill plus anomaly/constraint/shadow gates, verify one writer mode across instances, resume on the new path, and verify queued order events replay idempotently; permit direct rollback only before new-path commits and require pause/drain/reconciliation before any later writer rollback.
- [x] 10.6 Add metrics and alerts for isolated allocation anomalies and shared-SKU FIFO pending age/blocking predecessor/blocked SKU, plus manual repair and cancellation runbooks; do not auto-repair inconsistent state or bypass a valid FIFO predecessor.
