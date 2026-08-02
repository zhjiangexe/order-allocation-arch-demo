# outbox-event-delivery Specification

## Purpose

TBD - created by archiving change 'fix-outbox-partition-key-semantics'. Update Purpose after archive.

## Requirements

### Requirement: Outbox rows separate aggregate identity from delivery metadata

An `event_outbox` row SHALL express domain identity and delivery decisions in
distinct columns. `aggregatetype` and `aggregateid` SHALL identify the aggregate
the Integration Event belongs to. `route` and `partition_key` SHALL carry the two
delivery decisions — target topic and Kafka message key. Neither aggregate column
SHALL influence topic or message-key selection, and neither delivery column SHALL
be treated as aggregate identity.

For Integration Events emitted on behalf of an Order aggregate, `aggregateid`
SHALL be the `orderId`, regardless of any partition-key configuration.

#### Scenario: Order aggregate events record orderId as aggregate identity under every partition strategy

- **WHEN** an Integration Event is appended to the outbox for an Order aggregate
- **THEN** `aggregatetype` is `Order` and `aggregateid` is that order's `orderId`,
  and the value of `archone.allocation.partition-key-strategy` does not change it

##### Example: same event under both strategies

| `partition-key-strategy` | `aggregatetype` | `aggregateid` | `partition_key` |
| --- | --- | --- | --- |
| `order-id` (default) | `Order` | `orderId` | `orderId` |
| `stock` | `Order` | `orderId` | `<owner>/<node>` |

The `partition_key` column SHALL NOT hold a bare SKU code under any strategy. It once
did, and the illustration above outlived that; a stale example is worse than no example,
because it reads as a specification of the format.


