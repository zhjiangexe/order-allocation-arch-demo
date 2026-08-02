# hot-sku-concurrency-demo Specification

## Purpose

TBD - created by archiving change 'add-hot-sku-concurrency-demo'. Update Purpose after archive.

## Requirements

### Requirement: Execute a hot-SKU concurrent submission burst

The allocation SIT suite SHALL provide a named scenario that creates 1,000 PENDING Orders for one SKU with ten units of on-hand inventory and submits one distinct `OrderPlacedIntegrationEvent` for each Order through the allocation Kafka entrypoint. The scenario SHALL release all submissions from one start gate while the existing datasource pool bounds simultaneous database transactions.

#### Scenario: One thousand orders compete for ten units

- **GIVEN** one StockPool for `HOT-SKU` with on-hand quantity ten and 1,000 PENDING Orders for `HOT-SKU`, each with quantity one
- **WHEN** 1,000 distinct OrderPlaced events are submitted concurrently through the allocation entrypoint
- **THEN** the scenario processes every submitted event without changing allocation policy or event contracts


<!-- @trace
source: add-hot-sku-concurrency-demo
updated: 2026-07-24
code:
  - docs/stock-reservation-design.md
  - docs/done/demo-01-hot-sku-concurrency-implementation.md
  - gradle/libs.versions.toml
tests:
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationHotSkuConcurrencyIntegrationTest.java
-->

---
### Requirement: Demonstrate a real optimistic-lock conflict

The scenario SHALL produce genuine optimistic-lock conflicts on stock, observed through
the retry counters rather than asserted from a fabricated barrier.

**Contention SHALL be concentrated on a single stock row.** With stock split by owner,
warehouse, arrival and expiry, the same burst spread over several rows contends far
less — the scenario would still pass while measuring something weaker than it did
before. The seeded stock for this scenario SHALL therefore be one row, and the
scenario SHALL assert that it is one row, so the concentration cannot be lost by an
unrelated change to seed data.

#### Scenario: A burst against one stock row produces retries

- **GIVEN** the SKU under test holds its entire quantity in a single stock row
- **WHEN** many orders for it are submitted concurrently
- **THEN** the retry counters record conflicts, and the final allocated total does not
  exceed that row's on-hand quantity

#### Scenario: The scenario fails loudly if stock is no longer concentrated

- **WHEN** the scenario runs against stock held in more than one row
- **THEN** it fails rather than passing with reduced contention


