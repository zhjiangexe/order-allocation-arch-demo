## REMOVED Requirements

### Requirement: A line's status and backordered timestamp mirror its header
**Reason**: line 的缺貨時間戳被移除。它的唯一存在理由是「讓缺貨佇列能從單一表篩選與排序」，而那個查詢從來就不是單表——它是 `orders` 與 `order_lines` 的 join，篩選用行的貨主與 SKU，排序取自 header。標題列舉了 status 與 timestamp 兩者，移除其中之一後不再正確。
**Migration**: 由 **A line's status mirrors its header, and no timestamp is stored on it** 取代，並記下該欄位為何從一開始就沒有被讀到。

### Requirement: Backorder queues are scoped to one owner and one SKU
**Reason**: 佇列的範圍增加了倉別，排序鍵也由進入缺貨的時間改為訂單識別碼（UUID v7，等於到達順序）。庫存按貨主、倉庫、入庫日與效期持有，跨倉的佇列會回傳這次補貨不可能滿足的訂單，而它們仍然佔用以張數計的喚醒上限。標題明確列舉了範圍的兩個維度，加上第三個之後不再正確。
**Migration**: 由 **Backorder queues are scoped to one owner, one warehouse, and one SKU** 取代。

## ADDED Requirements

### Requirement: A line's status mirrors its header, and no timestamp is stored on it

Because an order is fulfilled complete, all of an order's lines reach an allocation outcome
together. When an order is marked allocated, backordered, or cancelled, every line SHALL be
updated in the same transaction and SHALL end in the state the header records.

No backordered timestamp and no allocated timestamp SHALL be stored on the line. Both would
equal the header's, and neither is read.

A backordered timestamp was previously stored on the line so that the backorder queue could
be filtered and ordered from one table. **That query was never single-table.** It joins
`orders` to `order_lines`, filters on the line's owner and SKU code, and takes both its
predicate and its ordering from the header — so the column was never reached, and the index
built over it could never serve the ordering it existed for. Adding a warehouse to the
queue's scope puts the query further from single-table, not closer.

The ordering key SHALL be the order identifier, which already encodes when the order entered
the system.

#### Scenario: Marking an order backordered stamps its header and its lines alike

- **GIVEN** a rehydrated order with two lines
- **WHEN** the order is marked backordered at a given instant
- **THEN** the header carries that instant, both lines carry the backordered status, and
  neither line carries a timestamp of its own

---
### Requirement: Backorder queues are scoped to one owner, one warehouse, and one SKU

The backordered-demand query SHALL take an owner, a warehouse and a SKU code, and SHALL
return that owner's outstanding demand for that SKU in that warehouse, in the order the
orders entered the system, so repeated queries against unchanged data return an identical
sequence.

One owner's queue SHALL NOT be affected by another owner's orders, even when both use the
same SKU code, because in third-party logistics SKU codes collide across owners and the goods
are not interchangeable.

**The warehouse SHALL be part of the scope, not merely available to the caller.** Stock is
held per owner, warehouse, arrival and expiry, so an order can only be satisfied from its own
warehouse. A queue that spans warehouses returns orders that this replenishment cannot
satisfy; they consume the wake limit — which counts orders, not successful allocations — and
are then skipped. The waste grows with the number of warehouses.

**The ordering key SHALL be the order identifier, which is time-ordered by construction.**
It records when the order entered this system, and that is the only sequence a queue can
honour: before an order arrives, the system knows nothing of it and can hold nothing for it.

It SHALL NOT be the time the order entered backorder. That is a processing timestamp: two
orders placed a millisecond apart enter backorder in whatever sequence the consumer happened
to process them, and retries, rebalances and optimistic-lock conflicts all change it.
Ordering by it lets the system's own scheduling jitter decide precedence between customers.

It SHALL NOT be the upstream placed time either. That value is optional, and it is decided by
a system whose clock and delivery schedule are outside this one's control — an order placed
three days ago and delivered today would otherwise be sequenced ahead of orders that have
been waiting since yesterday.

**This rests on order identifiers being time-ordered**, and SHALL be covered by a test
asserting that identifiers generated later sort after earlier ones. Were they to become
random, the queue would silently lose its order: nothing would throw, nothing would be
logged, and no existing test would fail.

Replenishment SHALL name the owner and the warehouse whose queue it wakes. Without them the
scoped query has no caller able to supply them, and the scoping would exist in the schema but
never take effect.

#### Scenario: Two owners using the same SKU code hold separate queues