<!-- @trace
source: complete-stock-strategy-rename
updated: 2026-07-30
code:
  - docs/stock-reservation-design.md
  - frontend/vite.config.ts
  - frontend/src/api/catalog.ts
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/BackorderCreatedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/Order.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/repository/StockPoolRepository.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/jpa/JpaStockReservationRepository.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/repository/OrderRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecase.java
  - order-promising/src/main/resources/db/migration/V2__create_ordering_tables.sql
  - docs/dom-promising-scope.md
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/model/StockPool.java
  - order-promising/src/main/resources/db/migration/V3__create_stock_pools.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/OrderPlacedIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/entity/StockReservationEntity.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/StockReplenishedIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/translator/OrderingDomainEventTranslator.java
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/KafkaIntegrationEventDispatcher.java
  - frontend/src/pages/StockPage.tsx
  - e2e/perf/README.md
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/mapper/StockReservationMapper.java
  - e2e/perf/k6/hot-sku-burst.js
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/OrderAllocation.java
  - e2e/perf/kafka-connect/register-outbox-connector.sh
  - frontend/src/components/StockPanel.module.css
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/BackorderWakeRequestedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/OrderLine.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/event/BackorderWakeContinuationRequired.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/model/ReservationStatus.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/coordinator/OrderAllocationCoordinator.java
  - e2e/perf/docker-compose.yml
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/OrderAllocatedIntegrationEvent.java
  - e2e/perf/k6/results/hot-sku-burst-20260730T164656.json
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/model/StockReservation.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationOutcome.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/StockReservationRepositoryImpl.java
  - order-promising/src/main/resources/application-dev.properties
  - order-promising/src/main/resources/db/migration/V4__create_stock_reservations.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/application/command/WakeBackordersCommand.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/OrderCancelledIntegrationEventHandler.java
  - order-promising/src/main/resources/db/migration/V2__create_stock_pools.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/translator/AllocationDomainEventTranslator.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/OrderPlacedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/ReplenishmentUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolResponse.java
  - order-promising/src/main/java/com/flowzati/archone/common/configuration/CommonConfiguration.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/OrderCancelledIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/event/OrderAllocationCompleted.java
  - frontend/src/api/types.ts
  - frontend/src/components/AppHeader.tsx
  - order-promising/src/main/java/com/flowzati/archone/common/time/BusinessCalendar.java
  - e2e/perf/k6/results/hot-sku-burst-20260730T164603.json
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/entity/StockPoolEntity.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/mapper/StockPoolMapper.java
  - frontend/src/components/StockPanel.tsx
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationRequest.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/StockReplenishedIntegrationEvent.java
  - frontend/src/api/client.ts
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationResult.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/OrderCancelled.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/command/ReplenishStockCommand.java
  - e2e/perf/run.sh
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/jpa/JpaStockRepository.java
  - order-promising/src/main/java/com/flowzati/archone/bootstrap/DevSeedDataInitializer.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/repository/jpa/JpaOrderRepository.java
  - order-promising/src/main/java/com/flowzati/archone/common/outbox/StockContentionKey.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/ReleaseReservationUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/GetStockPoolUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/BackorderWakeRequestedIntegrationEventHandler.java
  - order-promising/src/main/resources/db/migration/V3__create_ordering_tables.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/StockPoolRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationService.java
  - docs/system-layer-map.md
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/AllocateOrderUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/demo/ReplenishmentProbeController.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/BatchPick.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/repository/OrderRepository.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/repository/StockReservationRepository.java
  - order-promising/src/main/java/com/flowzati/archone/common/outbox/OutboxAggregateTypes.java
  - order-promising/src/main/resources/application.properties
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolController.java
  - docs/execution-roadmap.md
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/KafkaIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/IntegrationEventHandler.java
  - frontend/README.md
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/LineSnapshot.java
tests:
  - order-promising/src/test/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolControllerTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/outbox/DomainEventTranslatorTest.java
  - frontend/src/components/AppHeader.test.tsx
  - order-promising/src/sit/java/com/flowzati/archone/stock/infrastructure/repository/StockPoolPersistenceIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/infrastructure/mapper/StockPoolMapperTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/model/StockPoolTest.java
  - frontend/src/components/StockPanel.test.tsx
  - order-promising/src/test/java/com/flowzati/archone/stock/application/usecase/ReplenishmentUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/usecase/AllocateOrderUsecaseTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationFifoGuaranteeScopeIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationWorkflowEndToEndIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/service/AllocationSelectorTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/time/BusinessCalendarTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/usecase/ReleaseReservationUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/model/StockFixtures.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/model/StockReservationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/infrastructure/mapper/StockReservationMapperTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/event/EventSeparationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationHotSkuConcurrencyIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/application/usecase/InboundCommandTransactionIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/common/outbox/OutboxCdcIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/demo/ReplenishmentProbeEndToEndIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/ordering/infrastructure/repository/OrderPersistenceIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationKafkaIntegrationEventConsumerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationConcurrencyEndToEndIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/bootstrap/DevSeedDataIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/service/AllocationServiceTest.java
  - order-promising/src/test/java/com/flowzati/archone/demo/DemoConfigControllerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationFifoReplenishmentBatchIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/infrastructure/repository/StockReservationPersistenceIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/demo/ReplenishmentProbeControllerTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/domain/model/OrderTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/coordinator/OrderAllocationCoordinatorTest.java
  - order-promising/src/sit/java/com/flowzati/archone/common/outbox/OutboxAggregateQueryIntegrationTest.java
-->

---
### Requirement: Debezium derives the Kafka message key from partition_key

The Debezium Outbox Event Router SHALL be configured with
`table.field.event.key = partition_key`, replacing its default of `aggregateid`.
The published Kafka record key SHALL equal the row's `partition_key` value and
SHALL NOT be derived from `aggregateid`. Topic selection SHALL continue to come
from `route.by.field = route`.

Both connector configurations — the one exercised by the outbox CDC integration
test and the one registered by the `e2e/perf` setup script — SHALL declare
identical Event Router settings, so that the behavior verified in tests is the
behavior exercised under load.

#### Scenario: Record key follows partition_key when it differs from aggregate identity

- **GIVEN** an outbox row whose `aggregateid` and `partition_key` hold different values
- **WHEN** Debezium publishes that row through the Outbox Event Router
- **THEN** the resulting Kafka record key equals `partition_key`, and the record
  is published to the topic named by `route`

##### Example: an ordering event under the stock strategy

- **GIVEN** an outbox row with `aggregateid = 7f1c…` (an orderId),
  `partition_key = <owner>/<node>`, `route = ordering.order-events`
- **WHEN** Debezium publishes it
- **THEN** the Kafka record key is `<owner>/<node>` and the topic is
  `ordering.order-events`

The example SHALL use a key the system can actually produce. Debezium copies the column
through without interpreting it, so any value would demonstrate the mechanism equally
well — which is exactly why an unproducible one is harmful rather than harmless: it
teaches a format that no row will ever hold.


