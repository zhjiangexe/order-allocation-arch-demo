## ADDED Requirements

### Requirement: A stock-consuming source creates an allocation demand

Any source that requires inventory to be reserved before warehouse execution SHALL create an `AllocationDemand`. The demand SHALL identify its source type, canonical source id, and stable allocation-unit key. A canonical source id SHALL be globally unique within its source type; an adapter whose raw id is namespace-local SHALL include that namespace when producing the canonical id. The allocation-unit key SHALL come from a source-stable business split identity and SHALL NOT be derived solely from mutable location or picking configuration. The demand SHALL contain one or more demand lines with allocation-owned demand-line ids, source-line references, SKU codes, positive integer base-unit quantities, and an allocation-owned canonical line sequence.

The quantity aggregated for one SKU SHALL also remain a positive integer within the persistence quantity range. Aggregation SHALL use checked arithmetic, and acceptance SHALL reject the whole source allocation unit before creating demand, movement, picking, or reservation records when a sum overflows or exceeds that range.

Inbound receipts and operations whose purpose is only to add or move supply without competing for available stock SHALL NOT create an allocation demand.

#### Scenario: An order creates an allocation demand

- **WHEN** an order requests outbound stock from a location
- **THEN** the allocation context creates a pending demand with source type `ORDER`, a canonical order source id, a stable allocation-unit key, and one line for each requested order line

#### Scenario: A non-order stock-consuming source creates an allocation demand

- **WHEN** an internal transfer requests stock from a source location
- **THEN** the allocation context creates a pending demand with source type `TRANSFER` and no order id requirement

#### Scenario: An inbound receipt does not create an allocation demand

- **WHEN** goods are received into a stock location
- **THEN** the receipt creates or completes an inbound movement and increases supply without creating a pending allocation demand

#### Scenario: Same-SKU line aggregation cannot overflow

- **GIVEN** one source allocation unit contains positive lines for the same SKU whose sum exceeds the supported integer quantity range
- **WHEN** the source request is accepted
- **THEN** the request is rejected as an invalid demand and no allocation demand or execution record is created

### Requirement: Source-specific behavior remains outside allocation demand

`AllocationDemand` SHALL contain only data and transitions common to stock allocation: source allocation-unit identity, allocation scope, demand lines, allocation status, and allocation version. Source-specific statuses, cancellation policy, fulfillment lifecycle, and post-allocation actions SHALL remain in the source adapter or source context.

The allocation context SHALL use `sourceType`, canonical `sourceId`, and `allocationUnitKey` to address a source allocation unit, but SHALL NOT load or embed the source aggregate or external-id mapping as part of the allocation-demand model.

#### Scenario: An order-specific status is not stored on allocation demand

- **WHEN** an order-backed demand is created
- **THEN** the demand stores the order source reference and allocation state without storing order fulfillment status

#### Scenario: A source adapter handles its own completion

- **WHEN** a transfer-backed allocation completion fact is published
- **THEN** the transfer adapter handles the transfer-specific transition and the allocation demand remains responsible only for allocation state

### Requirement: Allocation demand creation is idempotent per source allocation unit

The allocation context SHALL enforce uniqueness for `(sourceType, sourceId, allocationUnitKey)`. The source adapter SHALL supply a canonical internal source id and derive a stable and reproducible allocation-unit key for every split demand; it SHALL NOT use a retry-specific random value or a value that changes when location or picking configuration changes. The first-version order adapter SHALL use `PRIMARY` because it creates exactly one allocation unit per order. An accepted or cancelled first-version order SHALL NOT be amended or reopened under the same order id; a replacement SHALL use a new order id. Mapping an external-system id to the canonical source id SHALL remain outside the allocation-demand model.

Normalized accepted content SHALL include owner, facility, source location, required-by time, release priority, source-provided execution intent, and canonical demand-line content. Demand lines SHALL be canonicalized by stable source-line id so transport ordering does not change equality; duplicate source-line ids in one allocation unit SHALL be rejected. The allocation context SHALL derive and persist a unique immutable line sequence from that canonical order.

The allocation context SHALL persist an accepted-content schema version on the demand and keep normalized fields in their owning demand, line, movement, and picking records so structural equality does not require loading the source aggregate or duplicating execution intent into the demand aggregate. Structural equality SHALL compare only immutable accepted execution intent from movement and picking records, including normalized source/destination locations, operation direction or picking type, and grouping policy. It SHALL NOT compare mutable execution state, reservations, move lines, actual stock batches, or completion timestamps. Time values SHALL be normalized to the persistence-supported UTC precision and nullable/default values SHALL use the accepted-content version's fixed representation. The comparator SHALL NOT rely on an unversioned opaque serialized hash as the sole correctness check. Allocation-generated ids, enqueue time, and persistence timestamps SHALL NOT participate in accepted-content equality.

