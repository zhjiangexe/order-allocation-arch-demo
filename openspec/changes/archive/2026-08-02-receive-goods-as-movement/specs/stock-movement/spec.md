## ADDED Requirements

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