<!-- @trace
source: complete-stock-strategy-rename
updated: 2026-07-30
code:
  - docs/stock-reservation-design.md
  - frontend/vite.config.ts
  - frontend/src/api/catalog.ts
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/BackorderCreatedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/Order.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/repository/StockPoolRepository.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/jpa/JpaStockReservationRepository.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/repository/OrderRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecase.java
  - order-promising/src/main/resources/db/migration/V2__create_ordering_tables.sql
  - docs/dom-promising-scope.md
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/model/StockPool.java
  - order-promising/src/main/resources/db/migration/V3__create_stock_pools.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/OrderPlacedIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/entity/StockReservationEntity.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/StockReplenishedIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/translator/OrderingDomainEventTranslator.java
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/KafkaIntegrationEventDispatcher.java
  - frontend/src/pages/StockPage.tsx
  - e2e/perf/README.md
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/mapper/StockReservationMapper.java
  - e2e/perf/k6/hot-sku-burst.js
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/OrderAllocation.java
  - e2e/perf/kafka-connect/register-outbox-connector.sh
  - frontend/src/components/StockPanel.module.css
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/BackorderWakeRequestedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/OrderLine.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/event/BackorderWakeContinuationRequired.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/model/ReservationStatus.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/coordinator/OrderAllocationCoordinator.java
  - e2e/perf/docker-compose.yml
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/OrderAllocatedIntegrationEvent.java
  - e2e/perf/k6/results/hot-sku-burst-20260730T164656.json
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/model/StockReservation.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationOutcome.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/StockReservationRepositoryImpl.java
  - order-promising/src/main/resources/application-dev.properties
  - order-promising/src/main/resources/db/migration/V4__create_stock_reservations.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/application/command/WakeBackordersCommand.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/OrderCancelledIntegrationEventHandler.java
  - order-promising/src/main/resources/db/migration/V2__create_stock_pools.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/translator/AllocationDomainEventTranslator.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/OrderPlacedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/ReplenishmentUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolResponse.java
  - order-promising/src/main/java/com/flowzati/archone/common/configuration/CommonConfiguration.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/OrderCancelledIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/event/OrderAllocationCompleted.java
  - frontend/src/api/types.ts
  - frontend/src/components/AppHeader.tsx
  - order-promising/src/main/java/com/flowzati/archone/common/time/BusinessCalendar.java
  - e2e/perf/k6/results/hot-sku-burst-20260730T164603.json
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/entity/StockPoolEntity.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/mapper/StockPoolMapper.java
  - frontend/src/components/StockPanel.tsx
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationRequest.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/StockReplenishedIntegrationEvent.java
  - frontend/src/api/client.ts
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationResult.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/OrderCancelled.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/command/ReplenishStockCommand.java
  - e2e/perf/run.sh
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/jpa/JpaStockRepository.java
  - order-promising/src/main/java/com/flowzati/archone/bootstrap/DevSeedDataInitializer.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/repository/jpa/JpaOrderRepository.java
  - order-promising/src/main/java/com/flowzati/archone/common/outbox/StockContentionKey.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/ReleaseReservationUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/GetStockPoolUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/BackorderWakeRequestedIntegrationEventHandler.java
  - order-promising/src/main/resources/db/migration/V3__create_ordering_tables.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/StockPoolRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationService.java
  - docs/system-layer-map.md
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/AllocateOrderUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/demo/ReplenishmentProbeController.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/BatchPick.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/repository/OrderRepository.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/repository/StockReservationRepository.java
  - order-promising/src/main/java/com/flowzati/archone/common/outbox/OutboxAggregateTypes.java
  - order-promising/src/main/resources/application.properties
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolController.java
  - docs/execution-roadmap.md
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/KafkaIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/IntegrationEventHandler.java
  - frontend/README.md
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/LineSnapshot.java
tests:
  - order-promising/src/test/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolControllerTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/outbox/DomainEventTranslatorTest.java
  - frontend/src/components/AppHeader.test.tsx
  - order-promising/src/sit/java/com/flowzati/archone/stock/infrastructure/repository/StockPoolPersistenceIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/infrastructure/mapper/StockPoolMapperTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/model/StockPoolTest.java
  - frontend/src/components/StockPanel.test.tsx
  - order-promising/src/test/java/com/flowzati/archone/stock/application/usecase/ReplenishmentUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/usecase/AllocateOrderUsecaseTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationFifoGuaranteeScopeIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationWorkflowEndToEndIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/service/AllocationSelectorTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/time/BusinessCalendarTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/usecase/ReleaseReservationUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/model/StockFixtures.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/model/StockReservationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/infrastructure/mapper/StockReservationMapperTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/event/EventSeparationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationHotSkuConcurrencyIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/application/usecase/InboundCommandTransactionIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/common/outbox/OutboxCdcIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/demo/ReplenishmentProbeEndToEndIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/ordering/infrastructure/repository/OrderPersistenceIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationKafkaIntegrationEventConsumerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationConcurrencyEndToEndIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/bootstrap/DevSeedDataIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/service/AllocationServiceTest.java
  - order-promising/src/test/java/com/flowzati/archone/demo/DemoConfigControllerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationFifoReplenishmentBatchIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/infrastructure/repository/StockReservationPersistenceIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/demo/ReplenishmentProbeControllerTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/domain/model/OrderTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/coordinator/OrderAllocationCoordinatorTest.java
  - order-promising/src/sit/java/com/flowzati/archone/common/outbox/OutboxAggregateQueryIntegrationTest.java