Reprocessing the same identity with the same normalized accepted content SHALL return the existing allocation demand unchanged. Reprocessing that identity with different accepted content SHALL fail with a source-demand conflict and SHALL NOT mutate the existing demand or create a competing demand.

#### Scenario: A retried source message does not duplicate demand

- **GIVEN** an allocation demand already exists for `ORDER/order-1/PRIMARY`
- **WHEN** the same normalized source message is processed again
- **THEN** the existing allocation demand is returned unchanged and no second demand is created

#### Scenario: Conflicting content cannot overwrite an existing demand

- **GIVEN** an allocation demand already exists for `ORDER/order-1/PRIMARY` with quantity 5
- **WHEN** the same source allocation-unit identity is submitted with quantity 7
- **THEN** creation fails with a source-demand conflict and the existing quantity remains 5

#### Scenario: Transport line ordering does not create a false conflict

- **GIVEN** an allocation demand was accepted with source lines A and B
- **WHEN** the same identity and line content is retried with B listed before A
- **THEN** the existing demand is returned because canonical line content is unchanged

#### Scenario: Execution progress does not create a false source conflict

- **GIVEN** an accepted demand's movements and picking have advanced from their initial execution states without changing their immutable execution intent
- **WHEN** the identical normalized source message is processed again
- **THEN** the existing demand is returned and mutable movement, reservation, picking, and completion fields do not participate in the comparison

#### Scenario: Changed scheduling content is not silently accepted

- **GIVEN** an allocation demand was accepted with one required-by time and release priority
- **WHEN** the same identity is retried with a different required-by time or release priority
- **THEN** creation fails with a source-demand conflict and the accepted scheduling snapshot remains unchanged

#### Scenario: Mutable location configuration cannot create a second order demand

- **GIVEN** `ORDER/order-1/PRIMARY` was accepted from location-A
- **WHEN** the outbound picking configuration changes to location-B and the order message is retried
- **THEN** creation fails with a source-demand conflict and no `ORDER/order-1/location-B` demand is created

#### Scenario: A cancelled order demand cannot be reopened with changed content

- **GIVEN** `ORDER/order-1/PRIMARY` is cancelled with its accepted content retained
- **WHEN** order-1 is submitted again with changed lines, quantity, scope, scheduling, or execution intent
- **THEN** creation fails with a source-demand conflict and the cancelled demand is not replaced or reopened

#### Scenario: A replacement order has a new source identity

- **GIVEN** order-1 was cancelled and the business needs a replacement order
- **WHEN** a new order with order id order-2 is accepted
- **THEN** the allocation context creates `ORDER/order-2/PRIMARY` without reusing order-1's allocation-unit identity

### Requirement: Allocation demand owns only allocation state

An allocation demand SHALL use the allocation states `PENDING`, `ALLOCATED`, and `CANCELLED`.

`PENDING` SHALL mean that the demand still competes for stock. `ALLOCATED` SHALL mean that the requested quantity has been successfully reserved. `CANCELLED` SHALL mean that allocation is no longer valid.

Fulfillment states such as picked, packed, shipped, and completed SHALL remain owned by movements, pickings, or the source context and SHALL NOT be duplicated as allocation-demand states.

This version SHALL NOT transition an allocated demand back to pending. A reservation invalidated by short pick, quarantine, damage, or inventory discrepancy requires a separate compensation or reallocation capability and SHALL NOT be represented as an ordinary source cancellation.

#### Scenario: A pending demand becomes allocated only after a complete plan commits

- **WHEN** an allocation plan reserves every demand line successfully
- **THEN** the demand changes from `PENDING` to `ALLOCATED`

#### Scenario: A failed allocation remains pending

- **WHEN** one demand line cannot be supplied in full
- **THEN** no allocation demand state changes and the demand remains `PENDING`

#### Scenario: A short pick is not silently requeued

- **GIVEN** an allocated demand whose warehouse execution reports a short pick
- **WHEN** the allocation-demand state is evaluated
- **THEN** it does not automatically return to `PENDING` and a separate compensation capability is required

### Requirement: An allocation demand has one inventory scope

An allocation demand SHALL belong to exactly one owner, facility, and source location. A source request spanning multiple source locations SHALL be split into separate allocation demands with different stable allocation-unit keys before allocation.

For the order adapter, the source location SHALL be the facility outbound picking type's `defaultFromLocationId` captured at first acceptance, while the allocation-unit key remains `PRIMARY`. The demand source location SHALL equal every related outbound movement's source location and every stock pool location reserved for the demand. The source location SHALL belong to the recorded facility.

#### Scenario: A multi-location source is split before allocation

