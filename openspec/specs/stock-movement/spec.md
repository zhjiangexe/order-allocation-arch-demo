# stock-movement Specification

## Purpose

TBD - created by archiving change 'record-every-movement'. Update Purpose after archive.

## Requirements

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

---
### Requirement: Recording a movement is a step of its own

Creating a dispatch document and the movements under it SHALL be reachable without any
allocation taking place, and SHALL NOT require demand as its only possible input.

This is what makes receiving expressible. Goods arriving from a supplier are a movement —
vendors to stock — and nothing about them is allocated: there is no order, no availability
question, no whole-order rule. If recording only exists inside the path that satisfies
orders, receiving has two ways forward and both are wrong: copy the recording logic, or
route receiving through a step named for allocation.

The step SHALL resolve where the movements run between from the operation type of the
warehouse, and SHALL fail loudly when that warehouse has no operation type for the
direction asked for. Accepting the work and quietly recording nothing would make the demand
disappear without trace — it would appear in no queue, because queues are read from
movements.

The step SHALL return what it created, so that a caller which goes on to assign stock does
not have to read the same rows back.

#### Scenario: Recording happens without any allocation

- **GIVEN** a warehouse with an operation type for the direction being recorded
- **WHEN** movements are recorded
- **THEN** a dispatch document and its movements exist, each needing goods
- **AND** no stock has been drawn on and no availability was consulted

#### Scenario: A warehouse without an operation type refuses the work

- **GIVEN** a warehouse with no operation type for the direction being recorded
- **WHEN** recording is attempted
- **THEN** it fails
- **AND** no dispatch document and no movement are left behind

#### Scenario: What was recorded is handed back to the caller

- **GIVEN** movements have just been recorded for an order
- **WHEN** the caller goes on to assign stock to them
- **THEN** it uses the movements it was given
- **AND** does not read them back by the identifier of the demand they came from

---
### Requirement: Goods arriving are a movement from a supplier

Stock arriving SHALL be recorded as a movement from a supplier location into the warehouse's
internal location, under a dispatch document of the inbound operation type. That document
SHALL carry no order, because nothing was ordered through this system — the goods are the
owner's, arriving on the owner's arrangement.

This is the case the nullable order reference on a dispatch document exists for. Until now
every document served an order; the field was nullable in anticipation, and this requirement
is where that anticipation is paid.

Receiving SHALL NOT consult availability, reserve anything, or apply the whole-order rule.
Those belong to satisfying demand, and nothing is being satisfied here.

#### Scenario: Arriving goods produce a document with no order

- **GIVEN** a replenishment for a SKU at a warehouse
- **WHEN** it is recorded
- **THEN** a dispatch document of the inbound type exists with no order
- **AND** its movement runs from a supplier location to that warehouse's internal location

#### Scenario: An inbound movement is not outstanding demand

- **GIVEN** an inbound movement exists
- **WHEN** the queue of movements needing goods is read
- **THEN** that movement is not offered for allocation, because its document serves no order

---
### Requirement: Stock on hand changes only through a completed movement line

The quantity a stock row holds SHALL change only when a movement line says it did, and a
movement line SHALL name the stock row it applies to. Code that can reach stock SHALL NOT be
able to change what is held without such a line.

This is the whole point of recording movements. Without it, any code holding the stock
repository can change the numbers, and a wrong change leaves no trace — the three questions
the movement tables exist to answer (where did this come from, why did it drop, which
document moved it) stay unanswerable for anything that took the shortcut.

The line, not the movement, SHALL be what applies the change. A movement states what is to happen;
a line states what did, and to which row. Odoo draws the same line: its move-level
completion filters and flips state, and delegates the quant change to the lines.

**Only the increase is in scope.** Completion of an outgoing movement — the decrease —
arrives with shipping. A symmetric implementation where half has no caller would rot; the
uncalled half SHALL fail loudly rather than exist untested.

#### Scenario: Stock cannot be increased without a line

- **GIVEN** a stock row
- **WHEN** something attempts to increase what it holds without a movement line naming it
- **THEN** it cannot — the operation does not exist

#### Scenario: A line applying to a different row is refused

- **GIVEN** a movement line naming one stock row
- **WHEN** it is applied to a different row
- **THEN** it is refused

#### Scenario: Completing an inbound movement increases what is held

- **GIVEN** an inbound movement with a line naming a stock row
- **WHEN** the movement completes
- **THEN** that row holds the line's quantity more than before
- **AND** the movement is done

#### Scenario: Completing an outgoing movement is refused for now

- **GIVEN** a movement whose source is an internal location
- **WHEN** completion is attempted
- **THEN** it fails, because the decrease is not implemented until shipping exists
