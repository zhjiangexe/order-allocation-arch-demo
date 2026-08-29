# stock-movement Specification

## Purpose

TBD - created by archiving change 'record-every-movement'. Update Purpose after archive.

## Requirements

### Requirement: Every movement of goods is recorded with both of its ends

A movement SHALL record where the goods come from and where they go, as two
locations, and SHALL carry a state saying how far it has got. Neither end may be
absent.

Recording only one end is what the system does today: a reservation says which
batch was locked for which order line, and never says where those goods are bound.
The missing half has to be supplied the moment goods actually leave, and supplying
it then means reinterpreting every reservation already recorded.

Both ends being locations — rather than one being a location and the other implied
— is what makes the total quantity across all places conserved. Conservation is
what turns "the numbers changed and nobody knows why" from an unanswerable
question into a detectable one.

#### Scenario: A movement without a destination cannot be recorded

- **WHEN** a movement is written with a source but no destination
- **THEN** the write is refused

#### Scenario: A movement records the batch it actually draws on

- **GIVEN** an order line needing stock that is held as two batches
- **WHEN** the movement for that line is satisfied from both
- **THEN** the movement carries one line per batch, each naming the batch and the
  quantity taken from it

---

### Requirement: Movement state covers only transitions this system performs

Movement state SHALL be constrained by the database to `CONFIRMED`, `ASSIGNED`, `DONE` and `CANCELLED`.

`CONFIRMED` SHALL mean that the movement requirement exists but holds no stock. `ASSIGNED` SHALL mean that move lines identify stock
currently reserved for the complete movement. `DONE` SHALL mean that the retained move lines record completed physical execution.
`CANCELLED` SHALL mean that the movement will not execute and holds no stock.

Assignment SHALL transition `CONFIRMED -> ASSIGNED`. Releasing a still-valid reservation SHALL transition `ASSIGNED -> CONFIRMED` after
removing its move lines. Completion SHALL transition `ASSIGNED -> DONE`. Cancellation SHALL transition a reversible `CONFIRMED` or
`ASSIGNED` move to `CANCELLED`, releasing assigned stock first. `DONE` SHALL be terminal.

A waiting-for-predecessor state and a partially-available state SHALL NOT be introduced until movement chaining or a partial assignment
policy is implemented.

#### Scenario: A confirmed movement is pending work

- **GIVEN** a stock-consuming move is confirmed
- **WHEN** pending allocation work is queried
- **THEN** the move is eligible as part of its stock operation without requiring another demand record

#### Scenario: Release preserves movement identity

- **GIVEN** an assigned move whose requirement remains valid
- **WHEN** its reservation is released
- **THEN** the same move returns to `CONFIRMED` and can be assigned again

#### Scenario: A completed movement cannot be reopened

- **GIVEN** a move is `DONE`
- **WHEN** release or cancellation is attempted
- **THEN** the operation is rejected

#### Scenario: A state outside the set is refused

- **WHEN** a movement is persisted with a state outside the permitted set
- **THEN** the database rejects the write

### Requirement: Reservation is a stage of a movement, not a parallel ledger

Reserved stock SHALL be expressed only by `StockMoveLine` rows whose parent stock-consuming move is `ASSIGNED`. Each line SHALL identify
the exact stock quant and positive quantity reserved for its move. No `Allocation`, `AllocationSlice` or other parallel reservation
lifecycle SHALL duplicate those facts.

For every quant, its reserved counter SHALL equal the sum of move-line quantities belonging to assigned stock-consuming moves for that
quant. Releasing SHALL decrement the counters and delete the active move lines before returning moves to `CONFIRMED`. Cancelling an
assigned move SHALL perform the same release before entering `CANCELLED`. Completing SHALL decrement on-hand and reserved counters,
retain the move lines as execution evidence and enter `DONE`. A state-changing release, cancellation or completion SHALL append its
lifecycle audit fact to Outbox in the same transaction.

#### Scenario: Assigning creates the only current reservation detail

