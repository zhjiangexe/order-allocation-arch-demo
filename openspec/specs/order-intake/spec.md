# order-intake Specification

## Purpose

TBD - created by archiving change 'add-owner-and-order-line-model'. Update Purpose after archive.

## Requirements

### Requirement: An order carries an owner, an upstream reference, and a delivery commitment

An order SHALL identify the owner whose goods it draws on, the order number assigned
by that owner's upstream system, **the warehouse it ships from**, the destination zone,
the destination address, and the promised delivery date. The destination zone and the
destination address SHALL be separate fields because they serve different consumers:
the address is a fulfillment and label input, the zone is retained as the coarse form
of the destination.

The shipping warehouse SHALL be required. It is supplied by the owner's upstream system
at order creation and the system SHALL NOT derive, default, or revisit it. A missing
warehouse SHALL be rejected rather than resolved.

The address SHALL be held on the order itself rather than in a separate address
entity, because an address is specified per order and is never reused.

#### Scenario: A placed order records owner, upstream reference, warehouse, and destination

- **WHEN** an order is placed with an owner, an upstream order number, a warehouse, a
  destination zone, a destination address, and a promised delivery date
- **THEN** all six are persisted with the order and are returned when that order is
  queried

#### Scenario: An order without a warehouse is rejected

- **WHEN** an order is placed without naming a warehouse
- **THEN** the order is rejected and no order and no line are persisted

---
### Requirement: Order demand is expressed as lines

An order SHALL hold its demand as a collection of lines rather than as a SKU and a
quantity on the order itself. Each line SHALL carry its SKU code, its quantity, its
own line number preserving the upstream document's structure, its own status, and its
own backordered timestamp.

Each line SHALL also carry the owner. This is a denormalisation of the header's owner
and is permitted because the value is immutable for the life of the order: the owner
of an order never changes, so the copy can never diverge. It exists so that a line
references catalog entries by owner and SKU code without joining its header.

A line SHALL NOT be reachable except through its order. Its quantity and status
cannot be changed independently, because the order is the consistency boundary.

#### Scenario: An order exposes its lines and no longer exposes a SKU

- **WHEN** an order is queried
- **THEN** its representation carries a collection of lines, each with a SKU code and
  a quantity, and carries no SKU or quantity of its own

---
### Requirement: An allocation outcome applies to a whole order, never to part of it

An order SHALL be allocated only when its whole demand can be satisfied. When it cannot,
no part of that order SHALL reserve stock and the whole order SHALL become backordered.

Reserving stock for the satisfiable part of an order that cannot ship would hold
inventory for goods that will not leave, which is why partial reservation is forbidden
rather than merely discouraged.

An order SHALL expose its demand to allocation as quantities aggregated per SKU, not as
a sequence of lines. Allocation therefore has no line to process individually, so
per-line reservation is not merely tested against — it cannot be expressed. With one
line the aggregate holds one entry and behavior is unchanged; with more lines it holds
more, and allocation needs no modification to remain correct.

A partially-allocated order status SHALL NOT exist.

#### Scenario: Demand exceeding available stock reserves nothing at all

- **GIVEN** an order with two lines for the same SKU, each requesting five units, and a
  stock pool that can promise five units
- **WHEN** the order is allocated
- **THEN** no reservation exists for that order and the whole order is backordered

##### Example: aggregated demand decides the outcome

| Lines | Aggregated demand | Available to promise | Reservations created |
| --- | --- | --- | --- |
| 5 + 5, same SKU | 10 | 5 | none — whole order backordered |
| 5 + 5, same SKU | 10 | 10 | one, for the whole demand |
| 5, single line | 5 | 5 | one, for the whole demand |

---
### Requirement: An order line references an existing catalog entry

A line's owner and SKU code together SHALL reference an existing SKU in the catalog.
An order naming a SKU code that the catalog does not hold for that owner SHALL be
rejected and SHALL NOT be persisted.

An order's owner and warehouse together SHALL reference an assignment that exists. An
order naming a warehouse that its owner is not assigned to SHALL be rejected and SHALL
NOT be persisted.

Both SHALL be enforced by the storage layer's referential integrity rather than by a
prior application-level lookup, so that no order can reach storage through a path that
skips the check.

#### Scenario: An order naming an unknown SKU is rejected

- **WHEN** an order is placed whose line names a SKU code that the catalog does not
  hold for that order's owner
