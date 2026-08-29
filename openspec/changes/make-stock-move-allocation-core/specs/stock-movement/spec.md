## ADDED Requirements

### Requirement: A picking groups stock-consuming movements without duplicating line facts

A stock-consuming `StockPicking` SHALL identify its operation type, direction, owner, source and destination locations, canonical source
allocation-unit identity, assignment policy, required-by time, release priority and enqueue time. It SHALL contain no SKU or quantity.
Its state SHALL be a transactionally maintained summary of its moves and SHALL NOT be an independent fulfillment lifecycle.

The combination `(sourceType, sourceId, allocationUnitKey)` SHALL be unique. A source request spanning more than one source location SHALL
be split before registration. Inventory `StockPicking` SHALL remain distinct from WMS `Shipment`; Inventory SHALL NOT own wave, task,
operator or packing state.

#### Scenario: One source unit creates one movement group

- **WHEN** a stock-consuming source unit with multiple canonical lines is accepted
- **THEN** one confirmed picking groups one confirmed move per source line
- **AND** SKU and quantity exist only on the moves

#### Scenario: Inventory grouping does not create warehouse work

- **WHEN** a confirmed or assigned Inventory picking is persisted
- **THEN** no WMS shipment, wave or pick task is written by Inventory

### Requirement: Assigned picking cancellation requires reversible warehouse execution

Cancelling a confirmed picking SHALL require no WMS coordination because no warehouse execution exists. Cancelling an assigned picking
SHALL require the WMS cancellation coordinator to confirm, using `pickingId` and a stable cancellation-operation id, that execution has
not started or has stopped. Rejection, timeout or an indeterminate response SHALL leave the picking, moves, move lines and quant counters
unchanged.

After durable WMS confirmation, one local transaction SHALL release reserved counters, remove active move lines, cancel every reversible
move and its picking, and append the cancellation fact to Outbox. Retrying the same cancellation operation SHALL reuse its external
decision and SHALL NOT repeat side effects.

A trusted source-terminal fact MAY carry that durable checkpoint when its boundary contract guarantees it is emitted only after WMS
execution is safe. Inventory SHALL persist the carried confirmation under the cancellation-operation id before changing local state;
it SHALL NOT call WMS again. A source request that lacks this guarantee SHALL still use the WMS cancellation coordinator.

#### Scenario: Confirmed picking cancellation is local

- **GIVEN** a confirmed picking has no move lines or WMS execution
- **WHEN** its source is cancelled
- **THEN** its moves and picking become `CANCELLED` without an external cancellation call

#### Scenario: Assigned picking cancellation waits for WMS confirmation

- **GIVEN** an assigned picking has reserved move lines
- **WHEN** WMS confirms that execution is reversible
- **THEN** Inventory releases the reservation and cancels the moves and picking in one local transaction

#### Scenario: Unconfirmed warehouse cancellation changes nothing

- **GIVEN** an assigned picking whose WMS execution cannot be confirmed reversible
- **WHEN** cancellation is attempted
- **THEN** the picking remains assigned and its move lines and quant counters remain unchanged

#### Scenario: Cancellation retry does not release twice

- **GIVEN** an assigned-picking cancellation operation has already completed
- **WHEN** the same cancellation-operation id is retried
- **THEN** the persisted decision and result are returned without repeating counter or state changes

#### Scenario: A trusted terminal fact reuses the completed warehouse checkpoint

- **GIVEN** a source-terminal event can only be emitted after WMS cancellation is durably complete
- **WHEN** Inventory consumes that event for an assigned picking
- **THEN** it records the carried warehouse confirmation and cancels locally without calling WMS again

### Requirement: A stock-consuming movement identifies its source line

Every stock-consuming move SHALL belong to one stock-consuming picking and SHALL identify one stable `sourceLineId`. The combination
`(pickingId, sourceLineId)` SHALL be unique. The move SHALL carry the SKU, positive demand quantity and explicit source and destination
locations copied from the accepted operation intent.

Generic movement persistence SHALL NOT require `allocationDemandId`, `allocationDemandLineId`, `orderId` or `orderLineId`. A source adapter
SHALL canonicalize source-specific identifiers before registration and SHALL validate any source-specific type conversion used when
publishing an outcome.

#### Scenario: An order line becomes a canonical movement

- **WHEN** an order adapter accepts one order line for stock consumption
- **THEN** the registered move carries its canonical source-line identity, SKU, quantity and both endpoints
- **AND** it carries no allocation-demand reference

#### Scenario: A duplicate source line is rejected

- **GIVEN** a picking already contains a move for one source-line identity
- **WHEN** another move with the same picking and source-line identity is persisted
- **THEN** persistence rejects the duplicate

## MODIFIED Requirements

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
- **THEN** the move is eligible as part of its picking without requiring another demand record

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

### Requirement: Recording a movement is a step of its own

A supply-only or inbound movement SHALL be recordable independently. A stock-consuming source acceptance SHALL record one confirmed
picking and its complete confirmed move set before attempting allocation. Movement registration SHALL NOT load or mutate the source
aggregate.

An allocation shortage SHALL leave the existing moves confirmed and SHALL create no move lines or reserved counter changes. Assignment
SHALL mutate those existing moves and SHALL NOT replace them with newly generated assigned moves.

#### Scenario: An inbound movement is recorded independently

- **GIVEN** a receipt from a supplier
- **WHEN** the receipt is accepted
- **THEN** an inbound movement can be recorded without any allocation-demand identity

#### Scenario: Insufficient outbound supply retains confirmed movements

- **GIVEN** an accepted stock-consuming picking with insufficient stock
- **WHEN** its initial assignment is attempted
- **THEN** its existing moves remain `CONFIRMED` with no move lines

#### Scenario: Assignment preserves the movement identity

- **GIVEN** a confirmed move has a complete eligible assignment proposal
- **WHEN** assignment commits
- **THEN** that same move becomes `ASSIGNED` instead of being replaced by another move

### Requirement: Picking direction does not depend on order identity

Inbound, outbound and internal operation semantics SHALL be determined by operation type or direction, never by order identity. A
stock-consuming source SHALL register an Inventory `StockPicking` before assignment. That picking SHALL group movement intent and policy;
it SHALL NOT represent WMS warehouse execution.

#### Scenario: Inbound picking remains operation-driven

- **WHEN** an inbound receipt creates a picking
- **THEN** its direction comes from operation configuration rather than an order id

#### Scenario: Outbound acceptance creates an inventory picking

- **WHEN** an order-backed stock-consuming source is accepted
- **THEN** it creates a confirmed Inventory picking and confirmed moves before assignment

#### Scenario: WMS owns physical work grouping

- **WHEN** Inventory publishes an assigned picking snapshot
- **THEN** WMS creates its own shipment and warehouse tasks idempotently from `pickingId`

## REMOVED Requirements

### Requirement: A demand movement identifies its allocation demand line

**Reason**: The allocation-demand line duplicates the movement requirement and makes move identity begin only after reservation.

**Migration**: Link a stock-consuming move directly to its picking and stable source-line identity; planners and move lines use `moveId`.
