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
  - order-promising/src/main/java/com/flowzati/archone/stock/application/command/AllocateWaitingDemandCommand.java
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

---
### Requirement: The order form composes a basket of several lines

The order form SHALL let the viewer add and remove lines, each naming a product, one of that
product's specifications, and a quantity. An order SHALL be submittable with one line or with
several.

The per-line rules SHALL be the ones already stated for a single line: goods chosen in two
steps rather than typed, selectable products restricted to the selected owner, quantity sent
as a number, and submission prevented for a non-positive quantity or an incomplete selection.
Changing the owner SHALL discard **every** line, since none of their goods is valid under a
different owner.

**The form SHALL show why a multi-line order was not allocated.** An order is allocated whole
or not at all, so an order can be backordered while one of its SKUs is plentiful — that is
counter-intuitive enough that seeing it is the point. The list SHALL therefore make each
line's goods and quantity visible on the order's row, as it already does for a single line.

Two lines naming the same specification SHALL be permitted. Intake accepts them and reads the
demand as their sum; forbidding it here would make the console reject orders the system
handles.

#### Scenario: An order with two different specifications is submitted and listed

- **WHEN** the viewer adds a second line naming a different specification and submits
- **THEN** the recent-orders list shows that order with both lines and status `PENDING`

#### Scenario: Removing a line leaves the rest intact

- **GIVEN** the form holds three lines
- **WHEN** the viewer removes the middle one
- **THEN** the remaining two keep their own selections and quantities

#### Scenario: The last line cannot be removed

- **GIVEN** the form holds one line
- **WHEN** the viewer attempts to remove it
- **THEN** the line remains, because an order without demand cannot be submitted

#### Scenario: Changing the owner clears every line

- **GIVEN** the form holds two lines with selections made under one owner
- **WHEN** the viewer selects a different owner
- **THEN** every line's product and specification selection is cleared

#### Scenario: An incomplete line blocks submission even when the others are complete

- **GIVEN** the form holds two lines, one complete and one without a specification
- **WHEN** the viewer submits
- **THEN** the form does not send the request

##### Example: what the basket makes visible

| Order | Stock | Outcome |
| --- | --- | --- |
| A×10 + B×5 | A: 100, B: 100 | allocated — both lines reserved |
| A×10 + B×5 | A: 100, B: 3 | **backordered — neither line reserved, though A is plentiful** |
| A×10 | A: 100 | allocated |

第二列是這個表格存在的理由：**有貨卻不配**，而那正是 ship-complete 的內容。

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
### Requirement: Stock state and replenishment share one page keyed to a warehouse

The stock page SHALL query by owner and warehouse, and SHALL replenish from the row of
the goods being replenished. Query and replenishment remain one continuous action — see
that nothing can be promised, add stock, look again — but the question the page answers
is now the one a warehouse asks: what is held here, and what is missing.

**The result SHALL be a line per specification, not per batch.** Each line SHALL show its
goods and four quantities: on-hand, reserved, available-to-promise, and expired.

**On-hand SHALL include expired batches and available-to-promise SHALL NOT.** On-hand is
a physical fact about the warehouse; available-to-promise is what allocation can actually
honour, and allocation never draws on an expired batch. The two therefore need not sum
with reserved, and the expired column SHALL account for the difference — that difference
is what separates "write it off" from "order more".

**Each line SHALL be expandable into its batches**, and the batches SHALL carry arrival
date, expiry date, on-hand, reserved, available-to-promise and whether they have expired.
Expired batches SHALL be shown with the reason rather than hidden — hiding them makes "we
hold stock we cannot ship" look identical to "we hold nothing", and those call for
different actions.

**The batches SHALL be listed in the order the backend returned them, and the page SHALL
NOT re-sort them.** That order runs earliest-expiry-first, which is the order allocation
would draw on them — so the page answers "which of these goes first" without the viewer
reconstructing the rule. Re-sorting client-side would state a consumption order of its
own, which could disagree with the one allocation follows while looking equally
authoritative; the tie-breaks reach down to batch identity, so the page could not
reproduce the real order even if it tried.

**Every specification the owner has SHALL be listed, including those the warehouse holds
none of**, showing zero. Those zeros are the answer to "what is this warehouse missing",
and they are what makes the replenish action reachable for goods that have never been
stocked here.

**Stock held under a SKU code the catalog does not know SHALL also be listed**, named by
its code alone. The catalog and the stock are separate records with no reference between
them, so a warehouse can hold goods the catalog has never heard of. Listing only what the
catalog knows would under-report what the warehouse physically holds — and the quantity
missing from the screen would be exactly the quantity nobody can account for.

