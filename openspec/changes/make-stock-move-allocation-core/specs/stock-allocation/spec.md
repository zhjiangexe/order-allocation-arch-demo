## ADDED Requirements

### Requirement: Allocation takes its demand from confirmed movements, never from a source aggregate

Allocation SHALL select a complete stock-consuming `StockPicking` and its confirmed moves. It SHALL NOT load or mutate an Order,
Transfer or other source aggregate and SHALL NOT reconstruct movement intent from a duplicate allocation-demand model.

Strict FIFO precedence SHALL be scoped by owner, source stock location and intersecting confirmed-move SKUs using
`(picking.enqueuedAt, picking.id)`. A candidate SHALL be eligible exactly when no earlier confirmed stock-consuming picking in that scope
has an intersecting confirmed SKU set. Required-by time, release priority, destination and current ATP SHALL NOT reorder this relation.
The final predecessor check SHALL occur inside the assignment transaction, and one transaction SHALL assign at most one picking.

#### Scenario: A shared confirmed SKU establishes precedence

- **GIVEN** an earlier confirmed picking needs SKU-A and SKU-B and a later confirmed picking needs SKU-B and SKU-C in the same scope
- **WHEN** the later picking is evaluated
- **THEN** the earlier picking blocks it because their confirmed SKU sets intersect

#### Scenario: Disjoint confirmed pickings are independent

- **GIVEN** an earlier confirmed picking needs only SKU-A and a later confirmed picking needs only SKU-B in the same scope
- **WHEN** SKU-B availability is reconciled
- **THEN** the later picking is not blocked by the earlier picking

#### Scenario: An unavailable predecessor remains visible

- **GIVEN** an earlier shared-SKU picking is currently short of another SKU
- **WHEN** a later otherwise-satisfiable picking is evaluated
- **THEN** the exact predecessor check rejects the later picking

### Requirement: Assignment publishes one canonical picking outcome

An assignment transaction SHALL write only Inventory stock, picking, movement, movement-line and Outbox facts. It SHALL NOT load a source
aggregate or write WMS-owned Shipment, Wave or PickTask records.

The Inventory `pickingId` SHALL be the stable operation-group identity across the assignment result and move-centric integration contract.
The result SHALL include source-unit trace, move identities and current batch-pick details. For an order-backed picking, exactly one
order-allocation committed fact SHALL be written to Outbox; Ordering and WMS SHALL consume it under separate subscription identities.

The breaking move-centric payload SHALL use a new contract version. Consumers SHALL accept both legacy and move-centric versions before
the producer switches. Legacy readers SHALL remain until source-topic retention, Outbox re-snapshot exposure and DLT replay windows have
expired; removing legacy readers SHALL require a later change.

#### Scenario: Picking identity crosses the assignment boundary

- **GIVEN** an assigned Inventory picking has id `picking-1`
- **WHEN** its assignment fact is published
- **THEN** the fact identifies `picking-1`, its moves and their batch picks without an allocation-demand id

#### Scenario: WMS remains a separate writer

- **WHEN** WMS consumes the assigned-picking fact
- **THEN** WMS creates or finds its execution by `pickingId` without Inventory writing WMS tables

#### Scenario: A transfer assignment needs no order event

- **WHEN** a transfer-backed picking is assigned
- **THEN** the core result remains source-addressable and no order-specific fact is required

#### Scenario: A retained legacy event remains consumable

- **GIVEN** a legacy allocation event is replayed during the compatibility window
- **WHEN** a deployed consumer receives it
- **THEN** the consumer resolves the canonical picking from the event's retained move identities and applies the legacy contract
  idempotently without treating its allocation identity as a picking identity

#### Scenario: New producers emit only the move-centric version

- **GIVEN** tolerant consumers are deployed
- **WHEN** the producer cutover completes
- **THEN** new assignment facts use the move-centric contract version and picking identity

### Requirement: A SHIP_COMPLETE picking is satisfiable only when every move is covered

