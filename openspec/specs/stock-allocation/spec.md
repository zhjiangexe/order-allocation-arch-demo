# stock-allocation Specification

## Purpose

TBD - created by archiving change 'add-batch-stock-and-fefo'. Update Purpose after archive.

## Requirements

### Requirement: A stock row references a SKU its owner actually holds

A stock row's owner and SKU code together SHALL reference an existing SKU in the
catalog. A row naming a SKU code the catalog does not hold for that owner SHALL be
rejected and SHALL NOT be persisted.

This SHALL be enforced by the storage layer's referential integrity, for the same
reason an order line's SKU reference is: SKU codes collide across owners, so a
reference that carries the owner is the only one that can be checked. Without it, a
mistyped code produces stock that exists but can never be allocated — a symptom that
takes a long time to trace back to its cause.

#### Scenario: Stock naming another owner's SKU code is rejected

- **GIVEN** a SKU code exists in the catalog for one owner only
- **WHEN** stock is written naming that code under a different owner
- **THEN** the write is rejected and no stock row is persisted


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
  - order-promising/src/main/java/com/flowzati/archone/stock/application/command/AllocateWaitingDemandCommand.java
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

### Requirement: Expired stock is present but not allocatable

Stock whose expiry date has passed SHALL remain in the system and SHALL be excluded
from allocation. It SHALL NOT be deleted and SHALL NOT be silently omitted from
queries.

Deleting it would destroy the record of goods physically present in the warehouse.
Omitting it from queries would make "we have 100 units but can ship none" indis-
tinguishable from "we have nothing" — and those two states call for different actions.

Stock that allocation may draw on SHALL be exactly the stock that is both unexpired
and not already fully reserved. A row whose entire quantity is reserved is, to
allocation, indistinguishable from one that does not exist; treating the two
differently would oblige every caller to remember to skip it.

Expiry SHALL be expressed as a property of the row — whether it has expired — rather
than as a verdict on what may be done with it. The verdict depends on quantity as
well, and folding the two together loses the distinction the previous paragraph
insists on. Nor SHALL the term "sellable" be used: a third-party logistics provider
does not sell the goods, the owner does; the question a warehouse answers is whether
goods can ship.

#### Scenario: An order is not satisfied from expired stock

- **GIVEN** the only stock for a SKU expired yesterday
- **WHEN** an order for that SKU is allocated
- **THEN** the order is backordered, and the expired stock's quantity is unchanged

#### Scenario: Expired stock remains visible and marked

- **WHEN** stock for a SKU is queried
- **THEN** expired rows appear in the response, marked as expired

#### Scenario: A fully reserved row is not drawn on

- **GIVEN** the only unexpired stock for a SKU is entirely reserved for other orders
- **WHEN** a further order for that SKU is allocated
- **THEN** the order is backordered and that row's reserved quantity is unchanged


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
  - order-promising/src/main/java/com/flowzati/archone/stock/application/command/AllocateWaitingDemandCommand.java
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

### Requirement: Allocation consumes the earliest-expiring stock first

Allocation SHALL take stock in order of expiry date, earliest first. Where two rows
share an expiry date, the earlier arrival SHALL be taken first; where those also match,
the order SHALL still be deterministic.

The second and third ordering keys are not decoration. Same-expiry rows are common —
one production batch delivered on two days produces exactly that. Without a total
order, the same stock and the same order allocate differently between runs, and the
write ordering that prevents deadlocks has nothing stable to sort by.

#### Scenario: A demand spanning two rows takes the nearer expiry first

- **GIVEN** stock of 60 units expiring in one month and 40 units expiring in six
- **WHEN** an order for 80 units is allocated
- **THEN** all 60 of the nearer-expiry stock and 20 of the later are taken

#### Scenario: Same-expiry rows are taken oldest arrival first

- **GIVEN** two rows share an expiry date and differ in arrival date
- **WHEN** an order smaller than either row is allocated
- **THEN** the earlier-arrived row is the one consumed

#### Scenario: Repeating an allocation reproduces the same choice

- **GIVEN** the same stock rows and the same order
- **WHEN** the allocation is performed again from the same starting state
- **THEN** the same rows are consumed in the same order


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
  - order-promising/src/main/java/com/flowzati/archone/stock/application/command/AllocateWaitingDemandCommand.java
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

### Requirement: An order is satisfied wholly or not at all

An order SHALL be allocated only when its entire demand can be met from allocatable
stock.
When it cannot, no stock SHALL be reserved for it and the whole order SHALL become
backordered.

This is the existing ship-complete rule, restated because batching makes it easy to
violate by accident: taking 60 of the 80 units needed leaves 60 units locked for an
order that cannot ship, while a later smaller order that could have shipped finds
nothing.

