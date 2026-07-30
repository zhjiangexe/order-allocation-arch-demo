## MODIFIED Requirements

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
| `stock` | `Order` | `orderId` | `<owner>/<node>` |

The `partition_key` column SHALL NOT hold a bare SKU code under any strategy. It once
did, and the illustration above outlived that; a stale example is worse than no example,
because it reads as a specification of the format.

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

##### Example: an ordering event under the stock strategy

- **GIVEN** an outbox row with `aggregateid = 7f1c…` (an orderId),
  `partition_key = <owner>/<node>`, `route = ordering.order-events`
- **WHEN** Debezium publishes it
- **THEN** the Kafka record key is `<owner>/<node>` and the topic is
  `ordering.order-events`

The example SHALL use a key the system can actually produce. Debezium copies the column
through without interpreting it, so any value would demonstrate the mechanism equally
well — which is exactly why an unproducible one is harmful rather than harmless: it
teaches a format that no row will ever hold.

---
### Requirement: Allocation outcome events key by order identity

Integration Events published to `promising.allocation-events` SHALL use the
`orderId` as `partition_key` regardless of the configured partition-key strategy.
The rationale SHALL be recorded at the point of decision in the code: this
repository contains no consumer of that topic, so the single-writer property the
`stock` strategy exists to provide is not required there, and applying it would be
a speculative extension.

**This exemption is scoped by topic, not by publisher.** The same translator also emits
a backorder-wake continuation event onto the inventory stock-events topic, and that one
SHALL always be keyed by the stock contention group — see the requirement covering the
wake bound. Keying it by `orderId` would put it on a different partition from the round
it exists to continue, and the two would then run in parallel; the single-writer property
is obtained precisely by sharing a key.

The distinction is which rows the consumer will write. An outcome event's consumer would
update that one order's row, so the order identity is the contention group. A continuation
event's consumer draws on a group of stock rows, so the stock contention group is.

#### Scenario: Allocation outcomes keep orderId as key under the stock strategy

- **GIVEN** `archone.allocation.partition-key-strategy` is `stock`
- **WHEN** an order is allocated or backordered and the outcome event is appended
- **THEN** the row's `route` is `promising.allocation-events` and its
  `partition_key` is the `orderId`, not the stock contention group

#### Scenario: A wake continuation is keyed by contention group under every configuration

- **WHEN** a backorder-wake continuation event is appended
- **THEN** its `partition_key` is the stock contention group rather than any order
  identity, whatever the strategy setting holds

The component that emits it SHALL NOT receive the strategy setting at all. Being unable
to read a setting is a stronger guarantee than reading it and choosing to ignore it: the
first cannot be undone by a later edit that "makes it consistent" with the outcome events
next to it.