- **GIVEN** a confirmed movement is fully satisfiable
- **WHEN** assignment commits
- **THEN** its assigned move lines identify every reserved quant and quantity
- **AND** no parallel allocation-slice record exists

#### Scenario: Releasing assigned goods returns them to available

- **GIVEN** a movement whose goods are assigned from a batch
- **WHEN** it is released
- **THEN** the batch reserved counter is decremented, its move lines are removed and the move becomes `CONFIRMED`

#### Scenario: Completion retains execution evidence

- **GIVEN** an assigned outgoing movement with exact move-line coverage
- **WHEN** it completes
- **THEN** on-hand and reserved quantities are decremented, the move becomes `DONE` and its move lines remain

#### Scenario: Release audit commits with the state change

- **GIVEN** an assigned movement is released
- **WHEN** the release transaction commits
- **THEN** its reservation removal and lifecycle audit fact commit together

### Requirement: An operation type says where its movements run between

An operation type SHALL carry a code saying whether it brings goods in, sends them
out, or moves them internally, together with the default source and destination
locations for that kind of work.

This is what keeps "a new kind of operation" a row of data rather than a code
change. The failure it avoids is well documented in systems that did not do it:
an outbound type enumeration grown to eighteen values, each added by editing and
redeploying.

**It does not say what follows what.** An operation type has no reference to a
next type; sequencing movements into a chain is a separate concern that this
system does not have. What it settles is what one leg looks like.

#### Scenario: A new kind of operation needs no code change

- **WHEN** an operation type is added with its own code and default locations
- **THEN** movements can be recorded under it without altering any enumeration

---

### Requirement: Recording a movement is a step of its own

A supply-only or inbound movement SHALL be recordable independently. A stock-consuming source acceptance SHALL record one confirmed
stock operation and its complete confirmed move set before attempting allocation. Movement registration SHALL NOT load or mutate the source
aggregate.

An allocation shortage SHALL leave the existing moves confirmed and SHALL create no move lines or reserved counter changes. Assignment
SHALL mutate those existing moves and SHALL NOT replace them with newly generated assigned moves.

#### Scenario: An inbound movement is recorded independently

- **GIVEN** a receipt from a supplier
- **WHEN** the receipt is accepted
- **THEN** an inbound movement can be recorded without any allocation-demand identity

#### Scenario: Insufficient outbound supply retains confirmed movements

- **GIVEN** an accepted stock-consuming stock operation with insufficient stock
- **WHEN** its initial assignment is attempted
- **THEN** its existing moves remain `CONFIRMED` with no move lines

#### Scenario: Assignment preserves the movement identity

- **GIVEN** a confirmed move has a complete eligible assignment proposal
- **WHEN** assignment commits
- **THEN** that same move becomes `ASSIGNED` instead of being replaced by another move

### Requirement: Goods arriving are a movement from a supplier

Stock arriving SHALL be recorded as a movement from a supplier location into the warehouse's
internal location, under a dispatch document of the inbound operation type. That document
SHALL carry no order, because nothing was ordered through this system — the goods are the
owner's, arriving on the owner's arrangement.

This is the case the nullable order reference on a dispatch document exists for. Until now
every document served an order; the field was nullable in anticipation, and this requirement
is where that anticipation is paid.

Receiving SHALL NOT consult availability, reserve anything, or apply the whole-order rule.
Those belong to satisfying demand, and nothing is being satisfied here.

#### Scenario: Arriving goods produce a document with no order

- **GIVEN** a replenishment for a SKU at a warehouse
- **WHEN** it is recorded
- **THEN** a dispatch document of the inbound type exists with no order
- **AND** its movement runs from a supplier location to that warehouse's internal location

#### Scenario: An inbound movement is not outstanding demand

- **GIVEN** an inbound movement exists
- **WHEN** the queue of movements needing goods is read
- **THEN** that movement is not offered for allocation, because its document serves no order

---

### Requirement: Stock on hand changes only through a completed movement line

The quantity a stock row holds SHALL change only when a movement line says it did, and a
movement line SHALL name the stock row it applies to. Code that can reach stock SHALL NOT be
able to change what is held without such a line.

