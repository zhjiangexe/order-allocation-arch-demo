## ADDED Requirements

### Requirement: Allocation planning produces a non-authoritative proposal

The repository-free planner SHALL produce an immutable `AllocationProposal` containing the allocation-demand id, demand version, authorized policy code, proposed demand-line-to-quant quantities, and all missing quantities. A proposal SHALL have no allocation id, SHALL NOT be persisted by this capability, and SHALL NOT reserve stock, materialize execution, or publish an outcome.

An insufficient ship-complete proposal SHALL contain no committable slices. A commit boundary SHALL revalidate demand, precedence, supply, and policy rather than treating the proposal as an authoritative reservation.

#### Scenario: Planning has no side effects

- **GIVEN** an active demand and an in-memory stock snapshot
- **WHEN** the planner produces a complete proposal
- **THEN** no allocation, slice, counter, movement, or event has changed

#### Scenario: An insufficient proposal cannot partially commit

- **GIVEN** one required SKU is short
- **WHEN** the ship-complete planner reports insufficiency
- **THEN** it reports every missing quantity and exposes no committable slice set

## MODIFIED Requirements

### Requirement: Allocation takes its demand from a published view, never from the order aggregate

Allocation SHALL read work from allocation-owned demand, demand-line, and commitment persistence. A candidate SHALL include demand identity, source trace, inventory scope, resolved destination, scheduling snapshot, canonical lines, and current open quantities derived from allocation slices. Allocation SHALL NOT load a source aggregate or reconstruct source intent or coverage from execution targets.

Strict FIFO precedence SHALL be scoped by owner, facility, source stock location, and SKU using `(enqueuedAt, allocationDemandId)`. A candidate SHALL be eligible exactly when no earlier `ACTIVE` demand with positive open quantity in the same scope has an intersecting open SKU set. Required-by time, priority, destination, target state, and released historical slices SHALL NOT reorder this relation.

Discovery MAY return a bounded raw list, but the commit transaction SHALL reevaluate the exact predecessor relation before creating commitment. One transaction SHALL commit at most one demand; successors SHALL be reconsidered in later bounded iterations.

#### Scenario: Allocation reads requirement and coverage without targets

- **WHEN** an open demand is selected
- **THEN** allocation receives its persisted source snapshot and slice-derived open quantities without loading an order, movement, or shipment

#### Scenario: A shared open SKU establishes precedence

- **GIVEN** an earlier open demand needs SKU-A and SKU-B and a later demand needs SKU-B
- **WHEN** the later demand is evaluated
- **THEN** the earlier demand blocks it because their open SKU sets intersect

#### Scenario: Fully covered demand does not block a successor

- **GIVEN** an earlier demand's lines are fully covered by reserved or consumed slices
- **WHEN** a later shared-SKU demand is evaluated
- **THEN** the earlier demand does not block it even if execution is incomplete

#### Scenario: Commit rechecks discovery

- **GIVEN** queue discovery returned a candidate whose predecessor state can change concurrently
- **WHEN** its proposal reaches the commit transaction
- **THEN** the exact current predecessor relation is checked before any allocation fact is written

### Requirement: Allocation publishes one canonical order outcome and writes only allocation-owned tables

Allocation SHALL write allocation-owned demand, allocation, slice, stock, movement, movement-line, and Outbox tables. It SHALL NOT write a source aggregate, inventory outbound picking, or WMS-owned shipment, wave, or pick-task record.

A successful transaction's canonical allocation id SHALL be `Allocation.id`, not `AllocationDemand.id` or a target id. Its result and committed event SHALL carry both allocation id and allocation-demand id, the full source allocation-unit identity, slice identities, and materialized movement details. A source publication adapter MAY map source references but SHALL NOT replace allocation identity.

For an order-backed demand, exactly one `OrderAllocationCommittedIntegrationEvent` SHALL be written to Outbox. Ordering and WMS SHALL consume the same fact under separate subscription identities. WMS SHALL idempotently create its execution grouping from allocation id.

#### Scenario: Commitment identity crosses the boundary

- **GIVEN** demand `demand-1` commits allocation `allocation-9`
- **WHEN** its order event is published
- **THEN** `allocationId` is `allocation-9` and `allocationDemandId` is `demand-1`

#### Scenario: WMS owns warehouse grouping

- **WHEN** WMS consumes a committed allocation event
- **THEN** it creates or finds warehouse execution by allocation id without allocation writing WMS tables

#### Scenario: A transfer result remains source-addressable

- **WHEN** a transfer-backed demand commits
- **THEN** its result carries allocation, demand, and source identities without requiring an order event

### Requirement: A multi-SKU allocation demand is satisfiable only when every one of its SKUs is

The enabled `SHIP_COMPLETE` policy SHALL accept a proposal only when it exactly covers every line's current open quantity. It SHALL aggregate repeated SKU lines for sufficiency, report every short SKU, and distribute FEFO quantities back to lines in immutable canonical line order. One short SKU SHALL leave the entire demand, all supply counters, and all commitment and target tables unchanged.

This change SHALL enable no partial-allocation, FIFO-bypass, substitution, cross-location, or caller-selected policy behavior. The model MAY retain multiple historical allocations, but one enabled commitment SHALL cover all current open quantities atomically.

#### Scenario: One SKU short leaves all layers untouched

- **GIVEN** an open demand needs SKU-A and SKU-B and SKU-B is short
- **WHEN** it is planned and considered for commit
- **THEN** no allocation, slice, reservation-counter change, or target is created for either SKU

