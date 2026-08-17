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

Creating a movement and its optional picking SHALL be reachable without allocation taking place and SHALL accept either a stock-consuming allocation demand or a supply-only movement request.

An allocation attempt SHALL NOT be required to create an inbound movement, and an inbound movement SHALL NOT require an allocation demand or an order identifier.

#### Scenario: A movement is recorded before allocation

- **GIVEN** a pending allocation demand with no available stock
- **WHEN** the source request is accepted
- **THEN** its outbound movements are recorded before any allocation attempt

#### Scenario: A supply-only inbound movement is recorded without demand

- **GIVEN** a receipt for goods from a supplier
- **WHEN** the receipt is accepted
- **THEN** an inbound movement can be recorded without an allocation demand or order id

## ADDED Requirements

### Requirement: A demand movement identifies its allocation demand line

Every outbound movement created for a stock-consuming allocation demand SHALL identify the allocation demand and the demand line it executes. An inbound movement SHALL not require those references.

#### Scenario: An outbound movement is traceable to its source demand

- **WHEN** an outbound movement is created for a transfer demand line
- **THEN** the movement stores the allocation demand id and allocation demand line id

#### Scenario: An inbound movement has no false demand reference

- **WHEN** an inbound receipt movement is created
- **THEN** it is not linked to an allocation demand line
