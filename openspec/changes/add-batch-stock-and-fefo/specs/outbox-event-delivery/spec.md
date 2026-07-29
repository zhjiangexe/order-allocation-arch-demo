## MODIFIED Requirements

### Requirement: Partition key strategy selects only the delivery key

The `archone.allocation.partition-key-strategy` setting SHALL determine the
`partition_key` written for `ordering.order-events` Integration Events: value
`sku` SHALL write **the order's owner, warehouse and SKU code joined together**, and
any other value SHALL write the `orderId`. The setting SHALL NOT affect
`aggregatetype`, `aggregateid`, `route`, payload content, or the Integration Event
contract.

The key SHALL name exactly the dimensions whose stock rows one allocation may touch.
Stock is identified by five dimensions, but a single allocation consumes across arrival
dates and expiry dates — so those two SHALL NOT be in the key, or messages that compete
for the same rows would be spread across partitions and the single-writer property
would be lost. Owner and warehouse SHALL be in the key, because stock separated by them
is no longer contended.

**Choosing too coarse a key is safer than too fine.** Too coarse only over-serialises
work that could have run in parallel; too fine breaks single-writer and produces the
optimistic-lock conflicts the strategy exists to prevent.

The key SHALL place its fixed-width parts first and the SKU code last. SKU codes are
owner-defined free text and may contain any character including the separator; with the
fixed-width identifiers leading, no two different triples can produce the same string.

#### Scenario: One owner's stock in one warehouse converges onto one partition

- **GIVEN** `archone.allocation.partition-key-strategy` is `sku`
- **WHEN** multiple orders are placed for the same owner, warehouse and SKU code
- **THEN** every resulting `ordering.order-events` record carries the same key and
  therefore lands on the same partition, while each row still records its own order's
  `orderId` as `aggregateid`

#### Scenario: Two owners sharing a SKU code no longer share a partition

- **GIVEN** two owners each place an order for the same SKU code
- **WHEN** the resulting records are inspected
- **THEN** their keys differ, because stock separated by owner is not contended and
  serialising them would be pointless
