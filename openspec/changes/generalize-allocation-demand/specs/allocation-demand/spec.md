## ADDED Requirements

### Requirement: A stock-consuming source creates an allocation demand

Any source that requires inventory to be reserved before warehouse execution SHALL create an `AllocationDemand`. The demand SHALL identify its source type and source id, and SHALL contain one or more demand lines with source-line references, SKU codes, and positive quantities.

Inbound receipts and operations whose purpose is only to add or move supply without competing for available stock SHALL NOT create an allocation demand.

#### Scenario: An order creates an allocation demand

- **WHEN** an order requests outbound stock from a location
- **THEN** the allocation context creates a pending demand with source type `ORDER` and one line for each requested order line

#### Scenario: A non-order stock-consuming source creates an allocation demand

- **WHEN** an internal transfer requests stock from a source location
- **THEN** the allocation context creates a pending demand with source type `TRANSFER` and no order id requirement

#### Scenario: An inbound receipt does not create an allocation demand

- **WHEN** goods are received into a stock location
- **THEN** the receipt creates or completes an inbound movement and increases supply without creating a pending allocation demand

### Requirement: Allocation demand creation is idempotent per source

The allocation context SHALL enforce uniqueness for a source identity. Reprocessing the same `(sourceType, sourceId)` SHALL return or update the existing allocation demand and SHALL NOT create a second demand that competes for the same stock.

If source ids are not globally unique, the source system identity SHALL also be part of the source identity.

#### Scenario: A retried source message does not duplicate demand

- **GIVEN** an allocation demand already exists for source type `ORDER` and source id `order-1`
- **WHEN** the source message for `ORDER/order-1` is processed again
- **THEN** no second allocation demand is created

### Requirement: Allocation demand owns only allocation state

An allocation demand SHALL use the allocation states `PENDING`, `ALLOCATED`, and `CANCELLED`.

`PENDING` SHALL mean that the demand still competes for stock. `ALLOCATED` SHALL mean that the requested quantity has been successfully reserved. `CANCELLED` SHALL mean that allocation is no longer valid.

Fulfillment states such as picked, packed, shipped, and completed SHALL remain owned by movements, pickings, or the source context and SHALL NOT be duplicated as allocation-demand states.

#### Scenario: A pending demand becomes allocated only after a complete plan commits

- **WHEN** an allocation plan reserves every demand line successfully
- **THEN** the demand changes from `PENDING` to `ALLOCATED`

#### Scenario: A failed allocation remains pending

- **WHEN** one demand line cannot be supplied in full
- **THEN** no allocation demand state changes and the demand remains `PENDING`

### Requirement: An allocation demand has one inventory scope

An allocation demand SHALL belong to exactly one owner, facility, and source location. A source request spanning multiple source locations SHALL be split into separate allocation demands before allocation.

#### Scenario: A multi-location source is split before allocation

- **WHEN** a transfer requests SKU-A from location-A and SKU-B from location-B
- **THEN** the allocation context creates separate demands and never presents both locations as one allocation basket

### Requirement: Allocation candidates are demand-first and execution-aware

The candidate query used by availability wake-up and reconciliation SHALL select pending allocation demands and SHALL include only candidates that have an unassigned outbound demand movement. If a candidate has a picking, the picking SHALL not be cancelled or completed. A stock-availability predicate MAY prefilter candidates, but the allocation decision SHALL perform the final quantity and ship-complete check.

The query SHALL return a candidate as a demand with its lines and references to the movements that will be updated; it SHALL NOT use the presence of an order id as the generic definition of an allocation demand.

#### Scenario: A non-order outbound demand is eligible

- **GIVEN** a pending transfer demand with a confirmed outbound movement and available stock
- **WHEN** the allocation candidate query runs
- **THEN** the transfer demand is returned even though it has no order id

#### Scenario: An inbound movement is not an allocation candidate

- **GIVEN** a confirmed inbound movement and available stock at its destination
- **WHEN** the allocation candidate query runs
- **THEN** the inbound movement is not returned as a demand candidate

### Requirement: Allocation cancellation releases or removes execution state

Cancelling a pending allocation demand SHALL cancel its unassigned demand movements. Cancelling an allocated demand SHALL release its stock reservations before cancelling the associated execution state. Both operations SHALL be idempotent by allocation-demand identity.

#### Scenario: A pending demand is cancelled without reserving stock

- **GIVEN** a pending demand with confirmed unassigned movements
- **WHEN** its source is cancelled
- **THEN** the demand becomes `CANCELLED`, its unassigned movements are cancelled, and no stock reservation is created

#### Scenario: An allocated demand releases stock on cancellation

- **GIVEN** an allocated demand with reserved stock
- **WHEN** its source is cancelled
- **THEN** the reservation is released and the demand and its execution movements become cancelled
