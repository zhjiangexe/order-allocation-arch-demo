## 1. Commit Boundary Vocabulary

- [x] 1.1 Rename `StockOperationAssignmentTransaction` to `StockAllocationCommitter`, change `execute` to `commit`, and migrate all production, test, architecture and transaction-pointcut references.
- [x] 1.2 Rename `QuantReservationSet` and its nested vocabulary to the stage-neutral `MoveQuantAllocationSet` model across assignment, release and completion.
- [x] 1.3 Replace residual movement, snapshot, batch, picking and generic use-case locals with current StockMove and Allocation vocabulary.
- [x] 1.4 Rename `StockOperationAssigner` to `StockOperationAssignmentCoordinator` across production and active tests.
- [x] 1.5 Rename `LockedStockOperation` to `StockOperationComposite`, move lock semantics to `ForUpdate` loader vocabulary, and update lifecycle callers.
- [x] 1.6 Rename Inventory aggregate persistence ports and adapters from `Store` to their full `Repository` names.
- [x] 1.7 Rename Inventory read-projection `Query` ports and JDBC adapters to full role-specific `Repository` names without merging interfaces.
- [x] 1.8 Replace shortened repository fields and constructor parameters with their complete lower-camel type names across affected production and tests.

## 2. Commit Flow Structure

- [x] 2.1 Rewrite `StockAllocationCommitter.commit` as the ordered validate, lock, replay, revalidate, quant-lock, apply, result and publish application script.
- [x] 2.2 Extract `StockOperationAssignmentResultFactory` for deterministic move-line grouping, operation-type enrichment and committed-result construction.
- [x] 2.3 Preserve synchronous publication inside the committer transaction and add or update tests proving replay, stale-proposal, lock-order, exact-coverage and rollback behavior.

## 3. Architecture and Documentation

- [x] 3.1 Update `InventoryBoundaryArchitectureTest` to protect the new committer and stage-neutral lifecycle working model without adding a generic commit abstraction.
- [x] 3.2 Update active allocation Markdown, diagrams and the AllocateOrderUsecase deep-dive HTML to show `StockAllocationCommitter.commit` and the revised commit stages.
- [x] 3.3 Search production, active tests and active documentation for retired commit-boundary and affected legacy local vocabulary, while leaving historical OpenSpec artifacts and immutable migrations unchanged.
- [x] 3.4 Update architecture checks and active documentation for the coordinator and composite vocabulary.
- [x] 3.5 Update architecture checks and active documentation for the unified Repository naming policy.

## 4. Verification

- [x] 4.1 Run `spotlessApply`, `spotlessCheck`, Inventory unit and architecture tests, and assignment persistence, rollback and concurrency SIT.
- [x] 4.2 Run the full backend test suite and strict OpenSpec validation.
- [x] 4.3 Run all E2E scenarios, verify implementation against proposal, design, specs and tasks, and confirm no test containers remain.
- [x] 4.4 Run formatting, Inventory tests, affected assignment SIT, strict OpenSpec validation and a retired-name scan.
- [x] 4.5 Run formatting, Inventory and full backend tests, affected Spring SIT, strict OpenSpec validation and Store/Query/short-property scans.
