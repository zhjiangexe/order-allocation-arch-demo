# order-intake Specification

## Purpose

TBD - created by archiving change 'add-owner-and-order-line-model'. Update Purpose after archive.

## Requirements

### Requirement: An order carries an owner, an upstream reference, and a delivery commitment

An order SHALL identify the owner whose goods it draws on, the order number assigned
by that owner's upstream system, **the warehouse it ships from**, the destination zone,
the destination address, and the promised delivery date. The destination zone and the
destination address SHALL be separate fields because they serve different consumers:
the address is a fulfillment and label input, the zone is retained as the coarse form
of the destination.

The shipping warehouse SHALL be required. It is supplied by the owner's upstream system
at order creation and the system SHALL NOT derive, default, or revisit it. A missing
warehouse SHALL be rejected rather than resolved.

The address SHALL be held on the order itself rather than in a separate address
entity, because an address is specified per order and is never reused.

#### Scenario: A placed order records owner, upstream reference, warehouse, and destination

- **WHEN** an order is placed with an owner, an upstream order number, a warehouse, a
  destination zone, a destination address, and a promised delivery date
- **THEN** all six are persisted with the order and are returned when that order is
  queried

#### Scenario: An order without a warehouse is rejected

- **WHEN** an order is placed without naming a warehouse
- **THEN** the order is rejected and no order and no line are persisted

---
### Requirement: Order demand is expressed as lines

An order SHALL hold its demand as a collection of lines rather than as a SKU and a
quantity on the order itself. Each line SHALL carry its SKU code, its quantity, its
own line number preserving the upstream document's structure, its own status, and its
own backordered timestamp.

Each line SHALL also carry the owner. This is a denormalisation of the header's owner
and is permitted because the value is immutable for the life of the order: the owner
of an order never changes, so the copy can never diverge. It exists so that a line
references catalog entries by owner and SKU code without joining its header.

A line SHALL NOT be reachable except through its order. Its quantity and status
cannot be changed independently, because the order is the consistency boundary.

#### Scenario: An order exposes its lines and no longer exposes a SKU

- **WHEN** an order is queried
- **THEN** its representation carries a collection of lines, each with a SKU code and
  a quantity, and carries no SKU or quantity of its own

---
### Requirement: Order intake accepts exactly one line per order

Placing an order SHALL be rejected unless it carries exactly one line. This is an
intake policy, not a structural constraint: the persistence schema SHALL permit any
number of lines, and rehydrating an order from storage SHALL NOT enforce the limit.

The asymmetry is deliberate. Rehydration must be able to reconstruct whatever the
database holds, and the schema permits many lines so that relaxing the intake policy
later requires no structural migration. It also means a multi-line order can be
constructed in tests today, keeping the multi-line read path exercised from the start.

#### Scenario: Intake rejects an order that does not carry exactly one line

- **WHEN** an order is placed with zero lines, or with two or more lines
- **THEN** the order is rejected and nothing is persisted

##### Example: line counts at intake and at rehydration

| Lines | Intake | Rehydration |
| --- | --- | --- |
| 0 | rejected | rejected — an order without demand is not a state storage can hold |
| 1 | accepted | accepted |
| 2 | rejected | accepted |

#### Scenario: Rehydration reconstructs a multi-line order

- **GIVEN** stored data describing one order with two lines
- **WHEN** that order is rehydrated
- **THEN** the order is reconstructed with both lines, mapped and serialised
  correctly, even though intake would have rejected it

---
### Requirement: A line's status and backordered timestamp mirror its header

Because an order is fulfilled complete, all of an order's lines reach an allocation
outcome together. When an order is marked allocated, backordered, or cancelled, every
line SHALL be updated in the same transaction and SHALL end in the state the header
records.

A line's backordered timestamp SHALL therefore equal its header's. It is stored on the
line only so that the backorder queue can be filtered and ordered from one table; it
carries no information the header does not already hold.

