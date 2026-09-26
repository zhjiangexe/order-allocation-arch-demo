## MODIFIED Requirements

### Requirement: A stock-consuming source creates an allocation demand

Any source that requires inventory to be reserved before warehouse execution SHALL create an `AllocationDemand`. The demand SHALL identify its source type, canonical source id, stable allocation-unit key, owner, facility, source stock location, resolved destination location, scheduling snapshot, and canonical demand lines. The destination SHALL be resolved by the source adapter before acceptance and SHALL be immutable accepted source intent; allocation SHALL NOT reload mutable route configuration later.

Acceptance SHALL persist only the allocation demand and its lines. A newly accepted demand SHALL be `ACTIVE` and have its full required quantity open. It SHALL NOT create an `Allocation`, `AllocationSlice`, `StockMove`, `StockMoveLine`, stock reservation, or outbound `StockPicking`. Inbound receipts and supply-only operations SHALL NOT create an allocation demand.

Demand quantities SHALL be positive integers within the persistence range. Same-SKU aggregation SHALL use checked arithmetic, and any invalid request SHALL be rejected before a demand, commitment, or execution record is created.

#### Scenario: An outbound order creates source truth only

- **WHEN** an order requests outbound stock from a resolved source and destination
- **THEN** acceptance creates one active demand with canonical lines and no commitment, reservation, movement, or outbound picking

#### Scenario: A non-order source creates a demand

- **WHEN** an internal transfer requests stock from a source location to a destination
- **THEN** allocation creates an active `TRANSFER` demand without requiring an order id or pre-creating target records

#### Scenario: An inbound receipt does not create an allocation demand

- **WHEN** goods are received into a stock location
- **THEN** the receipt creates or completes an inbound movement without creating an allocation demand

#### Scenario: Same-SKU aggregation cannot overflow

- **GIVEN** one source allocation unit contains same-SKU lines whose sum exceeds the supported range
- **WHEN** the source request is accepted
- **THEN** the request is rejected and no demand, commitment, or execution record is created

### Requirement: Allocation demand owns only allocation state

An allocation demand SHALL persist only the requirement lifecycle states `ACTIVE` and `CANCELLED`. `ACTIVE` SHALL mean that the accepted requirement remains valid; it SHALL NOT mean that the demand is wholly open or wholly covered. `CANCELLED` SHALL mean that no additional allocation may be committed for the requirement.

For each demand line, `reservedQuantity` SHALL be the sum of `RESERVED` allocation slices, `consumedQuantity` SHALL be the sum of `CONSUMED` slices, `coveredQuantity` SHALL be their sum, and `openQuantity` SHALL equal required quantity minus covered quantity. Released slices SHALL remain historical facts but SHALL NOT contribute to coverage. Covered quantity SHALL remain between zero and the immutable required quantity.

Fulfillment states such as assigned, picked, packed, shipped, and completed SHALL remain owned by movements, WMS work, or the source context. This change enables multiple historical allocations but enables only a policy that commits the complete open demand at once.

#### Scenario: A new demand is active and fully open

- **WHEN** an allocation demand is accepted
- **THEN** it is `ACTIVE`, every line has zero coverage, and open quantity equals required quantity

#### Scenario: Reserved slices cover demand without changing requirement lifecycle

- **WHEN** a complete allocation commits for an active demand
- **THEN** its reserved slices reduce open quantities to zero while the demand remains `ACTIVE`

#### Scenario: Release reopens coverage without reopening a demand state

- **GIVEN** an active fully covered demand has a reversible allocation
- **WHEN** that allocation is released
- **THEN** released slices no longer count as coverage and the demand has open quantity again

#### Scenario: Execution progress is not a demand status

- **GIVEN** a fully covered active demand
- **WHEN** its targets are picked or completed
- **THEN** execution changes without introducing a picked, shipped, or completed demand state

### Requirement: An allocation demand has one inventory scope

An allocation demand SHALL belong to exactly one owner, facility, and source stock location. It SHALL also carry exactly one resolved destination location as movement intent, but destination SHALL NOT be part of inventory scope or the FIFO key. A request spanning source locations SHALL be split into separate demands with stable allocation-unit keys before acceptance.

