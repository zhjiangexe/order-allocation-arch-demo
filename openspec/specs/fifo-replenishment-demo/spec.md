# fifo-replenishment-demo Specification

## Purpose

TBD - created by archiving change 'add-fifo-replenishment-demo'. Update Purpose after archive.

## Requirements

### Requirement: Wake a queued backorder list on StockReplenished

The allocation SIT suite SHALL provide a named scenario that seeds 1,000 stable
FIFO-ordered BACKORDERED Orders for one SKU with no allocatable stock, then submits
one `StockReplenishedIntegrationEvent` through the allocation Kafka entrypoint.
The scenario SHALL exercise the existing `ReplenishmentUsecase` and
`StrictFifoAllocationPolicy` without changing allocation policy, Kafka topics, or
Integration Event contracts.

**The wake SHALL be bounded.** A replenishment SHALL allocate at most a configurable
number of orders in one transaction; when a round allocates the full bound, a
continuation event SHALL be published onto the same topic with the same partition key
and the round SHALL stop.

Without a bound, the number of stock rows one transaction touches is decided by the
queue's contents rather than by the event — so the write set cannot be known in advance,
and the ordering that prevents deadlocks has nothing to sort. Before stock was split
this was a throughput concern; with stock split it is a correctness one.

**The continuation condition SHALL count the orders a round actually allocated, not the
orders it read.** The two differ only when the queue is blocked at its head, and that is
precisely the case that must not loop: with a blocking order at the front and stock still
on hand, every round reads a full bound and allocates none, so a read-based condition
continues forever. Counting allocations makes the bound both terminate and guarantee
progress — a further round is requested only when the whole batch moved.

Deciding it the other way round — continuing while any unallocated order remains — loops
for the same reason.

The continuation event SHALL be published through the same translation layer as every
other outbound event, so that the topic, partition key and aggregate identity of an
outbound event are decided in one place. Emitting it directly from the use case would
also work, and would make that use case the only one that knows the outbox exists.

This rests on FIFO guaranteeing only the queue as it stood when the replenishment
arrived. Were that contract tightened to strict global FIFO, bounding would have to
become paging inside one transaction, which does not shorten the transaction at all.

#### Scenario: A single replenishment event wakes a queue of 1,000 backorders

- **GIVEN** no allocatable stock for `FIFO-SKU` and 1,000 BACKORDERED Orders for it in
  stable FIFO order
- **WHEN** one `StockReplenishedIntegrationEvent` is submitted through the
  allocation entrypoint
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

The blocked order SHALL NOT be skipped in favour of the satisfiable orders behind it.
Head-of-line blocking is the guarantee FIFO makes, not a defect in it — a queue that
reorders itself around an order too large to fill is no longer first-come-first-served,
and the large order would never be filled while smaller ones keep arriving.


