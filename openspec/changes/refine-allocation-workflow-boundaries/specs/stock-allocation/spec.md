## ADDED Requirements

### Requirement: Initial allocation and backorder waking use the same allocation semantics

An initial allocation attempt and a backorder wake attempt SHALL use the same allocation decision
and assignment semantics. Both paths SHALL preserve the existing owner and warehouse isolation,
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

### Requirement: Stock availability and its first wake round are atomic

The first bounded backorder wake round triggered by newly available stock SHALL execute in the same
local transaction as the movement completion that increases that stock. The stock increase, all
assignments made by that round, and their outcome events SHALL commit or roll back together.

Every bounded round SHALL return a result that identifies the allocated orders and says whether the
configured order limit was reached. In the current Integration Event orchestration, a transaction
owner receiving a result that requires continuation SHALL publish one continuation request in that
transaction. Handling that continuation SHALL claim its message and run another bounded round in a
new local transaction; it SHALL NOT repeat the inbound movement or stock increase.

#### Scenario: The first wake round commits with the stock increase

- **GIVEN** completing an inbound movement makes stock available to waiting outbound movements
- **WHEN** the first wake round assigns one or more waiting orders
- **THEN** the stock increase, assignments, and allocation-completed events commit in one local
  transaction

#### Scenario: Failure rolls back the first wake round and stock increase

- **GIVEN** an inbound movement is being completed and its first wake round has started
- **WHEN** that transaction fails before commit
- **THEN** neither the stock increase nor any assignment or outcome event from that round is
  committed

#### Scenario: A full round requests separate continuation

- **GIVEN** a wake round processes the configured maximum number of candidate orders
- **WHEN** the round completes
- **THEN** its result reports the allocated orders and that continuation is required
- **AND** one continuation request is recorded with the transaction
- **AND** its consumer runs the next bounded round in a separate transaction without repeating the
  stock increase

### Requirement: Allocation transaction boundaries are independent of their orchestrator

Allocation commands, results, and transactional use cases SHALL NOT depend on Kafka event classes
or Temporal SDK types. An entrypoint SHALL translate transport input into an application command
before invoking the use case. Initial allocation SHALL return an explicit attempt result, and a
bounded wake SHALL return an explicit round result, while business-fact Integration Events remain
the channel by which outcomes reach other bounded contexts.

This requirement establishes a shared application seam; it does not enable a Temporal runtime.
Before a Workflow branches on a retried Activity result, the adapter SHALL provide durable replay or
authoritative reconstruction of the original committed result.

#### Scenario: An Integration Event adapter invokes the transactional boundary

- **GIVEN** an allocation-related Integration Event has been received
- **WHEN** its handler invokes application logic
- **THEN** the handler maps the event and metadata into transport-neutral command input
- **AND** the transactional use case contains no dependency on the Kafka event class

#### Scenario: A future Activity wraps one complete transaction

- **GIVEN** a Temporal adapter is introduced after durable result replay is available
- **WHEN** it invokes initial allocation or a bounded wake round
- **THEN** one Activity invokes one complete transactional use case
- **AND** `MovementRecorder`, `MovementAssigner`, and `BackorderWaker` are not exposed as separate
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
