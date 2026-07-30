# demo-console-frontend Specification

## Purpose

TBD - created by archiving change 'add-demo-console-frontend'. Update Purpose after archive.

## Requirements

### Requirement: The console presents orders and stock as two navigable pages

The console SHALL expose exactly two routed pages: an orders page and a stock
page. The orders page SHALL be the default route. A shared header SHALL be
present on both pages and SHALL display the partition-key strategy currently in
effect, so a viewer can tell whether the running system uses the order-identifier
strategy or the stock strategy without leaving the console.

**The header SHALL show the backend's value verbatim, and SHALL derive its explanatory
label by comparing against that value's exact literal.** The comparison SHALL be written
against a named constant matching the backend's own, not an inline string repeated at the
point of use.

**An unrecognised value SHALL be reported as the non-single-writer behaviour.** The header
states which guarantee is in force, and the two directions of being wrong are not
symmetric: reporting single-writer when it is not active claims a guarantee the system
does not have, while reporting its absence merely understates a capability. A value the
console does not recognise is, by definition, one whose guarantees it cannot vouch for.

This is not hypothetical. The setting's value was renamed from `sku` to `stock` while the
console kept comparing against `sku`; the comparison then failed for every input, and the
header labelled the single-writer strategy as the optimistic-lock one. A failed string
comparison raises nothing and logs nothing — which is why both branches of the comparison
SHALL be covered by tests rather than left to inspection.

The orders page's list SHALL show every field of the order representation, so that
inspecting one order requires no further navigation, modal, or detail view. This
includes the owner: each row SHALL name the owner the order belongs to, taken from the
order response rather than resolved separately.

Because intake accepts exactly one line per order, each row SHALL correspond to one
order and SHALL render that order's single line inline. The row SHALL identify the
line's goods as the product name together with the specification name rather than as a
bare SKU code, since a SKU code alone is meaningless without its owner.

#### Scenario: Opening the console lands on the orders page with the strategy visible

- **WHEN** the console is opened at its root path
- **THEN** the orders page is shown, and the header reports the effective
  partition-key strategy retrieved from the backend

##### Example: header text for each backend configuration

| Backend `archone.allocation.partition-key-strategy` | Header reports |
| --- | --- |
| `order-id` (default) | the order-identifier strategy |
| `stock` | the stock strategy, labelled as single-writer |
| anything else | the order-identifier behaviour — never single-writer |

#### Scenario: An unrecognised strategy value is not reported as single-writer

- **GIVEN** the backend reports a strategy value the console does not know
- **WHEN** the header renders
- **THEN** it does not claim single-writer, and it still shows the value it was given

#### Scenario: A failed strategy request yields no strategy conclusion

- **GIVEN** the configuration request failed
- **WHEN** the header renders
- **THEN** it reports the failure and states neither strategy, because naming one would
  be indistinguishable from having read it from the backend

#### Scenario: Navigating between the two pages preserves the header

- **WHEN** the viewer navigates from the orders page to the stock page
- **THEN** the stock page is shown with the same header, and the strategy is not
  re-requested on every navigation

##### Example: configuration requests across a navigation sequence

- **GIVEN** the console has finished its initial load
- **WHEN** the viewer navigates orders → stock → orders
- **THEN** the configuration request count is unchanged from what the initial
  load produced — navigating adds none

#### Scenario: A listed order names its owner and its goods in readable form

- **GIVEN** two owners each have an order for a SKU code they both define
- **WHEN** the orders page lists them
- **THEN** each row names its own owner, and each row describes its goods as the
  product name with the specification name, so the two rows are distinguishable