<!-- @trace
source: add-batch-stock-and-fefo
updated: 2026-07-30
code:
  - frontend/src/components/StockPanel.tsx
  - docs/execution-roadmap.md
  - e2e/perf/k6/results/hot-sku-burst-20260730T164656.json
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/event/OrderAllocatedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/LineSnapshot.java
  - e2e/perf/run.sh
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/event/StockReplenishedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/event/BackorderWakeRequestedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/coordinator/OrderAllocationCoordinator.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/usecase/AllocateOrderUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/repository/jpa/JpaOrderRepository.java
  - e2e/perf/k6/hot-sku-burst.js
  - e2e/perf/kafka-connect/register-outbox-connector.sh
  - order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/rest/StockPoolController.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/OrderLine.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/model/StockPool.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/command/WakeBackordersCommand.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/model/ReservationStatus.java
  - order-promising/src/main/java/com/flowzati/archone/common/outbox/StockContentionKey.java
  - frontend/vite.config.ts
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/OrderCancelled.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/kafka/StockReplenishedIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/bootstrap/DevSeedDataInitializer.java
  - order-promising/src/main/java/com/flowzati/archone/common/outbox/OutboxAggregateTypes.java
  - order-promising/src/main/resources/application-dev.properties
  - frontend/src/api/catalog.ts
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/OrderCancelledIntegrationEvent.java
  - order-promising/src/main/resources/db/migration/V4__create_stock_reservations.sql
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/repository/jpa/JpaStockReservationRepository.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocationResult.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/BatchPick.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/kafka/BackorderWakeRequestedIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/IntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocationRequest.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/repository/StockReservationRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/KafkaIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/common/configuration/CommonConfiguration.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/entity/StockPoolEntity.java
  - order-promising/src/main/java/com/flowzati/archone/common/time/BusinessCalendar.java
  - docs/stock-reservation-design.md
  - frontend/src/api/client.ts
  - order-promising/src/main/resources/db/migration/V3__create_stock_pools.sql
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/repository/jpa/JpaStockRepository.java
  - e2e/perf/k6/results/hot-sku-burst-20260730T164603.json
  - frontend/README.md
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/event/BackorderWakeContinuationRequired.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/model/StockReservation.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/repository/OrderRepository.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/event/OrderAllocationCompleted.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/event/translator/AllocationDomainEventTranslator.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/repository/StockReservationRepository.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/repository/StockPoolRepository.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/translator/OrderingDomainEventTranslator.java
  - order-promising/src/main/resources/db/migration/V2__create_ordering_tables.sql
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/usecase/ReplenishmentUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/OrderAllocation.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocationOutcome.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/kafka/OrderPlacedIntegrationEventHandler.java
  - e2e/perf/docker-compose.yml
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/mapper/StockReservationMapper.java
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/KafkaIntegrationEventDispatcher.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/OrderPlacedIntegrationEvent.java
  - docs/dom-promising-scope.md
  - order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/rest/StockPoolResponse.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/kafka/OrderCancelledIntegrationEventHandler.java
  - frontend/src/api/types.ts
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/command/ReplenishStockCommand.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/mapper/StockPoolMapper.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/repository/StockPoolRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/demo/ReplenishmentProbeController.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/usecase/ReleaseReservationUsecase.java
  - order-promising/src/main/resources/application.properties
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/event/BackorderCreatedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/repository/OrderRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocationService.java
  - e2e/perf/README.md
  - docs/system-layer-map.md
  - order-promising/src/main/resources/db/migration/V2__create_stock_pools.sql
  - order-promising/src/main/resources/db/migration/V3__create_ordering_tables.sql
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/usecase/GetStockPoolUsecase.java
  - frontend/src/pages/StockPage.tsx
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/Order.java
  - frontend/src/components/StockPanel.module.css
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/entity/StockReservationEntity.java
tests:
  - order-promising/src/test/java/com/flowzati/archone/demo/ReplenishmentProbeControllerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationFifoGuaranteeScopeIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/model/StockFixtures.java
  - order-promising/src/test/java/com/flowzati/archone/common/time/BusinessCalendarTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/model/StockPoolTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/application/usecase/ReplenishmentUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/event/EventSeparationTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/application/usecase/ReleaseReservationUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/model/StockReservationTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/entrypoint/rest/StockPoolControllerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/infrastructure/repository/StockReservationPersistenceIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/bootstrap/DevSeedDataIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/application/usecase/InboundCommandTransactionIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/common/outbox/OutboxCdcIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/outbox/DomainEventTranslatorTest.java
  - order-promising/src/test/java/com/flowzati/archone/demo/DemoConfigControllerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/demo/ReplenishmentProbeEndToEndIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationConcurrencyEndToEndIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/infrastructure/mapper/StockPoolMapperTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationWorkflowEndToEndIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationKafkaIntegrationEventConsumerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/infrastructure/repository/StockPoolPersistenceIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecaseTest.java
  - order-promising/src/sit/java/com/flowzati/archone/ordering/infrastructure/repository/OrderPersistenceIntegrationTest.java
  - frontend/src/components/StockPanel.test.tsx
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/service/AllocationServiceTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/application/coordinator/OrderAllocationCoordinatorTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/domain/model/OrderTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/application/usecase/AllocateOrderUsecaseTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationFifoReplenishmentBatchIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/service/AllocationSelectorTest.java
  - order-promising/src/sit/java/com/flowzati/archone/common/outbox/OutboxAggregateQueryIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationHotSkuConcurrencyIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/infrastructure/mapper/StockReservationMapperTest.java
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