## ADDED Requirements

### Requirement: Assignment workflow models and ports are owned by the application feature

Inventory SHALL organize stock assignment as an application feature that owns candidate selection, backlog discovery, supply loading,
commit orchestration and their workflow models. Queue keys, predecessor evidence, assignment candidates and assignment results SHALL be
immutable application models and SHALL NOT be part of the pure allocation domain contract.

The allocation domain contract SHALL remain `StockOperationDemand + StockAllocationSupply -> StockAllocationProposal`. Assignment
candidate, backlog and supply repositories SHALL be application output ports implemented by infrastructure adapters; an interface
SHALL NOT be classified as a domain repository merely because it uses the `Repository` suffix.

#### Scenario: Candidate selection stays outside pure planning

- **WHEN** a confirmed stock operation is considered for assignment
- **THEN** the application assignment feature SHALL obtain its demand and predecessor evidence through an assignment-owned repository
- **AND** the domain planner SHALL receive only immutable demand and supply and SHALL return only an immutable proposal

#### Scenario: A replenishment wake uses the same assignment boundary

- **WHEN** backlog reconciliation discovers a queue with available stock
- **THEN** its immutable queue key SHALL enter the same assignment feature used by initial allocation
- **AND** queue and predecessor types SHALL NOT become inputs to the domain planner

### Requirement: Operator stock queries return immutable projections

The operator-facing stock-location repository SHALL return immutable application projections rather than mutable `StockQuant`
aggregates. Each projection SHALL expose the stock-quant identity, SKU, in-date, expiry date, on-hand quantity and reserved quantity
needed by the query, and SHALL derive available-to-promise and expiry without offering stock mutation behavior.

The JDBC adapter SHALL map database rows directly to those projections in one query ordered by SKU, expiry date, in-date and stock-
quant identity. The REST boundary SHALL preserve the existing grouped JSON contract, successful empty result, expired-stock visibility
and quantity values.

#### Scenario: Query results cannot reserve stock

- **WHEN** the stock-location use case returns batches to an entrypoint
- **THEN** every returned element SHALL be an immutable projection with no reserve, release, receive or consume operation
- **AND** no mutable `StockQuant` aggregate SHALL cross the read-repository boundary

#### Scenario: Stock view behavior and ordering are preserved

- **WHEN** a location contains multiple SKUs and batches including expired stock
- **THEN** the adapter SHALL load them with one ordered database query
- **AND** the REST response SHALL retain SKU grouping, FEFO-compatible batch order, expired markers and available-to-promise values

#### Scenario: An empty location remains a normal result

- **WHEN** no stock rows exist for the requested owner and location
- **THEN** the use case SHALL return an immutable empty result
- **AND** the REST endpoint SHALL retain its successful empty response rather than report a missing aggregate

### Requirement: Versioned event contracts are translated outside Application

Allocation assignment, stock-operation lifecycle and stock-receipt transactions SHALL publish through application-owned ports using
source-neutral application results, snapshots or immutable publication models. Application and domain code SHALL NOT construct or
import versioned integration events, publication envelopes, channel constants or external aggregate-reference types. Publication
models SHALL NOT implement or be named as Domain Events, and these ports SHALL NOT require Domain Event dispatch.

Infrastructure messaging adapters SHALL translate application facts into the existing versioned contracts and SHALL write through the
existing Outbox mechanism synchronously inside the state-changing transaction. Subscription identities and inbound consumer wiring
SHALL belong to messaging entrypoints rather than application packages.

#### Scenario: Assignment publication remains atomic

- **WHEN** a ready stock allocation proposal is committed successfully
- **THEN** the committer SHALL invoke an application publisher port before its transaction returns
- **AND** the infrastructure adapter SHALL create the existing assignment integration event and Outbox publication atomically with the
  move-line, quant and lifecycle-state writes

#### Scenario: Publication failure rolls back assignment

- **GIVEN** a ready stock allocation proposal
- **WHEN** its synchronous Outbox publication fails before transaction completion
- **THEN** the assignment transaction SHALL fail
- **AND** no selected-quant reservation, move line, assigned move/operation state or Outbox row SHALL persist

#### Scenario: Receipt availability publication stays contract independent

- **WHEN** a receipt transaction increases stock availability
- **THEN** the receipt use case SHALL publish an immutable source-neutral availability model through an application-owned port
- **AND** infrastructure SHALL create the existing availability integration event and Outbox publication inside that transaction

#### Scenario: Contract evolution does not enter the allocation application

- **WHEN** a versioned integration-event class or transport envelope changes
- **THEN** the change SHALL be isolated to infrastructure messaging translation and its contract tests
- **AND** Inventory application models, use cases, planners and committers SHALL remain independent of that versioned contract

### Requirement: Allocation domain and application models remain framework neutral

Allocation domain types SHALL NOT depend on Spring, Jackson, persistence implementations, transport APIs or versioned contracts.
Allocation application models and ports SHALL NOT depend on Jackson, Kafka, Temporal, JDBC/JPA implementations or versioned contracts.
Pure domain services SHALL be composed by outer-layer configuration rather than discovered through framework annotations.

#### Scenario: Architecture verification detects an outer-layer dependency

- **WHEN** a domain or application model imports a prohibited framework, transport or integration-contract type
- **THEN** Inventory architecture verification SHALL fail before the change can be accepted
