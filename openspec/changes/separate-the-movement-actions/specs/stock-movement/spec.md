## ADDED Requirements

### Requirement: Recording a movement is a step of its own

Creating a dispatch document and the movements under it SHALL be reachable without any
allocation taking place, and SHALL NOT require demand as its only possible input.

This is what makes receiving expressible. Goods arriving from a supplier are a movement —
vendors to stock — and nothing about them is allocated: there is no order, no availability
question, no whole-order rule. If recording only exists inside the path that satisfies
orders, receiving has two ways forward and both are wrong: copy the recording logic, or
route receiving through a step named for allocation.

The step SHALL resolve where the movements run between from the operation type of the
warehouse, and SHALL fail loudly when that warehouse has no operation type for the
direction asked for. Accepting the work and quietly recording nothing would make the demand
disappear without trace — it would appear in no queue, because queues are read from
movements.

The step SHALL return what it created, so that a caller which goes on to assign stock does
not have to read the same rows back.

#### Scenario: Recording happens without any allocation

- **GIVEN** a warehouse with an operation type for the direction being recorded
- **WHEN** movements are recorded
- **THEN** a dispatch document and its movements exist, each needing goods
- **AND** no stock has been drawn on and no availability was consulted

#### Scenario: A warehouse without an operation type refuses the work

- **GIVEN** a warehouse with no operation type for the direction being recorded
- **WHEN** recording is attempted
- **THEN** it fails
- **AND** no dispatch document and no movement are left behind

#### Scenario: What was recorded is handed back to the caller

- **GIVEN** movements have just been recorded for an order
- **WHEN** the caller goes on to assign stock to them
- **THEN** it uses the movements it was given
- **AND** does not read them back by the identifier of the demand they came from
