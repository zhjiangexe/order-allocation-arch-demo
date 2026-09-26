## ADDED Requirements

### Requirement: Allocation treats StockOperation as its selection and atomicity boundary

Allocation SHALL select a complete confirmed stock-consuming `StockOperation` and its confirmed moves without loading its source
aggregate. The pure planner SHALL consume immutable operation, move and quant snapshots and SHALL produce only an immutable proposal;
it SHALL NOT mutate persistence or publish an outcome.

For a fully satisfiable operation, one transaction SHALL lock and reload the operation, its moves and relevant quants; revalidate the
proposal and exact predecessor; reserve the planned quantities; create `StockMoveLine` details; transition the existing moves and
operation to `ASSIGNED`; and append the committed fact to Outbox. The lock order SHALL remain `StockOperation`, then `StockMove` in ID
order, then `StockQuant` in global write order.

#### Scenario: Planning has no side effect

- **GIVEN** a confirmed stock operation and an immutable stock snapshot
- **WHEN** the planner computes an assignment proposal
- **THEN** no operation, move, move line, quant counter or Outbox fact is changed

#### Scenario: A complete proposal assigns one operation atomically

- **WHEN** an eligible complete proposal commits
- **THEN** every original move gains exact move-line coverage and becomes `ASSIGNED`
- **AND** its stock operation becomes `ASSIGNED` and one committed fact is persisted in the same transaction

#### Scenario: A commit failure leaves no partial allocation

- **GIVEN** one counter, line, state or Outbox write fails
- **WHEN** the assignment transaction rolls back
- **THEN** the stock operation and its moves remain `CONFIRMED` and no partial reservation remains

### Requirement: Allocation policies are invariant under the operation rename

Renaming the operation group SHALL NOT change `SHIP_COMPLETE`, strict shared-SKU FIFO, FEFO, owner and source-location isolation,
proposal revalidation, reservation counters or retry idempotency. Strict precedence SHALL continue to use intersecting confirmed-move
SKUs and `(operation.enqueuedAt, operation.id)` within owner and source-location scope.

A `SHIP_COMPLETE` stock operation SHALL be assignable only when every confirmed move is covered exactly. A short SKU SHALL leave every
move confirmed and SHALL reserve no stock. FEFO drafts SHALL continue to identify `moveId`, `stockQuantId` and quantity.

#### Scenario: A short SKU changes nothing

- **GIVEN** a confirmed `SHIP_COMPLETE` stock operation needs multiple SKUs and one SKU is short
- **WHEN** allocation is attempted
- **THEN** no move line or reservation is created and every move remains `CONFIRMED`

#### Scenario: An older shared-SKU operation keeps precedence

- **GIVEN** an older confirmed stock operation and a newer one share an owner, source location and confirmed SKU
- **WHEN** the newer operation is evaluated
- **THEN** it remains behind the older operation regardless of the vocabulary rename

#### Scenario: FEFO remains deterministic

- **GIVEN** a stock operation is satisfiable from multiple eligible batches
- **WHEN** the same immutable snapshots are planned repeatedly
- **THEN** the same quant quantities map to the same move IDs in deterministic order

### Requirement: Assignment outcomes publish the canonical stock-operation identity

An assignment result and every new move-centric integration contract SHALL identify the Inventory operation by `stockOperationId` and
SHALL include source-unit trace, move identities and current batch-pick details. A new producer SHALL emit only the new contract version
after tolerant consumers are deployed; it SHALL NOT dual-publish old and new facts for one assignment.

During the declared compatibility window, consumers SHALL accept both the legacy contract containing `pickingId` and the new version
containing `stockOperationId`, normalize either at ingress to one stock-operation command, and apply it idempotently. Historical Outbox
or DLT payloads SHALL NOT be rewritten. New audit publication SHALL use aggregate type `StockOperation`; history readers that span the
window SHALL normalize legacy aggregate type `StockPicking` to the same identity.

#### Scenario: A new outcome crosses contexts with one identity

- **GIVEN** an assigned stock operation has a stable UUID
- **WHEN** its assignment fact is published
- **THEN** Ordering and WMS receive `stockOperationId` with that UUID and the move-centric batch snapshot

#### Scenario: A legacy assignment fact remains consumable

- **GIVEN** a retained legacy fact containing `pickingId` is replayed during the compatibility window
- **WHEN** a tolerant consumer receives it
- **THEN** the consumer normalizes the value to `stockOperationId` at ingress and applies the fact idempotently

#### Scenario: Producer cutover does not duplicate warehouse execution

- **GIVEN** consumers accept both contract versions
- **WHEN** the producer switches to the stock-operation version
- **THEN** it publishes exactly one version for each new assignment

### Requirement: In-flight workflow history survives the terminology cutover

The canonical Temporal assignment signal SHALL be `stockOperationAssigned` with a `StockOperationAssignmentSnapshot`. During the
compatibility window, the workflow contract SHALL retain the history-visible `pickingAssigned` signal and its legacy snapshot and SHALL
convert it inside the workflow to the same stock-operation checkpoint.

New adapters SHALL send only the canonical signal. A legacy DTO or field alias SHALL remain confined to the workflow compatibility
boundary and SHALL NOT appear in Inventory or WMS domain/application APIs. Pure payload normalization SHALL NOT change workflow command
sequence, activity names, timers or branching.