<!-- @trace
source: add-batch-stock-and-fefo
updated: 2026-07-30
code:
  - frontend/src/components/StockPanel.tsx
  - docs/execution-roadmap.md
  - e2e/perf/k6/results/hot-sku-burst-20260730T164656.json
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/OrderAllocatedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/LineSnapshot.java
  - e2e/perf/run.sh
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/StockReplenishedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/BackorderWakeRequestedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/coordinator/OrderAllocationCoordinator.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/AllocateOrderUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/repository/jpa/JpaOrderRepository.java
  - e2e/perf/k6/hot-sku-burst.js
  - e2e/perf/kafka-connect/register-outbox-connector.sh
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolController.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/OrderLine.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/model/StockPool.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/command/WakeBackordersCommand.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/model/ReservationStatus.java
  - order-promising/src/main/java/com/flowzati/archone/common/outbox/StockContentionKey.java
  - frontend/vite.config.ts
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/OrderCancelled.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/StockReplenishedIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/bootstrap/DevSeedDataInitializer.java
  - order-promising/src/main/java/com/flowzati/archone/common/outbox/OutboxAggregateTypes.java
  - order-promising/src/main/resources/application-dev.properties
  - frontend/src/api/catalog.ts
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/OrderCancelledIntegrationEvent.java
  - order-promising/src/main/resources/db/migration/V4__create_stock_reservations.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/jpa/JpaStockReservationRepository.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationResult.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/BatchPick.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/BackorderWakeRequestedIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/IntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationRequest.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/StockReservationRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/KafkaIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/common/configuration/CommonConfiguration.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/entity/StockPoolEntity.java
  - order-promising/src/main/java/com/flowzati/archone/common/time/BusinessCalendar.java
  - docs/stock-reservation-design.md
  - frontend/src/api/client.ts
  - order-promising/src/main/resources/db/migration/V3__create_stock_pools.sql
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/jpa/JpaStockRepository.java
  - e2e/perf/k6/results/hot-sku-burst-20260730T164603.json
  - frontend/README.md
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/event/BackorderWakeContinuationRequired.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/model/StockReservation.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/repository/OrderRepository.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/event/OrderAllocationCompleted.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/translator/AllocationDomainEventTranslator.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/repository/StockReservationRepository.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/repository/StockPoolRepository.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/translator/OrderingDomainEventTranslator.java
  - order-promising/src/main/resources/db/migration/V2__create_ordering_tables.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/ReplenishmentUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/OrderAllocation.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationOutcome.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/OrderPlacedIntegrationEventHandler.java
  - e2e/perf/docker-compose.yml
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/mapper/StockReservationMapper.java
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/KafkaIntegrationEventDispatcher.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/OrderPlacedIntegrationEvent.java
  - docs/dom-promising-scope.md
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolResponse.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/OrderCancelledIntegrationEventHandler.java
  - frontend/src/api/types.ts
  - order-promising/src/main/java/com/flowzati/archone/stock/application/command/ReplenishStockCommand.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/mapper/StockPoolMapper.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/StockPoolRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/demo/ReplenishmentProbeController.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/ReleaseReservationUsecase.java
  - order-promising/src/main/resources/application.properties
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/BackorderCreatedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/repository/OrderRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationService.java
  - e2e/perf/README.md
  - docs/system-layer-map.md
  - order-promising/src/main/resources/db/migration/V2__create_stock_pools.sql
  - order-promising/src/main/resources/db/migration/V3__create_ordering_tables.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/GetStockPoolUsecase.java
  - frontend/src/pages/StockPage.tsx
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/Order.java
  - frontend/src/components/StockPanel.module.css
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/entity/StockReservationEntity.java
tests:
  - order-promising/src/test/java/com/flowzati/archone/demo/ReplenishmentProbeControllerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationFifoGuaranteeScopeIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/model/StockFixtures.java
  - order-promising/src/test/java/com/flowzati/archone/common/time/BusinessCalendarTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/model/StockPoolTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/usecase/ReplenishmentUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/event/EventSeparationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/usecase/ReleaseReservationUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/model/StockReservationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolControllerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/infrastructure/repository/StockReservationPersistenceIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/bootstrap/DevSeedDataIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/application/usecase/InboundCommandTransactionIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/common/outbox/OutboxCdcIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/outbox/DomainEventTranslatorTest.java
  - order-promising/src/test/java/com/flowzati/archone/demo/DemoConfigControllerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/demo/ReplenishmentProbeEndToEndIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationConcurrencyEndToEndIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/infrastructure/mapper/StockPoolMapperTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationWorkflowEndToEndIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationKafkaIntegrationEventConsumerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/infrastructure/repository/StockPoolPersistenceIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecaseTest.java
  - order-promising/src/sit/java/com/flowzati/archone/ordering/infrastructure/repository/OrderPersistenceIntegrationTest.java
  - frontend/src/components/StockPanel.test.tsx
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/service/AllocationServiceTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/coordinator/OrderAllocationCoordinatorTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/domain/model/OrderTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/usecase/AllocateOrderUsecaseTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationFifoReplenishmentBatchIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/service/AllocationSelectorTest.java
  - order-promising/src/sit/java/com/flowzati/archone/common/outbox/OutboxAggregateQueryIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationHotSkuConcurrencyIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/infrastructure/mapper/StockReservationMapperTest.java
-->

---
### Requirement: Redeliver retry-exhausted events safely

The hot-SKU scenario SHALL collect only `AllocationConcurrencyExhaustedException` failures from the concurrent wave and SHALL redeliver each original event with the same event ID after the wave completes. The scenario SHALL fail if retry-exhausted deliveries remain after its bounded recovery limit or if any other delivery failure occurs.

#### Scenario: Exhausted event converges on redelivery

- **GIVEN** an OrderPlaced event exhausted the application retry policy and its transaction rolled back
- **WHEN** the scenario redelivers the same event ID after the concurrent wave
- **THEN** Inbox accepts the event as new and the Order reaches one final allocation outcome


<!-- @trace
source: add-hot-sku-concurrency-demo
updated: 2026-07-24
code:
  - docs/stock-reservation-design.md
  - docs/done/demo-01-hot-sku-concurrency-implementation.md
  - gradle/libs.versions.toml
tests:
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationHotSkuConcurrencyIntegrationTest.java
-->

---
### Requirement: Reconcile final allocation state

After all deliveries and bounded redeliveries complete, the hot-SKU scenario SHALL assert the persisted allocation invariants: ten allocated Orders, 990 backordered Orders, ten ACTIVE StockReservations with total quantity ten, and a StockPool with on-hand quantity ten, reserved quantity ten, and available-to-promise zero. Each of the 1,000 event IDs SHALL have an Inbox claim, and allocation outcome Outbox records SHALL total 1,000 and correspond to the final Order outcomes.

#### Scenario: Final state contains no oversell or lost event

- **GIVEN** all 1,000 submitted event IDs have completed processing
- **WHEN** the scenario queries StockPool, Orders, StockReservations, Inbox, and Outbox persistence
- **THEN** all reconciliation invariants hold and no Order has more than one ACTIVE StockReservation

<!-- @trace
source: add-hot-sku-concurrency-demo
updated: 2026-07-24
code:
  - docs/stock-reservation-design.md
  - docs/done/demo-01-hot-sku-concurrency-implementation.md
  - gradle/libs.versions.toml
tests:
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationHotSkuConcurrencyIntegrationTest.java
-->