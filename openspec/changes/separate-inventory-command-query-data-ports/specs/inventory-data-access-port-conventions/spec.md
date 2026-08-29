## ADDED Requirements

### Requirement: Application owns Inventory data-access ports
The Inventory Context SHALL declare every custom persistence or projection port in the Application layer. The Domain
layer MUST remain independent of Repository, Store, Finder, Spring Data and other persistence abstractions.

#### Scenario: Command invokes domain behavior
- **WHEN** a command requires persisted Domain state before invoking Domain behavior
- **THEN** an Application Finder loads the Domain object, the Domain behavior changes it in memory, and an Application
  Store persists the result

#### Scenario: Infrastructure implements business ports
- **WHEN** one JPA adapter provides read and mutation operations for the same Inventory model
- **THEN** it MAY implement separate Application Finder and Store interfaces without combining those interfaces

### Requirement: Port package reveals use-case ownership
Inventory Stores SHALL be declared under an Application command-port package. A command-owned Finder SHALL be
declared under an Application command-port package, and a query-owned Finder SHALL be declared under an Application
query-port package. Query Application code MUST NOT depend on a command-port package.

#### Scenario: Reviewing a command dependency
- **WHEN** a developer inspects a command-port Finder and Store
- **THEN** their shared package identifies command ownership while their suffixes distinguish pure reads from effects

#### Scenario: Reviewing a query dependency
- **WHEN** a developer inspects a query-port Finder
- **THEN** its package identifies query ownership and no query-port Store exists

## MODIFIED Requirements

### Requirement: Command persistence uses Store
The Inventory Context SHALL name every Application-owned data-access port method that changes authoritative state or
acquires a transaction lock with the `Store` suffix. A Store MUST NOT expose an ordinary pure-read method.

#### Scenario: Command persists state
- **WHEN** a command saves, deletes or atomically claims authoritative state
- **THEN** it uses an Application `*Store`

#### Scenario: Command acquires a row lock
- **WHEN** a command must load state with a pessimistic database lock
- **THEN** the Store exposes an explicit `lock...` method rather than a `find...` method

### Requirement: Read-only access uses Finder
The Inventory Context SHALL name every Application-owned pure-read data-access port with the `Finder` suffix. A Finder
MUST NOT mutate authoritative state or acquire a transaction lock. A command-owned Finder MAY return Domain objects
or immutable planning inputs, while a query-owned Finder SHALL return an immutable view, DTO or projection.

#### Scenario: Command loads before mutation
- **WHEN** a command workflow performs an ordinary read of Domain or reference state before modifying it
- **THEN** it uses a command-owned `*Finder` for the read and a `*Store` only for the later effect

#### Scenario: Allocation command loads planning input
- **WHEN** allocation assignment loads demand, predecessor, supply or backlog input without locking or mutation
- **THEN** it uses a command-owned `*Finder`

#### Scenario: Query use case reads a view
- **WHEN** an Inventory query use case obtains operator-facing or diagnostic data
- **THEN** it uses a query-owned `*Finder` without depending on a command port

### Requirement: Framework repositories remain infrastructure details
The Inventory naming convention SHALL NOT rename Spring Data `Jpa*Repository` interfaces solely because they are
located in Infrastructure. Such interfaces MUST remain implementation details behind Application-owned Finder and
Store ports.

#### Scenario: Finder and Store implemented with Spring Data
- **WHEN** a persistence adapter delegates read and mutation methods to a Spring Data JPA repository
- **THEN** the Application interfaces use Finder and Store vocabulary while the internal `Jpa*Repository` retains
  Spring Data naming

## REMOVED Requirements

### Requirement: Domain aggregate access uses Repository
**Reason**: Inventory Domain objects never load themselves, so persistence-port ownership in Domain adds a second
classification model without enabling Domain behavior.

**Migration**: Move each custom Domain `*Repository` interface to Application, split pure reads into `*Finder` and
mutations or locking operations into `*Store`, and then update adapters and callers.
