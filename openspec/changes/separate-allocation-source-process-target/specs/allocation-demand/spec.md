## MODIFIED Requirements

### Requirement: A stock-consuming source creates an allocation demand

Any source that requires inventory to be reserved before warehouse execution SHALL create an `AllocationDemand`. The demand SHALL identify its source type, canonical source id, stable allocation-unit key, owner, facility, source stock location, resolved destination location, scheduling snapshot, and canonical demand lines. The destination SHALL be resolved by the source adapter before acceptance and SHALL be immutable accepted source intent; allocation SHALL NOT reload mutable route configuration later.

Acceptance SHALL persist only the allocation demand and its lines. A newly accepted demand SHALL be `PENDING` and SHALL NOT create a `StockMove`, `StockMoveLine`, stock reservation, or outbound `StockPicking`. Inbound receipts and operations whose purpose is only to add or move supply without competing for available stock SHALL NOT create an allocation demand.

Demand quantities SHALL be positive integers within the persistence range. Same-SKU aggregation SHALL use checked arithmetic, and any invalid source request SHALL be rejected before a demand or execution record is created.

#### Scenario: An outbound order creates source truth only

- **WHEN** an order requests outbound stock from a resolved source and destination
- **THEN** allocation acceptance creates one pending demand with canonical lines and creates no movement, reservation, or outbound picking

#### Scenario: A non-order stock-consuming source creates a demand

- **WHEN** an internal transfer requests stock from a source location to a destination location
- **THEN** the allocation context creates a pending `TRANSFER` demand without requiring an order id or pre-creating execution records

#### Scenario: An inbound receipt does not create an allocation demand

- **WHEN** goods are received into a stock location
- **THEN** the receipt creates or completes an inbound movement and increases supply without creating a pending allocation demand

#### Scenario: Same-SKU line aggregation cannot overflow

- **GIVEN** one source allocation unit contains positive lines for the same SKU whose sum exceeds the supported integer quantity range
- **WHEN** the source request is accepted
- **THEN** the request is rejected and no demand or execution record is created

### Requirement: Allocation demand creation is idempotent per source allocation unit

The allocation context SHALL enforce uniqueness for `(sourceType, sourceId, allocationUnitKey)`. Normalized accepted content SHALL include accepted-content version, owner, facility, source stock location, resolved destination location, required-by time, release priority, and canonical demand-line content. Demand lines SHALL be canonicalized by stable source-line id; duplicate source-line ids SHALL be rejected, and allocation SHALL derive an immutable line sequence from that order.

Structural replay equality SHALL read only immutable demand and demand-line source snapshots. It SHALL NOT depend on a movement, picking, reservation, batch, mutable execution state, or current route configuration. Time and nullable values SHALL use the accepted-content version's fixed representation. Allocation-generated ids and persistence timestamps SHALL NOT participate in equality; source-provided `enqueuedAt` SHALL participate as part of the immutable scheduling snapshot.

Reprocessing the same identity with the same normalized accepted content SHALL return the existing demand unchanged. Reprocessing that identity with different content SHALL fail with a source-demand conflict and SHALL NOT mutate the existing demand or create a competing demand.

#### Scenario: An identical retry needs no execution records

- **GIVEN** a pending demand exists and has no movements
- **WHEN** the same normalized source allocation unit is accepted again
- **THEN** the existing demand is returned using only the persisted demand snapshot

#### Scenario: Destination drift is a source conflict

- **GIVEN** a demand was accepted with destination location A
- **WHEN** the same source allocation-unit identity is retried with destination location B
- **THEN** acceptance fails with a source-demand conflict and the original snapshot remains unchanged

#### Scenario: Allocation progress does not alter source equality

- **GIVEN** an accepted demand has since been allocated and has execution records
- **WHEN** the identical normalized source request is replayed
- **THEN** it returns the existing demand because target-side progress is excluded from source equality

#### Scenario: Transport line ordering does not create a false conflict

