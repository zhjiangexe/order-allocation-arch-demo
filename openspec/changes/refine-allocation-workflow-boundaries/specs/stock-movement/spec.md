## MODIFIED Requirements

### Requirement: Recording a movement is a step of its own

Recording movements SHALL be reachable without any allocation taking place, and SHALL NOT require
demand as its only possible input. A `StockMove` SHALL be the required fact describing inventory
work; a `StockPicking` SHALL be an optional grouping for movements that must be operated together.
A generic movement SHALL therefore be valid without a picking.

Order-driven outbound recording SHALL remain stricter than the generic movement model. It SHALL
create exactly one picking for the order and SHALL place every outbound movement recorded for that
order under that picking. The picking is the order's ship-complete execution boundary and SHALL NOT
be shared with another order.

This is what makes receiving and standalone inventory work expressible without pretending every
movement needs an order shipment. Goods arriving from a supplier are a movement — supplier to
stock — and nothing about them is allocated: there is no order, no availability question, no
whole-order rule. The current inbound flow SHALL continue to group its movement under an inbound
picking; that caller policy does not make picking mandatory in the generic movement model.

The recording step SHALL resolve where grouped movements run between from the operation type of the
warehouse, and SHALL fail loudly when that warehouse has no operation type for the direction asked
for. Accepting the work and quietly recording nothing would make demand disappear without trace —
it would appear in no queue, because queues are read from movements.

The step SHALL return what it created, so that a caller which goes on to assign stock does not have
to read the same rows back.

#### Scenario: A generic movement does not require a picking

- **GIVEN** inventory work that does not need to be grouped into a warehouse operation
- **WHEN** its movement is constructed and persisted
- **THEN** the movement exists with no picking
- **AND** its source, destination, SKU, quantity, owner, and execution state remain explicit

#### Scenario: Order outbound recording creates one picking

- **GIVEN** an order with one or more outbound movement requirements
- **WHEN** its movements are recorded
- **THEN** exactly one outbound picking exists for that order
- **AND** every movement recorded for the order belongs to that picking
- **AND** no stock has been drawn on and no availability was consulted

#### Scenario: Two orders never share an outbound picking

- **GIVEN** two orders shipping from the same warehouse
- **WHEN** their outbound movements are recorded
- **THEN** each order's movements belong to a different picking

#### Scenario: A warehouse without an operation type refuses grouped work

- **GIVEN** a warehouse with no operation type for the grouped direction being recorded
- **WHEN** recording is attempted
- **THEN** it fails
- **AND** no picking and no movement are left behind

#### Scenario: What was recorded is handed back to the caller

- **GIVEN** movements have just been recorded for an order
- **WHEN** the caller goes on to assign stock to them
- **THEN** it uses the movements it was given
- **AND** does not read them back by the identifier of the demand they came from

## ADDED Requirements

### Requirement: Only order-driven outbound movements enter the allocation queue

Only outbound movements grouped by a picking that identifies an order SHALL be eligible for the
order-allocation queue. A standalone movement without a picking, an inbound movement, or a movement
whose picking has no order SHALL NOT be offered as order demand.

#### Scenario: A standalone movement does not become order demand

- **GIVEN** a standalone movement with no picking
- **WHEN** the queue of movements needing goods is read
- **THEN** that movement is not offered for order allocation

#### Scenario: An inbound picking does not become order demand

- **GIVEN** an inbound movement grouped under a picking with no order
- **WHEN** the queue of movements needing goods is read
- **THEN** that movement is not offered for order allocation

#### Scenario: An order outbound movement remains eligible

- **GIVEN** a waiting outbound movement whose picking identifies an order
- **WHEN** the allocation queue for its owner, warehouse, and SKU is read
- **THEN** the movement is eligible according to the existing FIFO and whole-order rules
