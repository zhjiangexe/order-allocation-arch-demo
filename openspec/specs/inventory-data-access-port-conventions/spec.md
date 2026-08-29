# Inventory Data Access Port Conventions Specification

## Purpose

Define the ownership, cohesive Store vocabulary and dependency rules for Inventory Context data-access ports while
keeping persistence mechanisms behind Application-owned abstractions.

## Requirements

### Requirement: Application owns Inventory data-access ports
The Inventory Context SHALL declare every custom persistence, planning-input or projection port in the Application
layer using the `Store` suffix. The Domain layer MUST remain independent of Repository, Store, Finder, Spring Data and
other persistence abstractions.

#### Scenario: Command invokes domain behavior
- **WHEN** a command requires persisted Domain state before invoking Domain behavior
- **THEN** an Application Store loads the Domain object, the Domain behavior changes it in memory, and an Application
  Store persists the result

#### Scenario: Infrastructure implements a cohesive business port
- **WHEN** one persistence adapter provides read, lock and mutation operations for the same Inventory model
- **THEN** it implements one cohesive Application Store without requiring separate Finder and Store interfaces

### Requirement: Port package reveals capability ownership
Inventory Stores SHALL be declared under the owning capability's neutral `application.port` package. Command and
query use cases MAY depend on these Stores, while command/query use-case packages MUST NOT determine Store names or
port package locations.

#### Scenario: Reviewing an entity Store
- **WHEN** a developer inspects a Store for a canonical Inventory model
- **THEN** its capability path identifies ownership and its method names identify read, lock or mutation behavior

#### Scenario: Reviewing a projection Store
- **WHEN** a developer inspects an operator-facing or diagnostic projection Store
- **THEN** it is isolated by its projection-specific type and capability path rather than by a Finder suffix

### Requirement: Command persistence uses Store
The Inventory Context SHALL use Application-owned `*Store` ports for command data access. A cohesive Store MAY expose
ordinary reads, transaction locks and mutations needed to load and persist its model.

#### Scenario: Command loads and persists state
- **WHEN** a command loads a Domain object, invokes behavior and saves the changed object
- **THEN** it uses the model's Application Store for both the read and the mutation

#### Scenario: Command acquires a row lock
- **WHEN** a command must load state with a pessimistic database lock
- **THEN** the Store exposes an explicit `lock...` method that makes the effect visible

### Requirement: Read-only access uses Store
The Inventory Context SHALL use focused Application-owned `*Store` ports for pure reads, immutable planning inputs and
operator-facing projections. A read-focused Store MUST remain scoped to its cohesive model or purpose and MUST NOT be
merged into an unrelated generic Store merely because both access the database.

#### Scenario: Allocation command loads planning input
- **WHEN** allocation assignment loads demand, predecessor, supply or backlog input
- **THEN** it uses a focused Application Store owned by allocation planning

#### Scenario: Query use case reads a view
- **WHEN** an Inventory query use case obtains an operator-facing or diagnostic projection
- **THEN** it uses a focused Application Store that returns an immutable view, DTO or projection

#### Scenario: Same-model read and write capabilities coexist
- **WHEN** ordinary reads and mutations address the same canonical Inventory model and persistence lifecycle
- **THEN** they MAY share one cohesive Store rather than being split solely by method effect

### Requirement: Framework repositories remain infrastructure details
The Inventory naming convention SHALL NOT rename Spring Data `Jpa*Repository` interfaces solely because they are
located in Infrastructure. Such interfaces MUST remain implementation details behind Application-owned Store ports.

#### Scenario: Store implemented with Spring Data
- **WHEN** a persistence adapter delegates reads, locks or mutations to a Spring Data JPA repository
- **THEN** the Application interface uses Store vocabulary while the internal `Jpa*Repository` retains Spring Data
  naming

### Requirement: Naming migration preserves behavior
The port and adapter rename SHALL preserve SQL, entity mappings, transaction boundaries, lock order, flush timing,
algorithms and externally observable REST, event, Temporal and WMS behavior.

#### Scenario: Existing workflows run after renaming
- **WHEN** Inventory allocation, reservation, receipt, completion, cancellation and visibility workflows execute after
  the naming migration
- **THEN** their observable outcomes and persistence behavior remain unchanged
