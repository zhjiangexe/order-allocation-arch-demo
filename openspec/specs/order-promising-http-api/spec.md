# order-promising-http-api Specification

## Purpose

TBD - created by archiving change 'add-demo-console-api'. Update Purpose after archive.

## Requirements

### Requirement: Placing an order accepts a JSON command and returns the created order

The order placement endpoint SHALL accept only HTTP POST on `/orders`. The command
SHALL be supplied in a JSON request body, not as query parameters, and SHALL carry the
owner, the upstream order number, **the shipping warehouse**, the destination zone, the
destination address, the promised delivery date, and the order's lines. The response
status SHALL be `200` and the response body SHALL use the same order representation
returned by the single-order query endpoint, so that a client needs one order type
rather than two.

Requests to `/orders` using any HTTP method other than POST SHALL NOT create an
order.

A command carrying no lines, or more than one line, SHALL be rejected. A command whose
line names a SKU code the catalog does not hold for that owner SHALL be rejected. A
command naming no warehouse, or a warehouse its owner is not assigned to, SHALL be
rejected. A command whose owner and upstream order number already exist SHALL be
rejected without creating a second order.

#### Scenario: A JSON command creates an order and returns its full representation

- **WHEN** a client sends `POST /orders` with a JSON body containing an owner, an
  upstream order number, a warehouse the owner is assigned to, a destination zone and
  address, a promised delivery date, and one line with a SKU code and a positive
  quantity
- **THEN** the response is `200` carrying that order's identifier, owner identifier,
  warehouse identifier, destination, promised delivery date, status `PENDING`, placed
  timestamp, and its lines, in the same shape as the single-order query response

#### Scenario: A GET request to the orders path never creates an order

- **WHEN** a client sends `GET /orders`
- **THEN** the response is the recent-orders list and no new order is persisted

##### Example: commands the endpoint rejects

| Command | Result |
| --- | --- |
| one line, known SKU code, assigned warehouse, unused upstream order number | order created |
| no lines | rejected, nothing persisted |
| two lines | rejected, nothing persisted |
| one line naming a SKU code the owner does not have | rejected, nothing persisted |
| no warehouse | rejected, nothing persisted |
| a warehouse the owner is not assigned to | rejected, nothing persisted |
| upstream order number already used by that owner | rejected, no second order |

---
### Requirement: Recent orders are listed in stable descending order

The recent-orders endpoint SHALL return orders sorted by placed time descending,
using the order identifier as a tie-breaker so that repeated requests against
unchanged data return an identical sequence. The `limit` parameter SHALL default
to 20 and SHALL accept values from 1 to 100 inclusive. A `limit` outside that
range SHALL be rejected with `400`; the endpoint SHALL NOT silently reduce an
out-of-range value to the maximum, because a client would otherwise be unable to
distinguish a truncated response from a complete one.

Each listed order SHALL carry the same fields as the single-order query response,
including its owner identifier and its lines.

An order SHALL identify its owner by identifier only. The owner's name SHALL NOT be
duplicated into the order representation: a client rendering owner names already holds
the catalog it loaded to offer owner selection, and resolving names from it costs one
request for the whole view rather than one per row. Carrying the name would instead cost
one master-data lookup on every list request, in exchange for something the caller
already has.

#### Scenario: Repeated requests return an identical sequence

- **GIVEN** several orders share the same placed timestamp
- **WHEN** the recent-orders endpoint is called twice without intervening writes
- **THEN** both responses list the same orders in the same order

#### Scenario: A listed order identifies its owner without carrying the name

- **WHEN** the recent-orders endpoint returns an order
- **THEN** that order carries the owner's identifier and does not carry the owner's name

##### Example: limit boundary handling

| `limit` | Result |
| --- | --- |
| omitted | 20 most recent orders |
| 1 | 1 order |
| 100 | up to 100 orders |
| 101 | `400` |
| 0 | `400` |
| -1 | `400` |

---
### Requirement: Stock pool state is queryable by SKU

The stock-pool query endpoint SHALL return, for a given owner and SKU, **the rows that
SKU is held in** — each carrying its warehouse, arrival date, expiry date, on-hand
quantity, reserved quantity, available-to-promise quantity, and whether it has expired.
The available-to-promise field SHALL be named after the domain concept rather than an
abbreviation, matching the vocabulary already used in the allocation domain model.

