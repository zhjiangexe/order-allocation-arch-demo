# outbox-event-delivery Specification

## Purpose

TBD - created by archiving change 'fix-outbox-partition-key-semantics'. Update Purpose after archive.

## Requirements

### Requirement: Outbox rows separate aggregate identity from delivery metadata

An `event_outbox` row SHALL express domain identity and delivery decisions in
distinct columns. `aggregatetype` and `aggregateid` SHALL identify the aggregate
the Integration Event belongs to. `route` and `partition_key` SHALL carry the two
delivery decisions — target topic and Kafka message key. Neither aggregate column
SHALL influence topic or message-key selection, and neither delivery column SHALL
be treated as aggregate identity.

For Integration Events emitted on behalf of an Order aggregate, `aggregateid`
SHALL be the `orderId`, regardless of any partition-key configuration.

#### Scenario: Order aggregate events record orderId as aggregate identity under every partition strategy

- **WHEN** an Integration Event is appended to the outbox for an Order aggregate
- **THEN** `aggregatetype` is `Order` and `aggregateid` is that order's `orderId`,
  and the value of `archone.allocation.partition-key-strategy` does not change it

##### Example: same event under both strategies

| `partition-key-strategy` | `aggregatetype` | `aggregateid` | `partition_key` |
| --- | --- | --- | --- |
| `order-id` (default) | `Order` | `orderId` | `orderId` |
| `sku` | `Order` | `orderId` | `sku` |

---
### Requirement: Debezium derives the Kafka message key from partition_key

The Debezium Outbox Event Router SHALL be configured with
`table.field.event.key = partition_key`, replacing its default of `aggregateid`.
The published Kafka record key SHALL equal the row's `partition_key` value and
SHALL NOT be derived from `aggregateid`. Topic selection SHALL continue to come
from `route.by.field = route`.

Both connector configurations — the one exercised by the outbox CDC integration
test and the one registered by the `e2e/perf` setup script — SHALL declare
identical Event Router settings, so that the behavior verified in tests is the
behavior exercised under load.

#### Scenario: Record key follows partition_key when it differs from aggregate identity

- **GIVEN** an outbox row whose `aggregateid` and `partition_key` hold different values
- **WHEN** Debezium publishes that row through the Outbox Event Router
- **THEN** the resulting Kafka record key equals `partition_key`, and the record
  is published to the topic named by `route`

##### Example: v3 ordering event

- **GIVEN** an outbox row with `aggregateid = 7f1c…` (an orderId),
  `partition_key = HOT-SKU`, `route = ordering.order-events`
- **WHEN** Debezium publishes it
- **THEN** the Kafka record key is `HOT-SKU` and the topic is `ordering.order-events`

---
### Requirement: Partition key strategy selects only the delivery key

The `archone.allocation.partition-key-strategy` setting SHALL determine the
`partition_key` written for `ordering.order-events` Integration Events: value
`sku` SHALL write the order's SKU, and any other value SHALL write the `orderId`.
The setting SHALL NOT affect `aggregatetype`, `aggregateid`, `route`, payload
content, or the Integration Event contract.

#### Scenario: SKU strategy converges same-SKU messages onto one partition

- **GIVEN** `archone.allocation.partition-key-strategy` is `sku`
- **WHEN** multiple orders for the same SKU are placed
- **THEN** every resulting `ordering.order-events` record carries that SKU as its
  key and therefore lands on the same partition, while each row still records its
  own order's `orderId` as `aggregateid`

---
### Requirement: Allocation outcome events key by order identity

Integration Events published to `promising.allocation-events` SHALL use the
`orderId` as `partition_key` regardless of the configured partition-key strategy.
The rationale SHALL be recorded at the point of decision in the code: this
repository contains no consumer of that topic, so the single-writer property the
`sku` strategy exists to provide is not required there, and applying it would be
a speculative extension.

#### Scenario: Allocation outcomes keep orderId as key under the SKU strategy

- **GIVEN** `archone.allocation.partition-key-strategy` is `sku`
- **WHEN** an order is allocated or backordered and the outcome event is appended
- **THEN** the row's `route` is `promising.allocation-events` and its
  `partition_key` is the `orderId`, not the SKU

---
### Requirement: Outbox rows are queryable by aggregate identity

Because delivery decisions no longer occupy `aggregateid`, querying outbox rows by
`aggregatetype` and `aggregateid` SHALL return every Integration Event emitted for
that aggregate, under any partition-key strategy. A query for one order SHALL
return that order's lifecycle events in `timestamp` order, and SHALL NOT depend on
payload field names or on the active partition-key strategy.

#### Scenario: One order's events are retrievable by orderId under the SKU strategy

- **GIVEN** `archone.allocation.partition-key-strategy` is `sku`
- **AND** an order has been placed and subsequently allocated
- **WHEN** outbox rows are queried by `aggregatetype = 'Order'` and that order's
  `aggregateid`
- **THEN** both the `OrderPlacedIntegrationEvent` row and the
  `OrderAllocatedIntegrationEvent` row are returned, ordered by `timestamp`

##### Example: rows returned for one backordered-then-allocated order

| `type` | `route` | `partition_key` | Returned by orderId query |
| --- | --- | --- | --- |
| `OrderPlacedIntegrationEvent` | `ordering.order-events` | `HOT-SKU` | yes |
| `BackorderCreatedIntegrationEvent` | `promising.allocation-events` | `orderId` | yes |
| `OrderAllocatedIntegrationEvent` | `promising.allocation-events` | `orderId` | yes |
