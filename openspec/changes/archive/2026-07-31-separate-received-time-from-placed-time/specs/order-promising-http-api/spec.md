## MODIFIED Requirements

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
