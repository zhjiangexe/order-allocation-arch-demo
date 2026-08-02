## MODIFIED Requirements

### Requirement: A line's status mirrors its header, and no timestamp is stored on it

Because an order is fulfilled complete, all of an order's lines reach an allocation outcome
together. A line SHALL therefore hold no status of its own: the header's status is the
line's status, and reading a line's status SHALL mean reading the header's.

Storing it per line was justified by a use that does not exist. The reason recorded on the
type was that a multi-line order could then show a status per line without changing the
published contract — but whole-order fulfilment guarantees those values are identical, and
that guarantee does not weaken when more lines are allowed. Two rows holding the same value
by construction are one value stored twice.

**The published contract SHALL keep exposing a status per line**, derived from the header at
read time. The value is unchanged, so nothing downstream changes; what changes is that
there is one place it can be wrong instead of two.

No backordered timestamp and no allocated timestamp SHALL be stored on the line. Both would
equal the header's, and neither is read.

A backordered timestamp was previously stored on the line so that the backorder queue could
be filtered and ordered from one table. **That query was never single-table.** It joins
`orders` to `order_lines`, filters on the line's owner and SKU code, and takes both its
predicate and its ordering from the header — so the column was never reached, and the index
built over it could never serve the ordering it existed for.

The ordering key SHALL be the order identifier, which already encodes when the order entered
the system.

#### Scenario: A line reports the status its header carries

- **GIVEN** an order marked backordered
- **WHEN** its lines are read
- **THEN** every line reports the backordered status
- **AND** no line holds a status of its own

#### Scenario: Advancing an order advances what its lines report

- **GIVEN** a backordered order whose lines report the backordered status
- **WHEN** the order is marked allocated
- **THEN** every line reports the allocated status without any line being written