#### Scenario: A partially satisfiable order reserves nothing

- **GIVEN** allocatable stock totals 50 units across two rows
- **WHEN** an order for 80 units is allocated
- **THEN** the order is backordered and both rows' reserved quantities are unchanged


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
  - order-promising/src/main/java/com/flowzati/archone/stock/application/command/AllocateWaitingDemandCommand.java
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

### Requirement: Seed data makes every allocation outcome reproducible

Seed data SHALL include, for one SKU, three unexpired rows of near, middle and far
expiry, of which **two share an expiry date and differ in arrival date**, plus one
expired row. It SHALL include an order whose demand spans more than one row.

Each element exists to make one behaviour observable: the three expiries make the
ordering visible, the shared expiry is the only way the tie-break is exercised at all,
the expired row is the "present but not allocatable" case, and the spanning order is the
only way multi-row consumption and multi-reservation are seen to happen.

#### Scenario: Seeded stock exercises the tie-break

- **WHEN** the seeded stock for that SKU is inspected
- **THEN** two rows share an expiry date and differ in arrival date

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
  - order-promising/src/main/java/com/flowzati/archone/stock/application/command/AllocateWaitingDemandCommand.java
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

### Requirement: Allocatable stock is supplied to allocation grouped by SKU

The batches offered to an allocation decision SHALL be grouped by SKU code, and that
grouping SHALL hold a key for **every** SKU the demand names. A demanded SKU with no key
SHALL be refused as a caller error.

**A SKU with no allocatable batch SHALL be expressed as an empty group, not as an absent
key.** The two mean different things: an empty group is ordinary stock-out, an absent key is
the caller having assembled the wrong input. Collapsing them makes a programming error
indistinguishable from a business outcome — "the batches for that SKU were never loaded"
would look exactly like "that SKU is sold out", and only one of those is a bug.

**Covering, not exactly equal.** Waking a queue supplies the union of every candidate's SKUs,
so an order naming only one of them legitimately sees keys it does not use. Extra keys are
harmless: allocation draws only on the groups its lines name, so a batch nobody asked for is
never touched. The dangerous direction is the missing one.

#### Scenario: A grouping missing one of the demanded SKUs is refused

- **GIVEN** an order naming two SKUs
- **WHEN** allocation is attempted with batches grouped for only one of them
- **THEN** the attempt is refused as an error, and no stock is modified

#### Scenario: A grouping carrying SKUs this order does not name is accepted

- **GIVEN** a queued order naming one SKU, woken in a round whose candidates together name three
- **WHEN** allocation is attempted with all three groups
- **THEN** the order is judged on its own SKU, and the other two groups are untouched

#### Scenario: A SKU with no allocatable batch is an ordinary stock-out

- **GIVEN** an order naming two SKUs, one of which has an empty batch group
- **WHEN** allocation is attempted
- **THEN** the order is not allocated, no stock is modified, and the outcome is a stock-out
  rather than an error


<!-- @trace
source: allocate-multi-sku-orders-as-one-basket
updated: 2026-07-31
code:
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/selector/policy/StrictFifoAllocationPolicy.java
  - order-promising/src/main/java/com/flowzati/archone/bootstrap/DevSeedDataInitializer.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/selector/policy/MaximizeFulfilledOrdersPolicy.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/selector/context/BasicAllocationContext.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/coordinator/OrderAllocationCoordinator.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/AllocateOrderUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/selector/context/BasicAllocationContextFactory.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationRequest.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationResult.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/entrypoint/rest/PlaceOrderRequest.java
  - frontend/src/components/OrderTable.tsx
  - frontend/src/components/PlaceOrderForm.module.css
  - frontend/src/components/PlaceOrderForm.tsx
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationService.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/SkuQuantities.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/repository/StockPoolRepository.java
  - docs/execution-roadmap.md
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationPlan.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/Order.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/jpa/JpaStockRepository.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/StockPoolRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/ReplenishmentUsecase.java
tests:
  - frontend/src/components/PlaceOrderForm.test.tsx
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/service/AllocationServiceTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/OrderingArchitectureTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/usecase/ReplenishmentUsecaseTest.java
  - order-promising/src/sit/java/com/flowzati/archone/bootstrap/DevSeedDataIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/service/AllocationPlanTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/entrypoint/rest/OrderControllerTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/service/selector/AllocationPolicyTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/domain/model/OrderTest.java
  - order-promising/src/test/java/com/flowzati/archone/testsupport/OrderFixtures.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/usecase/AllocateOrderUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/service/SkuQuantitiesTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationWorkflowEndToEndIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/service/AllocationSelectorTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/coordinator/OrderAllocationCoordinatorTest.java
-->

---

### Requirement: Waking a queue loads every SKU its candidates need

