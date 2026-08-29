## Context

`StockOperationAssigner` currently performs the correct application orchestration: it obtains one immutable movement candidate, obtains one immutable allocatable-stock snapshot, calls the repository-free `MovementAssignmentPlanner`, and crosses the transactional assignment boundary only for a ready proposal. The concrete planner is pure and deterministic, but the application service depends directly on that implementation.

The next likely changes are additional Inventory allocation policies. WMS wave planning may also emerge, but it allocates warehouse work to execution capacity and has different inputs, atomicity, outputs, and commit semantics. A cross-context `AllocationEngine<D, S, P>` would therefore capture syntactic similarity rather than substitutable domain behavior.

## Goals / Non-Goals

**Goals:**

- Establish one narrow, Inventory-owned planning port at the existing pure-planning boundary.
- Preserve the current typed demand-group and supply-snapshot inputs rather than replacing them with bare lists.
- Allow future Inventory planning implementations without changing application orchestration.
- Preserve every existing SHIP_COMPLETE, FEFO, precedence, transaction, and persistence behavior.

**Non-Goals:**

- Do not create common `AllocationDemand`, `AllocationSupply`, or `AllocationEngine` production interfaces.
- Do not define a WMS `WavePlanner` or make WMS depend on Inventory allocation types.
- Do not introduce strategy discovery, a planner registry, a generic matching kernel, or runtime policy selection.
- Do not change queries, SQL counts, commits, locks, database schema, or external contracts.

## Decisions

### 1. The port is Inventory-specific and uses existing boundary types

Add `StockAllocationPlanner` under the Inventory allocation domain service package with exactly one operation:

```java
MovementAssignmentProposal plan(
        MovementPlanningSnapshot snapshot,
        AllocatableBatches batches);
```

`MovementPlanningSnapshot` remains one operation-level atomic demand group and `AllocatableBatches` remains the eligible supply snapshot. The port does not flatten either boundary into `List<>` and does not accept repositories, transactions, clocks, or application commands.

Alternative considered: promote the POC `AllocationEngine<K, D, S, P>`. Rejected because Inventory allocation and WMS wave planning are not substitutable domain behaviors and their group boundaries are lost in bare lists.

### 2. The current planner is the default implementation

`MovementAssignmentPlanner` implements `StockAllocationPlanner` and retains its current Spring component registration and algorithm unchanged. No adapter or delegation class is added because it would only rename the same call.

Alternative considered: rename `MovementAssignmentPlanner` to `ShipCompleteFefoPlanner`. Rejected in this change because it expands the naming and policy-selection scope without a second production strategy.

### 3. Application orchestration depends only on the port

`StockOperationAssigner` changes its field and constructor parameter from `MovementAssignmentPlanner` to `StockAllocationPlanner`. The source selection, supply query, ready check, and transaction call remain identical.

This is the future extension seam: another Inventory implementation can satisfy the same contract or a later strategy router can implement the port. Callers do not need to know which mechanism computes the proposal.

### 4. Verify dependency direction without duplicating algorithm tests

Existing `MovementAssignmentPlannerTest` cases continue to characterize SHIP_COMPLETE and FEFO behavior. A focused architecture/unit test verifies that the concrete planner implements the port and that `StockOperationAssigner` receives the port rather than the concrete class. The test-only generic Demand/Supply POC remains outside production and is not treated as the target architecture.

## Risks / Trade-offs

- [Only one implementation exists, so the interface adds one production type] → Keep the port to one method with existing domain types and no framework methods.
- [Spring may become ambiguous after a future second implementation is added] → Resolve that only when a selection policy exists, using a domain-specific router or explicit bean selection.
- [A future generic kernel could be harder to introduce] → Both planners can later adapt their typed snapshots to a technical kernel internally; the stable domain port does not prevent that extraction.
- [The interface could be mistaken for a cross-context parent] → Document and test that it is Inventory-owned and does not extend the POC contracts or reference WMS types.

## Migration Plan

1. Add the port and make the current planner implement it.
2. Rewire the application service constructor to the port.
3. Run formatter, Inventory unit tests, and OpenSpec validation.

Rollback consists of reverting the constructor type and removing the interface declaration; there is no persisted or externally visible migration.

## Open Questions

None. Runtime selection between multiple Inventory strategies remains a future capability triggered by a real second implementation.
