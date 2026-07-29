## MODIFIED Requirements

### Requirement: Stock state and replenishment share one page keyed by SKU

The stock page SHALL query and replenish through one SKU field, because they are one
continuous action: see that nothing can be promised, add stock, look again.

**The query result SHALL be a list of stock rows, not a single set of numbers.** Each
row SHALL show its warehouse, arrival date, expiry date, on-hand, reserved,
available-to-promise, and whether it is sellable. Rows that are not sellable SHALL be
shown with the reason rather than hidden — hiding them makes "we hold stock we cannot
ship" look identical to "we hold nothing", and those call for different actions.

The rows SHALL be listed in the order allocation would consume them, so that the page
answers "which of these goes first" without the viewer reconstructing the rule.

Both query and replenishment SHALL require an owner. Stock is held per owner; a SKU
code alone names two owners' goods at once.

Replenishment SHALL additionally require a warehouse, an arrival date and an expiry
date, because those complete the identity of the row being added to. The form SHALL
prevent submission when any of them is missing rather than relying on the backend to
reject it.

#### Scenario: A SKU held in three rows shows three rows in consumption order

- **WHEN** the viewer queries a SKU their selected owner holds in three rows
- **THEN** three rows appear, ordered as allocation would consume them, each showing
  its warehouse, dates and quantities

#### Scenario: An expired row is shown with its reason

- **GIVEN** one of the rows has passed its expiry date
- **WHEN** the viewer queries that SKU
- **THEN** that row is present and marked as not sellable, showing that it expired

##### Example: replenishment fields required before submission

| Owner | Warehouse | Arrival date | Expiry date | Quantity | Result |
| --- | --- | --- | --- | --- | --- |
| selected | selected | set | set | 1 | submitted |
| selected | not selected | set | set | 1 | blocked in the form, no request sent |
| selected | selected | missing | set | 1 | blocked in the form, no request sent |
| selected | selected | set | missing | 1 | blocked in the form, no request sent |
| not selected | — | — | — | 1 | blocked in the form, no request sent |
