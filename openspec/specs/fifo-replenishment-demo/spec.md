# fifo-replenishment-demo Specification

## Purpose

TBD - created by archiving change 'add-fifo-replenishment-demo'. Update Purpose after archive.

## Requirements

### Requirement: Wake a queued backorder list on StockReplenished

The allocation SIT suite SHALL provide a named scenario that seeds 1,000 orders with stable
FIFO-ordered outstanding demand for one SKU in one owner's warehouse, with no allocatable
stock there, then submits one `StockReplenishedIntegrationEvent` through the allocation
Kafka entrypoint. The scenario SHALL exercise the existing `ReplenishmentUsecase` and
`StrictFifoAllocationPolicy` without changing allocation policy, Kafka topics, or
Integration Event contracts.

**The candidate queue SHALL be scoped to the replenished owner, warehouse and SKU.** Stock
is held per owner, warehouse, arrival and expiry, so an order shipping from another
warehouse cannot be satisfied by this replenishment. Including it consumes the bound —
which counts orders — and it is then skipped, so a queue that spans warehouses spends its
budget on orders that were never candidates.

**The wake SHALL be bounded.** A replenishment SHALL allocate at most a configurable number
of orders in one transaction; when a round allocates the full bound, a continuation event
SHALL be published onto the same topic with the same partition key and the round SHALL
stop.

Without a bound, the number of stock rows one transaction touches is decided by the queue's
contents rather than by the event — so the write set cannot be known in advance, and the
ordering that prevents deadlocks has nothing to sort. Before stock was split this was a
throughput concern; with stock split it is a correctness one.

**The continuation condition SHALL count the orders a round actually allocated, not the
orders it read.** The two differ only when the queue is blocked at its head, and that is
precisely the case that must not loop: with a blocking order at the front and stock still
on hand, every round reads a full bound and allocates none, so a read-based condition
continues forever. Counting allocations makes the bound both terminate and guarantee
progress — a further round is requested only when the whole batch moved.

Deciding it the other way round — continuing while any unallocated order remains — loops
for the same reason.

The bound and the query SHALL agree on their unit. Both count orders: the query returns
whole orders with all of their outstanding lines, because an order is satisfied wholly or
not at all. Were the query to return lines while the bound counted orders, the two would
coincide only while intake permits one line per order and diverge silently the moment it
does not.

The continuation event SHALL be published through the same translation layer as every other
outbound event, so that the topic, partition key and aggregate identity of an outbound
event are decided in one place. Emitting it directly from the use case would also work, and
would make that use case the only one that knows the outbox exists.

This rests on FIFO guaranteeing only the queue as it stood when the replenishment arrived.
Were that contract tightened to strict global FIFO, bounding would have to become paging
inside one transaction, which does not shorten the transaction at all.

#### Scenario: A single replenishment event wakes a queue of 1,000 backorders

- **GIVEN** no allocatable stock for `FIFO-SKU` in the replenished warehouse and 1,000
  orders with outstanding demand for it in stable FIFO order
- **WHEN** one `StockReplenishedIntegrationEvent` is submitted through the allocation
  entrypoint
- **THEN** the queue reaches a final allocation decision, across as many continuation
  rounds as the bound requires

#### Scenario: A round that allocates fewer than the bound does not continue

- **GIVEN** the queue holds fewer orders than the bound
- **WHEN** a replenishment is processed
- **THEN** no continuation event is published

#### Scenario: A queue blocked at its head stops instead of continuing forever

- **GIVEN** the order at the front of the queue demands more than the replenishment
  supplied, and more than a full bound of satisfiable orders queue behind it
- **WHEN** the replenishment is processed
- **THEN** the round reads a full bound of orders and allocates none of them, no
  continuation event is published even though unallocated orders remain, and the
  replenished stock is left entirely unreserved

#### Scenario: Orders shipping from another warehouse never enter the round

- **GIVEN** outstanding demand for the same owner and SKU in two warehouses, with the
  other warehouse's orders placed earlier
- **WHEN** stock is replenished in one of them
- **THEN** the round's candidates are drawn only from the replenished warehouse, and the
  earlier orders of the other warehouse neither consume the bound nor appear in the round

The blocked order SHALL NOT be skipped in favour of the satisfiable orders behind it.
Head-of-line blocking is the guarantee FIFO makes, not a defect in it — a queue that
reorders itself around an order too large to fill is no longer first-come-first-served, and
the large order would never be filled while smaller ones keep arriving.