<!-- @trace
source: complete-stock-strategy-rename
updated: 2026-07-30
code:
  - docs/stock-reservation-design.md
  - frontend/vite.config.ts
  - frontend/src/api/catalog.ts
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/event/BackorderCreatedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/Order.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/repository/StockPoolRepository.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/repository/jpa/JpaStockReservationRepository.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/repository/OrderRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecase.java
  - order-promising/src/main/resources/db/migration/V2__create_ordering_tables.sql
  - docs/dom-promising-scope.md
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/model/StockPool.java
  - order-promising/src/main/resources/db/migration/V3__create_stock_pools.sql
  - order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/kafka/OrderPlacedIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/entity/StockReservationEntity.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/kafka/StockReplenishedIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/translator/OrderingDomainEventTranslator.java
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/KafkaIntegrationEventDispatcher.java
  - frontend/src/pages/StockPage.tsx
  - e2e/perf/README.md
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/mapper/StockReservationMapper.java
  - e2e/perf/k6/hot-sku-burst.js
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/OrderAllocation.java
  - e2e/perf/kafka-connect/register-outbox-connector.sh
  - frontend/src/components/StockPanel.module.css
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/event/BackorderWakeRequestedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/OrderLine.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/event/BackorderWakeContinuationRequired.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/model/ReservationStatus.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/coordinator/OrderAllocationCoordinator.java
  - e2e/perf/docker-compose.yml
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/event/OrderAllocatedIntegrationEvent.java
  - e2e/perf/k6/results/hot-sku-burst-20260730T164656.json
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/model/StockReservation.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocationOutcome.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/repository/StockReservationRepositoryImpl.java
  - order-promising/src/main/resources/application-dev.properties
  - order-promising/src/main/resources/db/migration/V4__create_stock_reservations.sql
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/command/WakeBackordersCommand.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/kafka/OrderCancelledIntegrationEventHandler.java
  - order-promising/src/main/resources/db/migration/V2__create_stock_pools.sql
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/event/translator/AllocationDomainEventTranslator.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/OrderPlacedIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/usecase/ReplenishmentUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/rest/StockPoolResponse.java
  - order-promising/src/main/java/com/flowzati/archone/common/configuration/CommonConfiguration.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/application/event/OrderCancelledIntegrationEvent.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/event/OrderAllocationCompleted.java
  - frontend/src/api/types.ts
  - frontend/src/components/AppHeader.tsx
  - order-promising/src/main/java/com/flowzati/archone/common/time/BusinessCalendar.java
  - e2e/perf/k6/results/hot-sku-burst-20260730T164603.json
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/entity/StockPoolEntity.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/mapper/StockPoolMapper.java
  - frontend/src/components/StockPanel.tsx
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocationRequest.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/event/StockReplenishedIntegrationEvent.java
  - frontend/src/api/client.ts
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocationResult.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/OrderCancelled.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/command/ReplenishStockCommand.java
  - e2e/perf/run.sh
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/repository/jpa/JpaStockRepository.java
  - order-promising/src/main/java/com/flowzati/archone/bootstrap/DevSeedDataInitializer.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/repository/jpa/JpaOrderRepository.java
  - order-promising/src/main/java/com/flowzati/archone/common/outbox/StockContentionKey.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/usecase/ReleaseReservationUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/usecase/GetStockPoolUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/kafka/BackorderWakeRequestedIntegrationEventHandler.java
  - order-promising/src/main/resources/db/migration/V3__create_ordering_tables.sql
  - order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/repository/StockPoolRepositoryImpl.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocationService.java
  - docs/system-layer-map.md
  - order-promising/src/main/java/com/flowzati/archone/allocation/application/usecase/AllocateOrderUsecase.java
  - order-promising/src/main/java/com/flowzati/archone/demo/ReplenishmentProbeController.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/BatchPick.java
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/repository/OrderRepository.java
  - order-promising/src/main/java/com/flowzati/archone/allocation/domain/repository/StockReservationRepository.java
  - order-promising/src/main/java/com/flowzati/archone/common/outbox/OutboxAggregateTypes.java
  - order-promising/src/main/resources/application.properties
  - order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/rest/StockPoolController.java
  - docs/execution-roadmap.md
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/KafkaIntegrationEventHandler.java
  - order-promising/src/main/java/com/flowzati/archone/common/messaging/kafka/IntegrationEventHandler.java
  - frontend/README.md
  - order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/LineSnapshot.java
