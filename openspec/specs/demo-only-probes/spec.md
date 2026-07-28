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

The probe SHALL take an owner alongside the SKU and the quantity, and the published
event SHALL carry that owner. Replenishment names the owner whose backordered queue it
wakes; without it the consumer cannot tell which owner's queue to read, and two owners
using the same SKU code would wake each other's orders.

The published message SHALL satisfy the consumer's message contract: an event
identifier header, an event type header naming the event class, a payload whose
event identifier equals the header value, and the SKU as the record key. The record key
SHALL remain the bare SKU in this change, even though the event now carries an owner —
the key selects a partition, and the partitioning scheme is revised separately.

The probe SHALL NOT write to the outbox and SHALL NOT modify any local state. The
outbox exists to make a local state change atomic with event publication; the
probe changes no local state, so there is no transaction to align with.

The response SHALL be `202` and SHALL carry the published event's identifier, so
the caller can correlate the asynchronous outcome. The response SHALL NOT include
a predicted count of orders the replenishment will wake, because that count is a
snapshot taken before publication and can disagree with the actual outcome.

#### Scenario: A replenishment probe wakes a queued backorder within the observable window

- **GIVEN** a SKU has orders queued in BACKORDERED status for one owner and
  insufficient stock
- **WHEN** the replenishment probe is invoked for that owner and SKU with a quantity
  sufficient to satisfy the earliest queued orders
- **THEN** the response is `202` with the published event identifier, and within
  10 seconds the recent-orders query reports those orders as `ALLOCATED` in FIFO
  order

#### Scenario: Another owner's queue is left untouched

- **GIVEN** two owners both have orders queued in BACKORDERED status for the same SKU
  code
- **WHEN** the replenishment probe is invoked for one of them
- **THEN** only that owner's orders reach `ALLOCATED`, and the other owner's orders
  remain `BACKORDERED`

#### Scenario: The published message satisfies the consumer's contract

- **WHEN** the replenishment probe publishes an event
- **THEN** the message carries the event identifier and event type headers, its
  payload event identifier equals the header value, its payload names the owner, and
  its record key is the SKU

##### Example: message published for a replenishment of 500 units

| Part of message | Value |
| --- | --- |
| topic | `inventory.stock-events` |
| record key | `HOT-SKU` |
| header `id` | the generated event identifier, e.g. `3d9a…` |
| header `eventType` | `StockReplenishedIntegrationEvent` |
| payload `eventId` | `3d9a…` — identical to header `id` |
| payload `ownerId` | the owner named in the request |
| payload `sku` / `quantity` | `HOT-SKU` / `500` |
| response | `202` carrying `3d9a…` |

---
### Requirement: The active partition key strategy is observable

A read-only endpoint SHALL report the partition-key strategy currently in effect,
so an operator can tell whether the running system is using the order-identifier
strategy or the SKU strategy. The endpoint SHALL only report the value; it SHALL
NOT offer to change it, because the strategy is resolved at application startup.

#### Scenario: The configured strategy is reported

- **GIVEN** the application started with the SKU partition-key strategy
- **WHEN** the configuration endpoint is queried
- **THEN** the response reports the SKU strategy as the effective value

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