This is the whole point of recording movements. Without it, any code holding the stock
repository can change the numbers, and a wrong change leaves no trace — the three questions
the movement tables exist to answer (where did this come from, why did it drop, which
document moved it) stay unanswerable for anything that took the shortcut.

The line, not the movement, SHALL be what applies the change. A movement states what is to happen;
a line states what did, and to which row. Odoo draws the same line: its move-level
completion filters and flips state, and delegates the quant change to the lines.

**Only the increase is in scope.** Completion of an outgoing movement — the decrease —
arrives with shipping. A symmetric implementation where half has no caller would rot; the
uncalled half SHALL fail loudly rather than exist untested.

#### Scenario: Stock cannot be increased without a line

- **GIVEN** a stock row
- **WHEN** something attempts to increase what it holds without a movement line naming it
- **THEN** it cannot — the operation does not exist

#### Scenario: A line applying to a different row is refused

- **GIVEN** a movement line naming one stock row
- **WHEN** it is applied to a different row
- **THEN** it is refused

#### Scenario: Completing an inbound movement increases what is held

- **GIVEN** an inbound movement with a line naming a stock row
- **WHEN** the movement completes
- **THEN** that row holds the line's quantity more than before
- **AND** the movement is done

#### Scenario: Completing an outgoing movement is refused for now

- **GIVEN** a movement whose source is an internal location
- **WHEN** completion is attempted
- **THEN** it fails, because the decrease is not implemented until shipping exists

---

### Requirement: Stock operation direction does not depend on order identity

Inbound, outbound and internal operation semantics SHALL be determined by operation type or direction, never by order identity. A
stock-consuming source SHALL register an Inventory `StockOperation` before assignment. That stock operation SHALL group movement intent and policy;
it SHALL NOT represent WMS warehouse execution.

#### Scenario: Inbound stock operation remains operation-driven

- **WHEN** an inbound receipt creates a stock operation
- **THEN** its direction comes from operation configuration rather than an order id

#### Scenario: Outbound acceptance creates an inventory stock operation

- **WHEN** an order-backed stock-consuming source is accepted
- **THEN** it creates a confirmed Inventory stock operation and confirmed moves before assignment

#### Scenario: WMS owns physical work grouping

- **WHEN** Inventory publishes an assigned stock operation snapshot
- **THEN** WMS creates its own shipment and warehouse tasks idempotently from `stockOperationId`

### Requirement: A facility and a stock location are distinct concepts

A `Facility` SHALL identify the physical logistics operation site responsible for site-level policy
and contention. A `StockLocation` SHALL identify a concrete inventory or movement endpoint. Current
code and unreleased contracts SHALL use `facilityId` for the former and `locationId` for the latter;
the legacy aliases `nodeId`, `fulfillmentNodeId`, and `warehouseId` SHALL NOT remain.

#### Scenario: A movement keeps its concrete endpoints

- **GIVEN** a facility is responsible for a stock operation
- **WHEN** the operation records a movement
- **THEN** its facility-level configuration is selected by `facilityId`
- **AND** the movement source and destination remain explicit stock-location identifiers

### Requirement: A stock operation groups stock-consuming movements without duplicating line facts

A stock-consuming `StockOperation` SHALL identify its operation type, direction, owner, source and destination locations, canonical source
allocation-unit identity, assignment policy, required-by time, release priority and enqueue time. It SHALL contain no SKU or quantity.
Its state SHALL be a transactionally maintained summary of its moves and SHALL NOT be an independent fulfillment lifecycle.

The combination `(sourceType, sourceId, allocationUnitKey)` SHALL be unique. A source request spanning more than one source location SHALL
be split before registration. Inventory `StockOperation` SHALL remain distinct from WMS `Shipment`; Inventory SHALL NOT own wave, task,
operator or packing state.

#### Scenario: One source unit creates one movement group

- **WHEN** a stock-consuming source unit with multiple canonical lines is accepted
- **THEN** one confirmed stock operation groups one confirmed move per source line
- **AND** SKU and quantity exist only on the moves

