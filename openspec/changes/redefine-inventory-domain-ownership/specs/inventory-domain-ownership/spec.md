## ADDED Requirements

### Requirement: Inventory has five evidence-backed domain modules
The Inventory bounded context SHALL organize its command-side business ownership into exactly five currently implemented domain modules: Movement, Allocation, Reservation, Position, and Location. These modules SHALL remain internal to one bounded context and SHALL NOT imply separate services, databases, or bounded contexts.

#### Scenario: Production type has one domain owner
- **WHEN** a production type owns or enforces an Inventory business rule
- **THEN** it belongs to exactly one of Movement, Allocation, Reservation, Position, or Location, or is explicitly classified as cross-domain Application orchestration

#### Scenario: Package count does not create a new domain
- **WHEN** a workflow, read model, transport adapter, scheduler, or technical observer needs its own package
- **THEN** that package does not become an additional domain module unless it owns independently evolving business facts and rules

### Requirement: Movement owns stock-movement intent
Movement SHALL own `StockOperation`, `StockMove`, their source identity, lifecycle state, policy-group invariants, persistence ports, registration, completion and cancellation behavior. Stock Operation Type and Direction SHALL be Movement configuration because they define the shape and endpoints of a movement operation.

#### Scenario: Registering a source creates movement intent
- **WHEN** a source-neutral registration command is accepted
- **THEN** Movement creates or replays the canonical Stock Operation and Stock Moves without selecting supply or reserving stock

#### Scenario: Cancelling warehouse-backed work targets Movement
- **WHEN** Inventory coordinates cancellation with WMS for one Stock Operation
- **THEN** the durable cancellation checkpoint and WMS cancellation port are owned by Movement rather than Allocation

### Requirement: Allocation owns only non-authoritative planning
Allocation SHALL own immutable demand and supply planning inputs, FIFO/FEFO and completeness policies, candidate and backlog selection, the pure planner, and the non-authoritative proposal. Allocation planning SHALL NOT mutate Stock Operations, Stock Moves, Stock Move Lines, Stock Quants, reservation counters, or Outbox records.

#### Scenario: Planning sufficient stock
- **WHEN** Allocation receives a complete Movement demand and eligible Position supply
- **THEN** it returns a ready proposal without persisting a reservation or changing any authoritative model

#### Scenario: Planning insufficient stock
- **WHEN** eligible supply cannot satisfy the configured all-or-nothing policy
- **THEN** Allocation returns complete shortage information and no partial committed detail

### Requirement: Reservation owns the authoritative commitment boundary
Reservation SHALL own proposal revalidation, lock-and-commit orchestration, move-to-quant commitment detail, reservation-counter changes, release, committed-result publication, and assignment wake-up coordination. The existing `StockMoveLine` representation SHALL remain the durable active commitment detail unless a separate capability change introduces another representation.

#### Scenario: Committing a ready proposal
- **WHEN** Reservation receives a ready Allocation proposal
- **THEN** it locks and revalidates authoritative Movement and Position state before atomically reserving Quants, writing Stock Move Lines, changing Movement state, and publishing the committed result

#### Scenario: Releasing a reservation
- **WHEN** an assigned Stock Operation is released while execution remains reversible
- **THEN** Reservation atomically releases Position counters, removes active Stock Move Lines, and returns the canonical Movement to its confirmed state

#### Scenario: Persistence ports express reservation ownership
- **WHEN** persistence operations create, load, or delete active Stock Move Lines
- **THEN** those operations are exposed through a Reservation-owned Application port rather than remaining mixed with the Stock Move command repository

### Requirement: Position owns the current materialized stock state
Position SHALL own `StockQuant`, its identity dimensions, on-hand and reserved counters, quantity invariants, canonical write ordering, command persistence, receipt-side position changes, and stock-position visibility. `StockQuant` SHALL remain current materialized state and SHALL NOT be represented as an immutable transaction ledger.