No corresponding allocated timestamp SHALL be stored on the line. It would likewise
equal the header's, but no query orders by it, so it would be redundant without cause.

#### Scenario: Marking an order backordered stamps its header and its lines alike

- **GIVEN** a rehydrated order with two lines
- **WHEN** the order is marked backordered at a given instant
- **THEN** the header and both lines carry that same instant, and both lines carry the
  backordered status

---
### Requirement: An allocation outcome applies to a whole order, never to part of it

An order SHALL be allocated only when its whole demand can be satisfied. When it cannot,
no part of that order SHALL reserve stock and the whole order SHALL become backordered.

Reserving stock for the satisfiable part of an order that cannot ship would hold
inventory for goods that will not leave, which is why partial reservation is forbidden
rather than merely discouraged.

An order SHALL expose its demand to allocation as quantities aggregated per SKU, not as
a sequence of lines. Allocation therefore has no line to process individually, so
per-line reservation is not merely tested against — it cannot be expressed. With one
line the aggregate holds one entry and behavior is unchanged; with more lines it holds
more, and allocation needs no modification to remain correct.

A partially-allocated order status SHALL NOT exist.

#### Scenario: Demand exceeding available stock reserves nothing at all

- **GIVEN** an order with two lines for the same SKU, each requesting five units, and a
  stock pool that can promise five units
- **WHEN** the order is allocated
- **THEN** no reservation exists for that order and the whole order is backordered

##### Example: aggregated demand decides the outcome

| Lines | Aggregated demand | Available to promise | Reservations created |
| --- | --- | --- | --- |
| 5 + 5, same SKU | 10 | 5 | none — whole order backordered |
| 5 + 5, same SKU | 10 | 10 | one, for the whole demand |
| 5, single line | 5 | 5 | one, for the whole demand |

---
### Requirement: An order line references an existing catalog entry

A line's owner and SKU code together SHALL reference an existing SKU in the catalog.
An order naming a SKU code that the catalog does not hold for that owner SHALL be
rejected and SHALL NOT be persisted.

An order's owner and warehouse together SHALL reference an assignment that exists. An
order naming a warehouse that its owner is not assigned to SHALL be rejected and SHALL
NOT be persisted.

Both SHALL be enforced by the storage layer's referential integrity rather than by a
prior application-level lookup, so that no order can reach storage through a path that
skips the check.

#### Scenario: An order naming an unknown SKU is rejected

- **WHEN** an order is placed whose line names a SKU code that the catalog does not
  hold for that order's owner
- **THEN** the order is rejected and no order and no line are persisted

#### Scenario: An order naming a warehouse its owner is not assigned to is rejected

- **WHEN** an order is placed naming a warehouse that exists but that the order's owner
  is not assigned to
- **THEN** the order is rejected and no order and no line are persisted

##### Example: which orders reach storage

| Owner | Warehouse named | SKU code named | Result |
| --- | --- | --- | --- |
| `OWNER-A` | one `OWNER-A` is assigned to | one `OWNER-A` defines | persisted |
| `OWNER-A` | none | one `OWNER-A` defines | rejected, nothing persisted |
| `OWNER-A` | one only `OWNER-B` is assigned to | one `OWNER-A` defines | rejected, nothing persisted |
| `OWNER-A` | one that does not exist | one `OWNER-A` defines | rejected, nothing persisted |
| `OWNER-A` | one `OWNER-A` is assigned to | one only `OWNER-B` defines | rejected, nothing persisted |

The third and fifth rows are the same mistake in two dimensions: naming something that
exists but belongs to another owner. Neither is caught by existence alone.

---
### Requirement: An upstream order number is unique within its owner

The combination of owner and upstream order number SHALL be unique. Submitting an
order whose owner and upstream order number already exist SHALL fail explicitly.

The system SHALL NOT silently create a second order. In a warehouse a duplicate order
is a physical incident, whereas an error response is only a message.

Returning the existing order instead of failing is idempotent intake and is NOT
required here: until that behavior exists, a resubmission produces a failure, which is
not correct behavior but is safe behavior.

