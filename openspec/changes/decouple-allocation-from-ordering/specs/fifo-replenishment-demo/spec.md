## MODIFIED Requirements

### Requirement: Wake a queued backorder list on StockReplenished

The allocation SIT suite SHALL provide a named scenario that seeds 1,000 orders with stable
FIFO-ordered outstanding demand for one SKU in one owner's warehouse, with no allocatable
stock there, then submits one `StockReplenishedIntegrationEvent` through the allocation
Kafka entrypoint. The scenario SHALL exercise the existing `ReplenishmentUsecase` and
`StrictFifoAllocationPolicy` without changing allocation policy, Kafka topics, or
Integration Event contracts.

**The candidate queue SHALL be scoped to the replenished owner, warehouse and SKU.** Stock
is held per owner, warehouse, arrival and expiry, so an order shipping from another
warehouse cannot be satisfied by this replenishment. Including it consumes the bound —
which counts orders — and it is then skipped, so a queue that spans warehouses spends its
budget on orders that were never candidates.

**The wake SHALL be bounded.** A replenishment SHALL allocate at most a configurable number
of orders in one transaction; when a round allocates the full bound, a continuation event
SHALL be published onto the same topic with the same partition key and the round SHALL
stop.

Without a bound, the number of stock rows one transaction touches is decided by the queue's
contents rather than by the event — so the write set cannot be known in advance, and the
ordering that prevents deadlocks has nothing to sort. Before stock was split this was a
throughput concern; with stock split it is a correctness one.

**The continuation condition SHALL count the orders a round actually allocated, not the
orders it read.** The two differ only when the queue is blocked at its head, and that is
precisely the case that must not loop: with a blocking order at the front and stock still
on hand, every round reads a full bound and allocates none, so a read-based condition
continues forever. Counting allocations makes the bound both terminate and guarantee
progress — a further round is requested only when the whole batch moved.

Deciding it the other way round — continuing while any unallocated order remains — loops
for the same reason.

The bound and the query SHALL agree on their unit. Both count orders: the query returns
whole orders with all of their outstanding lines, because an order is satisfied wholly or
not at all. Were the query to return lines while the bound counted orders, the two would
coincide only while intake permits one line per order and diverge silently the moment it
does not.

The continuation event SHALL be published through the same translation layer as every other
outbound event, so that the topic, partition key and aggregate identity of an outbound
event are decided in one place. Emitting it directly from the use case would also work, and
would make that use case the only one that knows the outbox exists.

This rests on FIFO guaranteeing only the queue as it stood when the replenishment arrived.
Were that contract tightened to strict global FIFO, bounding would have to become paging
inside one transaction, which does not shorten the transaction at all.

#### Scenario: A single replenishment event wakes a queue of 1,000 backorders

- **GIVEN** no allocatable stock for `FIFO-SKU` in the replenished warehouse and 1,000
  orders with outstanding demand for it in stable FIFO order
- **WHEN** one `StockReplenishedIntegrationEvent` is submitted through the allocation
  entrypoint
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

#### Scenario: Orders shipping from another warehouse never enter the round

- **GIVEN** outstanding demand for the same owner and SKU in two warehouses, with the
  other warehouse's orders placed earlier
- **WHEN** stock is replenished in one of them
- **THEN** the round's candidates are drawn only from the replenished warehouse, and the
  earlier orders of the other warehouse neither consume the bound nor appear in the round

The blocked order SHALL NOT be skipped in favour of the satisfiable orders behind it.
Head-of-line blocking is the guarantee FIFO makes, not a defect in it — a queue that
reorders itself around an order too large to fill is no longer first-come-first-served, and
the large order would never be filled while smaller ones keep arriving.