Availability wake-up and reconciliation SHALL first discover a bounded set of whole pending demands from the affected inventory scope. Before planning a candidate, the transaction SHALL reject it when an earlier intersecting-SKU demand exists. It SHALL then load allocatable batches for every SKU in the eligible candidate, not only the triggering SKU.

The set of stock rows one attempt may touch SHALL be known before reservations are written, and repository query count SHALL NOT grow with the number of stock batches. A predecessor commit SHALL cause its successor to be reconsidered in a later bounded iteration, not in the same transaction.

#### Scenario: Another required SKU is loaded

- **GIVEN** a demand requires SKU-A and unavailable SKU-B
- **WHEN** SKU-A wakes its scope
- **THEN** the attempt also evaluates SKU-B and leaves SKU-A unreserved

#### Scenario: A predecessor blocks before stock planning

- **GIVEN** a candidate has an earlier pending demand sharing one SKU
- **WHEN** the candidate attempt begins
- **THEN** it stops before reserving or materializing target execution

#### Scenario: A successor is reconsidered after commit

- **GIVEN** two allocatable demands share a SKU
- **WHEN** the earlier demand commits
- **THEN** the later demand is considered in a subsequent bounded iteration

---

### Requirement: Every writer of stock rows uses one global order

Every code path that writes stock rows SHALL write them sorted by one order defined in a
single place, and that order SHALL be a total order over the rows.

Two transactions that lock the same rows in opposite orders wait for each other forever.
The only defence is that every writer agrees on one sequence, which means the sequence
cannot be each writer's own — it has to be one definition they all reach for.

Until now there was one writer, and the order lived inside it as a private detail. There are
now three paths that touch stock: assigning it, releasing it, and — when shipping arrives —
completing it. A copy of the comparator in each is the shape this fails in, because the
copies drift and the symptom is a deadlock under concurrency: not reproducible on demand,
absent from load tests, present in production.

The order SHALL be computed over a set known before the transaction begins, and SHALL NOT
rely on the incidental ordering of any query. The batch query happens to return rows in a
compatible order; releasing and waking assemble their sets from entirely different sources.

#### Scenario: Two paths write the same rows in the same sequence

- **GIVEN** two stock rows that both assigning and releasing would touch
- **WHEN** each path writes them
- **THEN** both write them in the same sequence

#### Scenario: The sequence does not follow the order rows arrived in

- **GIVEN** rows supplied to a write in an order opposite to the global one
- **WHEN** they are written
- **THEN** they are written in the global order, not the order supplied

---

### Requirement: Stock is held per owner, location, arrival and expiry

A stock row SHALL be identified by its owner, its location, its SKU code, the date the
goods arrived, and the date they expire. Two rows differing in any one of those five are
different stock and SHALL NOT be merged.

**The location SHALL have usage `internal`.** Stock is what the company holds, and only
internal locations count towards that. A row in a virtual location would be quantity the
system claims to hold in a place it does not operate.

The expiry date SHALL be mandatory. A nullable expiry inside a uniqueness constraint is
a trap in PostgreSQL, where NULLs compare as distinct: two same-day arrivals of a
non-perishable SKU would become two rows rather than one, silently.

The arrival date SHALL participate in identity rather than being a mere attribute.
Keeping it in identity means every replenishment either matches an existing row exactly
or creates a new one — there is no merge rule to define, and therefore none to get
wrong.

Quantities SHALL be held per row: on-hand and reserved. Available-to-promise SHALL be
derived from them at read time and SHALL NOT be stored, because a stored derivation is
a second source of truth that can disagree with the first.

**The quantities SHALL remain materialised on the row rather than summed from movements.**
This holds even once movements exist: a stock row is a balance that movements write, not a
view over them. The optimistic-lock version guarding that balance is the mechanism by which
replenishment and queue-waking serialise against concurrent orders — see the second of the
three replenishment decisions in `docs/dom-promising-scope.md`. Deriving the balance at
read time would remove the row that lock is taken on.

**Resolving that identity SHALL happen while completing an inbound movement, and a row
SHALL be opened holding nothing.** The five dimensions and the rule over them are unchanged;
what changes is who applies them. Arriving goods previously had two paths — add to the
matching row, or create a row already holding the arrival — and the second was a way to put
stock into the system without recording a movement. There is now one path: find or open the
row, then let the movement's line put the quantity into it.

A row holding nothing is a normal state, not a defect. It is what a stock row looks like
between being identified and being filled, and it is also what remains after everything in
it has been shipped.

#### Scenario: Two owners holding the same SKU code hold separate stock

- **GIVEN** two owners each hold stock of the same SKU code in the same location
- **WHEN** one owner's order consumes that stock
- **THEN** the other owner's available-to-promise is unchanged

