## ADDED Requirements

### Requirement: An order carries an owner, an upstream reference, and a delivery commitment

An order SHALL identify the owner whose goods it draws on, the order number assigned
by that owner's upstream system, the destination zone, the destination address, and
the promised delivery date. The destination zone and the destination address SHALL be
separate fields because they serve different consumers: the zone is a sourcing input,
the address is a fulfillment and label input.

The address SHALL be held on the order itself rather than in a separate address
entity, because an address is specified per order and is never reused.

#### Scenario: A placed order records owner, upstream reference, and destination

- **WHEN** an order is placed with an owner, an upstream order number, a destination
  zone, a destination address, and a promised delivery date
- **THEN** all five are persisted with the order and are returned when that order is
  queried

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

An order SHALL be allocated only when every one of its lines can be satisfied. When any
line cannot be satisfied, no line of that order SHALL reserve stock and the whole order
SHALL become backordered.

Reserving stock for the satisfiable lines of an order that cannot ship would hold
inventory for goods that will not leave, which is why partial reservation is forbidden
rather than merely discouraged.

A partially-allocated order status SHALL NOT exist.

#### Scenario: One unsatisfiable line prevents every line from reserving

- **GIVEN** an order with two lines whose first line can be satisfied from stock and
  whose second line cannot
- **WHEN** the order is allocated
- **THEN** neither line holds a reservation and the whole order is backordered

##### Example: reservations after allocating a two-line order

| Line | Requested | Stock available | Reservation held |
| --- | --- | --- | --- |
| 1 | 5 | 10 | none |
| 2 | 5 | 0 | none |

---
### Requirement: An order line references an existing catalog entry

A line's owner and SKU code together SHALL reference an existing SKU in the catalog.
An order naming a SKU code that the catalog does not hold for that owner SHALL be
rejected and SHALL NOT be persisted.

This SHALL be enforced by the storage layer's referential integrity rather than by a
prior application-level lookup, so that no order can reach storage through a path that
skips the check.

#### Scenario: An order naming an unknown SKU is rejected

- **WHEN** an order is placed whose line names a SKU code that the catalog does not
  hold for that order's owner
- **THEN** the order is rejected and no order and no line are persisted

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

#### Scenario: Two owners using the same SKU code hold separate queues

- **GIVEN** owner A and owner B each have backordered orders for SKU code `SKU-A`, and
  owner B's orders entered backorder earlier
- **WHEN** owner A's queue for `SKU-A` is read
- **THEN** only owner A's orders are returned, and owner B's earlier orders do not
  appear or affect the ordering

---
### Requirement: Order handling does not depend on the number of lines

Production code SHALL NOT reach a line by position. Retrieving an order's first line
positionally is correct while intake permits only one line, produces no failing test,
and silently ignores every other line once the policy is relaxed.

This SHALL be enforced by an automated check over production sources, not by review.

#### Scenario: Positional access to a line fails the build

- **WHEN** production code retrieves an order's line by index or by a first-element
  accessor
- **THEN** the automated check fails and identifies the offending source
