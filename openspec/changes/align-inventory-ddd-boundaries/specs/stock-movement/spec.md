## ADDED Requirements

### Requirement: Stock movement lifecycle uses a pragmatic record-centric domain model

`StockOperation`, `StockMove` and `StockMoveLine` SHALL be modeled as independently persisted domain records rather than as one
`StockOperation` aggregate hierarchy. Each record SHALL retain validation and lifecycle behavior appropriate to its own state, while
application transaction boundaries SHALL enforce operation/move summaries, move-line consistency, quant reservation consistency and
canonical lock ordering across records.

An application working composition that assembles one operation with its moves and move lines for a transaction SHALL have no
independent identity, repository or persistence mapping and SHALL NOT be presented as an aggregate root.

#### Scenario: Assignment updates the complete record set atomically

- **WHEN** an allocation proposal passes locked-state revalidation
- **THEN** one application transaction SHALL reserve the selected quants, create move lines, assign each move and update its operation
  summary
- **AND** a failure before transaction completion SHALL persist none of those changes

#### Scenario: Loading an operation does not imply an unbounded object graph

- **WHEN** an application feature needs only a stock-operation record
- **THEN** its persistence port SHALL be able to load that record without hydrating every move and move line
- **AND** features requiring the complete working set SHALL request the required records explicitly inside their transaction boundary

#### Scenario: A movement record protects its own lifecycle

- **WHEN** a caller requests an invalid move or operation state transition
- **THEN** the corresponding domain record SHALL reject that transition even though cross-record consistency is application-owned

### Requirement: Repository ownership follows reconstructed consistency boundaries

A repository that reconstructs and saves a true Inventory aggregate SHALL remain a domain repository. A repository that persists,
loads or locks independently stored movement lifecycle records for application workflows SHALL be an application persistence port.
Projection repositories used for a particular workflow SHALL belong to that application feature, and all JDBC/JPA implementations
SHALL remain in infrastructure.

The `Repository` suffix SHALL identify persistence or projection access consistently, while the full type name and package SHALL reveal
whether the repository serves an aggregate, shared movement records or a focused application projection.

#### Scenario: Stock quant persistence remains domain-owned

- **WHEN** code reserves, releases, receives or consumes physical stock balance
- **THEN** it SHALL load and save `StockQuant` through its domain aggregate repository

#### Scenario: Movement record persistence remains application-owned

- **WHEN** registration or lifecycle orchestration saves or locks operations, moves or move lines independently
- **THEN** it SHALL use movement application persistence ports
- **AND** infrastructure SHALL implement those ports without exposing JPA or JDBC types inward

### Requirement: Movement packages expose concepts rather than obsolete technical categories

Movement domain packages SHALL group operation concepts and move concepts without labeling `StockOperation` as an aggregate root or
`StockMoveLine` as a separately owned entity. Application packages SHALL be feature-first; commands, results, application enums,
working models and output ports SHALL be colocated with the registration, lifecycle or view feature that gives them meaning.

Capability-wide `dto`, `enum`, `type`, `query`, `result` and `service` packages SHALL NOT be used as miscellaneous destinations. A
shared application port SHALL exist only where multiple concrete movement features use the same persistence semantics.

#### Scenario: A workflow model is placed by its consumer

- **WHEN** a type is used only to select, coordinate or report one application workflow
- **THEN** that type SHALL live with the owning feature rather than in the movement or allocation domain

#### Scenario: A shared persistence port is not duplicated mechanically

- **WHEN** registration and lifecycle features require the same operation or move persistence semantics
- **THEN** they SHALL use one explicitly shared movement application port
- **AND** they SHALL NOT create duplicate repositories solely to satisfy feature package symmetry

### Requirement: Cross-context process management is distinct from composition wiring

Business coordination that spans fulfillment and Inventory cancellation SHALL be owned by an explicit orchestration or process-manager
package. The monolith bootstrap layer SHALL contain composition and adapter selection only and SHALL NOT own cancellation status,
request/result models or business progression rules.

#### Scenario: Deployment topology does not determine business ownership

- **WHEN** fulfillment cancellation coordination is deployed in the monolith
- **THEN** its process logic SHALL remain in an explicitly named cross-context orchestration package
- **AND** bootstrap SHALL only construct and connect the participating ports and adapters
