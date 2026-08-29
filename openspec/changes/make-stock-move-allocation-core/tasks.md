## 1. Protect the migration baseline

- [x] 1.1 Inventory the dirty worktree by prior OpenSpec change and identify every file introduced or modified only by the superseded demand/commitment design.
- [x] 1.2 Restore V1–V20 Flyway files to their published Git content using explicit patches and add a checksum guard proving historical migrations remain unchanged.
- [x] 1.3 Determine whether any configured non-disposable database has applied the worktree-only V21–V29 drafts; document the detected migration state before replacing or forward-repairing them.
- [x] 1.4 Replace the unpublished V21–V29 commitment drafts with an expand/backfill/validate/contract migration chain for move-centric pickings, moves and cancellation-operation keys.
- [x] 1.5 Add migration integration tests for a clean database and for deterministic conversion of pending, assigned, done and cancelled allocation-demand rows.

## 2. Make picking and move the canonical domain model

- [x] 2.1 Extend `StockPicking` with canonical source-unit identity, assignment policy, enqueue time and structural replay invariants without adding SKU or quantity.
- [x] 2.2 Extend `StockMove` with stable source-line identity and immutable line sequence; remove allocation-demand, allocation and order-specific core references.
- [x] 2.3 Add `ASSIGNED -> CONFIRMED` unassignment transitions to `StockMove` and `StockPicking`, while keeping `DONE` terminal and cancellation distinct.
- [x] 2.4 Reduce `StockMoveLine` to the sole `moveId + stockQuantId + quantity` reservation/execution detail and remove allocation-slice identity.
- [x] 2.5 Update domain unit tests to cover canonical construction, replay equality inputs, homogeneous `SHIP_COMPLETE` states, assignment, release, completion and cancellation.

## 3. Persist and query canonical movement groups

- [x] 3.1 Update picking/move/move-line JPA entities and mappers for the move-centric schema, including source identity, line sequence and removed duplicate references.
- [x] 3.2 Expand `StockPickingRepository` and `StockMoveRepository` ports with source lookup, locked group loading, ordered move loading, current move-line loading and line deletion operations.
- [x] 3.3 Implement repository adapters and indexes for confirmed picking queue order, shared-SKU intersection and deterministic `(pickingId, lineSequence, moveId)` loading.
- [x] 3.4 Add persistence integration tests for unique source units, unique source lines, row-local state/timestamp constraints and source-agnostic inbound records.
- [x] 3.5 Add database-backed invariant tests for homogeneous picking/move states and exact assigned move-line coverage.

## 4. Register outbound intent as confirmed movements

- [x] 4.1 Replace `AcceptAllocationDemandCommand` with a source-neutral movement-group registration command carrying operation type, normalized source identity, scope, policy, scheduling and canonical lines.
- [x] 4.2 Replace `AllocationDemandRegistrar` with a transactional registrar that creates one confirmed picking and one confirmed move per canonical source line.
- [x] 4.3 Implement source-unit idempotency by comparing immutable picking and move content; return the existing group on an equal retry and reject drift without writing partial rows.
- [x] 4.4 Update the order source adapter and other stock-consuming adapters to resolve operation endpoints and canonicalize source identifiers before registration.
- [x] 4.5 Update registration unit and integration tests for identical replay, content conflict, multi-line canonical order, checked quantity aggregation and insufficient-supply persistence.

## 5. Plan and select from confirmed moves

- [x] 5.1 Replace demand planning snapshots and slice drafts with immutable picking/move snapshots and `MoveReservationDraft(moveId, stockQuantId, quantity)`.
- [x] 5.2 Rename and rewrite the pure planner as `MovementAssignmentPlanner`, preserving aggregate SKU sufficiency, FEFO and deterministic repeated-SKU distribution by move line sequence.
- [x] 5.3 Replace `PendingDemandSelection` with `PendingPickingSelection` and implement the exact earlier confirmed shared-SKU predecessor predicate.
- [x] 5.4 Replace demand queue keys, wake discovery and backlog age queries with owner/location/SKU picking queues ordered by `(enqueuedAt, pickingId)`.
- [x] 5.5 Add planner and selection tests for every short SKU, no partial drafts, disjoint queues, unavailable predecessors, deterministic FEFO and bounded one-picking selection.

## 6. Assign existing movements atomically

