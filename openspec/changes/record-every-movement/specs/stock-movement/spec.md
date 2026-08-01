## ADDED Requirements

### Requirement: Every movement of goods is recorded with both of its ends

A movement SHALL record where the goods come from and where they go, as two
locations, and SHALL carry a state saying how far it has got. Neither end may be
absent.

Recording only one end is what the system does today: a reservation says which
batch was locked for which order line, and never says where those goods are bound.
The missing half has to be supplied the moment goods actually leave, and supplying
it then means reinterpreting every reservation already recorded.

Both ends being locations — rather than one being a location and the other implied
— is what makes the total quantity across all places conserved. Conservation is
what turns "the numbers changed and nobody knows why" from an unanswerable
question into a detectable one.

#### Scenario: A movement without a destination cannot be recorded

- **WHEN** a movement is written with a source but no destination
- **THEN** the write is refused

#### Scenario: A movement records the batch it actually draws on

- **GIVEN** an order line needing stock that is held as two batches
- **WHEN** the movement for that line is satisfied from both
- **THEN** the movement carries one line per batch, each naming the batch and the
  quantity taken from it

---

### Requirement: Demand that has no stock yet is a movement, not an absence

A movement SHALL be created for every order line when the order is taken, **even
when no stock is available for it**. Such a movement SHALL be in a state meaning
"needs goods, has not got them".

The alternative — creating a movement only once stock is found — leaves demand
with nowhere to live, so "still waiting" stays a query result rather than a
record. An order that can never be satisfied then leaves no trace at all: finding
it means comparing orders against reservations and inspecting the difference.

Making the waiting movement a real row is what lets the queue be read directly
instead of derived, and what makes an order stuck for a month visible as a row
that has not changed state.

#### Scenario: An order taken with no stock still produces movements

- **GIVEN** no allocatable stock for the ordered goods
- **WHEN** the order is taken
- **THEN** a movement exists for each of its lines, each stating that it needs
  goods and has not got them

#### Scenario: The allocation queue is read from movement state

- **WHEN** the movements waiting for a given owner, location and goods are queried
- **THEN** exactly the movements needing goods are returned, without consulting
  the order's own status

---

### Requirement: Movement state covers only transitions this system performs

Movement state SHALL be drawn from a fixed set: needing goods, goods assigned,
done, and cancelled. The set SHALL be constrained by the database.

**"Done" SHALL be part of the value set even though nothing produces it yet.**
Downstream predicates have to be written against the complete set now: an order
line is "already taken up" when a movement exists for it, and a completed movement
counts. Were the completed state introduced later, every shipped order would
become outstanding demand again on the day shipping arrives — and no test would
fail at the moment the mistake was made.

**A state meaning "waiting for an earlier movement" SHALL NOT be introduced.**
There are no chained movements: nothing produces a second leg. That state arrives
together with the relation that expresses which movement precedes which, and the
two are halves of the same thing.

**A partially-available state SHALL NOT be introduced.** An order is satisfied
wholly or not at all, so partial availability is not a state anything rests in.

#### Scenario: A state outside the set is refused

- **WHEN** a movement is written with a state outside the permitted set
- **THEN** the write is refused by the database

#### Scenario: A completed movement does not reopen its demand

- **GIVEN** an order line whose movement has completed
- **WHEN** outstanding demand is queried
- **THEN** that line is absent

---

### Requirement: Reservation is a stage of a movement, not a parallel ledger

Reserved stock SHALL be expressed as the lines of a movement whose state says the
goods are assigned. There SHALL NOT be a separate reservation record with its own
lifecycle.

**Releasing SHALL remove the movement's lines rather than marking them released.**
A released reservation states nothing: the goods did not move and are not held.
Keeping it obliges every reader to remember to filter it out, and the current
view's `status IN ('ACTIVE','CONSUMED')` predicate is exactly the shape that
obligation takes.

The cost is explicit: the history of a release no longer sits on the line. It sits
on the movement's state, which is where the history of a movement belongs.

#### Scenario: Releasing assigned goods returns them to available

- **GIVEN** a movement whose goods are assigned from a batch
- **WHEN** it is released
- **THEN** the batch's available quantity returns to what it was, and the movement
  no longer names that batch

#### Scenario: Released goods are not double-counted as still held

- **GIVEN** a movement that was assigned and then released
- **WHEN** what the owner holds is queried
- **THEN** the released quantity appears as available rather than reserved

---

### Requirement: An operation type says where its movements run between

An operation type SHALL carry a code saying whether it brings goods in, sends them
out, or moves them internally, together with the default source and destination
locations for that kind of work.

This is what keeps "a new kind of operation" a row of data rather than a code
change. The failure it avoids is well documented in systems that did not do it:
an outbound type enumeration grown to eighteen values, each added by editing and
redeploying.

**It does not say what follows what.** An operation type has no reference to a
next type; sequencing movements into a chain is a separate concern that this
system does not have. What it settles is what one leg looks like.

#### Scenario: A new kind of operation needs no code change

- **WHEN** an operation type is added with its own code and default locations
- **THEN** movements can be recorded under it without altering any enumeration

---

### Requirement: A dispatch document is not shared between orders

Movements for one order SHALL be gathered under one dispatch document, and
documents SHALL NOT be shared across orders.

**Were documents ever shared, the grouping key would have to include the owner.**
Grouping by source, destination and operation type alone — which is what
general-purpose warehouse systems do, because their isolation boundary is the
legal entity rather than the goods' owner — would put two owners' movements on one
document. That is not a defect to fix later: it is the defining feature of
third-party warehousing that one place serves many owners.

Stating it now costs nothing and prevents the rule being reconstructed wrongly the
day merging is wanted.

#### Scenario: Two orders never share a document

- **GIVEN** two orders from different owners shipping from the same location
- **WHEN** their movements are recorded
- **THEN** each order's movements sit on their own document
