# fifo-replenishment-demo Specification

## Purpose

TBD - created by archiving change 'add-fifo-replenishment-demo'. Update Purpose after archive.

## Requirements

### Requirement: Wake a queued backorder list on StockReplenished

The allocation SIT suite SHALL provide a named scenario that seeds 1,000 stable
FIFO-ordered BACKORDERED Orders for one SKU with an empty StockPool, then submits
one `StockReplenishedIntegrationEvent` through the allocation Kafka entrypoint.
The scenario SHALL exercise the existing `ReplenishmentUsecase` and
`StrictFifoAllocationPolicy` without changing allocation policy, Kafka topics, or
Integration Event contracts.

#### Scenario: A single replenishment event wakes a queue of 1,000 backorders

- **GIVEN** one StockPool for `FIFO-SKU` with on-hand quantity zero and 1,000
  BACKORDERED Orders for `FIFO-SKU` in stable FIFO order
- **WHEN** one `StockReplenishedIntegrationEvent` is submitted through the
  allocation entrypoint
- **THEN** the scenario processes the event within a single transaction and
  reaches a final allocation decision for the whole queue


<!-- @trace
source: add-fifo-replenishment-demo
updated: 2026-07-25
code:
  - docs/done/demo-02-fifo-replenishment-batch-implementation.md
  - docs/stock-reservation-design.md
tests:
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationFifoReplenishmentBatchIntegrationTest.java
-->

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
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationFifoReplenishmentBatchIntegrationTest.java
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
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationFifoReplenishmentBatchIntegrationTest.java
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
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationFifoReplenishmentBatchIntegrationTest.java
-->