- [x] 6.1 Implement `AssignPickingUsecase` as the single transaction that locks picking, ordered moves and globally ordered quants, then revalidates proposal versions, precedence, scope, expiry, ATP and exact coverage.
- [x] 6.2 Persist move lines, reserve quant counters, transition the existing moves and picking to `ASSIGNED`, and publish the source-specific Outbox fact in that same transaction.
- [x] 6.3 Implement idempotent committed-result reconstruction from assigned picking, moves and move lines without incrementing reserved quantities again.
- [x] 6.4 Replace `PendingDemandAllocator`, commitment writer and target materializer call sites with the shared selection/planning/assignment responsibility for initial and wake paths.
- [x] 6.5 Add transaction rollback tests for counter, move-line, state and Outbox failures, proving the original picking and moves remain confirmed.
- [x] 6.6 Add hot-SKU and same-picking concurrency tests proving one winner, no duplicate move lines, exact counters and the common picking-to-move-to-quant lock order.

## 7. Complete reversible and physical lifecycle operations

- [x] 7.1 Rewrite release to lock the canonical group, decrement reserved counters, delete active move lines and return still-valid assigned moves/picking to `CONFIRMED`.
- [x] 7.2 Rewrite source cancellation and cancellation-operation idempotency around `pickingId`; require durable WMS reversible-execution confirmation before releasing assigned stock and cancelling the group.
- [x] 7.3 Rewrite outbound completion to validate exact retained move-line coverage, decrement on-hand and reserved quantities and transition moves/picking to `DONE` without deleting execution evidence.
- [x] 7.4 Publish durable release/cancellation/completion lifecycle facts with picking, move, quant and quantity snapshots in the state-change transaction; keep observations supplementary and add no reservation-history table.
- [x] 7.5 Add unit, persistence and race tests for release/reassign, cancellation before/after assignment, completion, idempotent retry and rejection of operations on DONE groups.

## 8. Cut contracts and downstream contexts to picking identity

- [x] 8.1 Replace allocation-demand and allocation-slice fields in application results and Integration Events with `pickingId`, `moveId`, source trace and batch-pick facts; update JSON contract fixtures.
- [x] 8.2 Update Ordering consumers and fulfillment projections to apply the move-centric assignment and completion facts without reading Inventory tables.
- [x] 8.3 Update WMS shipment creation, handoff and cancellation to use `pickingId` idempotently while retaining WMS-owned shipment, wave and task identities.
- [x] 8.4 Update Temporal contracts, activities and workflow snapshots to carry picking/move identity and reconstruct committed assignment results on retries.
- [x] 8.5 Update demo seed data, fulfillment read models, REST/query responses and frontend-facing payloads to show source, picking, moves, current batches and WMS execution separately.
- [x] 8.6 Introduce the move-centric contract version, deploy tolerant legacy/new consumers before switching producers, and retain the legacy reader for a later removal change after topic, Outbox and DLT replay windows expire.

## 9. Remove the superseded parallel models

- [x] 9.1 Remove `AllocationDemand`, `AllocationDemandLine`, their repositories/entities/mappers/controllers and demand-specific query/health types after all call sites have moved.
- [x] 9.2 Remove `Allocation`, `AllocationSlice`, commitment repositories/entities/mappers/materializers and allocation writer-mode gates introduced by the superseded worktree change.
- [x] 9.3 Rewrite reconciliation to compare picking/move homogeneity, exact assigned move-line coverage and quant reserved counters, with no historical slice joins.
- [x] 9.4 Update architecture tests to forbid allocation-demand and allocation-slice persistence while preserving Inventory-to-WMS and source-context boundaries.
- [x] 9.5 Use `rg` and schema inspection to prove no production code, migration constraint, event contract or operational query retains dual demand/allocation-slice semantics.

## 10. Documentation, formatting and verification

- [x] 10.1 Rewrite allocation/movement architecture documents and diagrams around `source -> StockPicking -> StockMove -> StockMoveLine/StockQuant -> WMS`, including source-location versus source-document terminology.
- [x] 10.2 Update operational runbooks, reconciliation guidance and migration/cutover documentation, including rollback limits and the single-writer gate.
- [x] 10.3 Run `cd backend && ./gradlew spotlessApply`, inspect the formatting diff, then run `cd backend && ./gradlew spotlessCheck`.
- [x] 10.4 Run focused inventory, integration-contracts, workflow-runtime, ordering and WMS unit/architecture test tasks until they pass.
- [x] 10.5 Run PostgreSQL migration and persistence integration suites, including FIFO, concurrency, Outbox and lifecycle scenarios.
- [x] 10.6 Run the complete backend test suite and the full project e2e suite; record commands, environment and results without claiming success for unexecuted suites.
- [x] 10.7 Run strict OpenSpec validation and `openspec-verify`, resolve every artifact/implementation mismatch, and leave the change ready for review and archive.
