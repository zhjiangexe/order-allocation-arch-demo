## ADDED Requirements

### Requirement: Initial allocation and backorder waking use the same allocation semantics

An initial allocation attempt and a backorder wake attempt SHALL use the same allocation decision
and assignment semantics. Both paths SHALL preserve the existing owner and facility isolation,
FIFO order selection, FEFO batch selection, whole-order rule, and stock-lock ordering.

The two paths SHALL reuse the same allocation responsibility without invoking each other's
application use case. The initial path remains responsible for demand intake and outbound movement
recording; the wake path remains responsible for selecting an already-recorded bounded queue.

#### Scenario: Initial allocation applies the shared semantics

- **GIVEN** newly recorded outbound movements whose complete demand is covered by allocatable stock
- **WHEN** their initial allocation is attempted
- **THEN** stock is assigned using the same whole-order and batch-selection rules used by a wake
  attempt

#### Scenario: A wake round applies the shared semantics

- **GIVEN** waiting outbound movements selected in FIFO order after stock becomes available
- **WHEN** a bounded wake round is attempted
- **THEN** the movements are assigned using the same whole-order and batch-selection rules used by
  an initial attempt

#### Scenario: Neither flow delegates to the other flow

- **WHEN** initial allocation and backorder waking are inspected
- **THEN** each flow invokes the shared allocation responsibility directly
- **AND** neither application use case invokes the other application use case

### Requirement: Stock availability triggers convergent backorder allocation

A confirmed receipt SHALL commit its completed inbound execution and a
`StockAvailabilityIncreased` Outbox fact together. It SHALL NOT execute backorder allocation in the
receipt transaction. The fact's Integration Event handler and a periodic reconciliation scheduler
SHALL invoke the same transactional wake use case and the same FIFO/FEFO allocation semantics.

Every bounded round SHALL process at most the configured order limit. It SHALL NOT publish an
orchestration-only continuation event. Remaining eligible pending-demand queues SHALL be discovered by periodic
scheduled reconciliation. Event retries and overlap with the scheduler SHALL be safe:
already-assigned movements and reserved quantities SHALL NOT be applied twice.

#### Scenario: Receipt commits before allocation

- **GIVEN** a local receipt confirmation makes stock available to waiting outbound movements
- **WHEN** the receipt transaction commits
- **THEN** its completed inbound execution, physical stock increase, and availability fact commit
  together
- **AND** no waiting outbound movement is assigned by that receipt transaction

#### Scenario: Availability event triggers a prompt wake

- **GIVEN** a committed availability fact for a pending-demand queue with waiting outbound movements
- **WHEN** its Integration Event is consumed
- **THEN** the handler claims the message and invokes one transactional bounded wake round

#### Scenario: Scheduler reconciles a pending-demand queue

- **GIVEN** waiting outbound movements remain because an event was delayed, lost, or exhausted
- **WHEN** the reconciliation scheduler scans eligible pending-demand queue keys
- **THEN** it invokes the same transactional bounded wake use case without transport metadata
- **AND** allocation converges according to the same queue and stock rules

#### Scenario: A new order cannot bypass an older waiting demand

- **GIVEN** newly available stock has committed but its availability event has not yet allocated an
  older waiting picking
- **WHEN** a newer order attempts immediate allocation for any shared owner, location, and SKU
- **THEN** the newer order remains waiting behind the older picking
- **AND** the event or scheduler later allocates them through the same FIFO queue

#### Scenario: A full round leaves remaining work for reconciliation

- **GIVEN** a wake round processes the configured maximum number of candidate orders
- **WHEN** the round completes
- **THEN** no continuation event is recorded with the transaction
- **AND** the scheduler can discover the remaining eligible queue on a later scan
- **AND** the next bounded round runs in a separate transaction without repeating the receipt
  operation

### Requirement: Confirmed receipts create warehouse execution before physical stock changes

`StockPool` SHALL be the stock context's physical inventory source of truth. A local receipt
confirmation SHALL create an inbound picking and move, complete that move with a move line pointing
to the identified owner, location, SKU, in-date, and expiry-date batch, and change physical stock
only through that completed line. It SHALL record the availability fact in the same transaction,
while backorder allocation runs after commit.

#### Scenario: Receipt confirmation updates physical stock through movement completion

- **GIVEN** a synchronous receipt request identifies one stock batch and a positive quantity
- **WHEN** `ConfirmStockReceiptUsecase` handles it
- **THEN** one inbound picking, move, and move line are recorded and completed
- **AND** the matching batch is increased or a new batch is created from that move line