The endpoint SHALL take an owner. A SKU code alone no longer identifies stock — it
collides across owners, and the reply would mix two owners' goods into one list.

Expired rows SHALL be present in the response and marked, not omitted.
Omitting them makes "we hold 100 units but can ship none" indistinguishable from "we
hold nothing".

**The rows SHALL be ordered by warehouse, then by expiry date, then by arrival date,
then by identity.** Within one warehouse that is exactly the order allocation would draw
on them; grouping by warehouse first is what makes that meaningful, because allocation
never spans warehouses — each run is scoped to one. A single list sorted by expiry across
all warehouses would suggest a consumption order that no allocation will ever follow.

The ordering SHALL be a guarantee of this endpoint rather than left to callers. The
consumer displaying these rows cannot reconstruct it: the tie-breaks reach down to row
identity, which exists to make the order reproducible and carries no meaning a caller
could sort on.

An owner and SKU with no stock at all SHALL yield `404`.

#### Scenario: Rows are grouped by warehouse and ordered by expiry within each

- **GIVEN** an owner holds one SKU in two warehouses, each as several rows of differing
  expiry
- **WHEN** that owner's stock for that SKU is queried
- **THEN** the rows of each warehouse are contiguous, and within each warehouse the
  earliest-expiring row comes first

#### Scenario: A SKU held in three rows reports each of them

- **GIVEN** an owner holds one SKU as three rows of differing expiry
- **WHEN** that owner's stock for that SKU is queried
- **THEN** the response carries three entries, each with its own warehouse, arrival
  date, expiry date and quantities

#### Scenario: An expired row is returned and marked

- **GIVEN** one of an owner's rows for a SKU has passed its expiry date
- **WHEN** that owner's stock for that SKU is queried
- **THEN** that row appears in the response and is marked as expired

#### Scenario: An owner holding none of a SKU is reported as not found

- **WHEN** stock is queried for an owner and SKU with no rows
- **THEN** the response is `404`


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
### Requirement: The catalog is queryable over HTTP

The HTTP surface SHALL expose read-only endpoints listing owners, listing one owner's
products, and listing one product's SKUs, so that an order command can be composed by
selecting an owner, then a product, then a specification.

Product and SKU endpoints SHALL be addressed within their owner, matching the fact that
neither a product code nor a SKU code identifies anything on its own.

These endpoints SHALL be read-only. No HTTP method SHALL create, update, or delete an
owner, a product, or a SKU.

#### Scenario: Selecting downward returns only entries under the current selection

- **WHEN** a client lists owners, then lists one owner's products, then lists one of
  those products' SKUs
- **THEN** each response contains only entries belonging to the selection named in the
  request path

#### Scenario: The same SKU code under a different owner is a different resource

- **GIVEN** owner A and owner B both define SKU code `SKU-A`
- **WHEN** a client lists SKUs under owner A's product
- **THEN** only owner A's `SKU-A` is returned, with owner A's specification name and
  weight

---
### Requirement: An owner's warehouses are queryable over HTTP

The system SHALL expose a read-only endpoint returning the warehouses a given owner is
assigned to. The path SHALL nest the warehouses under the owner rather than taking the
owner as an optional filter, because an owner's assignment is what makes a warehouse
selectable at all — a flat warehouse list would invite callers to offer warehouses the
owner cannot ship from.

The response SHALL carry each warehouse's identifier, code, and name, and SHALL NOT
carry any attribute the system does not hold.

An unknown owner SHALL yield an empty list rather than an error, matching how the
existing catalog queries treat an owner with no products.

#### Scenario: An owner's warehouses are listed

- **WHEN** a client queries the warehouses of an owner assigned to two warehouses
- **THEN** the response carries exactly those two, each with its identifier, code, and
  name, in a stable order

#### Scenario: A warehouse the owner is not assigned to is absent

- **GIVEN** a warehouse exists that a given owner is not assigned to
- **WHEN** a client queries that owner's warehouses
- **THEN** that warehouse does not appear in the response