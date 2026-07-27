## MODIFIED Requirements

### Requirement: The console presents orders and stock as two navigable pages

The console SHALL expose exactly two routed pages: an orders page and a stock
page. The orders page SHALL be the default route. A shared header SHALL be
present on both pages and SHALL display the partition-key strategy currently in
effect, so a viewer can tell whether the running system uses the order-identifier
strategy or the SKU strategy without leaving the console.

The orders page's list SHALL show every field of the order representation, so that
inspecting one order requires no further navigation, modal, or detail view. This
includes the owner: each row SHALL name the owner the order belongs to, taken from the
order response rather than resolved separately.

Because intake accepts exactly one line per order, each row SHALL correspond to one
order and SHALL render that order's single line inline. The row SHALL identify the
line's goods as the product name together with the specification name rather than as a
bare SKU code, since a SKU code alone is meaningless without its owner.

#### Scenario: Opening the console lands on the orders page with the strategy visible

- **WHEN** the console is opened at its root path
- **THEN** the orders page is shown, and the header reports the effective
  partition-key strategy retrieved from the backend

##### Example: header text for each backend configuration

| Backend `archone.allocation.partition-key-strategy` | Header reports |
| --- | --- |
| `order-id` (default) | the order-identifier strategy |
| `sku` | the SKU strategy |

#### Scenario: Navigating between the two pages preserves the header

- **WHEN** the viewer navigates from the orders page to the stock page
- **THEN** the stock page is shown with the same header, and the strategy is not
  re-requested on every navigation

##### Example: configuration requests across a navigation sequence

- **GIVEN** the console has finished its initial load
- **WHEN** the viewer navigates orders → stock → orders
- **THEN** the configuration request count is unchanged from what the initial
  load produced — navigating adds none

#### Scenario: A listed order names its owner and its goods in readable form

- **GIVEN** two owners each have an order for a SKU code they both define
- **WHEN** the orders page lists them
- **THEN** each row names its own owner, and each row describes its goods as the
  product name with the specification name, so the two rows are distinguishable

---
### Requirement: Placing an order shows the result in the list on the same page

The orders page SHALL provide a form taking an owner, an upstream order number, a
destination zone, a destination address, a promised delivery date, and the goods
ordered. The goods SHALL be chosen in two steps — a product, then one of that
product's specifications — rather than typed as a SKU code, so that an unknown or
cross-owner SKU code cannot be submitted at all.

The selectable products SHALL be those of the selected owner, and the selectable
specifications SHALL be those of the selected product. Changing the owner SHALL discard
a product and specification chosen under the previous owner, since neither is valid
under a different owner.

On success the newly created order SHALL become visible in the recent-orders list on
that same page without navigation. The submitted quantity SHALL be sent as a number,
and the form SHALL prevent submission of a non-positive quantity or of an incomplete
selection rather than relying on the backend to reject it.

#### Scenario: A submitted order appears in the list as PENDING

- **WHEN** the viewer submits the form with an owner, an upstream order number, a
  destination, a promised delivery date, a selected specification, and a valid quantity
- **THEN** the recent-orders list on the same page shows that new order with
  status `PENDING`, naming that owner

#### Scenario: Changing the owner discards a selection made under the previous owner

- **GIVEN** the viewer has selected an owner, a product, and a specification
- **WHEN** the viewer selects a different owner
- **THEN** the product and specification selections are cleared, so the form can never
  submit goods belonging to a different owner

##### Example: form validation before submission

| Owner | Product | Specification | Quantity | Result |
| --- | --- | --- | --- | --- |
| selected | selected | selected | 1 | submitted |
| selected | selected | selected | 0 | blocked in the form, no request sent |
| selected | selected | selected | -3 | blocked in the form, no request sent |
| selected | selected | not selected | 1 | blocked in the form, no request sent |
| not selected | — | — | 1 | blocked in the form, no request sent |