The source stock location SHALL belong to the facility. Every stock quant referenced by an allocation slice for the demand and every materialized outbound movement SHALL use that owner and source location; every materialized movement SHALL use the demand's resolved destination.

#### Scenario: A multi-location source is split before allocation

- **WHEN** a transfer requests SKU-A from location-A and SKU-B from location-B
- **THEN** the adapter creates separate demands and never presents both locations as one basket

#### Scenario: Source and destination have different meanings

- **GIVEN** two demands share owner, facility, source location, and SKU but have different destinations
- **WHEN** precedence is evaluated
- **THEN** they share one FIFO inventory queue and later materialize movements to their own destinations

#### Scenario: A foreign quant cannot cover a demand

- **GIVEN** a proposed slice references a quant from another owner or source location
- **WHEN** commitment is validated
- **THEN** the whole commitment is rejected and no target is materialized

### Requirement: Allocation candidates are demand-first and execution-aware

Availability wake-up and reconciliation SHALL select `ACTIVE` allocation demands with at least one positive open line quantity. Eligibility SHALL derive from demand lifecycle and allocation-slice coverage and SHALL NOT require or infer state from a movement, picking, shipment, or other target. A stock-availability predicate MAY prefilter work, but the decision SHALL perform final precedence, quantity, and enabled-policy checks.

The query SHALL return whole demands with canonical lines and current open quantities. Shared-SKU predecessor evaluation SHALL consider only earlier active demands in the same scope that still have positive open quantity and whose open SKU footprint intersects the candidate's. A fully covered demand SHALL NOT block a successor merely because execution is incomplete.

#### Scenario: An active open demand with no target is eligible

- **GIVEN** an active transfer demand has open quantity and no commitment, movement, or shipment
- **WHEN** its inventory scope is reconciled
- **THEN** it can be selected from demand coverage and precedence

#### Scenario: A fully covered demand leaves the pending queue

- **GIVEN** an active demand has coverage equal to every required line
- **WHEN** pending candidates are queried
- **THEN** it is excluded even if execution remains incomplete

#### Scenario: Release makes an active demand eligible again

- **GIVEN** all reserved slices for an active demand are released
- **WHEN** candidates are queried
- **THEN** the demand is eligible again using its newly open quantities

### Requirement: Allocation cancellation stops only reversible execution

Cancelling an `ACTIVE` demand with no reserved allocation SHALL transition only the demand to `CANCELLED`. If it has a reserved allocation, cancellation SHALL first use that allocation id and an idempotent operation id to coordinate external warehouse cancellation. After confirmation, one local transaction SHALL release the commitment, cancel reversible Inventory targets, and mark the demand cancelled. Allocation SHALL NOT infer external WMS state.

Reprocessing an operation SHALL preserve its external decision and resume incomplete local work without reporting success while a reserved allocation remains. A demand with consumed commitment SHALL NOT be cancelled by deleting or releasing history; physical compensation after irreversible execution remains outside this lifecycle.

#### Scenario: Open cancellation touches requirement truth only

- **GIVEN** an active demand has no reserved allocation
- **WHEN** its source is cancelled
- **THEN** the demand becomes `CANCELLED` and no commitment or execution cleanup is attempted

#### Scenario: Reserved cancellation releases by allocation identity

- **GIVEN** an active demand has a reserved allocation with reversible targets
- **WHEN** warehouse cancellation is confirmed for that allocation id
- **THEN** its slices and targets are released atomically and the demand becomes `CANCELLED`

#### Scenario: Unconfirmed warehouse cancellation is refused

- **GIVEN** external execution cancellation cannot be confirmed for a reserved allocation
- **WHEN** demand cancellation is requested
- **THEN** demand, slices, counters, and targets remain unchanged

#### Scenario: Consumed commitment requires compensation

- **GIVEN** an active demand has consumed commitment
- **WHEN** source cancellation is requested
- **THEN** cancellation does not erase commitment or restore stock and requires compensation
