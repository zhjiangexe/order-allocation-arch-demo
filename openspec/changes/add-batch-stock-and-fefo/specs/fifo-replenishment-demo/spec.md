## MODIFIED Requirements

### Requirement: Wake a queued backorder list on StockReplenished

The allocation SIT suite SHALL provide a named scenario that seeds 1,000 stable
FIFO-ordered BACKORDERED Orders for one SKU with no sellable stock, then submits
one `StockReplenishedIntegrationEvent` through the allocation Kafka entrypoint.
The scenario SHALL exercise the existing `ReplenishmentUsecase` and
`StrictFifoAllocationPolicy` without changing allocation policy, Kafka topics, or
Integration Event contracts.

**The wake SHALL be bounded.** A replenishment SHALL wake at most a configurable number
of orders in one transaction; when the queue holds more, the use case SHALL publish a
continuation event onto the same topic with the same partition key and stop.

Without a bound, the number of stock rows one transaction touches is decided by the
queue's contents rather than by the event — so the write set cannot be known in advance,
and the ordering that prevents deadlocks has nothing to sort. Before stock was split
this was a throughput concern; with stock split it is a correctness one.

The continuation SHALL stop when a round wakes fewer orders than the bound. Waking
fewer means the queue is empty or blocked at its head; sending another event would
reach the same state, so the condition both terminates and guarantees progress.

This rests on FIFO guaranteeing only the queue as it stood when the replenishment
arrived. Were that contract tightened to strict global FIFO, bounding would have to
become paging inside one transaction, which does not shorten the transaction at all.

#### Scenario: A single replenishment event wakes a queue of 1,000 backorders

- **GIVEN** no sellable stock for `FIFO-SKU` and 1,000 BACKORDERED Orders for it in
  stable FIFO order
- **WHEN** one `StockReplenishedIntegrationEvent` is submitted through the
  allocation entrypoint
- **THEN** the queue reaches a final allocation decision, across as many continuation
  rounds as the bound requires

#### Scenario: A round that wakes fewer than the bound does not continue

- **GIVEN** the queue holds fewer orders than the bound
- **WHEN** a replenishment is processed
- **THEN** no continuation event is published