-->

---
### Requirement: Partition key strategy selects only the delivery key

The `archone.allocation.partition-key-strategy` setting SHALL determine the
`partition_key` written for `ordering.order-events` Integration Events: value `stock`
SHALL write **the order's owner and warehouse joined together**, and any other value
SHALL write the `orderId`. The setting SHALL NOT affect `aggregatetype`, `aggregateid`,
`route`, payload content, or the Integration Event contract.

The setting's value SHALL be named after **what it serialises**, not after which columns
compose the key. Naming it `sku` tied the contract to one particular composition, so
refining the composition would have made the name a lie.

The key SHALL NOT contain the SKU code. Stock is identified by five dimensions, so a key
naming the SKU is a closer fit for "which rows will be touched" — but it cannot survive
an order that spans several SKUs, because ship-complete requires every line's
available-to-promise to be judged in one transaction, and a per-SKU key necessarily
spreads that transaction across several writers. Dropping the SKU removes the problem
rather than deferring it: an order belongs to exactly one owner and warehouse however
many SKUs it spans, so one writer always sees the whole order.

Owner and warehouse SHALL both be in the key. Stock separated by either is not
contended, so a key omitting them would serialise work that can never conflict.

**Choosing too coarse a key is safer than too fine.** Too coarse only over-serialises
work that could have run in parallel; too fine breaks single-writer and produces the
optimistic-lock conflicts the strategy exists to prevent. The cost of this particular
coarsening is that one owner's orders in one warehouse queue behind one another even
when they name different SKUs.

#### Scenario: One owner's stock in one warehouse converges onto one partition

- **GIVEN** `archone.allocation.partition-key-strategy` is `stock`
- **WHEN** multiple orders are placed for the same owner and warehouse
- **THEN** every resulting `ordering.order-events` record carries the same key and
  therefore lands on the same partition, while each row still records its own order's
  `orderId` as `aggregateid`

#### Scenario: Two owners sharing a SKU code do not share a partition

- **GIVEN** two owners each place an order for the same SKU code in the same warehouse
- **WHEN** the resulting records are inspected
- **THEN** their keys differ, because stock separated by owner is not contended and
  serialising them would be pointless

#### Scenario: An order spanning several SKUs still yields one key

- **GIVEN** `archone.allocation.partition-key-strategy` is `stock`
- **WHEN** an order naming two different SKU codes is translated
- **THEN** it yields a single partition key containing neither SKU code, and the
  translation does not fail


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
### Requirement: Allocation outcome events key by order identity

Integration Events published to `promising.allocation-events` SHALL use the
`orderId` as `partition_key` regardless of the configured partition-key strategy.
The rationale SHALL be recorded at the point of decision in the code: this
repository contains no consumer of that topic, so the single-writer property the
`stock` strategy exists to provide is not required there, and applying it would be
a speculative extension.

**This exemption is scoped by topic, not by publisher.** The same translator also emits
a backorder-wake continuation event onto the inventory stock-events topic, and that one
SHALL always be keyed by the stock contention group — see the requirement covering the
wake bound. Keying it by `orderId` would put it on a different partition from the round
it exists to continue, and the two would then run in parallel; the single-writer property
is obtained precisely by sharing a key.

The distinction is which rows the consumer will write. An outcome event's consumer would
update that one order's row, so the order identity is the contention group. A continuation
event's consumer draws on a group of stock rows, so the stock contention group is.