- **THEN** the order is rejected and no order and no line are persisted

#### Scenario: An order naming a warehouse its owner is not assigned to is rejected

- **WHEN** an order is placed naming a warehouse that exists but that the order's owner
  is not assigned to
- **THEN** the order is rejected and no order and no line are persisted

##### Example: which orders reach storage

| Owner | Warehouse named | SKU code named | Result |
| --- | --- | --- | --- |
| `OWNER-A` | one `OWNER-A` is assigned to | one `OWNER-A` defines | persisted |
| `OWNER-A` | none | one `OWNER-A` defines | rejected, nothing persisted |
| `OWNER-A` | one only `OWNER-B` is assigned to | one `OWNER-A` defines | rejected, nothing persisted |
| `OWNER-A` | one that does not exist | one `OWNER-A` defines | rejected, nothing persisted |
| `OWNER-A` | one `OWNER-A` is assigned to | one only `OWNER-B` defines | rejected, nothing persisted |

The third and fifth rows are the same mistake in two dimensions: naming something that
exists but belongs to another owner. Neither is caught by existence alone.

---
### Requirement: An upstream order number is unique within its owner

The combination of owner and upstream order number SHALL be unique. Submitting an
order whose owner and upstream order number already exist SHALL fail explicitly.

The system SHALL NOT silently create a second order. In a warehouse a duplicate order
is a physical incident, whereas an error response is only a message.

Returning the existing order instead of failing is idempotent intake and is NOT
required here: until that behavior exists, a resubmission produces a failure, which is
not correct behavior but is safe behavior.

#### Scenario: A resubmitted upstream order number fails without creating a duplicate

- **GIVEN** an order already exists for one owner and one upstream order number
- **WHEN** an order with the same owner and the same upstream order number is
  submitted
- **THEN** the submission fails and exactly one order exists for that pair

---
### Requirement: Order handling does not depend on the number of lines

Reaching a line by position is correct while intake permits only one line, produces no
failing test, and silently ignores every other line once the policy is relaxed.

A small number of places genuinely need to collapse an order's lines into a single
value — the message key used for event partitioning and the label attached to allocation
retries both admit only one value, which no iteration can supply. These places are
correct today only because an order carries one line.

The order SHALL therefore expose exactly one named operation whose stated meaning is
"this caller assumes a single line", and every such place SHALL obtain its value through
it. Production code SHALL NOT otherwise reach a line by position, and this SHALL be
enforced by an automated check over production sources rather than by review.

The single-line assumption SHALL NOT be enforced by forbidding particular expressions.
An equivalent access written as a stream, or as a loop that stops after its first
iteration, does the same thing and would pass such a check, so enumerating forbidden
forms cannot be complete. Naming the assumption makes it searchable instead: relaxing
the intake policy later requires finding the callers of one operation rather than
auditing every access.

#### Scenario: Positional access outside the named operation fails the build

- **WHEN** production code reaches an order's line by position anywhere other than
  inside the named single-line operation
- **THEN** the automated check fails and identifies the offending source

#### Scenario: The single-line assumption is enumerable

- **WHEN** the callers of the named single-line operation are listed
- **THEN** that list is the complete set of places that must change once intake accepts
  more than one line per order

---
### Requirement: A line inherits its order's warehouse rather than carrying its own

An order line SHALL NOT carry a shipping warehouse. One order ships from one warehouse
and its lines cannot span warehouses, so a warehouse held on the line would be a copy
of the header that can never differ.

This SHALL remain true when orders are allowed to carry several lines. Several lines
mean several SKUs on one order, not several warehouses; per-line warehouses would only
be needed to split an order across warehouses, which the system does not do.

The line's denormalized owner SHALL be retained, because unlike the warehouse it earns
its place: the owner and SKU code together form the catalog's natural key, and the
foreign key cannot be expressed without it.

#### Scenario: A line reports the warehouse of its order

- **WHEN** an order's lines are queried
- **THEN** no line carries a warehouse of its own, and the order's warehouse applies to
  every line

---
### Requirement: An order records when we received it and, when supplied, when it was placed

An order SHALL record the instant this system received and accepted it. That instant SHALL
be written by this system, SHALL NOT be supplied by the caller, and SHALL NOT be null.

An order SHALL additionally record the instant the upstream system says the customer placed
it. That instant SHALL be supplied by the caller and SHALL be nullable, because an upstream
system is not obliged to send it.

