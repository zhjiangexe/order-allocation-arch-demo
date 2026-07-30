## MODIFIED Requirements

### Requirement: Stock pool state is queryable by SKU

The stock-pool query endpoint SHALL return, for a given owner and SKU, **the rows that
SKU is held in** — each carrying its warehouse, arrival date, expiry date, on-hand
quantity, reserved quantity, available-to-promise quantity, and whether it has expired.
The available-to-promise field SHALL be named after the domain concept rather than an
abbreviation, matching the vocabulary already used in the allocation domain model.

The endpoint SHALL take an owner. A SKU code alone no longer identifies stock — it
collides across owners, and the reply would mix two owners' goods into one list.

Expired rows SHALL be present in the response and marked, not omitted.
Omitting them makes "we hold 100 units but can ship none" indistinguishable from "we
hold nothing".

**The rows SHALL be ordered by warehouse, then by expiry date, then by arrival date,
then by identity.** Within one warehouse that is exactly the order allocation would draw
on them; grouping by warehouse first is what makes that meaningful, because allocation
never spans warehouses — each run is scoped to one. A single list sorted by expiry across
all warehouses would suggest a consumption order that no allocation will ever follow.

The ordering SHALL be a guarantee of this endpoint rather than left to callers. The
consumer displaying these rows cannot reconstruct it: the tie-breaks reach down to row
identity, which exists to make the order reproducible and carries no meaning a caller
could sort on.

An owner and SKU with no stock at all SHALL yield `404`.

#### Scenario: Rows are grouped by warehouse and ordered by expiry within each

- **GIVEN** an owner holds one SKU in two warehouses, each as several rows of differing
  expiry
- **WHEN** that owner's stock for that SKU is queried
- **THEN** the rows of each warehouse are contiguous, and within each warehouse the
  earliest-expiring row comes first

#### Scenario: A SKU held in three rows reports each of them

- **GIVEN** an owner holds one SKU as three rows of differing expiry
- **WHEN** that owner's stock for that SKU is queried
- **THEN** the response carries three entries, each with its own warehouse, arrival
  date, expiry date and quantities

#### Scenario: An expired row is returned and marked

- **GIVEN** one of an owner's rows for a SKU has passed its expiry date
- **WHEN** that owner's stock for that SKU is queried
- **THEN** that row appears in the response and is marked as expired

#### Scenario: An owner holding none of a SKU is reported as not found

- **WHEN** stock is queried for an owner and SKU with no rows
- **THEN** the response is `404`
