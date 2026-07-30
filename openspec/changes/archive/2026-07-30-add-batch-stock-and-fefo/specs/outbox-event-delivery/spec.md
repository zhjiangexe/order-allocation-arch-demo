## MODIFIED Requirements

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

---
### Requirement: Outbox rows are queryable by aggregate identity

Because delivery decisions no longer occupy `aggregateid`, querying outbox rows by
`aggregatetype` and `aggregateid` SHALL return every Integration Event emitted for
that aggregate, under any partition-key strategy. A query for one order SHALL
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
