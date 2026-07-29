## MODIFIED Requirements

### Requirement: The replenishment probe publishes a real upstream stock event

The replenishment probe SHALL publish a genuine `StockReplenishedIntegrationEvent`
to the inventory stock-events topic, taking the role of the external Inventory
bounded context that this repository consumes from but does not own. It SHALL NOT
invoke the replenishment use case directly, because doing so would fabricate the
message metadata that the inbox uses for idempotency and would bypass the retry,
backoff, and dead-letter handling that the Kafka entrypoint provides.

The probe SHALL take an owner, **a warehouse, an arrival date and an expiry date**
alongside the SKU and the quantity, and the published event SHALL carry all of them.
Stock is identified by those five together; a replenishment naming fewer cannot say
which row it adds to, and the consumer would have to invent the missing values.

The record key SHALL be the owner, warehouse and SKU joined together — the same key the
ordering events use under the SKU strategy. Stock is now held per owner and warehouse,
so those are the messages that contend for the same rows; the bare SKU would serialise
messages that no longer compete.

The probe SHALL NOT write to the outbox and SHALL NOT modify any local state. The
outbox exists to make a local state change atomic with event publication; the
probe changes no local state, so there is no transaction to align with.

The response SHALL be `202` and SHALL carry the published event's identifier, so
the caller can correlate the asynchronous outcome. The response SHALL NOT include
a predicted count of orders the replenishment will wake, because that count is a
snapshot taken before publication and can disagree with the actual outcome.

#### Scenario: A replenishment probe wakes a queued backorder within the observable window

- **GIVEN** a SKU has orders queued in BACKORDERED status for one owner and warehouse
- **WHEN** the probe publishes a replenishment naming that owner, warehouse, SKU,
  arrival date and expiry date
- **THEN** the event reaches the consumer, stock is added to the row those five
  identify, and the queued orders are allocated from it

#### Scenario: A replenishment carrying no warehouse is rejected

- **WHEN** the probe is called without a warehouse
- **THEN** no event is published and no stock changes