#### Scenario: A new workflow receives the canonical signal

- **WHEN** a new assignment reaches an active workflow
- **THEN** the adapter sends `stockOperationAssigned` with `stockOperationId`

#### Scenario: An old workflow history can replay

- **GIVEN** a workflow history contains `pickingAssigned` and a legacy assignment snapshot
- **WHEN** the renamed workflow implementation replays that history
- **THEN** it converts the legacy snapshot to the canonical checkpoint without changing emitted commands

### Requirement: StockOperationAssigner is the canonical assignment application entry

Initial assignment after source registration, availability-driven retry and backlog reconciliation SHALL invoke one
`StockOperationAssigner` application façade. The façade SHALL coordinate candidate acquisition, pure planning and transactional apply;
an application use case SHALL NOT invoke another application use case to reconstruct that flow. Availability and backlog entry points
SHALL pass one transport-neutral `AssignmentQueueKey` and SHALL NOT wrap the same fields in a second command type.

`MovementAssignmentPlanner` SHALL remain a deterministic calculation over immutable facts. `StockOperationAssignmentTransaction` SHALL
remain an internal apply boundary and SHALL NOT be exposed as an independent adapter entry point. A backlog reconciler SHALL discover
retryable keys and invoke the same façade rather than duplicate planning or apply logic.

#### Scenario: Source registration attempts assignment through the façade

- **GIVEN** source registration creates a confirmed stock operation
- **WHEN** the registration flow attempts initial assignment
- **THEN** it invokes `StockOperationAssigner` and does not invoke the assignment transaction directly

#### Scenario: Availability and reconciliation share one flow

- **GIVEN** an availability event and a scheduled reconciliation identify the same assignment queue
- **WHEN** each trigger attempts the next candidate
- **THEN** both pass the same `AssignmentQueueKey` to `StockOperationAssigner`
- **AND** both use the same candidate, planner and transactional apply sequence

### Requirement: Assignment candidate acquisition exposes immutable source facts

`AssignmentCandidateQuery` SHALL return an immutable `AssignmentCandidate` containing a `MovementPlanningSnapshot` and an optional
`StockOperationPredecessor`. It SHALL NOT expose a mutable `StockOperation` aggregate to the planner or triggering adapter. Candidate
acquisition SHALL be treated as an optimistic read, and the assignment transaction SHALL lock the canonical operation and moves, reload affected
quants, and recheck exact predecessor and proposal validity before changing any target state.

#### Scenario: Planning cannot mutate the selected operation

- **GIVEN** a confirmed operation is eligible for planning
- **WHEN** `AssignmentCandidateQuery` returns its candidate
- **THEN** the candidate contains immutable movement facts and no mutable operation aggregate

#### Scenario: A stale candidate cannot bypass final precedence

- **GIVEN** a candidate was planned before an older intersecting operation became visible to the transaction
- **WHEN** transactional apply performs its final predecessor check
- **THEN** it rejects or skips the stale proposal without creating move lines or changing reserved counters

### Requirement: Allocation command stores and read queries have separate ports

Allocation command stores SHALL expose only identity lookup, persistence and required lock operations. Candidate selection, backlog
discovery, deterministic FEFO supply and stock-operation reconciliation SHALL use purpose-specific query ports. A shared JPA aggregate
repository SHALL NOT serve as the public port for both command persistence and those unrelated read purposes.

The backlog query SHALL expose only production reconciliation needs. An oldest-enqueued-age query with no production caller SHALL be
removed rather than retained solely for a persistence test.

#### Scenario: FEFO planning reads through a supply query

- **GIVEN** a planner needs eligible quants for multiple SKUs
- **WHEN** it loads allocatable stock
- **THEN** it uses `AllocatableStockQuery` with deterministic owner, location, SKU and FEFO scope
- **AND** it does not call a command store's generic quant listing method

#### Scenario: Backlog discovery does not enlarge the operation store

- **WHEN** reconciliation discovers assignment queues with ready work
- **THEN** it uses `AssignmentBacklogQuery`
- **AND** `StockOperationStore` remains free of backlog and queue-head discovery methods

### Requirement: Assignment apply uses ephemeral validated working sets

The assignment transaction SHALL construct an internal `LockedStockOperation` from the complete locked operation, move and move-line
set and an internal `QuantReservationSet` from proposal quantities. These working sets SHALL centralize completeness, homogeneous-state,
exact-coverage, quant-scope and reservation-delta validation while preserving the lock order
`StockOperation -> StockMove ID order -> StockQuant global write order`.

Neither working set SHALL have a persistence identity, repository or lifecycle, and neither SHALL become a parallel reservation ledger.
If any validation, save or Outbox operation fails, the complete assignment SHALL roll back.

#### Scenario: A valid proposal commits through one validated target set

- **GIVEN** a proposal exactly covers every confirmed move with in-scope quants
- **WHEN** assignment apply succeeds
- **THEN** move lines, quant reserved counters, moves, operation state and Outbox facts commit in one transaction

#### Scenario: An invalid quant scope rolls back the complete assignment

- **GIVEN** a proposal references a quant outside the operation owner or source-location scope
- **WHEN** `QuantReservationSet` is validated
- **THEN** the transaction rejects the proposal
- **AND** no move line, reservation counter, move state, operation state or Outbox fact changes
