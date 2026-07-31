## ADDED Requirements

### Requirement: Allocation takes its demand from a published view, never from the order aggregate

Allocation SHALL obtain what it has to satisfy from a `demand_lines` view published on the
ordering side, mapped into its own read-only types. It SHALL NOT reference the order
aggregate, and its code SHALL NOT name the `orders` or `order_lines` tables — in imports,
in SQL strings, or in any other form. Checking imports alone does not stop code that
bypasses the type and writes the table directly.

Allocation's own type SHALL carry the order identifier, the owner, the warehouse, when the
order was received, and the lines. It SHALL NOT carry the order's status. Ordering's
record of the allocation outcome trails allocation's own decision, because it is advanced
by an event; using it as a gate would let a second replenishment read the same demand and
reserve stock for it twice.

Its name SHALL NOT contain `Order`. It describes the same real-world order as the ordering
aggregate but is a different model of it — read-only, five fields, no behaviour, no
lifecycle — and a name suggesting otherwise invites the question of why it lacks a status.
Holding an order identifier is how reservations and events are addressed, not evidence that
the type is an order.

**A query for a queue SHALL return whole orders, each with all of its outstanding lines**,
not the lines that matched the SKU being asked about. An order is satisfied wholly or not
at all, so a decision needs every line of it; a result filtered to one SKU cannot express
that question. This SHALL hold while intake permits one line per order, so that relaxing
that limit does not require the query, the view or the types to be rewritten.

The wake limit SHALL count orders, matching the unit the query returns.

#### Scenario: The allocation module does not reach into ordering

- **WHEN** allocation's sources are inspected
- **THEN** no file imports the order aggregate, and no file contains the `orders` or
  `order_lines` table names

#### Scenario: A queue entry carries every outstanding line of its order

- **GIVEN** an order with outstanding demand for two different SKUs
- **WHEN** the queue for one of those SKUs is read
- **THEN** the returned entry for that order carries both lines

#### Scenario: Demand is scoped and ordered by the view's caller

- **GIVEN** outstanding demand across two owners, two warehouses and two SKU codes
- **WHEN** a queue is read for one owner, warehouse and SKU code
- **THEN** only that combination is returned, in the order the orders entered the system

---
### Requirement: Outstanding demand is decided by reservations, not by an order's status

What is still owed SHALL be derived from the absence of a reservation for that line, using
the reservation statuses that mean the goods are committed: active, and consumed. A line
with such a reservation SHALL disappear from the view.

Consumed SHALL be included even though nothing produces that status yet. It is written when
stock leaves on shipment; a predicate naming only the active status would let every shipped
order reappear as outstanding demand and be allocated a second time. No test would catch
this at the time the status is introduced, because shipment does not exist yet.

Cancellation SHALL be excluded by the order's cancellation being recorded, which ordering
owns and writes synchronously, so it is immediately correct. This is the division: ordering
is authoritative for what was ordered and whether it was cancelled; allocation is
authoritative for what it has satisfied.

Extending the set of reservation statuses SHALL require re-examining this predicate. It is
expressed in one place for that reason — spread across application code, one of the copies
would be missed.

#### Scenario: A line with an active reservation is not outstanding

- **GIVEN** an order line holding an active reservation
- **WHEN** outstanding demand is read
- **THEN** that line does not appear

#### Scenario: A line whose reservation was consumed is not outstanding

- **GIVEN** an order line whose reservation has been consumed
- **WHEN** outstanding demand is read
- **THEN** that line does not appear

#### Scenario: A line whose reservation was released is outstanding again

- **GIVEN** an order line whose reservation was released
- **WHEN** outstanding demand is read
- **THEN** that line appears

#### Scenario: A cancelled order holds no outstanding demand

- **GIVEN** a cancelled order with lines that hold no reservation
- **WHEN** outstanding demand is read
- **THEN** none of its lines appear

---
### Requirement: Allocation publishes its outcome and writes only its own tables

Allocation SHALL record an outcome by writing its own tables and publishing an integration
event. It SHALL NOT load, mutate or save the order aggregate, and one transaction SHALL
modify one aggregate.

The event SHALL be the only channel by which the outcome reaches ordering. Stating the same
outcome both through a shared transaction and through an event would require the two to be
kept in agreement, and only one of them has a consumer.

#### Scenario: A completed allocation touches only allocation's tables

- **WHEN** an order is allocated
- **THEN** the stock row and the reservation are written, an outcome event is appended for
  publication, and no row in `orders` or `order_lines` is modified in that transaction

#### Scenario: A backorder is recorded the same way

- **WHEN** demand cannot be satisfied in full
- **THEN** no reservation is created, a backorder event is appended for publication, and no
  row in `orders` or `order_lines` is modified in that transaction
