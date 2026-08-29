## 1. Immutable Planning Contract

- [x] 1.1 Replace `MovementPlanningSnapshot` and nested `MoveRequirement` with immutable `StockOperationDemand` and `StockMoveDemand` value objects while preserving operation/move version and ordering invariants.
- [x] 1.2 Add immutable `StockQuantSupply` and `StockAllocationSupply` value objects with owner/location/SKU coverage and positive-ATP invariants, then remove `AllocatableBatches`.
- [x] 1.3 Replace `MovementAssignmentProposal` with immutable `StockAllocationProposal` while preserving ready/insufficient exact-coverage behavior and `ProposedMoveLine` vocabulary.

## 2. Query and Planning Boundaries

- [x] 2.1 Replace `AllocatableStockQuery` with the allocation-owned `StockAllocationSupplyQuery` port.
- [x] 2.2 Replace `JdbcAllocatableStockQuery` with `JdbcStockAllocationSupplyQuery`, retaining one deterministic FEFO SQL read and mapping rows directly to `StockQuantSupply` without constructing `StockQuant` aggregates.
- [x] 2.3 Update `StockAllocationPlanner` and `MovementAssignmentPlanner` to consume the new Demand/Supply types and rename internal batch vocabulary without changing SHIP_COMPLETE or FEFO decisions.
- [x] 2.4 Update `AssignmentCandidateQuery`, `StockOperationAssigner`, `StockOperationAssignmentTransaction`, and lifecycle helpers to use the normalized contract while preserving optimistic plan and locked commit behavior.

## 3. Tests and Cleanup

- [x] 3.1 Migrate planner and value-object tests to cover immutable supply rows, explicit empty SKU groups, scope validation, exact shortfalls and canonical FEFO output.
- [x] 3.2 Migrate assignment orchestration, transaction, persistence, rollback, concurrency, seed and quant-query integration tests to the new query and contract types.
- [x] 3.3 Update `InventoryBoundaryArchitectureTest` to protect the normalized decision core and forbid mutable aggregate dependencies in pure Demand/Supply/Proposal types.
- [x] 3.4 Move any unique generic POC behavior examples into production planner tests and remove the `allocation/poc/demandsupply` test-only architecture.

## 4. Documentation and Verification

- [x] 4.1 Update allocation architecture Markdown, diagrams and `allocate-order-usecase-deep-dive.html` so source, Demand, Supply, Proposal and commit names match production code.
- [x] 4.2 Run `spotlessApply`, `spotlessCheck`, Inventory unit/architecture tests and monolith persistence, rollback and concurrency SIT.
- [x] 4.3 Run the full backend test suite and repository-wide retired-name checks.
- [x] 4.4 Run all E2E scenarios and strict OpenSpec validation, then verify implementation against proposal, design, specs and tasks.
