## MODIFIED Requirements

### Requirement: Demand that has no stock yet is a movement, not an absence

Every stock-consuming allocation demand that requires warehouse execution SHALL have a corresponding outbound movement for each demand line, even when no stock is available. Such a movement SHALL remain in the state meaning that it needs goods and has not got them until allocation assigns stock or the demand is cancelled.

An inbound movement or a movement whose purpose is only to add supply SHALL NOT be treated as an allocation demand merely because it is confirmed.

#### Scenario: A stock-consuming non-order demand with no stock still produces movements

- **GIVEN** an internal transfer demand has no allocatable stock
- **WHEN** the demand is recorded
- **THEN** a confirmed outbound movement exists for each demand line and the allocation demand remains pending

#### Scenario: A confirmed inbound movement is not waiting demand

- **GIVEN** an inbound receipt has a confirmed movement from a supplier location
- **WHEN** waiting allocation is queried
- **THEN** the inbound movement is excluded even if it is not yet complete

#### Scenario: An assigned movement is no longer waiting for goods

- **GIVEN** a demand movement has been assigned stock
- **WHEN** waiting allocation is queried
- **THEN** that movement is excluded from the unassigned movement predicate

### Requirement: Recording a movement is a step of its own

Creating a movement and its optional picking SHALL be reachable without allocation taking place and SHALL accept either a stock-consuming allocation demand or a supply-only movement request. For a stock-consuming source, the allocation-side inbox claim, allocation demand, all of its lines, and all corresponding outbound movements and required picking SHALL commit in one local acceptance transaction. The source aggregate SHALL remain committed in its own context and SHALL NOT participate in a distributed transaction. Movement creation is separate from the allocation attempt, but it SHALL NOT leave a pending demand without execution movements or a demand movement without its allocation demand.

An allocation attempt SHALL NOT be required to create an inbound movement, and an inbound movement SHALL NOT require an allocation demand or an order identifier.

#### Scenario: A movement is recorded before allocation

- **GIVEN** a pending allocation demand with no available stock
- **WHEN** the source request is accepted
- **THEN** the demand and its outbound movements commit atomically before any allocation attempt

#### Scenario: Failed execution-record creation does not strand a demand

- **GIVEN** a stock-consuming source is being accepted
- **WHEN** one of its outbound movements cannot be persisted
- **THEN** the acceptance transaction rolls back and no pending allocation demand remains

#### Scenario: A supply-only inbound movement is recorded without demand

- **GIVEN** a receipt for goods from a supplier
- **WHEN** the receipt is accepted
- **THEN** an inbound movement can be recorded without an allocation demand or order id

## ADDED Requirements

### Requirement: A demand movement identifies its allocation demand line

Every outbound movement created for a stock-consuming allocation demand SHALL identify the allocation demand and the demand line it executes. An inbound movement SHALL not require those references.

The movement SHALL correlate through `allocationDemandLineId`. A source-specific `sourceLineId` SHALL remain traceability data and SHALL NOT be the allocation-core join key.

Every outbound demand movement's `fromLocationId` SHALL equal its allocation demand's source location. Destination location, picking type/direction, and picking creation policy SHALL come from a source-provided execution intent and SHALL be persisted on movement/picking execution records rather than copied into the allocation-demand aggregate.

During staged migration, allocation-demand references on existing movements MAY be nullable until backfill completes. After validation, an outbound demand movement SHALL have both `allocationDemandId` and `allocationDemandLineId`, while an inbound or supply-only movement SHALL have neither. The references SHALL be protected by foreign-key or equivalent integrity checks and deletion of an allocation demand SHALL NOT cascade-delete historical movements.

#### Scenario: An outbound movement is traceable to its source demand

- **WHEN** an outbound movement is created for a transfer demand line
- **THEN** the movement stores the allocation demand id and allocation demand line id

#### Scenario: An inbound movement has no false demand reference

- **WHEN** an inbound receipt movement is created
- **THEN** it is not linked to an allocation demand line

#### Scenario: A partially linked demand movement is rejected after migration validation

- **GIVEN** movement-reference backfill and constraint validation have completed
- **WHEN** an outbound demand movement has an allocation demand id but no allocation demand line id
- **THEN** persistence rejects the inconsistent movement

#### Scenario: Execution source location matches allocation scope

- **GIVEN** an allocation demand reserves stock at location-A
- **WHEN** its outbound demand movements are recorded
- **THEN** every movement starts at location-A and no stock pool from another location is reserved

### Requirement: Picking direction does not depend on order identity

When an execution policy creates a `StockPicking`, its outbound or inbound semantics SHALL be determined by picking type or direction, not by whether an order id is present. A non-order outbound picking SHALL be representable with outbound scheduling data. In the first migration stage, the order source adapter SHALL continue to create one outbound picking for its demand; production use of outbound demand without a picking is outside this change.

#### Scenario: A non-order outbound picking has no false order dependency

- **WHEN** a transfer test fixture creates an outbound picking with scheduling data and no order id
- **THEN** the picking is valid because its picking direction is outbound

#### Scenario: The order adapter keeps its warehouse work grouping

- **WHEN** an order-backed allocation demand is accepted
- **THEN** its outbound picking and demand movements are created in the same acceptance transaction