#### Scenario: Allocation outcomes keep orderId as key under the stock strategy

- **GIVEN** `archone.allocation.partition-key-strategy` is `stock`
- **WHEN** an order is allocated or backordered and the outcome event is appended
- **THEN** the row's `route` is `promising.allocation-events` and its
  `partition_key` is the `orderId`, not the stock contention group

#### Scenario: A wake continuation is keyed by contention group under every configuration

- **WHEN** a backorder-wake continuation event is appended
- **THEN** its `partition_key` is the stock contention group rather than any order
  identity, whatever the strategy setting holds

The component that emits it SHALL NOT receive the strategy setting at all. Being unable
to read a setting is a stronger guarantee than reading it and choosing to ignore it: the
first cannot be undone by a later edit that "makes it consistent" with the outcome events
next to it.


<!-- @trace
source: complete-stock-strategy-rename
updated: 2026-07-30
code:
  - docs/stock-reservation-design.md
  - frontend/vite.config.ts
  - frontend/src/api/catalog.ts
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/BackorderCreatedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/Order.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/repository/StockPoolRepository.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/jpa/JpaStockReservationRepository.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/repository/OrderRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecase.java
  - order-promising/src/main/resources/db/migration/V2__create_ordering_tables.sql
  - docs/dom-promising-scope.md
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/model/StockPool.java
  - order-promising/src/main/resources/db/migration/V3__create_stock_pools.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/OrderPlacedIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/entity/StockReservationEntity.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/StockReplenishedIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/translator/OrderingDomainEventTranslator.java
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/KafkaIntegrationEventDispatcher.java
  - frontend/src/pages/StockPage.tsx
  - e2e/perf/README.md
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/mapper/StockReservationMapper.java
  - e2e/perf/k6/hot-sku-burst.js
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/OrderAllocation.java
  - e2e/perf/kafka-connect/register-outbox-connector.sh
  - frontend/src/components/StockPanel.module.css
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/BackorderWakeRequestedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/OrderLine.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/event/BackorderWakeContinuationRequired.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/model/ReservationStatus.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/coordinator/OrderAllocationCoordinator.java
  - e2e/perf/docker-compose.yml
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/OrderAllocatedIntegrationEvent.java
  - e2e/perf/k6/results/hot-sku-burst-20260730T164656.json
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/model/StockReservation.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationOutcome.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/StockReservationRepositoryImpl.java
  - order-promising/src/main/resources/application-dev.properties
  - order-promising/src/main/resources/db/migration/V4__create_stock_reservations.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/application/command/WakeBackordersCommand.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/OrderCancelledIntegrationEventHandler.java
  - order-promising/src/main/resources/db/migration/V2__create_stock_pools.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/translator/AllocationDomainEventTranslator.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/OrderPlacedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/ReplenishmentUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolResponse.java
  - order-promising/src/main/java/com/flowzati/archone/common/configuration/CommonConfiguration.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/OrderCancelledIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/event/OrderAllocationCompleted.java
  - frontend/src/api/types.ts
  - frontend/src/components/AppHeader.tsx
  - order-promising/src/main/java/com/flowzati/archone/common/time/BusinessCalendar.java
  - e2e/perf/k6/results/hot-sku-burst-20260730T164603.json
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/entity/StockPoolEntity.java
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/mapper/StockPoolMapper.java
  - frontend/src/components/StockPanel.tsx
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationRequest.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/event/StockReplenishedIntegrationEvent.java
  - frontend/src/api/client.ts
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationResult.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/OrderCancelled.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/command/ReplenishStockCommand.java
  - e2e/perf/run.sh
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/jpa/JpaStockRepository.java
  - order-promising/src/main/java/com/flowzati/archone/bootstrap/DevSeedDataInitializer.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/repository/jpa/JpaOrderRepository.java
  - order-promising/src/main/java/com/flowzati/archone/common/outbox/StockContentionKey.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/ReleaseReservationUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/GetStockPoolUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/kafka/BackorderWakeRequestedIntegrationEventHandler.java
  - order-promising/src/main/resources/db/migration/V3__create_ordering_tables.sql
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/StockPoolRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/AllocationService.java
  - docs/system-layer-map.md
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/AllocateOrderUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/demo/ReplenishmentProbeController.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/service/BatchPick.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/repository/OrderRepository.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/repository/StockReservationRepository.java
  - order-promising/src/main/java/com/flowzati/archone/common/outbox/OutboxAggregateTypes.java
  - order-promising/src/main/resources/application.properties
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolController.java
  - docs/execution-roadmap.md
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/KafkaIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/IntegrationEventHandler.java
  - frontend/README.md
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/LineSnapshot.java
tests:
  - order-promising/src/test/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolControllerTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/outbox/DomainEventTranslatorTest.java
  - frontend/src/components/AppHeader.test.tsx
  - order-promising/src/sit/java/com/flowzati/archone/stock/infrastructure/repository/StockPoolPersistenceIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/infrastructure/mapper/StockPoolMapperTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/model/StockPoolTest.java
  - frontend/src/components/StockPanel.test.tsx
  - order-promising/src/test/java/com/flowzati/archone/stock/application/usecase/ReplenishmentUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/usecase/AllocateOrderUsecaseTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationFifoGuaranteeScopeIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationWorkflowEndToEndIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/service/AllocationSelectorTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/time/BusinessCalendarTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/usecase/ReleaseReservationUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/model/StockFixtures.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/model/StockReservationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/infrastructure/mapper/StockReservationMapperTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/event/EventSeparationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationHotSkuConcurrencyIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/application/usecase/InboundCommandTransactionIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/common/outbox/OutboxCdcIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/demo/ReplenishmentProbeEndToEndIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/ordering/infrastructure/repository/OrderPersistenceIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationKafkaIntegrationEventConsumerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationConcurrencyEndToEndIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/bootstrap/DevSeedDataIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/domain/service/AllocationServiceTest.java
  - order-promising/src/test/java/com/flowzati/archone/demo/DemoConfigControllerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/entrypoint/kafka/AllocationFifoReplenishmentBatchIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/stock/infrastructure/repository/StockReservationPersistenceIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/demo/ReplenishmentProbeControllerTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/domain/model/OrderTest.java
  - order-promising/src/test/java/com/flowzati/archone/stock/application/coordinator/OrderAllocationCoordinatorTest.java
  - order-promising/src/sit/java/com/flowzati/archone/common/outbox/OutboxAggregateQueryIntegrationTest.java