- **WHEN** a transfer requests SKU-A from location-A and SKU-B from location-B
- **THEN** the allocation context creates separate demands with reproducible allocation-unit keys and never presents both locations as one allocation basket

#### Scenario: An order uses the configured outbound source location

- **GIVEN** an order identifies a facility that has multiple internal locations
- **WHEN** the order adapter creates its allocation demand
- **THEN** it uses the outbound picking type's default source location instead of expanding the order across every internal location

### Requirement: Allocation candidates are demand-first and execution-aware

The candidate query used by availability wake-up and reconciliation SHALL select pending allocation demands and SHALL include only candidates that have an unassigned outbound demand movement. If a candidate has a picking, the picking SHALL not be cancelled or completed. A stock-availability predicate MAY prefilter candidates, but the allocation decision SHALL perform the final quantity and ship-complete check.

The query SHALL return a candidate as a demand with its lines and references to the movements that will be updated; it SHALL NOT use the presence of an order id as the generic definition of an allocation demand.

#### Scenario: A non-order outbound demand is eligible

- **GIVEN** a pending transfer demand with a confirmed outbound movement and available stock
- **WHEN** the allocation candidate query runs
- **THEN** the transfer demand is returned even though it has no order id

#### Scenario: An inbound movement is not an allocation candidate

- **GIVEN** a confirmed inbound movement and available stock at its destination
- **WHEN** the allocation candidate query runs
- **THEN** the inbound movement is not returned as a demand candidate

### Requirement: Allocation cancellation stops only reversible execution

Cancelling a pending allocation demand SHALL cancel its unassigned demand movements. Cancelling an allocated demand SHALL be accepted only after a warehouse cancellation coordinator has confirmed that external execution has not started or has been stopped, and while every local execution record remains reversible. The allocation context SHALL NOT infer external WMS state. External warehouse coordination SHALL complete before the local database cancellation transaction starts. An accepted allocated cancellation SHALL release its stock reservations and cancel its associated execution state in one local transaction.

Each cancellation operation SHALL be identified by `(allocationDemandId, cancellationOperationId)` and SHALL persist external-decision progress separately from local completion. Reprocessing the same operation id SHALL retain the same external decision and SHALL NOT reevaluate a rejection against later external state. If external cancellation was confirmed but the local cancellation transaction did not commit, a retry SHALL resume reservation release and local execution cancellation until the operation completes; it SHALL NOT return success while leaving the allocation locally active. The external coordinator SHALL accept the cancellation operation id as its idempotency key. The order adapter SHALL use the existing `OrderCancelledIntegrationEvent.eventId` as its cancellation operation id without changing the event payload. A source adapter SHALL serialize create and cancel commands for one allocation unit, or SHALL persist a cancellation tombstone so a late create cannot revive a cancelled source.

If external execution cancellation cannot be confirmed, the generic cancellation operation SHALL return a not-cancellable result and SHALL leave allocation demand, reservation, movement, and picking state unchanged. Physical compensation after warehouse work has started is outside the allocation-demand lifecycle.

#### Scenario: A pending demand is cancelled without reserving stock

- **GIVEN** a pending demand with confirmed unassigned movements
- **WHEN** its source is cancelled
- **THEN** the demand becomes `CANCELLED`, its unassigned movements are cancelled, and no stock reservation is created

#### Scenario: A reversible allocated demand releases stock on cancellation

- **GIVEN** an allocated demand with reserved stock, confirmed external execution cancellation, and reversible local execution records
- **WHEN** its source is cancelled
- **THEN** the reservation is released and the demand and its execution movements become cancelled

#### Scenario: Unconfirmed warehouse cancellation is refused

- **GIVEN** an allocated demand for which external execution cancellation cannot be confirmed
- **WHEN** generic allocation cancellation is requested
- **THEN** the request returns not cancellable and leaves the demand, reservations, movements, and picking unchanged

#### Scenario: A rejected cancellation retry keeps its decision

- **GIVEN** cancellation operation `cancel-1` was rejected for an allocated demand
- **WHEN** `cancel-1` is delivered again after external state changes
- **THEN** the same rejected decision is returned and no allocation state is changed

#### Scenario: Confirmed external cancellation resumes an incomplete local commit

- **GIVEN** cancellation operation `cancel-2` has a durable external-confirmed decision but the process failed before its local reservation-release transaction committed
- **WHEN** `cancel-2` is retried
- **THEN** the operation resumes the local cancellation transaction, releases reservations exactly once, and becomes completed only after demand and execution state commit

#### Scenario: A late create cannot revive a cancelled source

- **GIVEN** a source adapter cannot guarantee ordered create and cancel delivery and has persisted a cancellation tombstone
- **WHEN** a create command for the same source allocation unit arrives later
- **THEN** no pending allocation demand or outbound movement is created
