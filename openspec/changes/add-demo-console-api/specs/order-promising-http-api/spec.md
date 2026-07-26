## ADDED Requirements

### Requirement: Placing an order accepts a JSON command and returns the created order

The order placement endpoint SHALL accept only HTTP POST on `/orders`. The SKU and
quantity SHALL be supplied in a JSON request body, not as query parameters. The
response status SHALL be `200` and the response body SHALL use the same order
representation returned by the single-order query endpoint, so that a client needs
one order type rather than two.

Requests to `/orders` using any HTTP method other than POST SHALL NOT create an
order.

#### Scenario: A JSON command creates an order and returns its full representation

- **WHEN** a client sends `POST /orders` with a JSON body containing a SKU and a
  positive quantity
- **THEN** the response is `200` carrying that order's identifier, SKU, quantity,
  status `PENDING`, and placed timestamp, in the same shape as the single-order
  query response

#### Scenario: A GET request to the orders path never creates an order

- **WHEN** a client sends `GET /orders`
- **THEN** the response is the recent-orders list and no new order is persisted

### Requirement: Recent orders are listed in stable descending order

The recent-orders endpoint SHALL return orders sorted by placed time descending,
using the order identifier as a tie-breaker so that repeated requests against
unchanged data return an identical sequence. The `limit` parameter SHALL default
to 20 and SHALL accept values from 1 to 100 inclusive. A `limit` outside that
range SHALL be rejected with `400`; the endpoint SHALL NOT silently reduce an
out-of-range value to the maximum, because a client would otherwise be unable to
distinguish a truncated response from a complete one.

Each listed order SHALL carry the same fields as the single-order query response
except the integration event chain, which SHALL NOT be included in list responses.

#### Scenario: Repeated requests return an identical sequence

- **GIVEN** several orders share the same placed timestamp
- **WHEN** the recent-orders endpoint is called twice without intervening writes
- **THEN** both responses list the same orders in the same order

##### Example: limit boundary handling

| `limit` | Result |
| --- | --- |
| omitted | 20 most recent orders |
| 1 | 1 order |
| 100 | up to 100 orders |
| 101 | `400` |
| 0 | `400` |
| -1 | `400` |

### Requirement: A single order exposes its integration event chain

The single-order query response SHALL include an `events` array containing every
Integration Event emitted for that order, ordered by occurrence time. Each entry
SHALL carry the event identifier, the event type, the occurrence timestamp, and
the event payload exactly as it was published, without reshaping or flattening.

The chain SHALL be resolved by aggregate identity, so it SHALL return the same
events regardless of the active partition-key strategy. An order with no emitted
events SHALL yield an empty array, not an error.

#### Scenario: A backordered-then-allocated order shows its full causal chain

- **GIVEN** an order was placed, backordered for lack of stock, and later
  allocated after the SKU was replenished
- **WHEN** that order is queried by identifier
- **THEN** the `events` array contains the placement event, the backorder event,
  and the allocation event in that order, each with its payload as published

##### Example: chain entries for one order

| Position | Event type | Payload |
| --- | --- | --- |
| 1 | `OrderPlacedIntegrationEvent` | as published |
| 2 | `BackorderCreatedIntegrationEvent` | as published |
| 3 | `OrderAllocatedIntegrationEvent` | as published |

#### Scenario: An order queried immediately after placement returns its chain so far

- **WHEN** an order is queried before any allocation decision has been recorded
- **THEN** the response succeeds and `events` contains only the placement event

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