#### Scenario: Same-day arrivals of different expiry stay separate

- **WHEN** two deliveries of one SKU arrive at one location on the same day with
  different expiry dates
- **THEN** they are held as two rows, each carrying its own expiry

#### Scenario: An identical arrival adds to the existing row

- **GIVEN** stock exists for an owner, location, SKU, arrival date and expiry date
- **WHEN** a replenishment arrives naming all five identically
- **THEN** its quantity is added to that row and no second row is created

#### Scenario: Stock cannot be held in a virtual location

- **WHEN** a stock row is written against a location whose usage is not `internal`
- **THEN** the write is refused

---

### Requirement: Allocation draws stock from a location, and demand is published with one

Allocation SHALL select candidate stock by owner, **location**, and SKU code. It SHALL NOT
select by warehouse.

The two coincide while a warehouse has one internal location, and that is exactly why the
distinction has to be stated now: a query keyed on the warehouse would keep passing every
test until a second internal location appeared, and would then draw on stock the order was
never meant to reach.

**The published demand SHALL carry the location, resolved from the order's warehouse.**
Orders name warehouses; allocation speaks locations. Resolving it where the two meet keeps
each side to one vocabulary — were the demand to publish a warehouse, allocation would have
to understand both, and every query would carry the conversion.

The FEFO ordering SHALL be unchanged — expiry date, then arrival date, then row identity —
and the covering index SHALL keep that column order, with location in the position
warehouse held.

**The scope of a backorder queue SHALL likewise be keyed to the location.** A queue that
spans locations spends its bound on orders that were never candidates, which is the same
reason it was scoped to one warehouse before.

#### Scenario: Allocation ignores stock in another location

- **GIVEN** an owner holds allocatable stock of one SKU in two internal locations
- **WHEN** an order sourced from one of them is allocated
- **THEN** only that location's stock is drawn on, and the other location's
  available-to-promise is unchanged

#### Scenario: FEFO order is unchanged by the move to locations

- **GIVEN** several batches of one SKU in one location with differing expiry and arrival
  dates
- **WHEN** they are listed for allocation
- **THEN** they appear ordered by expiry date, then arrival date, then identity

#### Scenario: A newly opened row holds nothing until a movement line fills it

- **GIVEN** a replenishment naming five dimensions that match no existing row
- **WHEN** the row is opened
- **THEN** it holds nothing
- **AND** it holds the arrival's quantity only once the movement's line has been applied

---

### Requirement: Initial allocation and backorder waking use the same allocation semantics

An initial assignment attempt and a backorder wake attempt SHALL use the same stock operation selection, pure planning and transactional
assignment semantics. Both paths SHALL preserve owner/location isolation, strict FIFO selection, FEFO batch selection,
`SHIP_COMPLETE` and stock-lock ordering.

The initial path SHALL register the stock operation and confirmed moves before invoking the shared assignment responsibility. The wake path SHALL
select an already registered confirmed stock operation. Neither application use case SHALL invoke the other.

#### Scenario: Initial assignment applies the shared semantics

- **GIVEN** newly registered confirmed moves whose complete stock operation is covered by allocatable stock
- **WHEN** their initial assignment is attempted
- **THEN** the existing moves are assigned using the same semantics used by a wake attempt

#### Scenario: A wake round applies the shared semantics

- **GIVEN** a confirmed stock operation selected after stock becomes available
- **WHEN** a bounded wake round is attempted
- **THEN** its moves are assigned using the same semantics used by an initial attempt

#### Scenario: Neither flow delegates to the other flow

- **WHEN** initial assignment and backorder waking are inspected
- **THEN** each flow invokes the shared assignment responsibility directly
- **AND** neither application use case invokes the other application use case

### Requirement: Stock availability triggers convergent backorder allocation

A confirmed receipt SHALL commit its completed inbound execution and a `StockAvailabilityIncreased` Outbox fact together. It SHALL NOT
assign waiting outbound stock operations in the receipt transaction. The fact handler and a periodic reconciliation scheduler SHALL invoke the
same transactional bounded wake use case and FIFO/FEFO assignment semantics.

Every bounded round SHALL process at most the configured stock operation limit. It SHALL NOT publish an orchestration-only continuation event.
Remaining confirmed stock operation queues SHALL be discovered by periodic reconciliation. Event retries and overlap with the scheduler SHALL
be safe: already assigned moves and reserved quantities SHALL NOT be applied twice.

#### Scenario: Receipt commits before assignment

- **GIVEN** a local receipt makes stock available to confirmed outbound moves
- **WHEN** the receipt transaction commits
- **THEN** its completed inbound execution, physical stock increase and availability fact commit together
- **AND** no waiting outbound move is assigned by that receipt transaction