- **GIVEN** owner A and owner B each have outstanding demand for SKU code `SKU-A`, and owner
  B's orders were placed earlier
- **WHEN** owner A's queue for `SKU-A` is read
- **THEN** only owner A's orders are returned, and owner B's earlier orders do not appear or
  affect the ordering

#### Scenario: A queue excludes orders shipping from another warehouse

- **GIVEN** owner A has outstanding demand for `SKU-A` in the north warehouse and in the south
  warehouse
- **WHEN** the queue for owner A, south warehouse and `SKU-A` is read
- **THEN** only the south warehouse's orders are returned

#### Scenario: Replenishment wakes only the named owner's and warehouse's queue

- **GIVEN** owner A and owner B both have outstanding demand for SKU code `SKU-A`
- **WHEN** stock is replenished for owner A, the south warehouse and SKU code `SKU-A`
- **THEN** only owner A's south-warehouse orders enter the allocation decision

#### Scenario: Precedence follows the order of arrival

- **GIVEN** two orders for the same owner, warehouse and SKU, where the one that arrived
  first entered backorder later
- **WHEN** the queue is read
- **THEN** the one that arrived first comes first

#### Scenario: An order delivered late does not overtake orders already waiting

- **GIVEN** an order whose upstream placed time is earlier than every waiting order's, but
  which this system received last
- **WHEN** the queue is read
- **THEN** it comes last, because nothing could have been held for it before it arrived

#### Scenario: Order identifiers are time-ordered

- **WHEN** two orders are created one after the other
- **THEN** the later order's identifier sorts after the earlier one's

##### Example: why the three candidate keys disagree

| | Order A | Order B | Order C |
| --- | --- | --- | --- |
| arrived (identifier) | 10:00:00.000 | 10:00:00.001 | 10:00:00.002 |
| entered backorder | 10:00:02 (one retry) | 10:00:01 | 10:00:03 |
| upstream placed time | not supplied | 09:58 | three days earlier |

Queue by identifier: A, B, C — the order they arrived.
Queue by backorder time: B, A, C — a retry moved A behind B.
Queue by upstream placed time: C, B, A — C overtakes orders that waited longer, and A has
no key at all.

---
### Requirement: Ordering advances an order's status from allocation's events

An order's status SHALL be advanced by ordering alone, in response to the allocation outcome
events published to the allocation events topic. Allocation SHALL NOT write to `orders` or
`order_lines`.

The event carries an order identifier and a timestamp; ordering SHALL re-read the order and
advance it. Nothing in the event is treated as the order's state, so the two cannot disagree.

Consumption SHALL be deduplicated through the existing inbox mechanism, because the delivery
guarantee is at-least-once and advancing a status twice must not produce a second outcome.

**An event naming an order that is already cancelled SHALL be a no-op, not a failure.**
Allocation completing and a customer cancelling are concurrent, and after this change they are
no longer serialised by a shared transaction: allocation can succeed and publish while the
cancellation is in flight. Treating the late outcome as an error would send one dead letter for
every ordinary cancellation that happens to race. The reservation is released by the
cancellation path on allocation's side, so both sides remain correct.

There SHALL be a lag between allocation deciding and the order's status reflecting it. That lag
is acceptable for display and SHALL NOT be used as a gate on allocation — the query that
decides what is still owed derives it from reservations, not from the order's status. A
replenishment arriving inside that window therefore cannot reserve stock for demand that has
already been satisfied.

#### Scenario: An allocation outcome advances the order

- **GIVEN** a pending order and an allocation outcome event naming it
- **WHEN** ordering consumes that event
- **THEN** the order and its lines are allocated, and the allocated timestamp is the one the
  event carried

#### Scenario: An outcome for a cancelled order changes nothing and fails nothing

- **GIVEN** an order already cancelled
- **WHEN** an allocation outcome event naming that order is consumed
- **THEN** the order remains cancelled, no exception escapes the handler, and no dead letter is
  produced

#### Scenario: A redelivered outcome is applied once

- **GIVEN** an allocation outcome event that has already been consumed
- **WHEN** the same event is delivered again
- **THEN** the order's status and timestamps are unchanged from the first delivery

#### Scenario: A replenishment inside the lag window does not double-reserve

- **GIVEN** an order just allocated, whose outcome event ordering has not yet consumed
- **WHEN** a replenishment for the same owner, warehouse and SKU is processed
- **THEN** that order does not appear among the candidates, because its lines already hold a
  reservation