A confirmed `SHIP_COMPLETE` picking SHALL be planned only after it passes the exact shared-SKU predecessor check. It SHALL be ready only
when allocatable stock covers the aggregate requested quantity of every SKU and the resulting reservation drafts exactly cover every
confirmed move. A short SKU SHALL leave all moves confirmed and SHALL reserve no stock.

The pure planner SHALL report every short SKU and SHALL return no reservation drafts for an insufficient proposal. For repeated SKU
moves, it SHALL perform sufficiency on the aggregate and distribute FEFO batch quantities in immutable move line-sequence order. Each
draft SHALL identify `moveId`, `stockQuantId` and quantity.

#### Scenario: One SKU short changes no movement or stock

- **GIVEN** a confirmed picking needs SKU-A and SKU-B and SKU-B is short
- **WHEN** it is planned
- **THEN** no move line or reservation is created and every move remains `CONFIRMED`

#### Scenario: Every move covered creates a complete proposal

- **GIVEN** every SKU aggregate is covered and the picking has no predecessor
- **WHEN** it is planned
- **THEN** the immutable proposal exactly covers every confirmed move

#### Scenario: Repeated SKU moves are deterministic

- **GIVEN** two confirmed moves request the same SKU across multiple FEFO batches
- **WHEN** the same snapshot is planned repeatedly
- **THEN** batch quantities map to move ids identically in immutable line-sequence order

### Requirement: Allocation assigns existing movements atomically

For one eligible and fully satisfiable picking, a single transaction SHALL lock and reload the picking, its moves and relevant stock
quants; verify proposal versions and exact precedence; reserve the planned quantities; create `StockMoveLine` details; transition the
existing moves to `ASSIGNED`; update the picking summary to `ASSIGNED`; and append the committed fact to Outbox.

The transaction SHALL follow the common lock order `StockPicking -> StockMove id order -> StockQuant global write order`. If any
revalidation, counter, move-line, state or Outbox write fails, every effect SHALL roll back and the picking and moves SHALL remain
confirmed. A retry after a successful commit SHALL reconstruct the original result from assigned moves and move lines without reserving
again.

#### Scenario: A successful proposal assigns a coherent existing move set

- **WHEN** a complete eligible proposal commits
- **THEN** every original move is assigned with exact move-line coverage, the picking is assigned and one committed fact is persisted

#### Scenario: A commit failure leaves the movement set confirmed

- **GIVEN** one counter, move-line, state or Outbox write fails
- **WHEN** the assignment transaction rolls back
- **THEN** no partial reservation remains and the original picking and moves remain `CONFIRMED`

#### Scenario: A committed retry does not reserve twice

- **GIVEN** an assignment committed but its caller did not observe the result
- **WHEN** the same deterministic invocation is retried
- **THEN** it returns the persisted assignment result without changing reserved quantities

### Requirement: Quant reservation counters reconcile with assigned move lines

For each stock quant, `reservedQuantity` SHALL equal the sum of quantities on move lines whose parent stock-consuming move is
`ASSIGNED`. Reconciliation SHALL report a counter mismatch, an assigned move without exact line coverage, a confirmed or cancelled move
with lines, or a `SHIP_COMPLETE` picking whose move states are mixed.

#### Scenario: A healthy assigned picking reconciles

- **GIVEN** every assigned move has exact move-line coverage and every referenced quant counter equals its assigned-line sum
- **WHEN** allocation reconciliation runs
- **THEN** it reports no anomaly for that picking or its quants

#### Scenario: Counter drift is detected

- **GIVEN** a quant reserved counter differs from the sum of its assigned outgoing move lines
- **WHEN** allocation reconciliation runs
- **THEN** it reports the mismatch and does not infer correctness from picking state alone

## MODIFIED Requirements

### Requirement: Initial allocation and backorder waking use the same allocation semantics

An initial assignment attempt and a backorder wake attempt SHALL use the same picking selection, pure planning and transactional
assignment semantics. Both paths SHALL preserve owner/location isolation, strict FIFO selection, FEFO batch selection,
`SHIP_COMPLETE` and stock-lock ordering.