#### Scenario: Every open line is covered

- **GIVEN** the candidate has precedence and supply covers every open line
- **WHEN** ship-complete commitment succeeds
- **THEN** committed slices exactly cover every open quantity

#### Scenario: Repeated SKU lines remain deterministic

- **GIVEN** two canonical lines request the same SKU across multiple FEFO quants
- **WHEN** the same snapshot is planned repeatedly
- **THEN** proposed quant quantities map identically to demand-line ids in canonical line order

### Requirement: Waking a queue loads every SKU its candidates need

Availability wake-up and reconciliation SHALL discover a bounded set of whole active demands with positive open quantity. Before planning a candidate, the attempt SHALL reject it when an earlier intersecting-open-SKU demand exists, then load allocatable quants for every open SKU in the eligible candidate.

The set of quant rows an attempt may touch SHALL be known before commitment, and repository query count SHALL NOT grow with the number of quants. Planning SHALL remain side-effect free. A predecessor commit SHALL cause its successor to be reconsidered in a later bounded iteration.

#### Scenario: Another open SKU is loaded

- **GIVEN** a demand has open quantity for available SKU-A and unavailable SKU-B
- **WHEN** SKU-A wakes its scope
- **THEN** the attempt also evaluates SKU-B and creates no commitment

#### Scenario: A predecessor blocks before planning supply

- **GIVEN** a candidate has an earlier open demand sharing one SKU
- **WHEN** the candidate attempt begins
- **THEN** it stops before proposing or reserving supply

#### Scenario: A successor is reconsidered after commit

- **GIVEN** two satisfiable open demands share a SKU
- **WHEN** the earlier demand commits
- **THEN** the later demand is considered in a subsequent bounded iteration

### Requirement: Initial allocation and backorder waking use the same allocation semantics

An initial attempt and an availability wake attempt SHALL invoke the same precedence evaluator, planner, enabled policy, and commitment transaction. Both paths SHALL preserve owner and facility isolation, shared-SKU strict FIFO, FEFO, ship-complete coverage, stock lock order, and one-demand-per-transaction behavior.

The paths SHALL differ only in orchestration: acceptance MAY trigger an initial attempt, while events and reconciliation discover existing open demand. Neither path SHALL create placeholder movements or delegate to the other's application use case.

#### Scenario: Initial allocation uses the shared commitment path

- **GIVEN** a newly accepted demand whose complete open quantity is available
- **WHEN** its initial attempt runs
- **THEN** it plans and commits through the same policy and commitment boundary used by wake-up

#### Scenario: Wake-up uses the shared commitment path

- **GIVEN** an older open demand becomes satisfiable
- **WHEN** an availability wake round runs
- **THEN** it plans and commits through the same policy and commitment boundary used initially

#### Scenario: Neither flow delegates to the other

- **WHEN** initial and wake orchestration are inspected
- **THEN** each invokes shared process responsibilities directly without invoking the other use case

### Requirement: Stock availability triggers convergent backorder allocation

A confirmed receipt SHALL commit completed inbound execution, physical stock increase, and a `StockAvailabilityIncreased` Outbox fact together. It SHALL NOT allocate open demand in the receipt transaction. The event handler and periodic reconciliation SHALL invoke the same bounded wake use case.

Every round SHALL process at most the configured demand limit and SHALL NOT publish an orchestration-only continuation event. Remaining open queues SHALL be rediscovered by reconciliation. Event retries and scheduler overlap SHALL be safe because fully covered demands are ineligible and commitment uses optimistic versions and exact predecessor checks.

#### Scenario: Receipt commits before allocation

- **WHEN** receipt confirmation makes stock available
- **THEN** inbound execution, stock increase, and availability fact commit together without committing an outbound allocation

#### Scenario: Availability event triggers a bounded wake

- **GIVEN** a committed availability fact for a scope with open demand
- **WHEN** it is consumed
- **THEN** the handler invokes one transactional bounded wake round

#### Scenario: Reconciliation converges missed work

- **GIVEN** open demand remains after an event is delayed, lost, or exhausted
- **WHEN** reconciliation scans eligible queue keys
- **THEN** it invokes the same wake semantics without transport metadata

#### Scenario: A retry does not duplicate commitment

- **GIVEN** a prior wake committed an allocation but acknowledgement was lost
- **WHEN** the event or scheduler retries the scope
- **THEN** the covered demand is not committed again

### Requirement: Allocation materializes its target atomically

For one eligible proposal, a single local transaction SHALL reload and validate the active demand, open quantities, predecessor relation, and quant versions; create one `Allocation` and its `RESERVED AllocationSlice` facts; update reserved counters; materialize one outbound `StockMove` per covered demand line and one `StockMoveLine` per slice; and append the committed fact to Outbox.

The transaction SHALL use allocation id as execution grouping identity and preserve allocation-demand id as source trace. Movement lines SHALL reference slices but SHALL NOT own reservation lifecycle. Any validation, reservation, target, or Outbox failure SHALL roll back every layer and leave the demand open.

#### Scenario: A successful proposal creates a coherent target

- **WHEN** a complete eligible proposal commits
- **THEN** slices, counters, assigned movements, traceable movement lines, and one completion fact commit together

#### Scenario: A target failure leaves the source open

- **GIVEN** one movement or movement-line write fails
- **WHEN** the transaction rolls back
- **THEN** no commitment or target remains and the demand's open quantities are unchanged
