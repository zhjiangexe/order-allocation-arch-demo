## Why

The allocation apply boundary is behaviorally correct but its name and implementation still mix an implementation mechanism, legacy
movement vocabulary, proposal validation, mutation, result assembly and publication. Now that planning is expressed as a
`StockAllocationProposal`, the commit path should reveal that proposal-to-authoritative-state transition directly without adding a
second architecture.

## What Changes

- Rename the internal apply boundary from `StockOperationAssignmentTransaction.execute(...)` to
  `StockAllocationCommitter.commit(...)`; keep transactionality as an implementation guarantee rather than part of the business name.
- Rename the shared initial/wake facade from `StockOperationAssigner` to `StockOperationAssignmentCoordinator` so its name reveals that
  it orchestrates candidate selection, pure planning and commit rather than performing all assignment behavior itself.
- Rewrite the commit method as a short, ordered application script: validate request, lock current state, handle replay, revalidate the
  proposal, lock selected quants, apply the allocation, assemble the result and publish it.
- Restore the Inventory persistence-port convention to the `Repository` suffix for both aggregate persistence and read projections;
  distinguish their roles through full type names such as `StockOperationRepository`, `StockOperationViewRepository` and
  `StockOperationAssignmentCandidateRepository`.
- Use the complete lower-camel type name for repository fields and constructor parameters instead of shortened `store`, `query`,
  `candidateQuery`, `supplyQuery` or generic `repository` properties, and replace residual `movement`, `snapshot`, `batch`, `picking`
  and generic `execute` names in the affected paths.
- Rename `QuantReservationSet` to a stage-neutral move-to-quant allocation mapping because it is constructed from both proposed move
  lines and committed move lines.
- Rename `LockedStockOperation` to `StockOperationComposite`; keep database-lock acquisition in `ForUpdate` loader method vocabulary
  because the ephemeral composition itself neither acquires nor owns a lock and is not a persisted aggregate.
- Extract assignment-result construction from the committer while preserving synchronous Outbox publication inside the same database
  transaction.
- Preserve all assignment, SHIP_COMPLETE, FEFO, precedence, locking, idempotency, state-transition, persistence and integration-event
  behavior.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `stock-allocation`: Clarify the internal proposal-commit boundary and require its implementation vocabulary and responsibility split
  to match the allocation and stock-movement model without changing observable allocation behavior.

## Impact

The change affects Inventory allocation application services, lifecycle working models, architecture and behavior tests, monolith
assignment SIT wiring, and active allocation documentation. It changes no database schema, persisted data, external API, integration
event payload, query count or stock lifecycle rule.
