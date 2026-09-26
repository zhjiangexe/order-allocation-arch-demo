## MODIFIED Requirements

### Requirement: Recording a movement is a step of its own

A supply-only or inbound movement SHALL be recordable independently without an allocation demand. For stock-consuming outbound work, acceptance SHALL record source intent as an allocation demand without creating a movement. The allocation transaction SHALL materialize outbound movements only after precedence and all-or-nothing stock planning succeed.

Movement recording SHALL NOT load or mutate the source aggregate. A failed outbound allocation transaction SHALL leave neither partial movements nor a partially allocated demand.

#### Scenario: An inbound movement is recorded independently

- **GIVEN** a receipt from a supplier
- **WHEN** the receipt is accepted
- **THEN** an inbound movement can be recorded without an allocation demand or order id

#### Scenario: Pending outbound intent is not execution

- **GIVEN** an accepted stock-consuming demand with insufficient stock
- **WHEN** acceptance commits
- **THEN** the demand remains pending and no outbound movement exists

#### Scenario: Allocation records outbound movement atomically

- **GIVEN** a pending demand has a complete eligible plan
- **WHEN** allocation commits
- **THEN** its reservations, assigned movements, allocated state, and completion fact commit together

### Requirement: A demand movement identifies its allocation demand line

Every outbound movement materialized by allocation SHALL identify the allocation demand and exactly one demand line. The movement SHALL correlate through `allocationDemandLineId`; source-specific line identity SHALL remain traceability data and SHALL NOT be an allocation-core join key. An inbound or supply-only movement SHALL have neither allocation-demand reference.

The movement's source location SHALL equal the demand's source stock location, and its destination SHALL equal the demand's accepted destination snapshot. Each demand line SHALL have at most one materialized movement. A demand transition to `ALLOCATED` SHALL require one assigned-or-later movement per demand line with reservation coverage equal to requested quantity.

#### Scenario: Allocation creates a traceable assigned movement

- **WHEN** a demand line is allocated
- **THEN** exactly one assigned movement references its demand and demand-line ids and uses the accepted source and destination

#### Scenario: An inbound movement has no false demand reference

- **WHEN** an inbound receipt movement is created
- **THEN** it has neither an allocation-demand id nor an allocation-demand-line id

#### Scenario: Partial linkage is rejected

- **WHEN** a movement provides only one of allocation-demand id and allocation-demand-line id
- **THEN** persistence rejects the inconsistent movement

### Requirement: Picking direction does not depend on order identity

Inbound and supply-only operation semantics SHALL be determined by operation type or direction, not by order identity. Outbound allocation SHALL NOT create or require an inventory `StockPicking`; warehouse grouping and fulfillment execution SHALL be owned by WMS and keyed from allocation-demand id.

#### Scenario: Inbound picking remains operation-driven

- **WHEN** an inbound receipt creates a picking
- **THEN** its direction comes from operation configuration rather than an order id

#### Scenario: Outbound allocation has no inventory picking

- **WHEN** an order-backed allocation commits
- **THEN** it creates assigned movements correlated to the demand and no outbound `StockPicking`

## REMOVED Requirements

### Requirement: Demand that has no stock yet is a movement, not an absence

**Reason**: The requirement conflates durable source intent with execution target state and forces pending work to maintain placeholder execution records.

**Migration**: Treat `PENDING AllocationDemand` as the only outstanding-demand truth. Remove pending placeholder movements and materialize assigned movements only in a successful allocation transaction.

### Requirement: A dispatch document is not shared between orders

**Reason**: Inventory no longer creates outbound dispatch documents. WMS owns `Shipment`, wave, and pick-task grouping.

**Migration**: Use allocation-demand id as the cross-context allocation identity and let WMS create its own idempotent execution grouping after the completion event.
