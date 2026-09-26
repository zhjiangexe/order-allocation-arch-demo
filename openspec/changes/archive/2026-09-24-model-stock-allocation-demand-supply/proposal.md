## Why

The stock allocation planner is pure, but its contract still names operation demand as a generic movement snapshot and passes mutable
`StockQuant` aggregates inside an `AllocatableBatches` wrapper. This obscures the demand/supply boundary and allows read-side planning to
depend on command-side inventory state, so the contract should be made explicit before additional allocation strategies appear.

## What Changes

- Rename the operation-level planning input to `StockOperationDemand` and its move-level facts to `StockMoveDemand`.
- Replace `AllocatableBatches` with an immutable `StockAllocationSupply` containing immutable `StockQuantSupply` rows rather than mutable
  `StockQuant` aggregates.
- Rename the pure planning result to `StockAllocationProposal` while retaining `ProposedMoveLine` as its move-to-quant output row.
- Rename the supply query port and JDBC adapter around `StockAllocationSupply`, and map SQL rows directly to immutable supply rows.
- Update assignment orchestration, planner internals, tests, architecture documentation, and the explanatory HTML to use the normalized
  vocabulary.
- Retire the test-only generic Demand/Supply POC after any unique behavior examples are covered by production planner tests; do not promote
  its generic interfaces into production.
- Preserve SHIP_COMPLETE, FEFO, precedence, locking, revalidation, reservation, persistence, query count, and external behavior.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `stock-allocation`: Require the pure planning boundary to consume explicit immutable stock-operation demand and stock-allocation supply
  projections, produce a stock-allocation proposal, and keep mutable aggregates on the transactional command side.

## Impact

- Affected module: `backend/inventory-context` and its monolith integration tests.
- Affected planning contract: `StockAllocationPlanner`, its implementation, candidate query result, supply query, proposal, and assignment
  orchestration.
- Affected documentation: OpenSpec stock-allocation requirements and allocation execution documentation.
- No database migration, REST API, integration event, workflow contract, WMS model, transaction boundary, SQL count, or allocation decision
  change.
