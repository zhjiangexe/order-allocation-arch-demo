## MODIFIED Requirements

### Requirement: Wake a queued backorder list on StockReplenished

The allocation SIT suite SHALL provide a named scenario that seeds 1,000 stable
FIFO-ordered BACKORDERED Orders for one SKU with no allocatable stock, then submits
one `StockReplenishedIntegrationEvent` through the allocation Kafka entrypoint.
The scenario SHALL exercise the existing `ReplenishmentUsecase` and
`StrictFifoAllocationPolicy` without changing allocation policy, Kafka topics, or
Integration Event contracts.

**The wake SHALL be bounded.** A replenishment SHALL allocate at most a configurable
number of orders in one transaction; when a round allocates the full bound, a
continuation event SHALL be published onto the same topic with the same partition key
and the round SHALL stop.

Without a bound, the number of stock rows one transaction touches is decided by the
queue's contents rather than by the event — so the write set cannot be known in advance,
and the ordering that prevents deadlocks has nothing to sort. Before stock was split
this was a throughput concern; with stock split it is a correctness one.

**The continuation condition SHALL count the orders a round actually allocated, not the
orders it read.** The two differ only when the queue is blocked at its head, and that is
precisely the case that must not loop: with a blocking order at the front and stock still
on hand, every round reads a full bound and allocates none, so a read-based condition
continues forever. Counting allocations makes the bound both terminate and guarantee
progress — a further round is requested only when the whole batch moved.

Deciding it the other way round — continuing while any unallocated order remains — loops
for the same reason.

The continuation event SHALL be published through the same translation layer as every
other outbound event, so that the topic, partition key and aggregate identity of an
outbound event are decided in one place. Emitting it directly from the use case would
also work, and would make that use case the only one that knows the outbox exists.

This rests on FIFO guaranteeing only the queue as it stood when the replenishment
arrived. Were that contract tightened to strict global FIFO, bounding would have to
become paging inside one transaction, which does not shorten the transaction at all.

#### Scenario: A single replenishment event wakes a queue of 1,000 backorders

- **GIVEN** no allocatable stock for `FIFO-SKU` and 1,000 BACKORDERED Orders for it in
  stable FIFO order
- **WHEN** one `StockReplenishedIntegrationEvent` is submitted through the
  allocation entrypoint
- **THEN** the queue reaches a final allocation decision, across as many continuation
  rounds as the bound requires

#### Scenario: A round that allocates fewer than the bound does not continue

- **GIVEN** the queue holds fewer orders than the bound
- **WHEN** a replenishment is processed
- **THEN** no continuation event is published

#### Scenario: A queue blocked at its head stops instead of continuing forever

- **GIVEN** the order at the front of the queue demands more than the replenishment
  supplied, and more than a full bound of satisfiable orders queue behind it
- **WHEN** the replenishment is processed
- **THEN** the round reads a full bound of orders and allocates none of them, no
  continuation event is published even though unallocated orders remain, and the
  replenished stock is left entirely unreserved

The blocked order SHALL NOT be skipped in favour of the satisfiable orders behind it.
Head-of-line blocking is the guarantee FIFO makes, not a defect in it — a queue that
reorders itself around an order too large to fill is no longer first-come-first-served,
and the large order would never be filled while smaller ones keep arriving.