#### Scenario: Inventory grouping does not create warehouse work

- **WHEN** a confirmed or assigned Inventory stock operation is persisted
- **THEN** no WMS shipment, wave or pick task is written by Inventory

### Requirement: Assigned stock operation cancellation requires reversible warehouse execution

Cancelling a confirmed stock operation SHALL require no WMS coordination because no warehouse execution exists. Cancelling an assigned stock operation
SHALL require the WMS cancellation coordinator to confirm, using `stockOperationId` and a stable cancellation-operation id, that execution has
not started or has stopped. Rejection, timeout or an indeterminate response SHALL leave the stock operation, moves, move lines and quant counters
unchanged.

After durable WMS confirmation, one local transaction SHALL release reserved counters, remove active move lines, cancel every reversible
move and its stock operation, and append the cancellation fact to Outbox. Retrying the same cancellation operation SHALL reuse its external
decision and SHALL NOT repeat side effects.

A trusted source-terminal fact MAY carry that durable checkpoint when its boundary contract guarantees it is emitted only after WMS
execution is safe. Inventory SHALL persist the carried confirmation under the cancellation-operation id before changing local state;
it SHALL NOT call WMS again. A source request that lacks this guarantee SHALL still use the WMS cancellation coordinator.

#### Scenario: Confirmed stock operation cancellation is local

- **GIVEN** a confirmed stock operation has no move lines or WMS execution
- **WHEN** its source is cancelled
- **THEN** its moves and stock operation become `CANCELLED` without an external cancellation call

#### Scenario: Assigned stock operation cancellation waits for WMS confirmation

- **GIVEN** an assigned stock operation has reserved move lines
- **WHEN** WMS confirms that execution is reversible
- **THEN** Inventory releases the reservation and cancels the moves and stock operation in one local transaction

#### Scenario: Unconfirmed warehouse cancellation changes nothing

- **GIVEN** an assigned stock operation whose WMS execution cannot be confirmed reversible
- **WHEN** cancellation is attempted
- **THEN** the stock operation remains assigned and its move lines and quant counters remain unchanged

#### Scenario: Cancellation retry does not release twice

- **GIVEN** an assigned-stock operation cancellation operation has already completed
- **WHEN** the same cancellation-operation id is retried
- **THEN** the persisted decision and result are returned without repeating counter or state changes

#### Scenario: A trusted terminal fact reuses the completed warehouse checkpoint

- **GIVEN** a source-terminal event can only be emitted after WMS cancellation is durably complete
- **WHEN** Inventory consumes that event for an assigned stock operation
- **THEN** it records the carried warehouse confirmation and cancels locally without calling WMS again

### Requirement: A stock-consuming movement identifies its source line

Every stock-consuming move SHALL belong to one stock-consuming stock operation and SHALL identify one stable `sourceLineId`. The combination
`(stockOperationId, sourceLineId)` SHALL be unique. The move SHALL carry the SKU, positive demand quantity and explicit source and destination
locations copied from the accepted operation intent.

Generic movement persistence SHALL NOT require `allocationDemandId`, `allocationDemandLineId`, `orderId` or `orderLineId`. A source adapter
SHALL canonicalize source-specific identifiers before registration and SHALL validate any source-specific type conversion used when
publishing an outcome.

#### Scenario: An order line becomes a canonical movement

- **WHEN** an order adapter accepts one order line for stock consumption
- **THEN** the registered move carries its canonical source-line identity, SKU, quantity and both endpoints
- **AND** it carries no allocation-demand reference

#### Scenario: A duplicate source line is rejected

- **GIVEN** a stock operation already contains a move for one source-line identity
- **WHEN** another move with the same stock operation and source-line identity is persisted
- **THEN** persistence rejects the duplicate

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
identities, per-quant quantities and scope validation from `QuantReservationSet`. The abstractions SHALL contain only facts loaded for
the current transaction and SHALL NOT own persistence, identity or an independent lifecycle.

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