#### Scenario: Availability event triggers a prompt wake

- **GIVEN** a committed availability fact for a queue containing confirmed stock operations
- **WHEN** its Integration Event is consumed
- **THEN** the handler claims the message and invokes one transactional bounded wake round

#### Scenario: Scheduler reconciles confirmed stock operation queues

- **GIVEN** confirmed stock operations remain because an event was delayed, lost or exhausted
- **WHEN** the reconciliation scheduler scans eligible queue keys
- **THEN** it invokes the same transactional bounded wake use case without transport metadata

#### Scenario: A new stock operation cannot bypass an older shared-SKU stock operation

- **GIVEN** an older confirmed stock operation remains unassigned after stock becomes available
- **WHEN** a newer stock operation attempts immediate assignment for any shared owner, location and SKU
- **THEN** the newer stock operation remains confirmed behind the older stock operation

#### Scenario: A full round leaves bounded work for reconciliation

- **GIVEN** a wake round processes the configured maximum number of stock operations
- **WHEN** the round completes
- **THEN** no continuation event is recorded and a later scheduler round can discover the remaining confirmed work

### Requirement: Confirmed receipts create warehouse execution before physical stock changes

`StockPool` SHALL be the stock context's physical inventory source of truth. A local receipt
confirmation SHALL create an inbound stock operation and move, complete that move with a move line pointing
to the identified owner, location, SKU, in-date, and expiry-date batch, and change physical stock
only through that completed line. It SHALL record the availability fact in the same transaction,
while backorder allocation runs after commit.

#### Scenario: Receipt confirmation updates physical stock through movement completion

- **GIVEN** a synchronous receipt request identifies one stock batch and a positive quantity
- **WHEN** `ConfirmStockReceiptUsecase` handles it
- **THEN** one inbound stock operation, move, and move line are recorded and completed
- **AND** the matching batch is increased or a new batch is created from that move line

#### Scenario: The use case validates the selected receipt location

- **GIVEN** a synchronous receipt request identifies a facility and one of its internal locations
- **WHEN** the REST adapter invokes `ConfirmStockReceiptUsecase`
- **THEN** the adapter does not access a stock-location repository
- **AND** the use case verifies that the location is internal and belongs to the facility before
  recording the movement
- **AND** the stock operation, move, physical stock, and availability fact use that location

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

---

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

---

### Requirement: Allocation takes its demand from confirmed movements, never from a source aggregate

Allocation SHALL select a complete stock-consuming `StockOperation` and its confirmed moves. It SHALL NOT load or mutate an Order,
Transfer or other source aggregate and SHALL NOT reconstruct movement intent from a duplicate allocation-demand model.

Strict FIFO precedence SHALL be scoped by owner, source stock location and intersecting confirmed-move SKUs using
`(stock operation.enqueuedAt, stock operation.id)`. A candidate SHALL be eligible exactly when no earlier confirmed stock-consuming stock operation in that scope
has an intersecting confirmed SKU set. Required-by time, release priority, destination and current ATP SHALL NOT reorder this relation.
The final predecessor check SHALL occur inside the assignment transaction, and one transaction SHALL assign at most one stock operation.

#### Scenario: A shared confirmed SKU establishes precedence

- **GIVEN** an earlier confirmed stock operation needs SKU-A and SKU-B and a later confirmed stock operation needs SKU-B and SKU-C in the same scope
- **WHEN** the later stock operation is evaluated
- **THEN** the earlier stock operation blocks it because their confirmed SKU sets intersect

#### Scenario: Disjoint confirmed stock operations are independent

- **GIVEN** an earlier confirmed stock operation needs only SKU-A and a later confirmed stock operation needs only SKU-B in the same scope
- **WHEN** SKU-B availability is reconciled
- **THEN** the later stock operation is not blocked by the earlier stock operation

#### Scenario: An unavailable predecessor remains visible

- **GIVEN** an earlier shared-SKU stock operation is currently short of another SKU
- **WHEN** a later otherwise-satisfiable stock operation is evaluated
- **THEN** the exact predecessor check rejects the later stock operation

### Requirement: Assignment publishes one canonical stock operation outcome

An assignment transaction SHALL write only Inventory stock, stock operation, movement, movement-line and Outbox facts. It SHALL NOT load a source
aggregate or write WMS-owned Shipment, Wave or PickTask records.

The Inventory `stockOperationId` SHALL be the stable operation-group identity across the assignment result and move-centric integration contract.
The result SHALL include source-unit trace, move identities and current batch-pick details. For an order-backed stock operation, exactly one
order-allocation committed fact SHALL be written to Outbox; Ordering and WMS SHALL consume it under separate subscription identities.

