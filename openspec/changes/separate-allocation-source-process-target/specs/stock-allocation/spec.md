## MODIFIED Requirements

### Requirement: Allocation takes its demand from a published view, never from the order aggregate

Allocation SHALL read pending work from allocation-owned demand and demand-line persistence. A candidate SHALL include allocation-demand identity, source allocation-unit identity, inventory scope, resolved destination, scheduling snapshot, and every canonical demand line. Allocation SHALL NOT load or mutate a source aggregate and SHALL NOT reconstruct source intent from a movement or picking.

The queue SHALL contain whole demands. Strict FIFO precedence SHALL be scoped by owner, facility, source stock location, and SKU using `(enqueuedAt, allocationDemandId)`. A candidate SHALL be eligible exactly when no earlier `PENDING` demand in the same inventory scope has an intersecting SKU set. Required-by time, release priority, destination, and target execution state SHALL NOT reorder this version's FIFO relation.

Queue discovery MAY obtain a bounded raw head list, but before planning each candidate the transaction SHALL evaluate the exact predecessor relation. A stock-availability prefilter SHALL NOT make an unavailable predecessor disappear from precedence. One transaction SHALL commit at most one demand; later demands SHALL be reconsidered in subsequent bounded iterations.

#### Scenario: Allocation reads source snapshots without source aggregates

- **WHEN** a pending demand is selected
- **THEN** allocation receives the persisted demand and all lines without loading the order or any pre-existing movement

#### Scenario: A shared SKU establishes precedence

- **GIVEN** an earlier demand needs SKU-A and SKU-B and a later demand needs SKU-B and SKU-C in the same inventory scope
- **WHEN** the later demand is evaluated
- **THEN** the earlier pending demand blocks it because their SKU sets intersect

#### Scenario: Disjoint demands are independent

- **GIVEN** an earlier demand needs only SKU-A and a later demand needs only SKU-B in the same inventory scope
- **WHEN** SKU-B is reconciled
- **THEN** the later demand is not blocked by the earlier demand

#### Scenario: An unavailable predecessor cannot be filtered away

- **GIVEN** an earlier shared-SKU demand is currently short of another SKU
- **WHEN** a later otherwise-satisfiable demand is evaluated
- **THEN** the exact predecessor check rejects the later demand

### Requirement: Allocation publishes one canonical order outcome and writes only allocation-owned tables

Allocation SHALL write only allocation-owned demand, stock, movement, movement-line, and outbox tables. It SHALL NOT create an outbound inventory `StockPicking`, load a source aggregate, or write WMS-owned `Shipment`, wave, or `PickTask` records.

The allocation-demand id SHALL be the canonical allocation id across the completion result and integration event. A successful transaction SHALL carry that id, the full source allocation-unit identity, allocation-demand-line identities, and assigned movement details. A source-specific publication adapter MAY map source references but SHALL NOT replace the allocation id with a picking id.

For an order-backed demand, exactly one `OrderAllocationCommittedIntegrationEvent` SHALL be written to Outbox. Ordering and WMS SHALL consume that fact under separate subscription identities. WMS SHALL create and own its shipment and warehouse execution grouping idempotently from the allocation-demand id.

#### Scenario: The demand id crosses the allocation boundary

- **GIVEN** a successful demand has id `demand-1`
- **WHEN** its order allocation event is published
- **THEN** `allocationId` is `demand-1` and no outbound inventory picking id is required

#### Scenario: WMS owns warehouse grouping

- **WHEN** WMS consumes the committed allocation event
- **THEN** it creates or finds warehouse execution by allocation-demand id without allocation writing WMS tables

#### Scenario: A transfer completion does not require an order event

- **WHEN** a transfer-backed demand commits
- **THEN** the core result remains source-addressable and no order-specific event is required

### Requirement: A multi-SKU allocation demand is satisfiable only when every one of its SKUs is

A pending demand SHALL be planned only after it passes the exact shared-SKU predecessor check. It SHALL be satisfiable only when allocatable stock covers the aggregate requested quantity of every SKU. A short SKU SHALL prevent the entire demand from committing and SHALL leave all stock and demand state unchanged.