---
### Requirement: Strict FIFO batch decision respects head-of-line blocking at volume

The scenario SHALL use a fixed, hand-computable order-quantity distribution:
the earliest 500 FIFO-ordered orders each request quantity one, the 501st
FIFO-ordered order (the "blocker") requests a quantity the replenishment cannot
satisfy, and the remaining 499 orders each request quantity one. The
replenishment quantity SHALL exactly equal the sum of the first 500 orders.
The scenario SHALL assert that orders after the blocker are not allocated even
though each individually would fit, proving `StrictFifoAllocationPolicy` stops
at the first unsatisfiable order instead of skipping ahead.

#### Example: Blocker order halts the batch even though later orders would fit

| FIFO position | Quantity | Expected outcome |
| --- | --- | --- |
| 1–500 | 1 each | ALLOCATED |
| 501 (blocker) | 999 | BACKORDERED |
| 502–1000 | 1 each | BACKORDERED (skipped even though individually satisfiable) |


<!-- @trace
source: add-fifo-replenishment-demo
updated: 2026-07-25
code:
  - docs/done/demo-02-fifo-replenishment-batch-implementation.md
  - docs/stock-reservation-design.md
tests:
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationFifoReplenishmentBatchIntegrationTest.java
-->

---
### Requirement: Reconcile final batch allocation state

After the single replenishment event completes, the scenario SHALL assert the
persisted invariants: exactly 500 Orders `ALLOCATED` and 500 Orders
`BACKORDERED`; exactly 500 ACTIVE StockReservations with total quantity 500,
each referencing a distinct Order; a StockPool with on-hand quantity 500,
reserved quantity 500, and available-to-promise zero; exactly one Inbox claim
for the single submitted event; and exactly 500 allocation outcome Outbox
records, all of type `OrderAllocatedIntegrationEvent`.

#### Scenario: Final state contains no oversell, skipped head-of-line order, or lost event

- **GIVEN** the single replenishment event has completed processing
- **WHEN** the scenario queries StockPool, Orders, StockReservations, Inbox,
  and Outbox persistence
- **THEN** all reconciliation invariants hold and no Order past the blocker
  has an ACTIVE StockReservation


<!-- @trace
source: add-fifo-replenishment-demo
updated: 2026-07-25
code:
  - docs/done/demo-02-fifo-replenishment-batch-implementation.md
  - docs/stock-reservation-design.md
tests:
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationFifoReplenishmentBatchIntegrationTest.java
-->

---
### Requirement: A subsequent sequential replenishment resumes the queue correctly

After the first replenishment leaves the blocker and the remaining orders
BACKORDERED, the scenario SHALL submit a second, sequential (not concurrent)
`StockReplenishedIntegrationEvent` with a quantity equal to the sum of the
still-backordered orders. The scenario SHALL assert that this second event
allocates the rest of the queue in the same FIFO order, proving that waking
the queue is verified end to end — not only that an insufficient replenishment
correctly stops at the blocker, but that a later sufficient replenishment
correctly resumes and completes it.

#### Example: Second replenishment clears the blocker and the remaining queue

| FIFO position | Quantity | Outcome after 1st event (qty 500) | Outcome after 2nd event (qty 1,498) |
| --- | --- | --- | --- |
| 1–500 | 1 each | ALLOCATED | ALLOCATED (unchanged) |
| 501 (blocker) | 999 | BACKORDERED | ALLOCATED |
| 502–1000 | 1 each | BACKORDERED | ALLOCATED |

#### Scenario: Final state after both replenishments contains no oversell or lost event

- **GIVEN** the second replenishment event has completed processing
- **WHEN** the scenario queries StockPool, Orders, StockReservations, Inbox,
  and Outbox persistence
- **THEN** all 1,000 Orders are `ALLOCATED`, the StockPool has
  available-to-promise zero, exactly 1,000 ACTIVE StockReservations exist with
  total quantity equal to the sum of both replenishments, exactly two Inbox
  claims exist (one per submitted event), and exactly 1,000 allocation outcome
  Outbox records exist, all of type `OrderAllocatedIntegrationEvent`

<!-- @trace
source: add-fifo-replenishment-demo
updated: 2026-07-25
code:
  - docs/done/demo-02-fifo-replenishment-batch-implementation.md
  - docs/stock-reservation-design.md
tests:
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationFifoReplenishmentBatchIntegrationTest.java
-->