Because this contract has not been released, its initial V1 schema SHALL be the stock-operation-centric payload. Producers and consumers
SHALL support only that V1 schema and SHALL NOT retain pre-release allocation-demand compatibility readers.

#### Scenario: Stock operation identity crosses the assignment boundary

- **GIVEN** an assigned Inventory stock operation has id `stock operation-1`
- **WHEN** its assignment fact is published
- **THEN** the fact identifies `stock operation-1`, its moves and their batch picks without an allocation-demand id

#### Scenario: WMS remains a separate writer

- **WHEN** WMS consumes the assigned-stock operation fact
- **THEN** WMS creates or finds its execution by `stockOperationId` without Inventory writing WMS tables

#### Scenario: A transfer assignment needs no order event

- **WHEN** a transfer-backed stock operation is assigned
- **THEN** the core result remains source-addressable and no order-specific fact is required

#### Scenario: Producers emit only the canonical V1 schema

- **WHEN** an assignment fact is published
- **THEN** it uses contract version 1 with stock operation identity and no allocation-demand compatibility fields

### Requirement: A SHIP_COMPLETE stock operation is satisfiable only when every move is covered

A confirmed `SHIP_COMPLETE` stock operation SHALL be planned only after it passes the exact shared-SKU predecessor check. It SHALL be ready only
when allocatable stock covers the aggregate requested quantity of every SKU and the resulting reservation drafts exactly cover every
confirmed move. A short SKU SHALL leave all moves confirmed and SHALL reserve no stock.

The pure planner SHALL report every short SKU and SHALL return no reservation drafts for an insufficient proposal. For repeated SKU
moves, it SHALL perform sufficiency on the aggregate and distribute FEFO batch quantities in immutable move line-sequence order. Each
draft SHALL identify `moveId`, `stockQuantId` and quantity.

#### Scenario: One SKU short changes no movement or stock

- **GIVEN** a confirmed stock operation needs SKU-A and SKU-B and SKU-B is short
- **WHEN** it is planned
- **THEN** no move line or reservation is created and every move remains `CONFIRMED`

#### Scenario: Every move covered creates a complete proposal

- **GIVEN** every SKU aggregate is covered and the stock operation has no predecessor
- **WHEN** it is planned
- **THEN** the immutable proposal exactly covers every confirmed move

#### Scenario: Repeated SKU moves are deterministic

- **GIVEN** two confirmed moves request the same SKU across multiple FEFO batches
- **WHEN** the same snapshot is planned repeatedly
- **THEN** batch quantities map to move ids identically in immutable line-sequence order

### Requirement: Allocation assigns existing movements atomically

For one eligible and fully satisfiable stock operation, a single transaction SHALL lock and reload the stock operation, its moves and relevant stock
quants; verify proposal versions and exact precedence; reserve the planned quantities; create `StockMoveLine` details; transition the
existing moves to `ASSIGNED`; update the stock operation summary to `ASSIGNED`; and append the committed fact to Outbox.

The transaction SHALL follow the common lock order `StockOperation -> StockMove id order -> StockQuant global write order`. If any
revalidation, counter, move-line, state or Outbox write fails, every effect SHALL roll back and the stock operation and moves SHALL remain
confirmed. A retry after a successful commit SHALL reconstruct the original result from assigned moves and move lines without reserving
again.

#### Scenario: A successful proposal assigns a coherent existing move set

- **WHEN** a complete eligible proposal commits
- **THEN** every original move is assigned with exact move-line coverage, the stock operation is assigned and one committed fact is persisted

#### Scenario: A commit failure leaves the movement set confirmed

- **GIVEN** one counter, move-line, state or Outbox write fails
- **WHEN** the assignment transaction rolls back
- **THEN** no partial reservation remains and the original stock operation and moves remain `CONFIRMED`

#### Scenario: A committed retry does not reserve twice

- **GIVEN** an assignment committed but its caller did not observe the result
- **WHEN** the same deterministic invocation is retried
- **THEN** it returns the persisted assignment result without changing reserved quantities

### Requirement: Quant reservation counters reconcile with assigned move lines

For each stock quant, `reservedQuantity` SHALL equal the sum of quantities on move lines whose parent stock-consuming move is
`ASSIGNED`. Reconciliation SHALL report a counter mismatch, an assigned move without exact line coverage, a confirmed or cancelled move
with lines, or a `SHIP_COMPLETE` stock operation whose move states are mixed.

#### Scenario: A healthy assigned stock operation reconciles

- **GIVEN** every assigned move has exact move-line coverage and every referenced quant counter equals its assigned-line sum
- **WHEN** allocation reconciliation runs
- **THEN** it reports no anomaly for that stock operation or its quants

#### Scenario: Counter drift is detected

