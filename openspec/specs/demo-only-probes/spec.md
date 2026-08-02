# demo-only-probes Specification

## Purpose

TBD - created by archiving change 'add-demo-console-api'. Update Purpose after archive.

## Requirements

### Requirement: The replenishment probe publishes a real upstream stock event

The replenishment probe SHALL publish a genuine `StockReplenishedIntegrationEvent`
to the inventory stock-events topic, taking the role of the external Inventory
bounded context that this repository consumes from but does not own. It SHALL NOT
invoke the replenishment use case directly, because doing so would fabricate the
message metadata that the inbox uses for idempotency and would bypass the retry,
backoff, and dead-letter handling that the Kafka entrypoint provides.

The probe SHALL take an owner, **a warehouse, an arrival date and an expiry date**
alongside the SKU and the quantity, and the published event SHALL carry all of them.
Stock is identified by those five together; a replenishment naming fewer cannot say
which row it adds to, and the consumer would have to invent the missing values.

The record key SHALL be the owner and warehouse joined together — **byte-for-byte the
same key the ordering events carry under the `stock` strategy**, produced by the same
shared rule rather than composed a second time here. Stock is held per owner and
warehouse, so those are the messages that contend for the same rows; the bare SKU would
serialise messages that no longer compete.

The key SHALL NOT contain the SKU, even though the probe knows it. A replenishment and
an order for the same owner and warehouse must land on the same partition for the
single-writer guarantee to hold over the rows they both touch, and the ordering side
cannot put the SKU in its key — see `outbox-event-delivery`. Two keys composed from
different dimensions would diverge silently: no error, just a lost guarantee.

The probe SHALL NOT write to the outbox and SHALL NOT modify any local state. The
outbox exists to make a local state change atomic with event publication; the
probe changes no local state, so there is no transaction to align with.

The response SHALL be `202` and SHALL carry the published event's identifier, so
the caller can correlate the asynchronous outcome. The response SHALL NOT include
a predicted count of orders the replenishment will wake, because that count is a
snapshot taken before publication and can disagree with the actual outcome.

#### Scenario: A replenishment probe wakes a queued backorder within the observable window

- **GIVEN** a SKU has orders queued in BACKORDERED status for one owner and warehouse
- **WHEN** the probe publishes a replenishment naming that owner, warehouse, SKU,
  arrival date and expiry date
- **THEN** the event reaches the consumer, stock is added to the row those five
  identify, and the queued orders are allocated from it

#### Scenario: A replenishment carrying no warehouse is rejected

- **WHEN** the probe is called without a warehouse
- **THEN** the response is `400`, no event is published and no stock changes

#### Scenario: A replenishment missing its quantity is a caller error, not a server error

- **WHEN** the probe is called with no quantity at all
- **THEN** the response is `400` rather than `500`

A missing field SHALL be reported as the caller's error. Every field of the request is
nullable on the wire, so an absent one must be rejected before it reaches a constructor
that would fail on it — otherwise the caller's omission is reported as a server fault
and the probe becomes useless for diagnosing exactly the mistakes it is there to surface.


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
### Requirement: The active partition key strategy is observable

A read-only endpoint SHALL report the partition-key strategy currently in effect,
so an operator can tell whether the running system is using the order-identifier
strategy or the stock strategy. The endpoint SHALL only report the value; it SHALL
NOT offer to change it, because the strategy is resolved at application startup.

The endpoint SHALL report the configured value verbatim rather than a label derived from
it. A derived label can disagree with the setting; the raw value cannot, and it is what
an operator would grep the configuration for.

#### Scenario: The configured strategy is reported

- **GIVEN** the application started with the stock partition-key strategy
- **WHEN** the configuration endpoint is queried
- **THEN** the response reports `stock` as the effective value


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
### Requirement: Probe endpoints exist only in the dev profile

Every probe endpoint SHALL be registered only when the dev profile is active,
matching how the existing development seed data is scoped. When the dev profile
is not active, requests to probe paths SHALL yield `404` — the endpoints SHALL be
absent rather than present-and-refusing, so that a non-dev deployment has no probe
surface at all.

#### Scenario: Probes are absent without the dev profile

- **GIVEN** the application is running without the dev profile
- **WHEN** a probe endpoint path is requested
- **THEN** the response is `404` and no probe handler is registered