#### Scenario: A resubmitted upstream order number fails without creating a duplicate

- **GIVEN** an order already exists for one owner and one upstream order number
- **WHEN** an order with the same owner and the same upstream order number is
  submitted
- **THEN** the submission fails and exactly one order exists for that pair

---
### Requirement: Backorder queues are scoped to one owner and one SKU

The backordered-demand query SHALL take both an owner and a SKU code, and SHALL return
that owner's backordered lines for that SKU in the order they entered backorder, using
the line identifier as a tie-breaker so repeated queries return an identical sequence.

One owner's queue SHALL NOT be affected by another owner's orders, even when both use
the same SKU code, because in third-party logistics SKU codes collide across owners
and the goods are not interchangeable.

Replenishment SHALL name the owner whose queue it wakes. Without it the scoped query has
no caller able to supply an owner, and the scoping would exist in the schema but never
take effect.

Stock itself remains unscoped: replenished units enter a pool both owners draw from.
Queues are separated before inventory is, and this intermediate state SHALL be recorded
where it can be found rather than left to be discovered.

#### Scenario: Two owners using the same SKU code hold separate queues

- **GIVEN** owner A and owner B each have backordered orders for SKU code `SKU-A`, and
  owner B's orders entered backorder earlier
- **WHEN** owner A's queue for `SKU-A` is read
- **THEN** only owner A's orders are returned, and owner B's earlier orders do not
  appear or affect the ordering

#### Scenario: Replenishment wakes only the named owner's queue

- **GIVEN** owner A and owner B both have backordered orders for SKU code `SKU-A`
- **WHEN** stock is replenished for owner A and SKU code `SKU-A`
- **THEN** only owner A's orders enter the allocation decision, and owner B's orders
  remain backordered

---
### Requirement: Order handling does not depend on the number of lines

Reaching a line by position is correct while intake permits only one line, produces no
failing test, and silently ignores every other line once the policy is relaxed.

A small number of places genuinely need to collapse an order's lines into a single
value — the message key used for event partitioning and the label attached to allocation
retries both admit only one value, which no iteration can supply. These places are
correct today only because an order carries one line.

The order SHALL therefore expose exactly one named operation whose stated meaning is
"this caller assumes a single line", and every such place SHALL obtain its value through
it. Production code SHALL NOT otherwise reach a line by position, and this SHALL be
enforced by an automated check over production sources rather than by review.

The single-line assumption SHALL NOT be enforced by forbidding particular expressions.
An equivalent access written as a stream, or as a loop that stops after its first
iteration, does the same thing and would pass such a check, so enumerating forbidden
forms cannot be complete. Naming the assumption makes it searchable instead: relaxing
the intake policy later requires finding the callers of one operation rather than
auditing every access.

#### Scenario: Positional access outside the named operation fails the build

- **WHEN** production code reaches an order's line by position anywhere other than
  inside the named single-line operation
- **THEN** the automated check fails and identifies the offending source

#### Scenario: The single-line assumption is enumerable

- **WHEN** the callers of the named single-line operation are listed
- **THEN** that list is the complete set of places that must change once intake accepts
  more than one line per order

---
### Requirement: A line inherits its order's warehouse rather than carrying its own

An order line SHALL NOT carry a shipping warehouse. One order ships from one warehouse
and its lines cannot span warehouses, so a warehouse held on the line would be a copy
of the header that can never differ.

This SHALL remain true when orders are allowed to carry several lines. Several lines
mean several SKUs on one order, not several warehouses; per-line warehouses would only
be needed to split an order across warehouses, which the system does not do.

The line's denormalized owner SHALL be retained, because unlike the warehouse it earns
its place: the owner and SKU code together form the catalog's natural key, and the
foreign key cannot be expressed without it.

#### Scenario: A line reports the warehouse of its order

- **WHEN** an order's lines are queried
- **THEN** no line carries a warehouse of its own, and the order's warehouse applies to
  every line