#### Scenario: The use case validates the selected receipt location

- **GIVEN** a synchronous receipt request identifies a facility and one of its internal locations
- **WHEN** the REST adapter invokes `ConfirmStockReceiptUsecase`
- **THEN** the adapter does not access a stock-location repository
- **AND** the use case verifies that the location is internal and belongs to the facility before
  recording the movement
- **AND** the picking, move, physical stock, and availability fact use that location

#### Scenario: A facility may offer multiple receipt locations

- **GIVEN** a facility has more than one internal stock location
- **WHEN** the stock UI prepares a receipt
- **THEN** it can list those locations and submit the selected `locationId`
- **AND** the receipt is not silently redirected to the operation type's default destination

#### Scenario: Receipt and availability fact commit atomically

- **GIVEN** a receipt confirmation increases available physical stock
- **WHEN** its transaction commits
- **THEN** the completed inbound execution, stock increase, and availability Outbox fact commit
  together
- **AND** backorder assignments belong to a later transaction

### Requirement: Allocation transaction boundaries are independent of their orchestrator

Allocation commands, in-process wake-round results, and transactional use cases SHALL NOT depend on Kafka event classes
or Temporal SDK types. An entrypoint SHALL translate transport input into an application command
before invoking the use case. Business-fact Integration Events remain
the channel by which outcomes reach other bounded contexts.

This requirement establishes a shared application seam; it does not enable a Temporal runtime.
If a future Workflow branches on a retried Activity result, the adapter SHALL introduce that result
with durable replay or authoritative reconstruction of the original committed result.

#### Scenario: An Integration Event adapter invokes the transactional boundary

- **GIVEN** an allocation-related Integration Event has been received
- **WHEN** its handler invokes application logic
- **THEN** the handler maps the event and metadata into transport-neutral command input
- **AND** the transactional use case contains no dependency on the Kafka event class

#### Scenario: A future Activity wraps one complete transaction

- **GIVEN** a Temporal adapter is introduced after durable result replay is available
- **WHEN** it invokes initial allocation or a bounded wake round
- **THEN** one Activity invokes one complete transactional use case
- **AND** `StockOperationRecorder` and `MovementAssigner` are not exposed as separate
  Activities merely because they are separate application components

#### Scenario: A retried Activity observes its original result

- **GIVEN** a transactional use case committed but its Activity completion was not recorded
- **WHEN** Temporal retries the same deterministic invocation
- **THEN** the adapter returns the original committed result without repeating business side effects

## MODIFIED Requirements

### Requirement: Allocation publishes its outcome and writes only its own tables

Allocation SHALL record an outcome by writing its own tables and publishing an integration event.
It SHALL NOT load, mutate or save the order aggregate, and one transaction SHALL modify one
aggregate.

The application flow that owns an allocation attempt SHALL record its outcome after assignment;
the reusable assignment responsibility SHALL NOT publish that outcome independently. An
unsuccessful initial attempt SHALL publish exactly one backorder event. An unsuccessful wake
attempt SHALL publish no additional backorder event. Every successful initial or wake attempt SHALL
publish exactly one allocation-completed event for the allocated order.

The event SHALL be the only channel by which the outcome reaches ordering. Stating the same outcome
both through a shared transaction and through an event would require the two to be kept in
agreement, and only one of them has a consumer.

#### Scenario: A completed initial allocation touches only allocation's tables

- **WHEN** a new order is allocated in full
- **THEN** the stock row and assignment execution state are written, exactly one allocation-completed
  event is appended for publication, and no row in `orders` or `order_lines` is modified in that
  transaction

#### Scenario: An unsuccessful initial attempt records one backorder

- **WHEN** newly recorded demand cannot be satisfied in full
- **THEN** no stock is assigned, exactly one backorder event is appended for publication, and no row
  in `orders` or `order_lines` is modified in that transaction

#### Scenario: An unsuccessful wake attempt records no duplicate backorder

- **GIVEN** an order already has waiting outbound movements because its initial attempt failed
- **WHEN** a wake round still cannot satisfy it in full
- **THEN** no stock is assigned and no additional backorder or allocation-completed event is
  appended for that order

#### Scenario: A successful wake attempt records completion once

- **GIVEN** an order has waiting outbound movements that can now be satisfied in full
- **WHEN** a wake round assigns them
- **THEN** exactly one allocation-completed event is appended for that order
- **AND** no backorder event is appended by the wake attempt
