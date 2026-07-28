## MODIFIED Requirements

### Requirement: Placing an order shows the result in the list on the same page

The orders page SHALL provide a form taking an owner, an upstream order number, **a
warehouse**, a destination zone, a destination address, a promised delivery date, and
the goods ordered. The goods SHALL be chosen in two steps — a product, then one of that
product's specifications — rather than typed as a SKU code, so that an unknown or
cross-owner SKU code cannot be submitted at all.

The selectable warehouses SHALL be those the selected owner is assigned to, and the
selectable products SHALL be those of the selected owner, and the selectable
specifications SHALL be those of the selected product. Changing the owner SHALL discard
a warehouse, a product, and a specification chosen under the previous owner, since none
of them is valid under a different owner.

The warehouse SHALL be selected rather than typed, for the same reason the SKU code is:
a typed identifier can name a warehouse the owner is not assigned to, which the backend
would reject after a pointless round trip.

On success the newly created order SHALL become visible in the recent-orders list on
that same page without navigation. The submitted quantity SHALL be sent as a number,
and the form SHALL prevent submission of a non-positive quantity or of an incomplete
selection rather than relying on the backend to reject it.

#### Scenario: A submitted order appears in the list as PENDING

- **WHEN** the viewer submits the form with an owner, an upstream order number, a
  warehouse, a destination, a promised delivery date, a selected specification, and a
  valid quantity
- **THEN** the recent-orders list on the same page shows that new order with
  status `PENDING`, naming that owner

#### Scenario: Changing the owner discards a selection made under the previous owner

- **GIVEN** the viewer has selected an owner, a warehouse, a product, and a
  specification
- **WHEN** the viewer selects a different owner
- **THEN** the warehouse, product, and specification selections are cleared, so the form
  can never submit a warehouse or goods belonging to a different owner

#### Scenario: Only the selected owner's warehouses are offered

- **GIVEN** two owners are assigned to different sets of warehouses
- **WHEN** the viewer selects one of them
- **THEN** the warehouse choices are exactly that owner's assigned warehouses

##### Example: form validation before submission

| Owner | Warehouse | Product | Specification | Quantity | Result |
| --- | --- | --- | --- | --- | --- |
| selected | selected | selected | selected | 1 | submitted |
| selected | selected | selected | selected | 0 | blocked in the form, no request sent |
| selected | selected | selected | selected | -3 | blocked in the form, no request sent |
| selected | selected | selected | not selected | 1 | blocked in the form, no request sent |
| selected | not selected | selected | selected | 1 | blocked in the form, no request sent |
| not selected | — | — | — | 1 | blocked in the form, no request sent |
