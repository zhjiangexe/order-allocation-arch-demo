## ADDED Requirements

### Requirement: StockOperation is the canonical Inventory operation group

Inventory SHALL represent the persistent operation group that owns movement intent and policy as `StockOperation`. A
`StockOperation` SHALL identify its operation type, direction, owner, source and destination locations, canonical source allocation-unit
identity, assignment policy, required-by time, release priority and enqueue time. It SHALL contain no SKU or quantity; those facts SHALL
remain on its `StockMove` children.

The canonical aggregate, state, direction and type vocabulary SHALL be `StockOperation`, `StockOperationState`,
`StockOperationDirection` and `StockOperationType`. A move SHALL refer to its group by `stockOperationId`, and operation configuration
SHALL be referred to by `stockOperationTypeId`. The rename SHALL preserve every existing aggregate and relationship UUID.

#### Scenario: An outbound source unit registers one stock operation

- **WHEN** a stock-consuming source unit with multiple canonical lines is accepted
- **THEN** one confirmed `StockOperation` groups one confirmed `StockMove` per source line
- **AND** SKU and quantity exist only on the moves

#### Scenario: An inbound operation remains operation-driven

- **WHEN** an inbound receipt registers movement intent
- **THEN** its direction and default endpoints come from `StockOperationType`
- **AND** no order identity is required to determine its direction

#### Scenario: Existing identity survives the persistence rename

- **GIVEN** an operation group and its moves exist before the schema migration
- **WHEN** the forward migration renames the operation tables and relationship columns
- **THEN** the operation UUID, move UUIDs, source identity, state, timestamps, versions and relationships remain unchanged

### Requirement: StockOperation state remains a movement lifecycle summary

A `StockOperation` state SHALL be maintained transactionally from its complete move set and SHALL NOT become an independent
fulfillment lifecycle. `CONFIRMED` SHALL mean every move is `CONFIRMED` and no move line exists. `ASSIGNED` SHALL mean every move is
`ASSIGNED` with exact move-line coverage. `DONE` SHALL mean every move is `DONE` with retained move-line evidence. `CANCELLED` SHALL
mean every move is `CANCELLED` with no active move line.

The rename SHALL NOT change movement registration, assignment, release, completion or cancellation transitions. `StockMoveLine` SHALL
remain the reservation detail while its move is assigned and, under the exact-execution invariant, the retained execution evidence after
completion. `StockQuant` SHALL remain the materialized stock balance.

#### Scenario: Assignment changes the existing movement set coherently

- **GIVEN** every confirmed move of a stock operation has exact reservation coverage
- **WHEN** assignment commits
- **THEN** those same moves and their stock operation become `ASSIGNED` in one transaction

#### Scenario: Completion retains exact execution evidence

- **GIVEN** an assigned stock operation satisfies exact execution
- **WHEN** completion commits
- **THEN** every move and the stock operation become `DONE`
- **AND** their move lines remain as execution evidence

#### Scenario: A failed state transition preserves the prior operation

- **GIVEN** an operation transition fails validation or persistence
- **WHEN** its transaction rolls back
- **THEN** its operation summary, moves, move lines and quant balances remain in their prior coherent state

### Requirement: Inventory operations remain distinct from WMS picking work

An Inventory `StockOperation` SHALL describe movement intent, routing, policy and lifecycle summary. It SHALL NOT own WMS `Shipment`,
`Wave`, `WarehouseWork`, `PickTask`, operator, packing or short-pick state. WMS terminology that describes actual picking work SHALL
remain unchanged.

Inventory and WMS SHALL correlate an operation and its independently owned shipment by `stockOperationId`. Renaming the Inventory
aggregate SHALL NOT change the existing one-to-one correlation value or transfer ownership of warehouse execution to Inventory.

#### Scenario: Inventory assignment does not write warehouse execution

- **WHEN** Inventory persists a confirmed or assigned stock operation
- **THEN** it writes no WMS shipment, wave, warehouse work or pick task

#### Scenario: WMS creates execution from the canonical correlation

- **WHEN** WMS consumes an assigned stock-operation fact
- **THEN** it creates or finds its own shipment and work by `stockOperationId`
- **AND** genuine WMS picking vocabulary remains visible only in the WMS execution model

### Requirement: Current Inventory interfaces use stock-operation vocabulary

Current Inventory domain, application, persistence and read interfaces SHALL expose stock-operation terminology. The canonical REST
collection SHALL be `/stock-operations`, and its current representation SHALL use `operation`, `stockOperationId` and
`stockOperationTypeId` rather than picking-named equivalents.

Current database metadata SHALL use `stock_operations`, `stock_operation_types`, `stock_operation_cancellations` and
`stock_operation_id` relationship columns. Deferred database invariants SHALL be recreated against the renamed metadata and SHALL
enforce the same homogeneous-state, move-line-coverage and reservation-counter rules as before the rename.