**The lines SHALL be ordered by product then specification, and that order SHALL NOT
depend on the quantities.** Replenishment is observed by querying again, so a line that
moved because its numbers changed is a line whose change cannot be seen.

Replenishment SHALL open from a line, and SHALL show the owner, warehouse and goods as
fixed context that cannot be edited — they are already settled by the query and the line,
and letting them differ would mean writing to something other than what was clicked.
The arrival date, expiry date and quantity SHALL be the only inputs, and the two dates
SHALL be prefilled from that specification's earliest-expiring batch **that has not
expired**, so that submitting unchanged adds to an existing batch and changing them opens
a new one. Expired batches SHALL be skipped: they sort first by expiry, and adding stock
to one would put it where allocation can never reach it. A specification whose batches are
all expired, or which has none, SHALL leave the dates empty.

The form SHALL prevent submission when a date or the quantity is missing rather than
relying on the backend to reject it.

**Submitting SHALL close the window and SHALL NOT re-query.** Replenishment is accepted
rather than applied, so an immediate re-query can show numbers that have not moved yet,
and the viewer could not tell that from numbers that will not move. The page SHALL
instead state that the replenishment was accepted and that the result requires querying
again.

#### Scenario: A specification held in three batches shows one line that expands to three

- **WHEN** the viewer queries an owner and warehouse where one specification is held in
  three batches
- **THEN** one line appears for that specification, and expanding it shows three batches
  ordered as allocation would consume them

#### Scenario: Expired stock is visible without expanding

- **GIVEN** a specification is held in four batches, one of them expired
- **WHEN** the viewer queries that owner and warehouse
- **THEN** that line's expired quantity is non-zero, and its available-to-promise
  excludes the expired batch

#### Scenario: A specification the warehouse holds none of is still listed

- **WHEN** the viewer queries an owner and warehouse holding none of one of that owner's
  specifications
- **THEN** that specification appears with zero quantities and can still be replenished

#### Scenario: Stock under a code the catalog does not know is still listed

- **GIVEN** a warehouse holds stock under a SKU code absent from the owner's catalog
- **WHEN** the viewer queries that owner and warehouse
- **THEN** that stock appears as its own line, identified by its code

#### Scenario: Replenishment opens with the batch it will add to

- **GIVEN** a specification held in batches
- **WHEN** the viewer opens replenishment from that line
- **THEN** the owner, warehouse and goods are shown but not editable, and the dates are
  those of its earliest-expiring unexpired batch

#### Scenario: An expired batch is not offered as the one to add to

- **GIVEN** a specification whose earliest-expiring batch has expired
- **WHEN** the viewer opens replenishment from that line
- **THEN** the dates are those of the earliest batch that has not expired

#### Scenario: Submitting does not pretend the stock has changed

- **WHEN** the viewer submits a replenishment
- **THEN** the window closes, the page reports that it was accepted, and the listed
  quantities are unchanged until the viewer queries again

##### Example: how a line's four quantities relate

| Batches | On-hand | Reserved | Available | Expired |
| --- | --- | --- | --- | --- |
| 60 all reserved, 40 with 20 reserved, 30 free | 130 | 80 | 50 | 0 |
| the same plus an expired batch of 25 | 155 | 80 | 50 | 25 |
| none held here | 0 | 0 | 0 | 0 |

第二列是這個欄位存在的理由：在手多了 25 而可承諾一件都沒多——**那 25 件出不了貨**。

<!-- @trace
source: key-the-stock-page-to-a-warehouse
updated: 2026-07-31
code:
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolController.java
  - order-promising/src/main/java/com/flowzati/archone/stock/application/usecase/GetStockPoolUsecase.java
  - frontend/src/api/types.ts
  - order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolResponse.java
  - order-promising/src/main/java/com/flowzati/archone/stock/domain/repository/StockPoolRepository.java
  - frontend/src/components/ReplenishDialog.tsx
  - frontend/src/api/client.ts
  - frontend/src/components/StockPanel.tsx
  - frontend/src/components/StockPanel.module.css
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/jpa/JpaStockRepository.java
  - frontend/README.md
  - frontend/src/api/stockLines.ts
  - order-promising/src/main/java/com/flowzati/archone/stock/infrastructure/repository/StockPoolRepositoryImpl.java
  - frontend/src/components/ReplenishDialog.module.css
  - frontend/src/pages/StockPage.tsx
tests:
  - order-promising/src/test/java/com/flowzati/archone/stock/entrypoint/rest/StockPoolControllerTest.java
  - frontend/src/api/stockLines.test.ts
  - frontend/src/pages/StockPage.test.tsx
  - frontend/src/components/StockPanel.test.tsx
  - order-promising/src/sit/java/com/flowzati/archone/stock/infrastructure/repository/StockPoolPersistenceIntegrationTest.java
-->
