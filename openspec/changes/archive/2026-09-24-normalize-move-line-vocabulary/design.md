## Context

The Inventory assignment flow has one canonical committed reservation detail: `StockMoveLine`, which links one
`StockMove` to one `StockQuant` and records the committed quantity. Before commitment, the planner emits the
same shape as `MoveReservationDraft`; after commitment, application results and lifecycle/query projections
call that shape `BatchPick`, `BatchSnapshot`, or `BatchReservation`.

Those names imply different domain concepts that do not exist at these boundaries. The planner value is not a
persisted draft, assignment has not yet become WMS physical picking, and a `StockQuant` is not itself a batch
aggregate. The rename crosses domain values, application results, JDBC query assembly, REST serialization,
publication factories, and tests, while versioned external event and HTTP JSON contracts must remain stable.

## Goals / Non-Goals

**Goals:**

- Make every internal representation visibly traceable to canonical `StockMoveLine` commitment detail.
- Preserve the distinction between proposed, committed, result, lifecycle before-image, and query-view stages.
- Keep planner output immutable, non-authoritative, and non-persistent.
- Keep existing integration-event classes, versions, and serialized `batchPicks`/`batches` fields unchanged.
- Keep the existing `/stock-operations` JSON `batches` property unchanged while normalizing its Java model.
- Preserve validation rules, deterministic ordering, allocation decisions, transactions, SQL, and persistence.

**Non-Goals:**

- Renaming `StockMoveLine`, changing its database representation, or introducing another reservation aggregate.
- Renaming `MovementPlanningSnapshot`, `MoveRequirement`, `AllocatableBatches`, or restructuring Demand/Supply.
- Renaming source adapters such as `OrderAllocationSourceLineEntity`.
- Introducing WMS pick, work, task, posting, or execution models.
- Publishing a new event version or changing REST response semantics.

## Decisions

### 1. Use stage-qualified MoveLine names

The internal lineage will be:

```text
MovementAssignmentProposal.ProposedMoveLine
                    | transactional commit
                    v
               StockMoveLine
                    |-- AssignedMoveLine  (committed assignment result)
                    |-- MoveLineSnapshot  (lifecycle before-image)
                    `-- MoveLineView      (read projection joined with quant attributes)
```

`MoveReservationDraft` becomes the top-level domain value `ProposedMoveLine`, and
`MovementAssignmentProposal` exposes `proposedMoveLines()`. The explicit `Proposed` qualifier prevents callers
from mistaking planner output for committed state.

`StockOperationAssignmentResult.AssignedMove` exposes `moveLines()` containing nested `AssignedMoveLine` values.
`StockOperationLifecycleSnapshot.MoveSnapshot` exposes `moveLines()` containing nested `MoveLineSnapshot`
values. `StockOperationView.Move` exposes `moveLines()` containing nested `MoveLineView` values.

Alternative considered: use `ReservationLine` throughout. Rejected because it hides the direct
`StockMoveLine` lineage and could be mistaken for a second reservation aggregate. Alternative considered:
retain `Batch*` names. Rejected because these values identify `StockQuant` rows and are not WMS physical picks.

### 2. Keep the canonical committed type unchanged

`StockMoveLine` remains the only persisted move-to-quant commitment detail. The proposal and projection types
remain value objects with no repository, identity, lifecycle state, or database table of their own.

Alternative considered: persist proposals or introduce a draft state on `StockMoveLine`. Rejected because it
would change optimistic planning into authoritative state and duplicate the transaction's version and ATP
revalidation responsibilities.

### 3. Translate normalized internals at existing publication boundaries

`OrderStockOperationAssignedPublicationFactory` will read internal `AssignedMoveLine` values but continue to
construct the existing `OrderAllocationCommittedIntegrationEvent.BatchPick` contract. Likewise,
`StockOperationLifecyclePublicationFactory` will read internal `MoveLineSnapshot` values but continue to
construct the existing `StockOperationLifecycleIntegrationEvent.BatchSnapshot` contract.

No contract-package type, event version, topic, payload property, aggregate reference, or publication target
will change. A future contract rename would require a separately versioned event and is not part of this change.

### 4. Preserve the REST wire name without a duplicate response hierarchy

`StockOperationView.Move` will use the Java component `moveLines` and nested type `MoveLineView`. Because this
query view is currently returned directly by `StockOperationRest`, the component will retain the serialized
JSON property name `batches` through an explicit Jackson property annotation. Controller tests will assert that
`moves[*].batches` remains present and `moves[*].moveLines` is not emitted.

Alternative considered: introduce a complete entrypoint response hierarchy solely to translate one renamed
property. Rejected for this vocabulary-only change because it duplicates the full operation read model. A
dedicated REST DTO can be introduced later if the HTTP model and application query model need independent
evolution.

### 5. Treat this as a mechanical rename with behavioral regression coverage

All constructors, validation messages, imports, factories, JDBC accumulators, tests, and documentation directly
using the renamed values will be updated. Existing domain, application, repository, integration, and controller
tests remain the behavioral baseline. Additional focused assertions will cover planner non-persistence lineage,
event contract mapping, and the legacy REST JSON property.

No SQL statement, transaction annotation, lock order, query count, or database schema will be modified.

## Risks / Trade-offs

- [Java and JSON names differ for `StockOperationView.Move`] -> Keep the legacy alias explicit beside the record
  component and cover both positive (`batches` exists) and negative (`moveLines` absent) serialization assertions.
- [A missed `Batch*` reference could leave mixed internal vocabulary] -> Search production code after the rename;
  allow `BatchPick` and `BatchSnapshot` only inside versioned contract types and boundary mapping code.
- [Mechanical rename accidentally changes validation or ordering] -> Preserve constructor invariants and run the
  complete Inventory unit/integration test suites plus formatting checks.
- [External consumers may infer new terminology from Java internals] -> Treat internal type names as
  implementation details; do not change existing wire names without a new versioned contract.

## Migration Plan

1. Rename the planner value and update proposal/planner/transaction consumers.
2. Rename assignment-result and lifecycle-snapshot nested values and update publication mappings.
3. Rename the query projection and JDBC assembly while retaining the REST JSON alias.
4. Update focused tests, documentation, and architecture searches.
5. Run Palantir formatting, module tests, broader backend tests, and OpenSpec verification.

Rollback is a source-level rename reversal. No persisted data or published schema migration is involved.

## Open Questions

None. Demand/Supply vocabulary and any future event-contract version are explicitly deferred.
