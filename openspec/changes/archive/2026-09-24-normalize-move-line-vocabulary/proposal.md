## Why

Inventory assignment currently describes the same move-to-quant reservation detail as a draft, a batch pick,
and a batch snapshot depending on the stage. These names obscure its direct lineage to `StockMoveLine` and
incorrectly suggest either a persisted draft lifecycle or WMS physical picking, making the planning and
commitment boundary harder to follow.

## What Changes

- Rename the planner-only `MoveReservationDraft` value to `ProposedMoveLine` so its non-persistent role and
  intended committed form are explicit.
- Rename internal committed-result detail from `BatchPick`/`batchPicks` to `AssignedMoveLine`/`moveLines`.
- Rename internal lifecycle before-image detail from `BatchSnapshot`/`batches` to
  `MoveLineSnapshot`/`moveLines`.
- Rename the internal stock-operation query detail from `BatchReservation`/`batches` to
  `MoveLineView`/`moveLines`.
- Preserve existing versioned integration-event types and serialized fields such as `batchPicks` and `batches`;
  publication adapters will translate from the normalized internal vocabulary.
- Preserve allocation decisions, transaction boundaries, database schema, query behavior, and REST/event wire
  compatibility.
- Defer Demand/Supply model restructuring and source-adapter naming cleanup to separate changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `stock-allocation`: Require planning, commitment, result, lifecycle snapshot, and query representations of
  move-to-quant reservation detail to retain an explicit `StockMoveLine` lineage while preserving published
  contracts and runtime behavior.

## Impact

- Affected module: `backend/inventory-context`.
- Affected internal types include `MovementAssignmentProposal`, `StockOperationAssignmentResult`,
  `StockOperationLifecycleSnapshot`, `StockOperationView`, their factories/mappers, and related tests.
- No database migration, table or column rename, integration-event version change, serialized field rename,
  REST contract change, dependency change, or allocation algorithm change.
