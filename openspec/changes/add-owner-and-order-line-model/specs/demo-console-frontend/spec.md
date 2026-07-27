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

---
### Requirement: Stock state and replenishment share one page keyed by SKU

The stock page SHALL take a SKU and report that SKU's on-hand, reserved, and
available-to-promise quantities. From the same page and for the same SKU, the
viewer SHALL be able to trigger a replenishment.

Triggering a replenishment SHALL additionally require selecting an owner, because
replenishment names the owner whose backordered queue it wakes. Querying stock SHALL
NOT require an owner: stock pools carry no owner in this change, so a query scoped to
one would be reporting a distinction the data does not make.

The page SHALL state that the two actions are scoped differently, so a viewer does not
read the reported quantities as belonging to the selected owner.

Because allocation resulting from replenishment is asynchronous, the page SHALL
report that the replenishment was accepted along with its event identifier, and
SHALL state that the outcome is observed by querying again or by refreshing the
orders page. The page SHALL NOT present the replenishment as if allocation had
already completed.

A SKU with no stock pool SHALL render an explicit not-found state.

Because both actions share one SKU field, every result the page displays SHALL
identify the SKU it belongs to, and editing the SKU field SHALL discard results
belonging to the previous SKU. The displayed SKU SHALL come from the backend
response rather than from the field, since the two can differ by the time a
response arrives.

#### Scenario: Querying a partially reserved SKU reports all three quantities

- **GIVEN** a stock pool holds 10 on hand with 4 reserved
- **WHEN** the viewer queries that SKU
- **THEN** the page reports on-hand 10, reserved 4, and available-to-promise 6

#### Scenario: Replenishment requires an owner but querying does not

- **WHEN** the viewer triggers a replenishment without having selected an owner
- **THEN** the action is blocked in the form and no request is sent, while querying the
  same SKU's stock remains available without selecting an owner

#### Scenario: A triggered replenishment is reported as accepted, not completed

- **WHEN** the viewer triggers a replenishment for the queried SKU and a selected owner
- **THEN** the page reports acceptance with the returned event identifier and
  states that the allocation outcome is observed by querying again, and it does
  not claim any order has been allocated

##### Example: what appears and what must not appear after triggering

| Shown | Not shown |
| --- | --- |
| the replenishment was accepted | "N orders allocated" |
| the returned event identifier | any order status change |
| that the outcome requires querying again | a predicted count of woken orders |
| that the quantities shown are not owner-scoped | the selected owner as an attribute of the stock pool |

#### Scenario: Editing the SKU discards the previous SKU's result

- **GIVEN** the viewer has queried one SKU and the page shows that SKU's result
- **WHEN** the viewer edits the SKU field
- **THEN** the previous SKU's result is no longer displayed, so the page never
  shows a result next to a field naming a different SKU

#### Scenario: An unknown SKU renders a not-found state

- **WHEN** the viewer queries a SKU that has no stock pool
- **THEN** the page renders an explicit not-found state rather than an error
  dialog or an empty result that looks like zero stock

##### Example: an absent SKU is distinguishable from an empty one

| SKU | Backend response | Page renders |
| --- | --- | --- |
| `SKU-EMPTY` (exists, no stock) | on-hand 0, reserved 0, ATP 0 | all three quantities as zero |
| `SKU-NOT-A-THING` (no stock pool) | `404` | an explicit "no stock pool for this SKU" state |
