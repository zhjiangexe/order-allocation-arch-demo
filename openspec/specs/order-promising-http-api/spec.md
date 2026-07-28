# order-promising-http-api Specification

## Purpose

TBD - created by archiving change 'add-demo-console-api'. Update Purpose after archive.

## Requirements

### Requirement: Placing an order accepts a JSON command and returns the created order

The order placement endpoint SHALL accept only HTTP POST on `/orders`. The command
SHALL be supplied in a JSON request body, not as query parameters, and SHALL carry the
owner, the upstream order number, the destination zone, the destination address, the
promised delivery date, and the order's lines. The response status SHALL be `200` and
the response body SHALL use the same order representation returned by the single-order
query endpoint, so that a client needs one order type rather than two.

Requests to `/orders` using any HTTP method other than POST SHALL NOT create an
order.

A command carrying no lines, or more than one line, SHALL be rejected. A command whose
line names a SKU code the catalog does not hold for that owner SHALL be rejected. A
command whose owner and upstream order number already exist SHALL be rejected without
creating a second order.

#### Scenario: A JSON command creates an order and returns its full representation

- **WHEN** a client sends `POST /orders` with a JSON body containing an owner, an
  upstream order number, a destination zone and address, a promised delivery date, and
  one line with a SKU code and a positive quantity
- **THEN** the response is `200` carrying that order's identifier, owner identifier,
  destination, promised delivery date, status `PENDING`, placed timestamp, and its
  lines, in the same shape as the single-order query response

#### Scenario: A GET request to the orders path never creates an order

- **WHEN** a client sends `GET /orders`
- **THEN** the response is the recent-orders list and no new order is persisted

##### Example: commands the endpoint rejects

| Command | Result |
| --- | --- |
| one line, known SKU code, unused upstream order number | order created |
| no lines | rejected, nothing persisted |
| two lines | rejected, nothing persisted |
| one line naming a SKU code the owner does not have | rejected, nothing persisted |
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

The stock-pool query endpoint SHALL return, for a given SKU, its on-hand quantity,
its reserved quantity, and its available-to-promise quantity. The
available-to-promise field SHALL be named after the domain concept rather than an
abbreviation, matching the vocabulary already used in the allocation domain model.
A SKU with no stock pool SHALL yield `404`.

#### Scenario: A partially reserved SKU reports all three quantities

- **GIVEN** a stock pool holds 10 on hand with 4 reserved
- **WHEN** that SKU is queried
- **THEN** the response reports on-hand 10, reserved 4, and available-to-promise 6

#### Scenario: An unknown SKU is reported as not found

- **WHEN** a SKU with no stock pool is queried
- **THEN** the response is `404`

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