-->

---
### Requirement: Outbox rows are queryable by aggregate identity

Because delivery decisions no longer occupy `aggregateid`, querying outbox rows by
`aggregatetype` and `aggregateid` SHALL return every Integration Event emitted for
that aggregate, under any partition-key strategy. A query for one order SHALL
return that order's lifecycle events in `timestamp` order, and SHALL NOT depend on
payload field names or on the active partition-key strategy.

That last clause is now stronger than it was: the order lifecycle events carry **nothing
but the order identity and a timestamp** — no SKU, no quantity, no owner, no warehouse.
A consumer holds the order identity and reads the order back, because it must read the
order anyway for the ship-to details and promised date that were never in the event.
Duplicating any of that into the payload would add a second source that can disagree
with the first.

Fields whose only justification is a consumer that does not exist SHALL NOT be carried.
Adding a field is non-breaking for consumers, which already ignore fields they do not
know; removing one is breaking. The minimum is therefore the correct starting point, and
a filtering dimension SHALL be added when a consumer that filters on it exists.

The exception is events that originate outside this system, which carry the fact itself
because there is no local aggregate to read.

#### Scenario: One order's events are retrievable by orderId under the stock strategy

- **GIVEN** `archone.allocation.partition-key-strategy` is `stock`
- **AND** an order has been placed and subsequently allocated
- **WHEN** outbox rows are queried by `aggregatetype = 'Order'` and that order's
  `aggregateid`
- **THEN** both the `OrderPlacedIntegrationEvent` row and the
  `OrderAllocatedIntegrationEvent` row are returned, ordered by `timestamp`

##### Example: rows returned for one backordered-then-allocated order

| `type` | `route` | `partition_key` | Returned by orderId query |
| --- | --- | --- | --- |
| `OrderPlacedIntegrationEvent` | `ordering.order-events` | `<owner>/<node>` | yes |
| `BackorderCreatedIntegrationEvent` | `promising.allocation-events` | `orderId` | yes |
| `OrderAllocatedIntegrationEvent` | `promising.allocation-events` | `orderId` | yes |

#### Scenario: A lifecycle event carries nothing but identity and time

- **WHEN** an order-placed or order-allocated outbox row's payload is inspected
- **THEN** it carries the event and order identities and a timestamp, and carries no
  SKU, quantity, owner or warehouse

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