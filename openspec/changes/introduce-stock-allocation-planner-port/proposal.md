## Why

The Inventory assignment pipeline already separates candidate loading, supply loading, pure planning, and transactional commitment, but its application orchestration depends directly on the only concrete planner. Introducing an Inventory-specific planner port now preserves that boundary for future stock-allocation strategies without prematurely coupling Inventory allocation to WMS wave planning through a generic demand/supply interface.

## What Changes

- Add a narrow `StockAllocationPlanner` domain interface whose contract maps one `MovementPlanningSnapshot` and one `AllocatableBatches` supply snapshot to a `MovementAssignmentProposal`.
- Make the existing deterministic `MovementAssignmentPlanner` implement the Inventory-specific port without changing SHIP_COMPLETE or FEFO behavior.
- Make `StockOperationAssigner` depend on the port rather than the concrete implementation.
- Add architecture-focused verification for the interface, implementation, and application dependency direction.
- Keep the test-only generic Demand/Supply POC isolated; do not introduce a shared Inventory/WMS planner parent or generic matching kernel.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `stock-allocation`: Require Inventory application orchestration to invoke pure stock planning through an Inventory-owned planner boundary while preserving existing assignment decisions and commit semantics.

## Impact

- Affected module: `backend/inventory-context`.
- Affected production types: `MovementAssignmentPlanner` and `StockOperationAssigner`.
- New production type: `StockAllocationPlanner` in the Inventory allocation domain service package.
- No database, event contract, REST API, WMS model, transaction, query count, or allocation behavior change.