The initial path SHALL register the picking and confirmed moves before invoking the shared assignment responsibility. The wake path SHALL
select an already registered confirmed picking. Neither application use case SHALL invoke the other.

#### Scenario: Initial assignment applies the shared semantics

- **GIVEN** newly registered confirmed moves whose complete picking is covered by allocatable stock
- **WHEN** their initial assignment is attempted
- **THEN** the existing moves are assigned using the same semantics used by a wake attempt

#### Scenario: A wake round applies the shared semantics

- **GIVEN** a confirmed picking selected after stock becomes available
- **WHEN** a bounded wake round is attempted
- **THEN** its moves are assigned using the same semantics used by an initial attempt

#### Scenario: Neither flow delegates to the other flow

- **WHEN** initial assignment and backorder waking are inspected
- **THEN** each flow invokes the shared assignment responsibility directly
- **AND** neither application use case invokes the other application use case

### Requirement: Stock availability triggers convergent backorder allocation

A confirmed receipt SHALL commit its completed inbound execution and a `StockAvailabilityIncreased` Outbox fact together. It SHALL NOT
assign waiting outbound pickings in the receipt transaction. The fact handler and a periodic reconciliation scheduler SHALL invoke the
same transactional bounded wake use case and FIFO/FEFO assignment semantics.

Every bounded round SHALL process at most the configured picking limit. It SHALL NOT publish an orchestration-only continuation event.
Remaining confirmed picking queues SHALL be discovered by periodic reconciliation. Event retries and overlap with the scheduler SHALL
be safe: already assigned moves and reserved quantities SHALL NOT be applied twice.

#### Scenario: Receipt commits before assignment

- **GIVEN** a local receipt makes stock available to confirmed outbound moves
- **WHEN** the receipt transaction commits
- **THEN** its completed inbound execution, physical stock increase and availability fact commit together
- **AND** no waiting outbound move is assigned by that receipt transaction

#### Scenario: Availability event triggers a prompt wake

- **GIVEN** a committed availability fact for a queue containing confirmed pickings
- **WHEN** its Integration Event is consumed
- **THEN** the handler claims the message and invokes one transactional bounded wake round

#### Scenario: Scheduler reconciles confirmed picking queues

- **GIVEN** confirmed pickings remain because an event was delayed, lost or exhausted
- **WHEN** the reconciliation scheduler scans eligible queue keys
- **THEN** it invokes the same transactional bounded wake use case without transport metadata

#### Scenario: A new picking cannot bypass an older shared-SKU picking

- **GIVEN** an older confirmed picking remains unassigned after stock becomes available
- **WHEN** a newer picking attempts immediate assignment for any shared owner, location and SKU
- **THEN** the newer picking remains confirmed behind the older picking

#### Scenario: A full round leaves bounded work for reconciliation

- **GIVEN** a wake round processes the configured maximum number of pickings
- **WHEN** the round completes
- **THEN** no continuation event is recorded and a later scheduler round can discover the remaining confirmed work

## REMOVED Requirements

### Requirement: Allocation takes its demand from a published view, never from the order aggregate

**Reason**: Pending requirement is now represented by confirmed moves; a persisted allocation-demand view/aggregate duplicates those
facts.

**Migration**: Select complete confirmed pickings and moves while preserving the source-aggregate boundary.

### Requirement: Allocation publishes one canonical order outcome and writes only allocation-owned tables

**Reason**: The allocation-demand identity and prohibition on outbound Inventory pickings conflict with move-centric grouping.

**Migration**: Publish the assigned picking identity and move/batch snapshot; WMS remains a separate writer.

### Requirement: A multi-SKU allocation demand is satisfiable only when every one of its SKUs is

**Reason**: `SHIP_COMPLETE` applies to the picking's confirmed move set, not a duplicate allocation-demand line set.

**Migration**: Plan aggregate SKU sufficiency and exact coverage from canonical moves.

### Requirement: Allocation materializes its target atomically

**Reason**: Moves are registered before allocation and SHALL retain identity across assignment.

**Migration**: Assign the existing confirmed moves and create only their reservation lines inside the atomic transaction.