The two SHALL NOT be conflated into one field. They answer different questions — one is
when the customer committed, the other is when we became able to act — and in third-party
logistics they routinely differ: upstream systems send in batches, retry after failures, and
re-run jobs, so an order can arrive minutes or a day after it was placed. With one field,
delay upstream and delay here cannot be told apart.

**The received instant SHALL be the ordering key wherever orders are sequenced**, including
the backorder queue and the recent-orders listing. The placed instant SHALL NOT be used for
sequencing: it is nullable, and it is decided by a system whose clock and delivery schedule
we do not control, so a late-arriving order could otherwise be sequenced ahead of orders
that have been waiting.

A placed instant later than the received instant SHALL be rejected only when it exceeds a
configurable tolerance. Upstream clocks drift by seconds, and comparing strictly would
reject ordinary orders; a placed instant hours or days into the future is a data error and
SHALL be refused. No lower bound SHALL be imposed — an old placed instant is a legitimate
historical import.

#### Scenario: An order without an upstream placed instant is accepted

- **WHEN** an order is placed without an upstream placed instant
- **THEN** the order is persisted, its received instant is set by this system, and its
  placed instant is empty

#### Scenario: An upstream placed instant is preserved as given

- **WHEN** an order is placed carrying an upstream placed instant earlier than now
- **THEN** that instant is persisted unchanged, and the received instant separately records
  when this system accepted the order

#### Scenario: A placed instant beyond the tolerance is rejected

- **WHEN** an order is placed whose upstream placed instant exceeds the received instant by
  more than the configured tolerance
- **THEN** the order is rejected and no order and no line are persisted

#### Scenario: A placed instant within the tolerance is accepted

- **WHEN** an order is placed whose upstream placed instant is slightly later than the
  received instant but within the configured tolerance
- **THEN** the order is accepted, because upstream clocks drift and a few seconds ahead is
  not a data error

##### Example: what the tolerance separates

| Upstream placed instant, relative to received | Result |
| --- | --- |
| three months earlier | accepted — historical import |
| one minute earlier | accepted |
| two seconds later | accepted — clock drift |
| one day later | rejected — data error |
| not supplied | accepted, stored empty |

---
### Requirement: A line's status mirrors its header, and no timestamp is stored on it

Because an order is fulfilled complete, all of an order's lines reach an allocation outcome
together. When an order is marked allocated, backordered, or cancelled, every line SHALL be
updated in the same transaction and SHALL end in the state the header records.

No backordered timestamp and no allocated timestamp SHALL be stored on the line. Both would
equal the header's, and neither is read.

A backordered timestamp was previously stored on the line so that the backorder queue could
be filtered and ordered from one table. **That query was never single-table.** It joins
`orders` to `order_lines`, filters on the line's owner and SKU code, and takes both its
predicate and its ordering from the header — so the column was never reached, and the index
built over it could never serve the ordering it existed for. Adding a warehouse to the
queue's scope puts the query further from single-table, not closer.

The ordering key SHALL be the order identifier, which already encodes when the order entered
the system.

#### Scenario: Marking an order backordered stamps its header and its lines alike

- **GIVEN** a rehydrated order with two lines
- **WHEN** the order is marked backordered at a given instant
- **THEN** the header carries that instant, both lines carry the backordered status, and
  neither line carries a timestamp of its own

---
### Requirement: Backorder queues are scoped to one owner, one warehouse, and one SKU

The backordered-demand query SHALL take an owner, a warehouse and a SKU code, and SHALL
return that owner's outstanding demand for that SKU in that warehouse, in the order the
orders entered the system, so repeated queries against unchanged data return an identical
sequence.

One owner's queue SHALL NOT be affected by another owner's orders, even when both use the
same SKU code, because in third-party logistics SKU codes collide across owners and the goods
are not interchangeable.

**The warehouse SHALL be part of the scope, not merely available to the caller.** Stock is
held per owner, warehouse, arrival and expiry, so an order can only be satisfied from its own
warehouse. A queue that spans warehouses returns orders that this replenishment cannot
satisfy; they consume the wake limit — which counts orders, not successful allocations — and
are then skipped. The waste grows with the number of warehouses.

**The ordering key SHALL be the order identifier, which is time-ordered by construction.**
It records when the order entered this system, and that is the only sequence a queue can
honour: before an order arrives, the system knows nothing of it and can hold nothing for it.