The planner SHALL report every short SKU. For repeated SKU lines it SHALL perform the sufficiency check on the aggregate and distribute FEFO batch quantities back to demand lines in immutable canonical line-sequence order. This version supports strict shared-SKU FIFO and all-or-nothing allocation only.

#### Scenario: One SKU short leaves the demand untouched

- **GIVEN** a demand needs SKU-A and SKU-B and SKU-B is short
- **WHEN** it is planned
- **THEN** no reservation or movement is created for either SKU and the demand remains pending

#### Scenario: Every SKU covered creates a complete plan

- **GIVEN** every SKU aggregate is covered and the demand has no predecessor
- **WHEN** it is planned
- **THEN** the immutable plan covers every demand line

#### Scenario: Repeated SKU lines are deterministic

- **GIVEN** two canonical lines request the same SKU across multiple FEFO batches
- **WHEN** the same snapshot is planned repeatedly
- **THEN** batch quantities map to demand-line ids identically in canonical line order

### Requirement: Waking a queue loads every SKU its candidates need

Availability wake-up and reconciliation SHALL first discover a bounded set of whole pending demands from the affected inventory scope. Before planning a candidate, the transaction SHALL reject it when an earlier intersecting-SKU demand exists. It SHALL then load allocatable batches for every SKU in the eligible candidate, not only the triggering SKU.

The set of stock rows one attempt may touch SHALL be known before reservations are written, and repository query count SHALL NOT grow with the number of stock batches. A predecessor commit SHALL cause its successor to be reconsidered in a later bounded iteration, not in the same transaction.

#### Scenario: Another required SKU is loaded

- **GIVEN** a demand requires SKU-A and unavailable SKU-B
- **WHEN** SKU-A wakes its scope
- **THEN** the attempt also evaluates SKU-B and leaves SKU-A unreserved

#### Scenario: A predecessor blocks before stock planning

- **GIVEN** a candidate has an earlier pending demand sharing one SKU
- **WHEN** the candidate attempt begins
- **THEN** it stops before reserving or materializing target execution

#### Scenario: A successor is reconsidered after commit

- **GIVEN** two allocatable demands share a SKU
- **WHEN** the earlier demand commits
- **THEN** the later demand is considered in a subsequent bounded iteration

## ADDED Requirements

### Requirement: Allocation materializes its target atomically

For one eligible and fully satisfiable demand, a single transaction SHALL reload the current demand and relevant stock-row versions, reserve the planned batches, create exactly one outbound `StockMove` per demand line, create `StockMoveLine` reservation details, transition every new movement directly to `ASSIGNED`, mark the demand `ALLOCATED`, and append its completion fact to Outbox. Demand and stock writes SHALL use optimistic version checks so a concurrent winner rolls the whole transaction back.

The transaction SHALL use allocation-demand id as execution grouping identity. It SHALL NOT depend on pre-existing outbound movements or pickings. If any validation, reservation, movement creation, or Outbox write fails, the transaction SHALL roll back all target effects and leave the demand pending.

#### Scenario: A successful plan creates a coherent target

- **WHEN** a complete eligible plan commits
- **THEN** assigned movements and reservation lines exist for every demand line, the demand is allocated, and one completion fact is persisted

#### Scenario: A commit failure leaves no partial target

- **GIVEN** one movement or reservation write fails
- **WHEN** the allocation transaction rolls back
- **THEN** no target record remains and the demand stays pending

## REMOVED Requirements

### Requirement: Outstanding demand is decided by whether a movement exists

**Reason**: Pending demand status is the sole queue truth; a movement is a committed target produced only after allocation succeeds.

**Migration**: Remove movement-state and picking-state predicates from pending queries and remove pre-created outbound execution records from demand acceptance.

### Requirement: Allocation satisfies movements, not orders directly

**Reason**: Allocation satisfies an allocation demand and atomically materializes movements. Neither an order nor a pre-existing movement is the generic process input.

**Migration**: Replace movement-update commit code with demand-plan materialization and correlate resulting movements by allocation-demand-line id.
