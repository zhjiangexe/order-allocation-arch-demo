## MODIFIED Requirements

### Requirement: Allocation takes its demand from a published view, never from the order aggregate

Allocation SHALL obtain its candidates from the allocation context's persisted `AllocationDemand` model, mapped into read-only decision types. It SHALL NOT load or mutate an order, transfer, replenishment, or production aggregate while deciding allocation.

An allocation demand SHALL carry its source reference, owner, facility, source location, and all of its demand lines. It SHALL NOT require an order id. The allocation queue SHALL contain whole allocation demands with every line needed for the ship-complete decision, not only the line matching the SKU that triggered the wake-up.

The wake limit SHALL count allocation demands, matching the unit returned by the candidate query. FIFO queues SHALL be scoped by owner, facility, source location, and SKU, and SHALL use allocation-owned `(enqueuedAt, allocationDemandId)` values; an idempotent source retry SHALL NOT refresh `enqueuedAt`. A demand SHALL be allocation-eligible only when every SKU it requires has no earlier pending demand in the same inventory scope. Scheduling snapshots such as required-by time and release priority SHALL NOT silently reorder the shared-SKU strict FIFO queues.

An earlier pending demand SHALL block a later demand when they share at least one SKU, even when the earlier demand is currently unsatisfied because of another SKU. Demands that share no SKU SHALL belong to independent queues and SHALL NOT block one another merely because they use the same owner and source location.

One allocation transaction SHALL commit at most one allocation demand. After a predecessor commits, a later demand SHALL be reconsidered by a subsequent bounded iteration; a transaction SHALL NOT lock or commit the remainder of a FIFO queue. A stock-availability prefilter SHALL NOT remove an unavailable predecessor from the precedence check.

The final writer cutover SHALL quiesce and drain legacy allocation consumers and reconciliation, run an idempotent final backfill and invariant validation, switch the single-writer feature flag, and only then resume processing through the new path. The cutover SHALL NOT require pausing order intake because unprocessed events remain in the broker and source acceptance is idempotent.

Before new consumers resume, rollback MAY switch back to legacy mode while allocation remains quiesced. After any new-path reservation, movement state, or completion event commits, rollback SHALL quiesce both paths again, reconcile shared movement and outbox state, and prove that legacy processing will not duplicate the committed work before legacy resumes. The system SHALL NOT hot-switch writers after new-path commits.

Reconciliation SHALL expose pending age, the blocking predecessor, and the blocked SKU for shared-SKU FIFO head-of-line blocking. It SHALL NOT automatically bypass a valid predecessor because it has waited too long; cancellation or a future explicit allocation policy SHALL make that business decision.

#### Scenario: Allocation does not load a source aggregate

- **WHEN** an allocation candidate is selected
- **THEN** allocation reads the persisted demand projection and does not load or mutate the source aggregate

#### Scenario: A candidate carries all of its demand lines

- **GIVEN** a transfer demand with outstanding demand for two SKUs
- **WHEN** the queue for one of those SKUs is read
- **THEN** the returned candidate carries both demand lines

#### Scenario: A non-order candidate is scoped and ordered

- **GIVEN** pending demands across two owners, two facilities, and two source locations
- **WHEN** a queue is read for one owner, facility, location, and SKU
- **THEN** only matching demands are returned in FIFO demand order

#### Scenario: A retried demand keeps its FIFO position

- **GIVEN** a pending demand already has an allocation-owned enqueue time
- **WHEN** its identical source message is retried after a later demand has arrived
- **THEN** the original enqueue time is retained and the retry does not move the demand behind the later demand

#### Scenario: A multi-SKU demand cannot bypass another SKU queue

- **GIVEN** an earlier pending demand needs SKU-B and a later demand needs SKU-A and SKU-B
- **WHEN** availability of SKU-A wakes the later demand while the earlier SKU-B demand is still pending
- **THEN** the later demand is not allocated because it has not reached precedence in the SKU-B queue

#### Scenario: Unrelated SKU queues do not block each other