- **GIVEN** a quant reserved counter differs from the sum of its assigned outgoing move lines
- **WHEN** allocation reconciliation runs
- **THEN** it reports the mismatch and does not infer correctness from stock operation state alone

### Requirement: Allocation treats StockOperation as its selection and atomicity boundary

Allocation SHALL select a complete confirmed stock-consuming `StockOperation` and its confirmed moves without loading its source
aggregate. The pure planner SHALL consume immutable operation, move and quant snapshots and SHALL produce only an immutable proposal;
it SHALL NOT mutate persistence or publish an outcome.

For a fully satisfiable operation, one transaction SHALL lock and reload the operation, its moves and relevant quants; revalidate the
proposal and exact predecessor; reserve the planned quantities; create `StockMoveLine` details; transition the existing moves and
operation to `ASSIGNED`; and append the committed fact to Outbox. The lock order SHALL remain `StockOperation`, then `StockMove` in ID
order, then `StockQuant` in global write order.

#### Scenario: Planning has no side effect

- **GIVEN** a confirmed stock operation and an immutable stock snapshot
- **WHEN** the planner computes an assignment proposal
- **THEN** no operation, move, move line, quant counter or Outbox fact is changed

#### Scenario: A complete proposal assigns one operation atomically

- **WHEN** an eligible complete proposal commits
- **THEN** every original move gains exact move-line coverage and becomes `ASSIGNED`
- **AND** its stock operation becomes `ASSIGNED` and one committed fact is persisted in the same transaction

#### Scenario: A commit failure leaves no partial allocation

- **GIVEN** one counter, line, state or Outbox write fails
- **WHEN** the assignment transaction rolls back
- **THEN** the stock operation and its moves remain `CONFIRMED` and no partial reservation remains

### Requirement: Allocation policies are invariant under the operation rename

Renaming the operation group SHALL NOT change `SHIP_COMPLETE`, strict shared-SKU FIFO, FEFO, owner and source-location isolation,
proposal revalidation, reservation counters or retry idempotency. Strict precedence SHALL continue to use intersecting confirmed-move
SKUs and `(operation.enqueuedAt, operation.id)` within owner and source-location scope.

A `SHIP_COMPLETE` stock operation SHALL be assignable only when every confirmed move is covered exactly. A short SKU SHALL leave every
move confirmed and SHALL reserve no stock. FEFO drafts SHALL continue to identify `moveId`, `stockQuantId` and quantity.

#### Scenario: A short SKU changes nothing

- **GIVEN** a confirmed `SHIP_COMPLETE` stock operation needs multiple SKUs and one SKU is short
- **WHEN** allocation is attempted
- **THEN** no move line or reservation is created and every move remains `CONFIRMED`

#### Scenario: An older shared-SKU operation keeps precedence

- **GIVEN** an older confirmed stock operation and a newer one share an owner, source location and confirmed SKU
- **WHEN** the newer operation is evaluated
- **THEN** it remains behind the older operation regardless of the vocabulary rename

#### Scenario: FEFO remains deterministic

- **GIVEN** a stock operation is satisfiable from multiple eligible batches
- **WHEN** the same immutable snapshots are planned repeatedly
- **THEN** the same quant quantities map to the same move IDs in deterministic order

### Requirement: Assignment outcomes publish the canonical stock-operation identity

An assignment result and every integration contract SHALL identify the Inventory operation by `stockOperationId` and SHALL include
source-unit trace, move identities and current batch-pick details. Because no prior contract has been released, producers and consumers
SHALL use only the canonical V1 schema and SHALL NOT dual-publish or normalize pre-release schemas. Audit publication SHALL use aggregate
type `StockOperation`.

#### Scenario: A new outcome crosses contexts with one identity

- **GIVEN** an assigned stock operation has a stable UUID
- **WHEN** its assignment fact is published
- **THEN** Ordering and WMS receive `stockOperationId` with that UUID and the move-centric batch snapshot

#### Scenario: One V1 fact creates one warehouse execution

- **WHEN** a stock operation is assigned
- **THEN** the producer publishes exactly one V1 fact and WMS applies it idempotently by `stockOperationId`

### Requirement: Workflow history starts with the canonical assignment signal

The Temporal assignment signal SHALL be `stockOperationAssigned` with a `StockOperationAssignmentSnapshot`. Because no workflow history
has been released, the workflow contract SHALL NOT retain the pre-release `pickingAssigned` signal or its snapshot. Adapters SHALL send
only the canonical signal, and Inventory or WMS domain/application APIs SHALL contain no legacy field alias.

#### Scenario: A new workflow receives the canonical signal

- **WHEN** a new assignment reaches an active workflow
- **THEN** the adapter sends `stockOperationAssigned` with `stockOperationId`

### Requirement: StockOperationAssigner is the canonical assignment application entry

