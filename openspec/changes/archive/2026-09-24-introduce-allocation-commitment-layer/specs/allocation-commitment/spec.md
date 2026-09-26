## ADDED Requirements

### Requirement: An allocation has a canonical identity distinct from its demand

A successful commitment SHALL create one immutable `Allocation` header with its own `allocationId`, the `allocationDemandId` it covers, the authorized policy code, commit time, and optimistic-lock version. The demand id SHALL identify accepted source intent; the allocation id SHALL identify one committed demand-to-supply decision. A planner proposal SHALL NOT contain or reserve an allocation id.

One demand MAY have multiple historical allocations after release and reallocation. The schema SHALL NOT make `allocationDemandId` unique, while the enabled ship-complete policy SHALL prevent more than one simultaneously reserved full-coverage allocation for the same demand.

#### Scenario: A committed allocation does not reuse demand identity

- **GIVEN** active demand `demand-1` has a complete eligible proposal
- **WHEN** the proposal commits
- **THEN** a new allocation with its own id references `demand-1`

#### Scenario: Reallocation preserves both decisions

- **GIVEN** one allocation for a demand was released
- **WHEN** the demand is allocated again
- **THEN** a new allocation id is created and the released allocation remains queryable

### Requirement: Allocation slices are the canonical reservation facts

Every committed pick SHALL be represented by an `AllocationSlice` with a positive integer quantity, one allocation id, one allocation-demand-line id, and one stock-quant id. Picks for the same allocation, demand line, and quant SHALL be merged before persistence, and that triple SHALL be unique. The referenced line and quant SHALL have matching owner, source location, and SKU scope.

Allocation slices SHALL be the canonical facts for demand coverage, reservation identity, and reservation history. A movement or movement line MAY trace a slice but SHALL NOT become an alternative reservation ledger or change coverage merely by being created, regrouped, cancelled, or deleted.

#### Scenario: One demand line uses two supply batches

- **GIVEN** a demand line is covered from two stock quants
- **WHEN** its allocation commits
- **THEN** two positive slices link the line to the two quants and sum to the committed line coverage

#### Scenario: Execution reshaping does not rewrite commitment

- **GIVEN** reserved slices have been materialized as outbound movement lines
- **WHEN** execution grouping changes without release or consumption
- **THEN** the slices and demand coverage remain unchanged

### Requirement: Slice lifecycle preserves release and consumption history

A new slice SHALL start in `RESERVED`. The only allowed transitions SHALL be `RESERVED` to `RELEASED` and `RESERVED` to `CONSUMED`; both terminal states SHALL be immutable. A released or consumed slice SHALL NOT be reopened, have its quantity changed, or be deleted to represent a later allocation.

A `RESERVED` slice SHALL have no terminal timestamp. A `RELEASED` slice SHALL have only `releasedAt`, and a `CONSUMED` slice SHALL have only `consumedAt`. Allocation lifecycle SHALL be projected from slice states rather than duplicated in a mutable header status.

#### Scenario: Release retains audit history

- **GIVEN** a reserved slice is still reversible
- **WHEN** its allocation is released
- **THEN** the slice becomes `RELEASED`, records its release time, and remains queryable

#### Scenario: A terminal slice cannot be reused

- **GIVEN** a slice is released or consumed
- **WHEN** a caller attempts to reserve it again or alter its quantity
- **THEN** the operation is rejected and a later allocation must create a new slice

### Requirement: Quant reservation counters agree with reserved slices

For every stock quant, `reservedQuantity` SHALL equal the sum of quantities of its `RESERVED` allocation slices. Creating a reserved slice and incrementing its quant counter, releasing a slice and decrementing the counter, or consuming a slice and applying the corresponding reserved and physical quantity changes SHALL occur in one local transaction.

All reserve, release, and consume paths SHALL lock demand or allocation state first and then lock affected quants in the shared `StockWriteOrder`. Optimistic version conflicts or invariant failures SHALL roll back both slice and quant changes. Reconciliation MAY detect drift but SHALL NOT replace transactional enforcement on the command path.

#### Scenario: Reservation commits both representations

- **WHEN** a slice reserving quantity 4 commits against a quant
- **THEN** the slice is `RESERVED` and the quant's reserved counter increases by 4 atomically

#### Scenario: A conflicting writer leaves no drift

- **GIVEN** two transactions contend for the same quant version
- **WHEN** one loses its optimistic-lock update
- **THEN** its allocation and slices roll back and the counter still equals the reserved-slice sum

### Requirement: One commitment transaction materializes a coherent target

A commitment transaction SHALL reload and validate the active demand, exact FIFO predecessor relation, demand version, open quantities, affected quant versions, inventory scope, FEFO eligibility, and enabled policy. It SHALL then create the allocation and slices, update quant reservation counters, materialize Inventory-owned outbound targets from those committed facts, and append the committed fact to Outbox.

Each outbound movement line created by the current single-leg materializer SHALL reference exactly one allocation slice and have the same quant and quantity. Any validation, persistence, target-materialization, or Outbox failure SHALL roll back allocation, slices, counters, movements, movement lines, and event together.

#### Scenario: A complete proposal becomes one coherent commitment

- **GIVEN** a complete proposal remains valid under current demand, precedence, and supply state
- **WHEN** it commits
- **THEN** allocation facts, reserved counters, execution targets, and one Outbox fact become visible together

#### Scenario: Target materialization failure leaves no commitment

- **GIVEN** slice creation succeeds but one outbound movement line cannot be persisted
- **WHEN** the transaction rolls back
- **THEN** no allocation, slice, counter change, target, or completion fact remains

### Requirement: Release is reversible and idempotent by allocation identity

Release SHALL address one allocation and an idempotent operation id. It SHALL proceed only when all slices remain `RESERVED` and all related targets are reversible, after any required external warehouse cancellation has been durably confirmed. One local transaction SHALL transition all slices to `RELEASED`, decrement quant counters, cancel reversible targets, and publish the outcome.

A retry of a completed release SHALL return its recorded result without decrementing counters again. A consumed allocation or an allocation with irreversible warehouse work SHALL NOT be released by this operation.

#### Scenario: A reversible allocation is released once

- **GIVEN** a reserved allocation has reversible targets and confirmed external cancellation
- **WHEN** its release operation is retried
- **THEN** every slice is released and every quant counter is decremented exactly once

#### Scenario: Consumed stock cannot be released

- **GIVEN** an allocation has consumed slices
- **WHEN** release is requested
- **THEN** release is rejected and physical compensation remains a separate capability

### Requirement: Consumption is complete and idempotent by allocation identity

Consumption SHALL address one allocation and the complete persisted set of its outbound movement ids. The transaction SHALL reject a subset, superset, foreign movement, released slice, or inconsistent quant reference. For valid completion it SHALL transition every `RESERVED` slice to `CONSUMED`, reduce reserved counters and physical stock according to existing stock semantics, and complete related movements in the shared lock order.

Reprocessing the same completed allocation and movement set SHALL return idempotent success without consuming stock twice.

#### Scenario: Complete warehouse execution consumes one allocation

- **GIVEN** WMS reports the complete movement set for a reserved allocation
- **WHEN** Inventory accepts completion
- **THEN** all slices become consumed, stock changes once, and all related movements complete

#### Scenario: A partial movement set is rejected

- **GIVEN** an allocation owns three outbound movements
- **WHEN** completion names only two
- **THEN** no slice, counter, physical quantity, or movement state changes