- **GIVEN** an earlier pending demand needs only unavailable SKU-B and a later demand needs only available SKU-C in the same inventory scope
- **WHEN** the SKU-C queue is reconciled
- **THEN** the later SKU-C demand is not blocked by the SKU-B demand and is allocated when its own stock and execution conditions are satisfied

#### Scenario: Shadow comparison recognizes the corrected order location model

- **GIVEN** a facility has multiple internal locations and its legacy view expands one order across all of them
- **WHEN** legacy and allocation-demand candidates are compared during migration
- **THEN** a row with existing execution is normalized to its move or picking source location, a row without execution uses the configured outbound source location, extra legacy locations and allocations rejected only by the new cross-SKU predecessor check are classified as expected corrected divergences, and only unexplained demand or allocation-outcome differences block cutover

#### Scenario: Final backfill converges before the writer switch

- **GIVEN** legacy allocation consumers may have written movements after an earlier shadow backfill
- **WHEN** the final writer cutover begins
- **THEN** legacy allocation work is paused and drained before the final backfill and validation, exactly one writer is enabled, and queued source events are processed idempotently after the new path resumes

#### Scenario: Rollback after a new-path commit is reconciled before writer restart

- **GIVEN** the new writer has already committed at least one reservation or completion event
- **WHEN** operators decide to return to the legacy writer
- **THEN** both writers remain stopped until shared movement and outbox state is reconciled and the legacy path is proven not to duplicate that work

#### Scenario: A long-blocked FIFO demand is observable but not bypassed

- **GIVEN** an earlier pending demand has blocked a shared SKU because another required SKU remains unavailable
- **WHEN** its pending-age alert threshold is reached
- **THEN** monitoring identifies the demand, predecessor relationship, and blocked SKU while allocation precedence remains unchanged

### Requirement: Allocation publishes a generic outcome and writes only allocation-owned tables

Allocation SHALL record its result by writing allocation-owned demand, stock, movement, and picking tables and by publishing a generic allocation fact. It SHALL NOT load, mutate, or save the source aggregate.

The completion fact SHALL carry the allocation-demand identity, full source allocation-unit identity, and allocation-demand-line identities so each source context can process only its own results. It is an allocation-context fact and SHALL NOT directly replace existing external wire contracts.

In the first migration stage, the order adapter SHALL translate the generic fact into the existing `OrderAllocatedIntegrationEvent` v1 and `AllocationCommittedForFulfillmentIntegrationEvent` v1 with unchanged event types and payload semantics. The fulfillment v1 `allocationId` SHALL remain the order picking id used by WMS to load execution idempotently; it SHALL NOT be replaced with the new allocation-demand id. The v1 order-line id SHALL be mapped from the order source-line reference. A new source SHALL define its own integration contract in its source-adapter change.

The allocation decision input and immutable plan SHALL use `allocationDemandId` and `allocationDemandLineId` as their internal correlation keys. `sourceLineId`, `orderId`, and `orderLineId` SHALL NOT be used as allocation-core join keys.

#### Scenario: A completed order allocation is source-addressable

- **WHEN** an order-backed allocation commits successfully
- **THEN** the allocation demand is marked allocated, execution records are updated, and the completion fact carries source type `ORDER`, the canonical order source id, and its allocation-unit key

#### Scenario: A completed transfer allocation does not require an order event

- **WHEN** a transfer-backed allocation commits successfully
- **THEN** the allocation context publishes the same generic completion fact with source type `TRANSFER`, without requiring an order-specific event

#### Scenario: Existing order fulfillment consumers remain compatible

- **WHEN** an order-backed generic allocation fact is translated during the first migration stage
- **THEN** existing ordering and fulfillment consumers receive their unchanged v1 integration events

#### Scenario: The existing fulfillment allocation id keeps its meaning

- **GIVEN** an order allocation has allocation demand `demand-1` and picking `picking-1`
- **WHEN** the order adapter publishes `AllocationCommittedForFulfillmentIntegrationEvent` v1
- **THEN** its `allocationId` remains `picking-1` so WMS can load the existing execution grouping

### Requirement: A multi-SKU allocation demand is satisfiable only when every one of its SKUs is

