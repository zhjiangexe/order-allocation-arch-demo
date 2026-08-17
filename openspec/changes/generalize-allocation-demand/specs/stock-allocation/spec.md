## MODIFIED Requirements

### Requirement: Allocation takes its demand from a published view, never from the order aggregate

Allocation SHALL obtain its candidates from the allocation context's persisted `AllocationDemand` model, mapped into read-only decision types. It SHALL NOT load or mutate an order, transfer, replenishment, or production aggregate while deciding allocation.

An allocation demand SHALL carry its source reference, owner, facility, source location, and all of its demand lines. It SHALL NOT require an order id. The allocation queue SHALL contain whole allocation demands with every line needed for the ship-complete decision, not only the line matching the SKU that triggered the wake-up.

The wake limit SHALL count allocation demands, matching the unit returned by the candidate query.

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

### Requirement: Allocation publishes a generic outcome and writes only allocation-owned tables

Allocation SHALL record its result by writing allocation-owned demand, stock, movement, and picking tables and by publishing a generic allocation fact. It SHALL NOT load, mutate, or save the source aggregate.

The completion fact SHALL carry the allocation-demand identity and source reference so each source context can process only its own results. A source-specific consumer MAY translate the generic fact into a source-specific state change.

#### Scenario: A completed order allocation is source-addressable

- **WHEN** an order-backed allocation commits successfully
- **THEN** the allocation demand is marked allocated, execution records are updated, and the completion fact carries source type `ORDER` and the order source id

#### Scenario: A completed transfer allocation does not require an order event

- **WHEN** a transfer-backed allocation commits successfully
- **THEN** the allocation context publishes the same generic completion fact with source type `TRANSFER`, without requiring an order-specific event

### Requirement: A multi-SKU allocation demand is satisfiable only when every one of its SKUs is

An allocation demand SHALL be satisfiable only when, for every SKU it names, allocatable stock covers that SKU's aggregated quantity. One SKU falling short SHALL prevent the whole demand from allocating, and SHALL leave every other SKU's stock untouched.

The check SHALL NOT short-circuit when reporting shortfall; all insufficient SKUs SHALL be identified.

#### Scenario: One SKU short blocks the whole demand

- **GIVEN** a demand for 10 of SKU-A and 5 of SKU-B, with 100 of SKU-A allocatable and 3 of SKU-B
- **WHEN** the demand is allocated
- **THEN** no reservation is created for either SKU and SKU-A remains available

#### Scenario: Every SKU covered allocates the whole demand

- **GIVEN** a demand for two SKUs and allocatable stock covers both quantities
- **WHEN** the demand is allocated
- **THEN** reservations exist for every demand line and the demand becomes allocated

### Requirement: Waking a queue loads every SKU its candidates need

Availability wake-up and reconciliation SHALL identify candidate allocation demands from the affected scope, then load allocatable batches for every SKU those candidates name, not only the SKU that triggered the wake-up.

The set of stock rows a wake round may touch SHALL be known before allocation plans are committed, and the number of repository queries SHALL NOT grow with the number of candidates.

#### Scenario: A candidate's other SKU is judged against its own stock

- **GIVEN** a pending demand for one unit each of SKU-A and SKU-B, with no SKU-B available
- **WHEN** SKU-A becomes available
- **THEN** the demand is not allocated and the new SKU-A stock remains unreserved

#### Scenario: A blocked FIFO candidate prevents a later candidate from bypassing it

- **GIVEN** the first candidate needs unavailable SKU-B and a later candidate needs only available SKU-A
- **WHEN** the queue is reconciled
- **THEN** the first candidate remains pending and the later candidate is not allocated ahead of it

## REMOVED Requirements

### Requirement: Outstanding demand is decided by whether a movement exists

**Reason**: The existence of a `StockMove` is an execution fact and is no longer the authoritative lifecycle of a generic allocation demand. Non-order sources also need a durable pending demand, and inbound movements must not be inferred as demand.

**Migration**: Create `AllocationDemand` records for stock-consuming sources, link their demand lines to outbound movements, and query pending demands with execution-aware predicates.

### Requirement: Allocation satisfies movements, not orders directly

**Reason**: The queue is no longer defined solely by movement state or order identity. Allocation satisfies a persisted allocation demand and then applies its plan to the related movements.

**Migration**: Replace movement-only waiting queries with demand-first candidate queries while preserving FIFO, FEFO, ship-complete, batch limits, and optimistic-locking behavior.
