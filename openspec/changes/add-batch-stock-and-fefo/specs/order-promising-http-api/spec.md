## MODIFIED Requirements

### Requirement: Stock pool state is queryable by SKU

The stock-pool query endpoint SHALL return, for a given owner and SKU, **the rows that
SKU is held in** — each carrying its warehouse, arrival date, expiry date, on-hand
quantity, reserved quantity, available-to-promise quantity, and whether it is sellable.
The available-to-promise field SHALL be named after the domain concept rather than an
abbreviation, matching the vocabulary already used in the allocation domain model.

The endpoint SHALL take an owner. A SKU code alone no longer identifies stock — it
collides across owners, and the reply would mix two owners' goods into one list.

Rows that are not sellable SHALL be present in the response and marked, not omitted.
Omitting them makes "we hold 100 units but can ship none" indistinguishable from "we
hold nothing".

An owner and SKU with no stock at all SHALL yield `404`.

#### Scenario: A SKU held in three rows reports each of them

- **GIVEN** an owner holds one SKU as three rows of differing expiry
- **WHEN** that owner's stock for that SKU is queried
- **THEN** the response carries three entries, each with its own warehouse, arrival
  date, expiry date and quantities

#### Scenario: An expired row is returned and marked

- **GIVEN** one of an owner's rows for a SKU has passed its expiry date
- **WHEN** that owner's stock for that SKU is queried
- **THEN** that row appears in the response and is marked as not sellable

#### Scenario: An owner holding none of a SKU is reported as not found

- **WHEN** stock is queried for an owner and SKU with no rows
- **THEN** the response is `404`