- **GIVEN** a demand was accepted with source lines A and B
- **WHEN** the same line content is retried with B before A
- **THEN** canonical line content is equal and no second demand is created

### Requirement: An allocation demand has one inventory scope

An allocation demand SHALL belong to exactly one owner, facility, and source stock location. It SHALL also carry exactly one resolved destination location as movement intent, but destination SHALL NOT be part of the inventory scope or FIFO queue key. A request spanning multiple source stock locations SHALL be split into separate demands with stable allocation-unit keys before acceptance.

The source stock location SHALL belong to the recorded facility. Every stock pool reserved for the demand and every materialized outbound movement SHALL use that source stock location; every materialized movement SHALL use the demand's resolved destination.

#### Scenario: A multi-location source is split before allocation

- **WHEN** a transfer requests SKU-A from location-A and SKU-B from location-B
- **THEN** the adapter creates separate demands and never presents both source locations as one allocation basket

#### Scenario: Source and destination have different meanings

- **GIVEN** two demands consume the same owner, facility, source location, and SKU but have different destinations
- **WHEN** allocation precedence is evaluated
- **THEN** they share one FIFO inventory queue while each later materializes a movement to its own destination

### Requirement: Allocation candidates are demand-first and execution-aware

Availability wake-up and reconciliation SHALL select `PENDING` allocation demands from persisted source snapshots. Candidate eligibility SHALL NOT require a pre-existing movement, picking, reservation, or other target-side execution record. A stock-availability predicate MAY prefilter work, but the allocation decision SHALL perform the final precedence, quantity, and all-or-nothing checks.

The query SHALL return whole demands with all canonical lines. It SHALL NOT use order identity or the existence or state of execution records as the generic definition of pending demand. Target-side execution consistency SHALL be validated only for allocated or cancelling demands.

#### Scenario: A pending demand with no movement is eligible

- **GIVEN** a pending transfer demand has no movement or picking
- **WHEN** its inventory scope is reconciled
- **THEN** it can be selected based on demand state and precedence

#### Scenario: Existing target records do not define pending work

- **GIVEN** an allocated demand has assigned movements
- **WHEN** pending candidates are queried
- **THEN** it is excluded because its demand status is not `PENDING`, independent of movement state

### Requirement: Allocation cancellation stops only reversible execution

Cancelling a `PENDING` demand SHALL transition only that demand to `CANCELLED`; it SHALL require no movement lookup, picking lookup, or reservation release because pending demand has no target execution.

Cancelling an `ALLOCATED` demand SHALL be accepted only after the external warehouse cancellation coordinator confirms that execution has not started or has stopped. The coordinator SHALL address warehouse work by allocation-demand id and use the cancellation-operation id for idempotency. After confirmation, one local transaction SHALL release reservations, cancel the demand's reversible movements, and mark the demand cancelled. The allocation context SHALL NOT infer external WMS state.

Each cancellation SHALL be identified by `(allocationDemandId, cancellationOperationId)`. Reprocessing an operation SHALL preserve its external decision and resume incomplete local work without reporting success while allocation remains active. Physical compensation after irreversible warehouse work is outside this lifecycle.

#### Scenario: Pending cancellation touches source truth only

- **GIVEN** a pending demand has no execution records
- **WHEN** its source is cancelled
- **THEN** the demand becomes `CANCELLED` and no execution cleanup is attempted

#### Scenario: Allocated cancellation uses demand identity

- **GIVEN** an allocated demand has reserved stock and assigned movements
- **WHEN** warehouse cancellation is confirmed for that allocation-demand id
- **THEN** local reservations and movements are cancelled atomically and the demand becomes `CANCELLED`

#### Scenario: Unconfirmed warehouse cancellation is refused

- **GIVEN** external execution cancellation cannot be confirmed
- **WHEN** allocated cancellation is requested
- **THEN** the operation is not cancellable and demand, reservations, and movements remain unchanged
