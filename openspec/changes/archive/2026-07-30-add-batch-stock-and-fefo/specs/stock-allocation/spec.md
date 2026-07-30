## ADDED Requirements

### Requirement: Stock is held per owner, warehouse, arrival and expiry

A stock row SHALL be identified by its owner, its warehouse, its SKU code, the date the
goods arrived, and the date they expire. Two rows differing in any one of those five are
different stock and SHALL NOT be merged.

The expiry date SHALL be mandatory. A nullable expiry inside a uniqueness constraint is
a trap in PostgreSQL, where NULLs compare as distinct: two same-day arrivals of a
non-perishable SKU would become two rows rather than one, silently.

The arrival date SHALL participate in identity rather than being a mere attribute.
Keeping it in identity means every replenishment either matches an existing row exactly
or creates a new one — there is no merge rule to define, and therefore none to get
wrong.

Quantities SHALL be held per row: on-hand and reserved. Available-to-promise SHALL be
derived from them at read time and SHALL NOT be stored, because a stored derivation is
a second source of truth that can disagree with the first.

#### Scenario: Two owners holding the same SKU code hold separate stock

- **GIVEN** two owners each hold stock of the same SKU code in the same warehouse
- **WHEN** one owner's order consumes that stock
- **THEN** the other owner's available-to-promise is unchanged

#### Scenario: Same-day arrivals of different expiry stay separate

- **WHEN** two deliveries of one SKU arrive at one warehouse on the same day with
  different expiry dates
- **THEN** they are held as two rows, each carrying its own expiry

#### Scenario: An identical arrival adds to the existing row

- **GIVEN** stock exists for an owner, warehouse, SKU, arrival date and expiry date
- **WHEN** a replenishment arrives naming all five identically
- **THEN** its quantity is added to that row and no second row is created

---

### Requirement: A stock row references a SKU its owner actually holds

A stock row's owner and SKU code together SHALL reference an existing SKU in the
catalog. A row naming a SKU code the catalog does not hold for that owner SHALL be
rejected and SHALL NOT be persisted.

This SHALL be enforced by the storage layer's referential integrity, for the same
reason an order line's SKU reference is: SKU codes collide across owners, so a
reference that carries the owner is the only one that can be checked. Without it, a
mistyped code produces stock that exists but can never be allocated — a symptom that
takes a long time to trace back to its cause.

#### Scenario: Stock naming another owner's SKU code is rejected

- **GIVEN** a SKU code exists in the catalog for one owner only
- **WHEN** stock is written naming that code under a different owner
- **THEN** the write is rejected and no stock row is persisted

---

### Requirement: Expired stock is present but not allocatable

Stock whose expiry date has passed SHALL remain in the system and SHALL be excluded
from allocation. It SHALL NOT be deleted and SHALL NOT be silently omitted from
queries.

Deleting it would destroy the record of goods physically present in the warehouse.
Omitting it from queries would make "we have 100 units but can ship none" indis-
tinguishable from "we have nothing" — and those two states call for different actions.

Stock that allocation may draw on SHALL be exactly the stock that is both unexpired
and not already fully reserved. A row whose entire quantity is reserved is, to
allocation, indistinguishable from one that does not exist; treating the two
differently would oblige every caller to remember to skip it.

Expiry SHALL be expressed as a property of the row — whether it has expired — rather
than as a verdict on what may be done with it. The verdict depends on quantity as
well, and folding the two together loses the distinction the previous paragraph
insists on. Nor SHALL the term "sellable" be used: a third-party logistics provider
does not sell the goods, the owner does; the question a warehouse answers is whether
goods can ship.

#### Scenario: An order is not satisfied from expired stock

- **GIVEN** the only stock for a SKU expired yesterday
- **WHEN** an order for that SKU is allocated
- **THEN** the order is backordered, and the expired stock's quantity is unchanged

#### Scenario: Expired stock remains visible and marked

- **WHEN** stock for a SKU is queried
- **THEN** expired rows appear in the response, marked as expired

#### Scenario: A fully reserved row is not drawn on

- **GIVEN** the only unexpired stock for a SKU is entirely reserved for other orders
- **WHEN** a further order for that SKU is allocated
- **THEN** the order is backordered and that row's reserved quantity is unchanged

---

### Requirement: Allocation consumes the earliest-expiring stock first

Allocation SHALL take stock in order of expiry date, earliest first. Where two rows
share an expiry date, the earlier arrival SHALL be taken first; where those also match,
the order SHALL still be deterministic.

The second and third ordering keys are not decoration. Same-expiry rows are common —
one production batch delivered on two days produces exactly that. Without a total
order, the same stock and the same order allocate differently between runs, and the
write ordering that prevents deadlocks has nothing stable to sort by.

#### Scenario: A demand spanning two rows takes the nearer expiry first

- **GIVEN** stock of 60 units expiring in one month and 40 units expiring in six
- **WHEN** an order for 80 units is allocated
- **THEN** all 60 of the nearer-expiry stock and 20 of the later are taken

#### Scenario: Same-expiry rows are taken oldest arrival first

- **GIVEN** two rows share an expiry date and differ in arrival date
- **WHEN** an order smaller than either row is allocated
- **THEN** the earlier-arrived row is the one consumed

#### Scenario: Repeating an allocation reproduces the same choice

- **GIVEN** the same stock rows and the same order
- **WHEN** the allocation is performed again from the same starting state
- **THEN** the same rows are consumed in the same order

---

### Requirement: An order is satisfied wholly or not at all

An order SHALL be allocated only when its entire demand can be met from allocatable
stock.
When it cannot, no stock SHALL be reserved for it and the whole order SHALL become
backordered.

This is the existing ship-complete rule, restated because batching makes it easy to
violate by accident: taking 60 of the 80 units needed leaves 60 units locked for an
order that cannot ship, while a later smaller order that could have shipped finds
nothing.

#### Scenario: A partially satisfiable order reserves nothing

- **GIVEN** allocatable stock totals 50 units across two rows
- **WHEN** an order for 80 units is allocated
- **THEN** the order is backordered and both rows' reserved quantities are unchanged

---

### Requirement: A reservation is held per order line and per stock row

A reservation SHALL reference one order line and one stock row. A line drawing on three
rows SHALL produce three reservations.

A single reservation carrying a list of rows was considered and rejected: release and
consumption both happen per row — one row is picked before another — and a
list-carrying reservation cannot express partial consumption. That is precisely what
the physical and logical ledgers must reconcile later.

#### Scenario: A line spanning two rows produces two reservations

- **WHEN** one order line is satisfied from two stock rows
- **THEN** two reservations exist, each naming that line and one of the rows, and their
  quantities sum to the line's quantity

---

### Requirement: Seed data makes every allocation outcome reproducible

Seed data SHALL include, for one SKU, three unexpired rows of near, middle and far
expiry, of which **two share an expiry date and differ in arrival date**, plus one
expired row. It SHALL include an order whose demand spans more than one row.

Each element exists to make one behaviour observable: the three expiries make the
ordering visible, the shared expiry is the only way the tie-break is exercised at all,
the expired row is the "present but not allocatable" case, and the spanning order is the
only way multi-row consumption and multi-reservation are seen to happen.

#### Scenario: Seeded stock exercises the tie-break

- **WHEN** the seeded stock for that SKU is inspected
- **THEN** two rows share an expiry date and differ in arrival date