It SHALL NOT be the time the order entered backorder. That is a processing timestamp: two
orders placed a millisecond apart enter backorder in whatever sequence the consumer happened
to process them, and retries, rebalances and optimistic-lock conflicts all change it.
Ordering by it lets the system's own scheduling jitter decide precedence between customers.

It SHALL NOT be the upstream placed time either. That value is optional, and it is decided by
a system whose clock and delivery schedule are outside this one's control — an order placed
three days ago and delivered today would otherwise be sequenced ahead of orders that have
been waiting since yesterday.

**This rests on order identifiers being time-ordered**, and SHALL be covered by a test
asserting that identifiers generated later sort after earlier ones. Were they to become
random, the queue would silently lose its order: nothing would throw, nothing would be
logged, and no existing test would fail.

Replenishment SHALL name the owner and the warehouse whose queue it wakes. Without them the
scoped query has no caller able to supply them, and the scoping would exist in the schema but
never take effect.

#### Scenario: Two owners using the same SKU code hold separate queues

- **GIVEN** owner A and owner B each have outstanding demand for SKU code `SKU-A`, and owner
  B's orders were placed earlier
- **WHEN** owner A's queue for `SKU-A` is read
- **THEN** only owner A's orders are returned, and owner B's earlier orders do not appear or
  affect the ordering

#### Scenario: A queue excludes orders shipping from another warehouse

- **GIVEN** owner A has outstanding demand for `SKU-A` in the north warehouse and in the south
  warehouse
- **WHEN** the queue for owner A, south warehouse and `SKU-A` is read
- **THEN** only the south warehouse's orders are returned

#### Scenario: Replenishment wakes only the named owner's and warehouse's queue

- **GIVEN** owner A and owner B both have outstanding demand for SKU code `SKU-A`
- **WHEN** stock is replenished for owner A, the south warehouse and SKU code `SKU-A`
- **THEN** only owner A's south-warehouse orders enter the allocation decision

#### Scenario: Precedence follows the order of arrival

- **GIVEN** two orders for the same owner, warehouse and SKU, where the one that arrived
  first entered backorder later
- **WHEN** the queue is read
- **THEN** the one that arrived first comes first

#### Scenario: An order delivered late does not overtake orders already waiting

- **GIVEN** an order whose upstream placed time is earlier than every waiting order's, but
  which this system received last
- **WHEN** the queue is read
- **THEN** it comes last, because nothing could have been held for it before it arrived

#### Scenario: Order identifiers are time-ordered

- **WHEN** two orders are created one after the other
- **THEN** the later order's identifier sorts after the earlier one's

##### Example: why the three candidate keys disagree

| | Order A | Order B | Order C |
| --- | --- | --- | --- |
| arrived (identifier) | 10:00:00.000 | 10:00:00.001 | 10:00:00.002 |
| entered backorder | 10:00:02 (one retry) | 10:00:01 | 10:00:03 |
| upstream placed time | not supplied | 09:58 | three days earlier |

Queue by identifier: A, B, C — the order they arrived.
Queue by backorder time: B, A, C — a retry moved A behind B.
Queue by upstream placed time: C, B, A — C overtakes orders that waited longer, and A has
no key at all.

---
### Requirement: Ordering advances an order's status from allocation's events

An order's status SHALL be advanced by ordering alone, in response to the allocation outcome
events published to the allocation events topic. Allocation SHALL NOT write to `orders` or
`order_lines`.

The event carries an order identifier and a timestamp; ordering SHALL re-read the order and
advance it. Nothing in the event is treated as the order's state, so the two cannot disagree.

Consumption SHALL be deduplicated through the existing inbox mechanism, because the delivery
guarantee is at-least-once and advancing a status twice must not produce a second outcome.

**An event naming an order that is already cancelled SHALL be a no-op, not a failure.**
Allocation completing and a customer cancelling are concurrent, and after this change they are
no longer serialised by a shared transaction: allocation can succeed and publish while the
cancellation is in flight. Treating the late outcome as an error would send one dead letter for
every ordinary cancellation that happens to race. The reservation is released by the
cancellation path on allocation's side, so both sides remain correct.

There SHALL be a lag between allocation deciding and the order's status reflecting it. That lag
is acceptable for display and SHALL NOT be used as a gate on allocation — the query that
decides what is still owed derives it from reservations, not from the order's status. A
replenishment arriving inside that window therefore cannot reserve stock for demand that has
already been satisfied.

#### Scenario: An allocation outcome advances the order