tests:
  - order-promising/src/test/java/com/flowzati/archone/allocation/entrypoint/rest/StockPoolControllerTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/outbox/DomainEventTranslatorTest.java
  - frontend/src/components/AppHeader.test.tsx
  - order-promising/src/sit/java/com/flowzati/archone/allocation/infrastructure/repository/StockPoolPersistenceIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/infrastructure/mapper/StockPoolMapperTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/model/StockPoolTest.java
  - frontend/src/components/StockPanel.test.tsx
  - order-promising/src/test/java/com/flowzati/archone/allocation/application/usecase/ReplenishmentUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/application/usecase/AllocateOrderUsecaseTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationFifoGuaranteeScopeIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationWorkflowEndToEndIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/service/AllocationSelectorTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/time/BusinessCalendarTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/application/usecase/ReleaseReservationUsecaseTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/model/StockFixtures.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/model/StockReservationTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/infrastructure/mapper/StockReservationMapperTest.java
  - order-promising/src/test/java/com/flowzati/archone/common/event/EventSeparationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationHotSkuConcurrencyIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/application/usecase/InboundCommandTransactionIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/common/outbox/OutboxCdcIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/demo/ReplenishmentProbeEndToEndIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/ordering/infrastructure/repository/OrderPersistenceIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationKafkaIntegrationEventConsumerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationConcurrencyEndToEndIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/bootstrap/DevSeedDataIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/domain/service/AllocationServiceTest.java
  - order-promising/src/test/java/com/flowzati/archone/demo/DemoConfigControllerTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationFifoReplenishmentBatchIntegrationTest.java
  - order-promising/src/sit/java/com/flowzati/archone/allocation/infrastructure/repository/StockReservationPersistenceIntegrationTest.java
  - order-promising/src/test/java/com/flowzati/archone/demo/ReplenishmentProbeControllerTest.java
  - order-promising/src/test/java/com/flowzati/archone/ordering/domain/model/OrderTest.java
  - order-promising/src/test/java/com/flowzati/archone/allocation/application/coordinator/OrderAllocationCoordinatorTest.java
  - order-promising/src/sit/java/com/flowzati/archone/common/outbox/OutboxAggregateQueryIntegrationTest.java
-->

---
### Requirement: Placing an order shows the result in the list on the same page

The orders page SHALL provide a form taking an owner, an upstream order number, **a
warehouse**, a destination zone, a destination address, a promised delivery date, and
the goods ordered. The goods SHALL be chosen in two steps — a product, then one of that
product's specifications — rather than typed as a SKU code, so that an unknown or
cross-owner SKU code cannot be submitted at all.

The selectable warehouses SHALL be those the selected owner is assigned to, and the
selectable products SHALL be those of the selected owner, and the selectable
specifications SHALL be those of the selected product. Changing the owner SHALL discard
a warehouse, a product, and a specification chosen under the previous owner, since none
of them is valid under a different owner.

The warehouse SHALL be selected rather than typed, for the same reason the SKU code is:
a typed identifier can name a warehouse the owner is not assigned to, which the backend
would reject after a pointless round trip.

On success the newly created order SHALL become visible in the recent-orders list on
that same page without navigation. The submitted quantity SHALL be sent as a number,
and the form SHALL prevent submission of a non-positive quantity or of an incomplete
selection rather than relying on the backend to reject it.

#### Scenario: A submitted order appears in the list as PENDING

- **WHEN** the viewer submits the form with an owner, an upstream order number, a
  warehouse, a destination, a promised delivery date, a selected specification, and a
  valid quantity
- **THEN** the recent-orders list on the same page shows that new order with
  status `PENDING`, naming that owner

#### Scenario: Changing the owner discards a selection made under the previous owner

- **GIVEN** the viewer has selected an owner, a warehouse, a product, and a
  specification
- **WHEN** the viewer selects a different owner
- **THEN** the warehouse, product, and specification selections are cleared, so the form
  can never submit a warehouse or goods belonging to a different owner

#### Scenario: Only the selected owner's warehouses are offered

- **GIVEN** two owners are assigned to different sets of warehouses
- **WHEN** the viewer selects one of them
- **THEN** the warehouse choices are exactly that owner's assigned warehouses

##### Example: form validation before submission

| Owner | Warehouse | Product | Specification | Quantity | Result |
| --- | --- | --- | --- | --- | --- |
| selected | selected | selected | selected | 1 | submitted |
| selected | selected | selected | selected | 0 | blocked in the form, no request sent |
| selected | selected | selected | selected | -3 | blocked in the form, no request sent |
| selected | selected | selected | not selected | 1 | blocked in the form, no request sent |
| selected | not selected | selected | selected | 1 | blocked in the form, no request sent |
| not selected | — | — | — | 1 | blocked in the form, no request sent |

---
### Requirement: Stock state and replenishment share one page keyed by SKU

The stock page SHALL query and replenish through one SKU field, because they are one
continuous action: see that nothing can be promised, add stock, look again.