#### Scenario: Reserving stock changes only commitment quantity
- **WHEN** Reservation commits eligible quantity from a Stock Quant
- **THEN** Position increases reserved quantity without changing on-hand quantity and preserves the Quant invariant

#### Scenario: Completing physical movement changes current position
- **WHEN** the existing exact-execution workflow completes an inbound or outbound Movement
- **THEN** Position applies the corresponding current-balance change in the same transaction without creating a placeholder Posting record

### Requirement: Location owns stock spatial identity
Location SHALL own `StockLocation`, location usage, location persistence, and location visibility. The former `warehouse` umbrella SHALL NOT remain as Inventory domain ownership; actual warehouse execution SHALL remain outside Inventory in `wms-context`.

#### Scenario: Movement resolves configured endpoints
- **WHEN** Movement registration resolves an operation type and its source and destination locations
- **THEN** it reads Movement-owned operation configuration and Location-owned spatial identity without importing WMS execution models

### Requirement: Cross-domain workflows are not promoted to domains
Intake, receipt, registration, completion, cancellation, lifecycle publication, visibility, retry observation, subscription identity, and Temporal activities SHALL be classified as Application workflows, read sides, entrypoints, or infrastructure. Each workflow SHALL be placed with the authoritative business result it produces, and a generic `lifecycle` or `support` bucket SHALL NOT own business rules.

#### Scenario: Order intake attempts assignment
- **WHEN** an Order event is translated into Inventory work
- **THEN** the Application workflow registers Movement intent and invokes Reservation assignment without making Order a member of the Inventory domain model

#### Scenario: Technical support is relocated
- **WHEN** a subscription identity, retry metric, logger, or observer is shared across workflows
- **THEN** it resides with its owning entrypoint or context-level observability infrastructure and does not become a miscellaneous Domain package

### Requirement: Ownership refactoring preserves runtime behavior
The ownership change SHALL preserve allocation algorithms, SHIP_COMPLETE/FIFO/FEFO semantics, lock order, transaction boundaries, idempotency, optimistic concurrency, Outbox atomicity, persisted representations, SQL behavior, REST schemas, integration-event schemas, and externally observable E2E behavior.

#### Scenario: Existing workflow executes after reorganization
- **WHEN** receipt, order allocation, availability wake-up, cancellation, completion, query, Events, and Temporal scenarios run after the ownership refactor
- **THEN** they produce the same committed database and external-event outcomes as before the refactor

#### Scenario: Source movement appears as deletion and addition
- **WHEN** version control cannot identify a package relocation as a rename
- **THEN** review proves that the corresponding business type still exists under its new owner and no implemented capability was removed

### Requirement: Future domains require independent business facts
Posting, Traceability, Inventory Control, and richer Availability SHALL remain documented extension seams and SHALL NOT receive placeholder entities, tables, repositories, interfaces, or empty packages in this change.

#### Scenario: Current execution remains exact
- **WHEN** planned Stock Move Lines must exactly equal completed execution
- **THEN** completion updates current Position without introducing a Posting domain

#### Scenario: Planned and actual execution diverge in a future requirement
- **WHEN** a future requirement permits partial execution, lot substitution, difference, correction, or reversal
- **THEN** that requirement is implemented through a separate capability change that introduces immutable Posting facts before Position projection

### Requirement: Architecture tests enforce policy rather than implementation shape
Inventory architecture fitness functions SHALL enforce bounded-context isolation, the five domain ownership boundaries, inward layer dependencies, framework-free Domain code, transport-independent Application code, explicit ports, and absence of retired ownership packages. They SHALL NOT enumerate exact production files or inspect method bodies, local variable names, comments, or incidental class morphology.

#### Scenario: Legal internal refactor
- **WHEN** an implementation changes class shape without violating ownership or dependencies
- **THEN** architecture tests continue to pass and behavioral tests determine whether runtime behavior remains correct
