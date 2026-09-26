## 1. Source Snapshot Model

- [x] 1.1 Replace `AllocationExecutionIntent` with a resolved destination input and remove picking policy from demand acceptance
- [x] 1.2 Persist immutable `destinationLocationId` on `AllocationDemand` and map it through JPA
- [x] 1.3 Rewrite accepted-content replay equality to compare demand and line snapshots only
- [x] 1.4 Update order source adapter and demand unit tests for source-only acceptance

## 2. Demand Registration

- [x] 2.1 Remove outbound `StockPicking` and placeholder `StockMove` creation from `AllocationDemandRegistrar`
- [x] 2.2 Remove obsolete registrar movement/picking dependencies and execution replay validation
- [x] 2.3 Verify acceptance is atomic, idempotent, and creates only demand plus demand lines

## 3. Queue and Precedence

- [x] 3.1 Replace execution-aware pending SQL predicates with demand-status raw candidate queries
- [x] 3.2 Represent precedence as one earlier intersecting-SKU blocker lookup and remove per-SKU queue-position structures
- [x] 3.3 Simplify `PendingDemandSelection` to load and lock one current demand, check its blocker, then load all required stock
- [x] 3.4 Remove obsolete pending-execution anomaly and required-queue-head repository APIs
- [x] 3.5 Add repository tests for intersecting-SKU FIFO, disjoint-SKU independence, and candidates without movements
- [x] 3.6 Verify the intersecting-SKU predecessor query uses the pending-scope and line-SKU indexes with a representative query plan

## 4. Allocation Transaction

- [x] 4.1 Replace `AllocationCommitData` and pre-existing execution validation with target materialization inputs
- [x] 4.2 Make the commit transaction reserve FEFO plan quantities and create one assigned `StockMove` per demand line
- [x] 4.3 Create reservation `StockMoveLine` records while materializing moves and enforce exact quantity coverage
- [x] 4.4 Mark the demand allocated and publish Outbox only after all target records are coherent
- [x] 4.5 Simplify `PendingDemandAllocator` into select, plan, and atomic materialize phases
- [x] 4.6 Add rollback, optimistic-lock, all-or-nothing, and direct-ASSIGNED movement tests

## 5. Allocation Identity and WMS Boundary

- [x] 5.1 Publish allocation-demand id as `allocationId` in `OrderAllocationCommittedIntegrationEvent`
- [x] 5.2 Add `ALLOCATION_DEMAND` fulfillment aggregate type and update workflow completion routing
- [x] 5.3 Update WMS handoff and shipment idempotency tests to use allocation-demand identity
- [x] 5.4 Remove outbound inventory picking assumptions from completion and query projections
- [x] 5.5 Load and validate the complete outbound movement set by allocation-demand id during completion

## 6. Cancellation and Read Side

- [x] 6.1 Make pending cancellation transition only the demand without execution cleanup
- [x] 6.2 Address allocated reservations and movements by allocation-demand id during confirmed cancellation
- [x] 6.3 Simplify allocation-demand query views to show source snapshot, process status, and materialized target separately
- [x] 6.4 Remove pending-execution consistency health checks and retain allocated-target invariant checks
- [x] 6.5 Update cancellation, REST query, and health tests for the separated lifecycle

## 7. Database Baseline

- [x] 7.1 Rewrite the pre-release allocation-demand baseline migrations to persist resolved destination
- [x] 7.2 Remove baseline backfill and constraints that require placeholder movements for pending demands
- [x] 7.3 Preserve and enforce allocated movement-to-demand-line integrity and one-move-per-line constraints
- [x] 7.4 Update schema and persistence integration tests for pending-without-target and allocated-with-target invariants

## 8. Architecture Documentation

- [x] 8.1 Rewrite `docs/architecture/allocation-precedence-policy.md` around Source, Process, and Target ownership
- [x] 8.2 Document the exact shared-SKU predecessor relation, transaction boundary, and prohibited duplicate truths
- [x] 8.3 Document the pre-release migration precondition and stop condition for deployed picking-id contracts

## 9. Verification

- [x] 9.1 Run Palantir formatting with `./gradlew spotlessApply` and verify with `./gradlew spotlessCheck`
- [x] 9.2 Run focused inventory allocation and integration-contract unit tests
- [x] 9.3 Run allocation persistence and end-to-end integration tests
- [x] 9.4 Run `openspec validate separate-allocation-source-process-target --strict` and reconcile completed task checkboxes
