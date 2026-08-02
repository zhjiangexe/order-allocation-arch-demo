## MODIFIED Requirements

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

**Resolving that identity SHALL happen while completing an inbound movement, and a row
SHALL be opened holding nothing.** The five dimensions and the rule over them are unchanged;
what changes is who applies them. Arriving goods previously had two paths — add to the
matching row, or create a row already holding the arrival — and the second was a way to put
stock into the system without recording a movement. There is now one path: find or open the
row, then let the movement's line put the quantity into it.

A row holding nothing is a normal state, not a defect. It is what a stock row looks like
between being identified and being filled, and it is also what remains after everything in
it has been shipped.

#### Scenario: Two owners holding the same SKU code hold separate stock

- **GIVEN** two owners each hold stock of the same SKU code in the same warehouse
- **WHEN** their stock is read
- **THEN** each owner sees only their own

#### Scenario: A replenishment matching all five dimensions adds to the existing row

- **GIVEN** a stock row for an owner, location, SKU, arrival date and expiry date
- **WHEN** a replenishment arrives naming all five identically
- **THEN** that row holds more than before
- **AND** no second row is created

#### Scenario: A replenishment differing in arrival date opens a new row

- **GIVEN** a stock row for an owner, location, SKU, arrival date and expiry date
- **WHEN** a replenishment arrives naming a different arrival date
- **THEN** a second row exists
- **AND** the two are allocated from separately, earliest expiry first

#### Scenario: A newly opened row holds nothing until a movement line fills it

- **GIVEN** a replenishment naming five dimensions that match no existing row
- **WHEN** the row is opened
- **THEN** it holds nothing
- **AND** it holds the arrival's quantity only once the movement's line has been applied
