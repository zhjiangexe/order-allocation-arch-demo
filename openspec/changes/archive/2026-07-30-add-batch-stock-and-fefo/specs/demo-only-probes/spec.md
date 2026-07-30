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

The record key SHALL be the owner and warehouse joined together — **byte-for-byte the
same key the ordering events carry under the `stock` strategy**, produced by the same
shared rule rather than composed a second time here. Stock is held per owner and
warehouse, so those are the messages that contend for the same rows; the bare SKU would
serialise messages that no longer compete.

The key SHALL NOT contain the SKU, even though the probe knows it. A replenishment and
an order for the same owner and warehouse must land on the same partition for the
single-writer guarantee to hold over the rows they both touch, and the ordering side
cannot put the SKU in its key — see `outbox-event-delivery`. Two keys composed from
different dimensions would diverge silently: no error, just a lost guarantee.

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
- **THEN** the response is `400`, no event is published and no stock changes

#### Scenario: A replenishment missing its quantity is a caller error, not a server error

- **WHEN** the probe is called with no quantity at all
- **THEN** the response is `400` rather than `500`

A missing field SHALL be reported as the caller's error. Every field of the request is
nullable on the wire, so an absent one must be rejected before it reaches a constructor
that would fail on it — otherwise the caller's omission is reported as a server fault
and the probe becomes useless for diagnosing exactly the mistakes it is there to surface.
