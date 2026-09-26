## ADDED Requirements

### Requirement: Domain aggregate access uses Repository
The Inventory Context SHALL reserve the `Repository` suffix on its custom data-access ports for interfaces owned by a
Domain aggregate or reference model. Application code MAY consume those Domain interfaces, while infrastructure SHALL
implement them without moving their ownership out of Domain.

#### Scenario: Application loads an aggregate
- **WHEN** an Application use case loads or saves a Domain aggregate
- **THEN** it may depend on that aggregate's Domain-owned `*Repository` interface

### Requirement: Command persistence uses Store
The Inventory Context SHALL name an Application-owned authoritative mutable persistence port with the `Store` suffix.
A Store MAY read, lock, save or delete state required to execute a command safely.

#### Scenario: Command loads before mutation
- **WHEN** a command workflow must load or lock Application-owned state before modifying it
- **THEN** the workflow uses an Application `*Store` port even though that port includes read operations

### Requirement: Read-only access uses Finder
The Inventory Context SHALL name an Application-owned read-only snapshot, view, candidate, backlog or reconciliation
port with the `Finder` suffix. A Finder MUST NOT expose a state-mutating operation.

#### Scenario: Allocation command reads planning input
- **WHEN** an allocation command obtains immutable demand, supply or candidate data for a domain decision
- **THEN** it uses an Application `*Finder` without granting that port mutation authority

#### Scenario: Query use case reads a view
- **WHEN** an Inventory query use case obtains a read model
- **THEN** it uses an Application `*Finder` and the Finder does not modify authoritative state

### Requirement: Framework repositories remain infrastructure details
The Inventory naming convention SHALL NOT rename Spring Data `Jpa*Repository` interfaces solely because they are
located in Infrastructure. Such interfaces MUST remain implementation details behind an Inventory-owned Repository,
Store or Finder port.

#### Scenario: Store implemented with Spring Data
- **WHEN** an Application Store adapter delegates to a Spring Data JPA repository
- **THEN** the adapter uses the `Store` vocabulary while the internal `Jpa*Repository` retains Spring Data naming

### Requirement: Naming migration preserves behavior
The port and adapter rename SHALL preserve SQL, entity mappings, transaction boundaries, lock order, flush timing,
algorithms and externally observable REST, event, Temporal and WMS behavior.

#### Scenario: Existing workflows run after renaming
- **WHEN** Inventory allocation, reservation, receipt, completion, cancellation and visibility workflows execute after
  the naming migration
- **THEN** their observable outcomes and persistence behavior remain unchanged