An allocation demand SHALL be satisfiable only when, for every SKU it names, allocatable stock covers that SKU's aggregated quantity. One SKU falling short SHALL prevent the whole demand from allocating, and SHALL leave every other SKU's stock untouched.

The check SHALL NOT short-circuit when reporting shortfall; all insufficient SKUs SHALL be identified.

This version supports only shared-SKU strict FIFO and all-or-nothing allocation. A source that requires partial allocation, shared-SKU precedence bypass, or source-level atomic completion across multiple allocation units SHALL NOT enable its production adapter under this capability.

When one demand contains multiple lines for the same SKU, the planner SHALL use their aggregate quantity for the all-or-nothing sufficiency check and SHALL distribute FEFO batch quantities back to those lines in immutable canonical line-sequence order.

#### Scenario: One SKU short blocks the whole demand

- **GIVEN** a demand for 10 of SKU-A and 5 of SKU-B, with 100 of SKU-A allocatable and 3 of SKU-B
- **WHEN** the demand is allocated
- **THEN** no reservation is created for either SKU and SKU-A remains available

#### Scenario: Every SKU covered allocates the whole demand

- **GIVEN** a demand for two SKUs and allocatable stock covers both quantities
- **WHEN** the demand is allocated
- **THEN** reservations exist for every demand line and the demand becomes allocated

#### Scenario: A partial-allocation source is not enabled

- **GIVEN** a source requires allocation of available lines while unavailable lines remain pending
- **WHEN** its adapter is evaluated against this version's capability
- **THEN** the adapter is not enabled because the allocation contract is all-or-nothing

#### Scenario: Same-SKU batch details map to lines deterministically

- **GIVEN** one demand has two canonical lines for the same SKU and stock is available across multiple FEFO batches
- **WHEN** the demand is planned repeatedly from the same snapshot
- **THEN** both plans assign identical batch quantities to each allocation-demand-line id in canonical line-sequence order

### Requirement: Waking a queue loads every SKU its candidates need

Availability wake-up and reconciliation SHALL identify candidate allocation demands from the affected scope, then load allocatable batches for every SKU those candidates name, not only the SKU that triggered the wake-up.

The set of stock rows a wake round may touch SHALL be known before allocation plans are committed, and the number of repository queries SHALL NOT grow with the number of candidates.

#### Scenario: A candidate's other SKU is judged against its own stock

- **GIVEN** a pending demand for one unit each of SKU-A and SKU-B, with no SKU-B available
- **WHEN** SKU-A becomes available
- **THEN** the demand is not allocated and the new SKU-A stock remains unreserved

#### Scenario: A blocked FIFO candidate prevents a later candidate from bypassing it

- **GIVEN** the first candidate needs available SKU-A and unavailable SKU-B and a later candidate needs only available SKU-A
- **WHEN** the queue is reconciled
- **THEN** the first candidate remains pending and the later candidate is not allocated ahead of it

#### Scenario: A successor is reconsidered after its predecessor commits

- **GIVEN** two allocatable pending demands share one SKU and the earlier demand has precedence
- **WHEN** one reconciliation round processes the scope
- **THEN** the earlier demand commits in its own transaction and the later demand is reconsidered by a subsequent bounded iteration rather than the same transaction

## REMOVED Requirements

### Requirement: Outstanding demand is decided by whether a movement exists

**Reason**: The existence of a `StockMove` is an execution fact and is no longer the authoritative lifecycle of a generic allocation demand. Non-order sources also need a durable pending demand, and inbound movements must not be inferred as demand.

**Migration**: Create `AllocationDemand` records for stock-consuming sources, link their demand lines to outbound movements, and query pending demands with execution-aware predicates.

### Requirement: Allocation satisfies movements, not orders directly

**Reason**: The queue is no longer defined solely by movement state or order identity. Allocation satisfies a persisted allocation demand and then applies its plan to the related movements.

**Migration**: Replace movement-only waiting queries with demand-first candidate queries while preserving FIFO, FEFO, ship-complete, batch limits, and optimistic-locking behavior.
