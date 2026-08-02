# order-promising-http-api Specification

## Purpose

TBD - created by archiving change 'add-demo-console-api'. Update Purpose after archive.

## Requirements

### Requirement: Placing an order accepts a JSON command and returns the created order

The order placement endpoint SHALL accept only HTTP POST on `/orders`. The command SHALL be
supplied in a JSON request body, not as query parameters, and SHALL carry the owner, the
upstream order number, **the shipping warehouse**, the destination zone, the destination
address, the promised delivery date, and the order's lines. The response status SHALL be
`200` and the response body SHALL use the same order representation returned by the
single-order query endpoint, so that a client needs one order type rather than two.

The command MAY additionally carry the instant the upstream system says the customer placed
the order. Omitting it SHALL be accepted; the order is then stored without one.

The order representation SHALL carry two distinct timestamps: the instant this system
received the order, and the instant it was placed upstream. The first SHALL always be
present; the second SHALL be absent when the caller did not supply it, and SHALL NOT be
filled in from the first — a substituted value is indistinguishable from an upstream that
genuinely sent the same instant.

Requests to `/orders` using any HTTP method other than POST SHALL NOT create an order.

A command carrying no lines, or more than one line, SHALL be rejected. A command whose line
names a SKU code the catalog does not hold for that owner SHALL be rejected. A command
naming no warehouse, or a warehouse its owner is not assigned to, SHALL be rejected. A
command whose owner and upstream order number already exist SHALL be rejected without
creating a second order. A command whose placed instant exceeds the received instant by more
than the configured tolerance SHALL be rejected.

#### Scenario: A JSON command creates an order and returns its full representation

- **WHEN** a client sends `POST /orders` with a JSON body containing an owner, an upstream
  order number, a warehouse the owner is assigned to, a destination zone and address, a
  promised delivery date, and one line with a SKU code and a positive quantity
- **THEN** the response is `200` carrying that order's identifier, owner identifier,
  warehouse identifier, destination, promised delivery date, status `PENDING`, received
  timestamp, and its lines, in the same shape as the single-order query response

#### Scenario: A command carrying an upstream placed instant returns both timestamps

- **WHEN** a client sends `POST /orders` additionally carrying the instant the order was
  placed upstream
- **THEN** the response carries both that instant and the received timestamp, and the two
  are separate fields

#### Scenario: A command omitting the placed instant returns only the received timestamp

- **WHEN** a client sends `POST /orders` without an upstream placed instant
- **THEN** the response carries the received timestamp and reports the placed instant as
  absent rather than repeating the received one

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
| no upstream placed instant | order created, placed instant stored empty |
| placed instant one day later than now | rejected, nothing persisted |

---
### Requirement: Recent orders are listed in stable descending order

The recent-orders endpoint SHALL return orders sorted by received time descending, using the
order identifier as a tie-breaker so that repeated requests against unchanged data return an
identical sequence. The `limit` parameter SHALL default to 20 and SHALL accept values from 1
to 100 inclusive. A `limit` outside that range SHALL be rejected with `400`; the endpoint
SHALL NOT silently reduce an out-of-range value to the maximum, because a client would
otherwise be unable to distinguish a truncated response from a complete one.

**Sorting SHALL NOT use the upstream placed time.** It is absent for orders whose upstream
did not send it, and it is decided by a clock this system does not control, so an order that
arrived late could be listed among orders received much earlier.

Each listed order SHALL carry the same fields as the single-order query response, including
its owner identifier and its lines.

An order SHALL identify its owner by identifier only. The owner's name SHALL NOT be
duplicated into the order representation: a client rendering owner names already holds the
catalog it loaded to offer owner selection, and resolving names from it costs one request for
the whole view rather than one per row. Carrying the name would instead cost one master-data
lookup on every list request, in exchange for something the caller already has.

#### Scenario: Repeated requests return an identical sequence

- **GIVEN** several orders share the same received timestamp
- **WHEN** the recent-orders endpoint is called twice without intervening writes
- **THEN** both responses list the same orders in the same order

#### Scenario: A late-arriving order is listed by when it arrived

- **GIVEN** an order placed upstream earlier than every other order but received last
- **WHEN** the recent-orders endpoint is called
- **THEN** that order appears first, because the list is ordered by received time

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

---
### Requirement: Stock held in a warehouse is queryable by owner and warehouse

The stock query endpoint SHALL return, for a given owner and warehouse, **every batch
that owner holds there**, grouped by SKU code. Each batch SHALL carry its arrival date,
expiry date, on-hand quantity, reserved quantity, available-to-promise quantity, and
whether it has expired. The available-to-promise field SHALL be named after the domain
concept rather than an abbreviation, matching the vocabulary of the allocation domain
model.

Both an owner and a warehouse SHALL be required. A SKU code alone no longer identifies
stock — it collides across owners — and allocation never spans warehouses, so a reply
covering several would suggest a pool that no single allocation can draw on.

Expired batches SHALL be present in the response and marked, not omitted. Omitting them
makes "we hold 100 units but can ship none" indistinguishable from "we hold nothing".

**Within each SKU the batches SHALL be ordered by expiry date, then arrival date, then
identity** — exactly the order allocation would draw on them. The ordering SHALL be a
guarantee of this endpoint rather than left to callers: the tie-breaks reach down to row
identity, which exists to make the order reproducible and carries no meaning a caller
could sort on.

**An owner and warehouse holding nothing SHALL yield an empty result, not `404`.** A
warehouse holding none of an owner's goods is an ordinary answer rather than a question
about something that does not exist — and it is the state a newly opened warehouse is in,
which is precisely when someone needs to look at it.

The endpoint SHALL NOT verify that the owner is assigned to the warehouse. That
assignment lives in the catalog, and stock has no reference to it; checking it here would
make the allocation side depend on the catalog for the first time.

#### Scenario: Every SKU the owner holds in that warehouse is reported

- **GIVEN** an owner holds two SKUs in one warehouse, one of them as three batches
- **WHEN** that owner's stock in that warehouse is queried
- **THEN** the response carries both SKUs, the first with three batches and the second
  with one, each batch with its own dates and quantities

#### Scenario: Batches of one SKU are ordered as allocation would consume them

- **GIVEN** an owner holds one SKU in a warehouse as several batches of differing expiry
- **WHEN** that owner's stock in that warehouse is queried
- **THEN** within that SKU the earliest-expiring batch comes first

#### Scenario: An expired batch is returned and marked

- **GIVEN** one of the batches has passed its expiry date
- **WHEN** that owner's stock in that warehouse is queried
- **THEN** that batch is present and marked as expired

#### Scenario: A warehouse holding nothing answers with an empty result

- **GIVEN** an owner holds no stock at all in a warehouse
- **WHEN** that owner's stock in that warehouse is queried
- **THEN** the response is successful and carries no SKUs

#### Scenario: Stock in another warehouse is not reported

- **GIVEN** an owner holds the same SKU in two warehouses
- **WHEN** that owner's stock in one of them is queried
- **THEN** only that warehouse's batches are present

##### Example: what identifies a stock query

| Owner | Warehouse | Result |
| --- | --- | --- |
| given | given | that owner's batches in that warehouse, grouped by SKU |
| given | given, holds nothing | successful, empty |
| given | omitted | `400` |
| omitted | given | `400` |

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