#### Scenario: A caller reads a stock operation

- **WHEN** a caller requests an operation from `/stock-operations`
- **THEN** the response uses stock-operation vocabulary and contains no Inventory picking-named field

#### Scenario: Deferred invariants still reject an incoherent operation

- **GIVEN** the schema has been migrated to stock-operation metadata
- **WHEN** a transaction attempts to commit a mixed-state operation or invalid move-line coverage
- **THEN** the database rejects the transaction with the same invariant enforced before the rename

### Requirement: Lifecycle transactions share one validated operation working set

Assignment, release, completion and cancellation transactions SHALL derive group completeness, homogeneous state and exact move-line
coverage from the same application-internal `LockedStockOperation` abstraction. Quant-affecting transitions SHALL derive ordered quant
identities, per-quant quantities and scope validation from `QuantReservationSet`. The abstractions SHALL contain only facts loaded for the
current transaction and SHALL NOT own persistence, identity or an independent lifecycle.

Durable cancellation state, checkpoint and external WMS decision SHALL remain owned by `StockOperationCancellation`. Temporary
preparation, target, step-result and external-decision values used only by one cancellation coordinator SHALL remain internal to that
coordinator rather than becoming independent top-level application concepts. The movement lifecycle snapshot SHALL be owned by the
movement application boundary.

#### Scenario: Release and cancellation apply the same coverage rule

- **GIVEN** an assigned stock operation has incomplete or inconsistent move-line coverage
- **WHEN** release or cancellation validates its locked working set
- **THEN** either transition rejects the operation before changing move or quant state

#### Scenario: A working set cannot become durable inventory truth

- **WHEN** a lifecycle transaction completes or rolls back
- **THEN** no `LockedStockOperation` or `QuantReservationSet` identity or record remains
- **AND** `StockMoveLine` and `StockQuant` remain the only reservation detail and materialized balance truth

### Requirement: Current inventory truth roles remain explicit and non-equivalent

The current model SHALL treat `MovementAssignmentProposal` as a non-durable planning result, `StockMoveLine` as committed reservation
detail, WMS shipment and work records as warehouse-execution truth, and `StockQuant` as the current materialized on-hand and reserved
balance. `StockQuant` SHALL NOT be described or exposed as an immutable inventory-transaction ledger.

`StockMoveLine` SHALL serve as retained completion evidence only while the exact-execution invariant guarantees that reserved and
completed quant details are identical. This change SHALL NOT claim support for independently persisted planned allocation, actual batch
confirmation, posting, correction or reversal. Adding planned-versus-actual divergence SHALL require a separate capability change and
SHALL NOT reinterpret `StockOperation` lifecycle state or `StockQuant` balance as transaction history.

#### Scenario: Exact execution keeps the compressed current model coherent

- **GIVEN** an assigned move reserves `LOT-A` quantity 10
- **WHEN** the move completes under the current exact-execution invariant
- **THEN** its retained move-line evidence still identifies `LOT-A` quantity 10
- **AND** no synthetic posting or second reservation record is created by this change

#### Scenario: Current balance is not presented as transaction history

- **GIVEN** a stock quant contains current on-hand and reserved quantities
- **WHEN** current architecture or API documentation describes that quant
- **THEN** it identifies a materialized balance rather than an immutable material-transaction ledger

#### Scenario: Planned-versus-actual divergence remains an explicit future capability

- **GIVEN** a future requirement needs planned `LOT-A` quantity 10 and actual `LOT-A` quantity 8 plus `LOT-B` quantity 2
- **WHEN** that capability is designed
- **THEN** it introduces explicit reservation, execution-confirmation and posting responsibilities in a separate change
- **AND** it does not overload `StockQuant` or `StockOperation` as the missing transaction ledger

### Requirement: Stock-operation collection reads use bounded projections

The current stock-operation collection read SHALL obtain operation, move, move-line and referenced quant data with a number of database
statements bounded independently of the number of returned operations. It SHALL NOT issue per-operation follow-up queries for moves,
move lines or quants. The projection SHALL preserve the canonical REST representation and SHALL use a read-query port separate from the
stock-operation command store.

#### Scenario: Listing more operations does not multiply query statements

- **GIVEN** the database contains one operation and then contains many operations with moves and move lines
- **WHEN** the collection projection is read for each fixture
- **THEN** both reads execute no more than the same fixed query-count upper bound
- **AND** neither read performs a move, move-line or quant query once per operation

#### Scenario: Projection optimization preserves the response

- **GIVEN** an operation has moves, assigned move lines and referenced quants
- **WHEN** the bounded projection is returned from `/stock-operations`
- **THEN** its operation, movement, reservation and quant values match the canonical representation before the query refactor
