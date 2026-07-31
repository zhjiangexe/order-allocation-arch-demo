## ADDED Requirements

### Requirement: The order form composes a basket of several lines

The order form SHALL let the viewer add and remove lines, each naming a product, one of that
product's specifications, and a quantity. An order SHALL be submittable with one line or with
several.

The per-line rules SHALL be the ones already stated for a single line: goods chosen in two
steps rather than typed, selectable products restricted to the selected owner, quantity sent
as a number, and submission prevented for a non-positive quantity or an incomplete selection.
Changing the owner SHALL discard **every** line, since none of their goods is valid under a
different owner.

**The form SHALL show why a multi-line order was not allocated.** An order is allocated whole
or not at all, so an order can be backordered while one of its SKUs is plentiful — that is
counter-intuitive enough that seeing it is the point. The list SHALL therefore make each
line's goods and quantity visible on the order's row, as it already does for a single line.

Two lines naming the same specification SHALL be permitted. Intake accepts them and reads the
demand as their sum; forbidding it here would make the console reject orders the system
handles.

#### Scenario: An order with two different specifications is submitted and listed

- **WHEN** the viewer adds a second line naming a different specification and submits
- **THEN** the recent-orders list shows that order with both lines and status `PENDING`

#### Scenario: Removing a line leaves the rest intact

- **GIVEN** the form holds three lines
- **WHEN** the viewer removes the middle one
- **THEN** the remaining two keep their own selections and quantities

#### Scenario: The last line cannot be removed

- **GIVEN** the form holds one line
- **WHEN** the viewer attempts to remove it
- **THEN** the line remains, because an order without demand cannot be submitted

#### Scenario: Changing the owner clears every line

- **GIVEN** the form holds two lines with selections made under one owner
- **WHEN** the viewer selects a different owner
- **THEN** every line's product and specification selection is cleared

#### Scenario: An incomplete line blocks submission even when the others are complete

- **GIVEN** the form holds two lines, one complete and one without a specification
- **WHEN** the viewer submits
- **THEN** the form does not send the request

##### Example: what the basket makes visible

| Order | Stock | Outcome |
| --- | --- | --- |
| A×10 + B×5 | A: 100, B: 100 | allocated — both lines reserved |
| A×10 + B×5 | A: 100, B: 3 | **backordered — neither line reserved, though A is plentiful** |
| A×10 | A: 100 | allocated |

第二列是這個表格存在的理由：**有貨卻不配**，而那正是 ship-complete 的內容。