**The query result SHALL be a list of stock rows, not a single set of numbers.** Each
row SHALL show its warehouse, arrival date, expiry date, on-hand, reserved,
available-to-promise, and whether it has expired. Expired rows SHALL be
shown with the reason rather than hidden — hiding them makes "we hold stock we cannot
ship" look identical to "we hold nothing", and those call for different actions.

**The rows SHALL be listed in the order the backend returned them, and the page SHALL
NOT re-sort them.** That order groups the rows by warehouse and runs earliest-expiry-first
within each, which is the order allocation would draw on them — so the page answers
"which of these goes first" without the viewer reconstructing the rule.

Re-sorting client-side would make the page state a consumption order of its own, which
could disagree with the one allocation actually follows while looking equally
authoritative. The tie-breaks reach down to row identity, so the page could not reproduce
the real order even if it tried.

Both query and replenishment SHALL require an owner. Stock is held per owner; a SKU
code alone names two owners' goods at once.

Replenishment SHALL additionally require a warehouse, an arrival date and an expiry
date, because those complete the identity of the row being added to. The form SHALL
prevent submission when any of them is missing rather than relying on the backend to
reject it.

#### Scenario: A SKU held in three rows shows three rows in consumption order

- **WHEN** the viewer queries a SKU their selected owner holds in three rows
- **THEN** three rows appear, ordered as allocation would consume them, each showing
  its warehouse, dates and quantities

#### Scenario: An expired row is shown with its reason

- **GIVEN** one of the rows has passed its expiry date
- **WHEN** the viewer queries that SKU
- **THEN** that row is present and marked as expired

##### Example: replenishment fields required before submission

| Owner | Warehouse | Arrival date | Expiry date | Quantity | Result |
| --- | --- | --- | --- | --- | --- |
| selected | selected | set | set | 1 | submitted |
| selected | not selected | set | set | 1 | blocked in the form, no request sent |
| selected | selected | missing | set | 1 | blocked in the form, no request sent |
| selected | selected | set | missing | 1 | blocked in the form, no request sent |
| not selected | — | — | — | 1 | blocked in the form, no request sent |


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
### Requirement: Data is fetched only in response to a user action

Every backend request SHALL be traceable to an explicit user action: opening a
page, submitting a form, pressing a query control, or pressing a refresh control.
The console SHALL NOT poll, SHALL NOT install timers that refetch, and SHALL NOT
refresh in the background.

The orders page SHALL provide a refresh control, since the list becomes stale
whenever the backend completes an asynchronous allocation.

#### Scenario: An idle console issues no requests

- **GIVEN** the orders page has finished loading
- **WHEN** the viewer takes no action for an extended period
- **THEN** no further backend requests are issued

#### Scenario: Refreshing after an asynchronous allocation shows the new statuses

- **GIVEN** a replenishment has been triggered and the backend has since
  allocated the queued orders
- **WHEN** the viewer presses the refresh control on the orders page
- **THEN** the list reflects the updated statuses

---
### Requirement: Backend failures are surfaced rather than swallowed

Every action SHALL surface its own failure to the viewer, identifying which action
failed. A failed request SHALL NOT leave previously loaded data displayed as
though it were the result of the failed action.

#### Scenario: A failed replenishment is reported and does not imply success

- **GIVEN** the backend cannot publish the replenishment event
- **WHEN** the viewer triggers a replenishment
- **THEN** the page reports that the replenishment failed, and does not display
  an event identifier or any indication of acceptance

---
### Requirement: The console reaches the backend through a development proxy

The console SHALL call the backend through a same-origin path that the dev server
proxies to the backend, so that the browser makes no cross-origin request and the
backend requires no CORS configuration. The backend origin SHALL be configured in
the dev server only, and SHALL NOT be hardcoded in application source.

#### Scenario: Requests are same-origin from the browser's perspective

- **WHEN** the console issues any backend request
- **THEN** the request targets the dev server's own origin and is forwarded to
  the backend by the proxy, and no CORS preflight occurs

##### Example: one request seen from each side

- **GIVEN** the dev server serves the console and the backend runs separately
- **WHEN** the console requests the recent-orders list
- **THEN** the browser records a same-origin request to the dev server's own
  origin, the backend receives the corresponding request at its own origin, no
  `OPTIONS` preflight is recorded, and the backend origin appears only in dev
  server configuration — never in application source