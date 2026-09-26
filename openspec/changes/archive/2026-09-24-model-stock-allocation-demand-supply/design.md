## Context

The assignment flow already has four operational stages: load one operation-level candidate, load eligible FEFO stock, calculate a
complete move-to-quant proposal, and transactionally revalidate and commit it. The first and third stages use immutable allocation value
objects, but their names still describe implementation-era movement snapshots. The supply stage is less isolated: `AllocatableBatches`
copies its collections but contains mutable `StockQuant` aggregates, so the pure planner can observe command-side objects and the word
`batch` incorrectly hides the canonical quant identity.

The normalized contract must remain Inventory-specific. A WMS wave planner has different units, outputs and commit semantics, so this
change must not turn the test-only generic Demand/Supply POC into production abstractions.

## Goals / Non-Goals

**Goals:**

- Make the planner contract read as `StockOperationDemand + StockAllocationSupply -> StockAllocationProposal`.
- Preserve canonical source lineage at row level through `StockMoveDemand`, `StockQuantSupply`, and `ProposedMoveLine`.
- Prevent the planning path from receiving mutable `StockOperation`, `StockMove`, or `StockQuant` aggregates.
- Keep the supply query deterministic, set-based and FEFO ordered with one SQL query.
- Preserve the transaction as the only boundary that locks mutable quants and creates authoritative move lines and reservations.

**Non-Goals:**

- Do not change SHIP_COMPLETE, FEFO, predecessor, retry, locking, reservation, state-transition or event behavior.
- Do not add generic `Demand`, `Supply`, `AllocationEngine`, strategy registry or WMS parent interfaces.
- Do not add a persisted proposal, supply snapshot table, quant-version comparison or new reservation ledger.
- Do not rename `StockOperation`, `StockMove`, `StockMoveLine`, `StockQuant`, or the physical `stock_pools` table.
- Do not change query count, database schema, external APIs or integration events.

## Decisions

### 1. Normalize the complete pure-planning contract

The port becomes:

```java
StockAllocationProposal plan(
        StockOperationDemand demand,
        StockAllocationSupply supply);
```

`StockOperationDemand` is one operation-level atomic demand group and contains ordered `StockMoveDemand` rows. It retains operation and
move versions because the proposal is optimistic and the transaction must reject a changed operation group. `StockAllocationSupply` is
the eligible shared stock offered to this planning attempt and contains `StockQuantSupply` rows grouped by demanded SKU.

`StockAllocationProposal` remains non-authoritative. Its ready form contains `ProposedMoveLine` rows; its insufficient form contains exact
SKU shortfalls and no partial rows. The proposal is not stored.

Alternative considered: retain the current class names and document Demand/Supply only in comments. Rejected because the public port
would continue to expose movement/batch implementation vocabulary and would not reveal the source/process/target boundary.

### 2. Model supply as an allocation-owned immutable projection

`StockAllocationSupply` and `StockQuantSupply` live under the allocation domain value-object package because they exist solely as input
to `StockAllocationPlanner`. A `StockQuantSupply` carries only facts needed to identify, scope, order and consume a candidate in planning:

```text
stockQuantId, ownerId, locationId, skuCode, inDate, expiryDate, availableToPromise
```

The row validates required identity/date fields and positive ATP. The enclosing supply validates one owner/location scope, correct SKU
group membership, and explicit groups for all requested SKUs; an empty group continues to mean a complete query result with no supply.
Its collections are immutable and preserve the JDBC adapter's canonical FEFO order.

The projection deliberately excludes mutable on-hand/reserved counters and aggregate behavior. It also excludes `capturedAt` and quant
version: neither would make a proposal authoritative, and the existing transaction already reloads selected quants under lock and
validates current scope, expiry and ATP before reserving. Operation and move versions remain in the proposal because those source facts
must match exactly; quant availability is revalidated from live command aggregates instead of compared to a read-model version.

Alternative considered: rename `AllocatableBatches` while continuing to hold `StockQuant`. Rejected because collection immutability does
not make mutable elements immutable and would leave the important boundary violation intact.

### 3. Make the supply read port consumer-owned

Rename the port to `StockAllocationSupplyQuery` and place it with the allocation assignment application ports. Rename the JDBC adapter to
`JdbcStockAllocationSupplyQuery` and place it under allocation query infrastructure. The adapter continues to issue one set-based query
for owner, source location and demanded SKUs, filters expired or exhausted rows, and orders by SKU, expiry date, in-date and quant ID.

The row mapper constructs `StockQuantSupply` directly. It must not instantiate `StockQuant`; the balance command store remains the only
path used by the assignment transaction to reload and mutate quants.

Alternative considered: leave the port under `balance.application.query`. Rejected because this projection is shaped by the allocation
consumer and naming it `StockAllocationSupply` inside the balance domain would leak allocation-process vocabulary into the balance
model.

### 4. Keep algorithm and commitment behavior stable

`MovementAssignmentPlanner` continues to implement `StockAllocationPlanner` and keeps its one SHIP_COMPLETE/FEFO implementation. Its
internal `batch` variables become `supply` variables, and its queue consumes `StockQuantSupply` rather than `StockQuant`. No strategy
router is introduced until a second production policy exists.

`StockOperationAssignmentTransaction` changes only its proposal type. It continues to create `QuantReservationSet`, lock live quants in
global order, validate current ATP and scope, reserve, create `StockMoveLine`, assign moves and operation, and publish through the same
transaction.

### 5. Retire the generic POC instead of preserving a second architecture

Any unique SHIP_COMPLETE or FEFO examples in `allocation/poc/demandsupply` are moved to production planner tests. The POC types and tests
are then deleted. This prevents generic `AllocationDemand`, `AllocationSupply`, and `AllocationEngine` vocabulary from competing with the
typed production contract.

## Risks / Trade-offs

- [The rename touches many unit and integration-test imports] → Apply it atomically and use architecture tests plus repository-wide search
  to prove the retired names are absent from production and active documentation.
- [A direct JDBC projection can drift from `StockQuant` ATP semantics] → Select the existing on-hand and reserved columns and compute ATP
  in the adapter through a focused mapper test; keep final authoritative validation in `StockOperationAssignmentTransaction`.
- [The word `Supply` could imply committed inventory] → Document and test that `StockAllocationSupply` is only eligible planning input and
  that a ready proposal still crosses lock-and-revalidate before reservation.
- [Future inbound or transfer supply will not fit `StockQuantSupply`] → Add distinct supply row types only when the planner can truly use
  them; the current outer name leaves that evolution open without a speculative parent interface.

## Migration Plan

1. Add the new immutable demand, supply and proposal value objects and their invariants.
2. Move/rename the supply query port and adapter, mapping SQL rows directly to `StockQuantSupply`.
3. Rewire the planner, candidate result, assigner and transaction to the normalized contract.
4. Migrate characterization, architecture and integration tests; retire the POC after equivalent coverage exists.
5. Update active documentation and run formatting, Inventory tests, backend tests, SIT and E2E.

Rollback is a source-only revert. No stored data or external contract requires migration.

## Open Questions

None.
