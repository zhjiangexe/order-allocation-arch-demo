## MODIFIED Requirements

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

## ADDED Requirements

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
