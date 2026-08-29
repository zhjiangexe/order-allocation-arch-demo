# allocation-demand Specification

## Purpose

Define how stock-consuming source units become canonical Inventory movement intent without introducing a duplicate demand aggregate,
and where legacy picking vocabulary is permitted during compatibility cutovers.

## Requirements

### Requirement: Source acceptance registers movement intent without AllocationDemand

A stock-consuming source adapter SHALL normalize source identity, allocation-unit identity and stable source-line identities into one
`RegisterStockOperationCommand`. Inventory SHALL idempotently register one confirmed `StockOperation` per accepted source allocation
unit and one confirmed `StockMove` per canonical source line before attempting assignment.

The move-centric path SHALL NOT create, load or mutate an `AllocationDemand` aggregate. The accepted operation SHALL enforce uniqueness
for `(sourceType, sourceId, allocationUnitKey)`, and replay with the same key SHALL compare immutable stock-operation and move content.
Generic Inventory registration SHALL NOT require an order or another source aggregate.

#### Scenario: A source unit becomes one canonical operation

- **WHEN** a source adapter accepts a stock-consuming unit with multiple lines
- **THEN** it registers one confirmed stock operation and one confirmed move per line
- **AND** no allocation-demand header or line is created

#### Scenario: An identical source replay is idempotent

- **GIVEN** a stock operation already exists for a source allocation-unit key
- **WHEN** the same immutable operation and move content is registered again
- **THEN** Inventory returns the existing `stockOperationId` without duplicating movement intent

#### Scenario: A conflicting source replay is rejected

- **GIVEN** a stock operation already exists for a source allocation-unit key
- **WHEN** registration reuses the key with different immutable operation or move content
- **THEN** Inventory rejects the conflicting replay

### Requirement: Scope precedence and cancellation use stock-operation identity

A source request spanning more than one source location SHALL be split before stock-operation registration. Allocation candidates SHALL
be selected from complete confirmed stock operations and their moves; no allocation-demand state or target-existence predicate SHALL
participate in precedence.

Cancellation coordination SHALL use `stockOperationId` and a stable cancellation-operation ID. Cancelling a confirmed operation SHALL
be local. Cancelling an assigned operation SHALL require durable confirmation that WMS execution is reversible before one local
transaction releases move lines and quant reservations, cancels reversible moves and their operation, and appends the cancellation fact
to Outbox.

#### Scenario: A multi-source request is split before registration

- **WHEN** one source request would consume stock from multiple source locations
- **THEN** the source adapter registers a distinct stock operation for each source location

#### Scenario: Confirmed cancellation needs no warehouse call

- **GIVEN** a confirmed stock operation has no reservation lines or WMS execution
- **WHEN** its source is cancelled
- **THEN** its moves and operation become `CANCELLED` locally

#### Scenario: Assigned cancellation changes nothing without durable confirmation

- **GIVEN** an assigned stock operation whose WMS execution cannot be confirmed reversible
- **WHEN** cancellation is attempted
- **THEN** the operation remains assigned and its move lines and quant counters remain unchanged

### Requirement: Legacy picking vocabulary is confined to compatibility adapters

During the declared compatibility window, an ingress adapter SHALL accept a supported legacy `pickingId`, signal or contract and SHALL
normalize it immediately to `stockOperationId`. Inventory source registration, allocation selection, planning, assignment, cancellation
and completion SHALL expose no `AllocationDemand` identity and no picking-named alias in their canonical domain or application APIs.

Legacy readers SHALL remain only until the applicable source-topic retention, Outbox re-snapshot, DLT replay and Temporal workflow
windows have expired. Their removal SHALL be performed by a later cleanup change using the recorded deployment-specific retention
thresholds.

#### Scenario: A legacy identifier stops at ingress

- **GIVEN** a supported legacy boundary payload contains `pickingId`
- **WHEN** its compatibility adapter accepts the payload
- **THEN** the adapter invokes the canonical application API with `stockOperationId`
- **AND** no picking-named field enters the canonical command

#### Scenario: Canonical APIs contain no demand aggregate identity

- **WHEN** source registration through completion APIs are inspected after the rename
- **THEN** they use stock operation and movement identity without `allocationDemandId` or `allocationDemandLineId`