- **GIVEN** a pending order and an allocation outcome event naming it
- **WHEN** ordering consumes that event
- **THEN** the order and its lines are allocated, and the allocated timestamp is the one the
  event carried

#### Scenario: An outcome for a cancelled order changes nothing and fails nothing

- **GIVEN** an order already cancelled
- **WHEN** an allocation outcome event naming that order is consumed
- **THEN** the order remains cancelled, no exception escapes the handler, and no dead letter is
  produced

#### Scenario: A redelivered outcome is applied once

- **GIVEN** an allocation outcome event that has already been consumed
- **WHEN** the same event is delivered again
- **THEN** the order's status and timestamps are unchanged from the first delivery

#### Scenario: A replenishment inside the lag window does not double-reserve

- **GIVEN** an order just allocated, whose outcome event ordering has not yet consumed
- **WHEN** a replenishment for the same owner, warehouse and SKU is processed
- **THEN** that order does not appear among the candidates, because its lines already hold a
  reservation

---
### Requirement: Order intake accepts one or more lines

Placing an order SHALL be rejected unless it carries at least one line. There SHALL be no
upper bound: an order may carry several lines, naming several SKU codes, and the same SKU
code may appear on more than one line.

An order without demand SHALL be rejected at intake and SHALL NOT be reconstructible from
storage either — it is not a state the system holds.

**The same SKU on two lines SHALL be legitimate**, not merely tolerated. Upstream systems
split a quantity across lines for their own reasons (different price tiers, different
customer references), and an order's demand is read as quantities aggregated per SKU, so
two lines naming one SKU are indistinguishable from one line carrying their sum.

#### Scenario: An order carrying two SKUs is accepted

- **WHEN** an order is placed with two lines naming different SKU codes
- **THEN** the order is persisted with both lines

#### Scenario: An order carrying the same SKU twice is accepted

- **WHEN** an order is placed with two lines naming the same SKU code
- **THEN** the order is persisted with both lines, and its demand for that SKU is the sum

#### Scenario: An order without lines is still rejected

- **WHEN** an order is placed with zero lines
- **THEN** the order is rejected and nothing is persisted

##### Example: line counts at intake and at rehydration

| Lines | Intake | Rehydration |
| --- | --- | --- |
| 0 | rejected | rejected — an order without demand is not a state storage can hold |
| 1 | accepted | accepted |
| 2, different SKUs | accepted | accepted |
| 2, same SKU | accepted | accepted |

**Intake and rehydration no longer disagree.** They differed only because of the
single-line policy; with it gone, both accept exactly the orders the schema permits.

<!-- @trace
source: allocate-multi-sku-orders-as-one-basket
updated: 2026-07-31
code:
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/selector/policy/StrictFifoAllocationPolicy.java
  - order-promising/src/main/java/com/flowzati/archone/bootstrap/DevSeedDataInitializer.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/selector/policy/MaximizeFulfilledOrdersPolicy.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/selector/context/BasicAllocationContext.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/coordinator/OrderAllocationCoordinator.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/usecase/AllocateOrderUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/selector/context/BasicAllocationContextFactory.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocationRequest.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocationResult.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/entrypoint/rest/PlaceOrderRequest.java
  - frontend/src/components/OrderTable.tsx
  - frontend/src/components/PlaceOrderForm.module.css
  - frontend/src/components/PlaceOrderForm.tsx
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocationService.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/SkuQuantities.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/repository/StockPoolRepository.java
  - docs/execution-roadmap.md
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocationPlan.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/Order.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/repository/jpa/JpaStockRepository.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/repository/StockPoolRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/usecase/ReplenishmentUsecase.java
tests:
  - frontend/src/components/PlaceOrderForm.test.tsx
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/service/AllocationServiceTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/OrderingArchitectureTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/application/usecase/ReplenishmentUsecaseTest.java
  - order-promising/src/sit/java/com/flowzati/archone/bootstrap/DevSeedDataIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/service/AllocationPlanTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/entrypoint/rest/OrderControllerTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/service/selector/AllocationPolicyTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/domain/model/OrderTest.java
  - order-promising/src/test/java/com/flowzati/archone/testsupport/OrderFixtures.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/application/usecase/AllocateOrderUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/service/SkuQuantitiesTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationWorkflowEndToEndIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/service/AllocationSelectorTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/application/coordinator/OrderAllocationCoordinatorTest.java
-->