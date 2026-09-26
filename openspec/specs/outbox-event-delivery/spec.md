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
| `stock` | `Order` | `orderId` | `<owner>/<node>` |

The `partition_key` column SHALL NOT hold a bare SKU code under any strategy. It once
did, and the illustration above outlived that; a stale example is worse than no example,
because it reads as a specification of the format.


<!-- @trace
source: complete-stock-strategy-rename
updated: 2026-07-30
-->

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


<!-- @trace
source: complete-stock-strategy-rename
updated: 2026-07-30
-->

---
### Requirement: Partition key strategy selects only the delivery key

The `archone.allocation.partition-key-strategy` setting SHALL determine the
`partition_key` written for `ordering.order-events` Integration Events: value `stock`
SHALL write **the order's owner and warehouse joined together**, and any other value
SHALL write the `orderId`. The setting SHALL NOT affect `aggregatetype`, `aggregateid`,
`route`, payload content, or the Integration Event contract.

The setting's value SHALL be named after **what it serialises**, not after which columns
compose the key. Naming it `sku` tied the contract to one particular composition, so
refining the composition would have made the name a lie.

The key SHALL NOT contain the SKU code. Stock is identified by five dimensions, so a key
naming the SKU is a closer fit for "which rows will be touched" — but it cannot survive
an order that spans several SKUs, because ship-complete requires every line's
available-to-promise to be judged in one transaction, and a per-SKU key necessarily
spreads that transaction across several writers. Dropping the SKU removes the problem
rather than deferring it: an order belongs to exactly one owner and warehouse however
many SKUs it spans, so one writer always sees the whole order.

Owner and warehouse SHALL both be in the key. Stock separated by either is not
contended, so a key omitting them would serialise work that can never conflict.

**Choosing too coarse a key is safer than too fine.** Too coarse only over-serialises
work that could have run in parallel; too fine breaks single-writer and produces the
optimistic-lock conflicts the strategy exists to prevent. The cost of this particular
coarsening is that one owner's orders in one warehouse queue behind one another even
when they name different SKUs.

#### Scenario: One owner's stock in one warehouse converges onto one partition

- **GIVEN** `archone.allocation.partition-key-strategy` is `stock`
- **WHEN** multiple orders are placed for the same owner and warehouse
- **THEN** every resulting `ordering.order-events` record carries the same key and
  therefore lands on the same partition, while each row still records its own order's
  `orderId` as `aggregateid`

#### Scenario: Two owners sharing a SKU code do not share a partition

- **GIVEN** two owners each place an order for the same SKU code in the same warehouse
- **WHEN** the resulting records are inspected
- **THEN** their keys differ, because stock separated by owner is not contended and
  serialising them would be pointless

#### Scenario: An order spanning several SKUs still yields one key

- **GIVEN** `archone.allocation.partition-key-strategy` is `stock`
- **WHEN** an order naming two different SKU codes is translated
- **THEN** it yields a single partition key containing neither SKU code, and the
  translation does not fail


<!-- @trace
source: add-batch-stock-and-fefo
updated: 2026-07-30
-->

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


<!-- @trace
source: complete-stock-strategy-rename
updated: 2026-07-30
-->

---
### Requirement: Outbox rows are queryable by aggregate identity

Querying outbox rows by `aggregatetype` and `aggregateid` SHALL return every Integration Event
emitted for that aggregate because delivery decisions no longer occupy `aggregateid`. It SHALL work
under any partition-key strategy. A query for one order SHALL
return that order's lifecycle events in `timestamp` order, and SHALL NOT depend on
payload field names or on the active partition-key strategy.

That last clause is now stronger than it was: the order lifecycle events carry **nothing
but the order identity and a timestamp** — no SKU, no quantity, no owner, no warehouse.
A consumer holds the order identity and reads the order back, because it must read the
order anyway for the ship-to details and promised date that were never in the event.
Duplicating any of that into the payload would add a second source that can disagree
with the first.

Fields whose only justification is a consumer that does not exist SHALL NOT be carried.
Adding a field is non-breaking for consumers, which already ignore fields they do not
know; removing one is breaking. The minimum is therefore the correct starting point, and
a filtering dimension SHALL be added when a consumer that filters on it exists.

The exception is events that originate outside this system, which carry the fact itself
because there is no local aggregate to read.

#### Scenario: One order's events are retrievable by orderId under the stock strategy

- **GIVEN** `archone.allocation.partition-key-strategy` is `stock`
- **AND** an order has been placed and subsequently allocated
- **WHEN** outbox rows are queried by `aggregatetype = 'Order'` and that order's
  `aggregateid`
- **THEN** both the `OrderPlacedIntegrationEvent` row and the
  `OrderAllocatedIntegrationEvent` row are returned, ordered by `timestamp`

##### Example: rows returned for one backordered-then-allocated order

| `type` | `route` | `partition_key` | Returned by orderId query |
| --- | --- | --- | --- |
| `OrderPlacedIntegrationEvent` | `ordering.order-events` | `<owner>/<node>` | yes |
| `BackorderCreatedIntegrationEvent` | `promising.allocation-events` | `orderId` | yes |
| `OrderAllocatedIntegrationEvent` | `promising.allocation-events` | `orderId` | yes |

#### Scenario: A lifecycle event carries nothing but identity and time

- **WHEN** an order-placed or order-allocated outbox row's payload is inspected
- **THEN** it carries the event and order identities and a timestamp, and carries no
  SKU, quantity, owner or warehouse

<!-- @trace
source: add-batch-stock-and-fefo
updated: 2026-07-30
-->