Initial assignment after source registration, availability-driven retry and backlog reconciliation SHALL invoke one
`StockOperationAssigner` application façade. The façade SHALL coordinate candidate acquisition, pure planning and transactional apply;
an application use case SHALL NOT invoke another application use case to reconstruct that flow. Availability and backlog entry points
SHALL pass one transport-neutral `AssignmentQueueKey` and SHALL NOT wrap the same fields in a second command type.

`MovementAssignmentPlanner` SHALL remain a deterministic calculation over immutable facts. `StockOperationAssignmentTransaction` SHALL
remain an internal apply boundary and SHALL NOT be exposed as an independent adapter entry point. A backlog reconciler SHALL discover
retryable keys and invoke the same façade rather than duplicate planning or apply logic.

#### Scenario: Source registration attempts assignment through the façade

- **GIVEN** source registration creates a confirmed stock operation
- **WHEN** the registration flow attempts initial assignment
- **THEN** it invokes `StockOperationAssigner` and does not invoke the assignment transaction directly

#### Scenario: Availability and reconciliation share one flow

- **GIVEN** an availability event and a scheduled reconciliation identify the same assignment queue
- **WHEN** each trigger attempts the next candidate
- **THEN** both pass the same `AssignmentQueueKey` to `StockOperationAssigner`
- **AND** both use the same candidate, planner and transactional apply sequence

### Requirement: Assignment candidate acquisition exposes immutable source facts

`AssignmentCandidateQuery` SHALL return an immutable `AssignmentCandidate` containing a `MovementPlanningSnapshot` and an optional
`StockOperationPredecessor`. It SHALL NOT expose a mutable `StockOperation` aggregate to the planner or triggering adapter. Candidate
acquisition SHALL be treated as an optimistic read, and the assignment transaction SHALL lock the canonical operation and moves, reload
affected quants, and recheck exact predecessor and proposal validity before changing any target state.

#### Scenario: Planning cannot mutate the selected operation

- **GIVEN** a confirmed operation is eligible for planning
- **WHEN** `AssignmentCandidateQuery` returns its candidate
- **THEN** the candidate contains immutable movement facts and no mutable operation aggregate

#### Scenario: A stale candidate cannot bypass final precedence

- **GIVEN** a candidate was planned before an older intersecting operation became visible to the transaction
- **WHEN** transactional apply performs its final predecessor check
- **THEN** it rejects or skips the stale proposal without creating move lines or changing reserved counters

### Requirement: Allocation command stores and read queries have separate ports

Allocation command stores SHALL expose only identity lookup, persistence and required lock operations. Candidate selection, backlog
discovery, deterministic FEFO supply and stock-operation reconciliation SHALL use purpose-specific query ports. A shared JPA aggregate
repository SHALL NOT serve as the public port for both command persistence and those unrelated read purposes.

The backlog query SHALL expose only production reconciliation needs. An oldest-enqueued-age query with no production caller SHALL be
removed rather than retained solely for a persistence test.

#### Scenario: FEFO planning reads through a supply query

- **GIVEN** a planner needs eligible quants for multiple SKUs
- **WHEN** it loads allocatable stock
- **THEN** it uses `AllocatableStockQuery` with deterministic owner, location, SKU and FEFO scope
- **AND** it does not call a command store's generic quant listing method

#### Scenario: Backlog discovery does not enlarge the operation store

- **WHEN** reconciliation discovers assignment queues with ready work
- **THEN** it uses `AssignmentBacklogQuery`
- **AND** `StockOperationStore` remains free of backlog and queue-head discovery methods

### Requirement: Assignment apply uses ephemeral validated working sets

The assignment transaction SHALL construct an internal `LockedStockOperation` from the complete locked operation, move and move-line
set and an internal `QuantReservationSet` from proposal quantities. These working sets SHALL centralize completeness, homogeneous-state,
exact-coverage, quant-scope and reservation-delta validation while preserving the lock order
`StockOperation -> StockMove ID order -> StockQuant global write order`.

Neither working set SHALL have a persistence identity, repository or lifecycle, and neither SHALL become a parallel reservation ledger.
If any validation, save or Outbox operation fails, the complete assignment SHALL roll back.

#### Scenario: A valid proposal commits through one validated target set

- **GIVEN** a proposal exactly covers every confirmed move with in-scope quants
- **WHEN** assignment apply succeeds
- **THEN** move lines, quant reserved counters, moves, operation state and Outbox facts commit in one transaction

#### Scenario: An invalid quant scope rolls back the complete assignment

- **GIVEN** a proposal references a quant outside the operation owner or source-location scope
- **WHEN** `QuantReservationSet` is validated
- **THEN** the transaction rejects the proposal
- **AND** no move line, reservation counter, move state, operation state or Outbox fact changes
