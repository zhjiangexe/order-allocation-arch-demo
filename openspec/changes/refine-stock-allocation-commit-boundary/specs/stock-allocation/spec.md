## ADDED Requirements

### Requirement: A ready stock allocation proposal commits through one cohesive boundary

The allocation application SHALL commit a ready `StockAllocationProposal` through an internal `StockAllocationCommitter`. The
committer SHALL expose a `commit` operation and SHALL keep proposal validation, canonical operation/move/quant locking, final
predecessor validation, quant reservation, `StockMoveLine` creation, operation/move assignment and integration-event publication inside
one database transaction.

The commit operation SHALL read as an ordered application script whose stages are explicit. Detailed assignment-result construction
SHALL be delegated to a focused result factory. Inventory persistence ports SHALL use the `Repository` suffix for aggregate persistence
and focused read projections without merging their responsibilities. Repository properties and constructor parameters SHALL use their
complete lower-camel type names. The boundary SHALL NOT expose shortened `store`, `query`, generic `repository`, `execute`, picking,
batch or snapshot vocabulary for allocation commit facts.

The move-to-quant quantity mapping shared by proposal commit, release and completion SHALL use one ephemeral, stage-neutral lifecycle
working model. It SHALL NOT represent a persisted Allocation aggregate or a parallel reservation ledger.

The shared initial and replenishment-wake application flow SHALL be coordinated by `StockOperationAssignmentCoordinator`. The complete
transaction-scoped `StockOperation`, `StockMove` and `StockMoveLine` structure SHALL be represented by
`StockOperationComposite`. The composite SHALL NOT claim to acquire or own database locks and SHALL NOT be modeled as a value object or
persisted aggregate; `ForUpdate` loader vocabulary SHALL expose lock acquisition at the application transaction boundary.

#### Scenario: A ready proposal becomes authoritative atomically

- **GIVEN** a ready `StockAllocationProposal` whose operation, moves, predecessor and selected quants remain valid
- **WHEN** `StockAllocationCommitter.commit` is invoked
- **THEN** it reserves the selected quants, creates exact `StockMoveLine` rows and assigns the existing moves and operation
- **AND** it publishes the assignment result through Outbox in the same transaction

#### Scenario: A stale proposal changes no authoritative state

- **GIVEN** a ready proposal whose operation, moves, predecessor, quant scope, expiry or available quantity changed after planning
- **WHEN** the committer performs its locked revalidation
- **THEN** it rejects the proposal before any reservation, move-line, state or Outbox change commits

#### Scenario: An assigned replay is reconstructed without duplicate effects

- **GIVEN** the proposal's operation is already consistently `ASSIGNED` with exact move-line coverage
- **WHEN** the same proposal reaches the committer again
- **THEN** it reconstructs the committed assignment result
- **AND** it does not reserve stock, create move lines or publish the event again

#### Scenario: Lifecycle mapping does not create a second reservation model

- **GIVEN** move-to-quant quantities originate from proposed move lines or committed move lines
- **WHEN** assignment, release or completion requires quant aggregation and lock ordering
- **THEN** the application uses the same ephemeral move-to-quant allocation mapping
- **AND** no independent Allocation or reservation record is persisted

#### Scenario: Coordination and locking remain explicit

- **GIVEN** an initial assignment or replenishment-wake attempt
- **WHEN** the application selects, plans and commits the operation
- **THEN** `StockOperationAssignmentCoordinator` exposes the orchestration role
- **AND** `StockOperationComposite` contains the complete operation structure without pretending to own its database locks

#### Scenario: Repository roles remain explicit at call sites

- **GIVEN** Inventory aggregate persistence and read projections use separate focused ports
- **WHEN** an application service declares or invokes those dependencies
- **THEN** each port uses a role-complete `Repository` type name
- **AND** its field and constructor parameter use the corresponding complete lower-camel name rather than `store`, `query` or generic
  `repository`
