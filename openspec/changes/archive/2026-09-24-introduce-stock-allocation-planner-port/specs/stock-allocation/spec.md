## ADDED Requirements

### Requirement: Inventory allocation planning is exposed through an Inventory-owned port

Inventory application orchestration SHALL invoke pure stock allocation planning through a `StockAllocationPlanner` domain port. The port SHALL accept one immutable `MovementPlanningSnapshot` demand-group snapshot and one immutable `AllocatableBatches` supply snapshot, and SHALL return one immutable `MovementAssignmentProposal` without reading repositories, mutating inventory state, or crossing the transactional commitment boundary.

The production port SHALL remain specific to Inventory allocation. It SHALL NOT extend a generic Inventory/WMS allocation engine, flatten either snapshot into bare demand or supply lists, or reference WMS wave, work, task, or execution-capacity types. The existing deterministic SHIP_COMPLETE and FEFO planner SHALL implement this port without changing allocation decisions.

#### Scenario: Application orchestration uses the Inventory planner boundary

- **GIVEN** an assignment candidate and its allocatable stock snapshot have been loaded
- **WHEN** `StockOperationAssigner` requests a planning decision
- **THEN** it invokes `StockAllocationPlanner` with the typed movement and supply snapshots
- **AND** it does not depend on the concrete planner implementation

#### Scenario: The planner port remains outside the commitment boundary

- **GIVEN** a confirmed stock operation is planned through `StockAllocationPlanner`
- **WHEN** the planner returns a proposal
- **THEN** no operation, move, move line, quant counter, or Outbox fact has changed
- **AND** only the application transaction boundary may commit a ready proposal

#### Scenario: WMS planning is not forced into the Inventory contract

- **WHEN** the production planner type hierarchy is inspected
- **THEN** `StockAllocationPlanner` references only Inventory movement, stock-supply, and proposal types
- **AND** no generic Demand/Supply or WMS planner parent is required
