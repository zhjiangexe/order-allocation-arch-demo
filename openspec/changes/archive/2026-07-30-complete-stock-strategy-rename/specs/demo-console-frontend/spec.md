## MODIFIED Requirements

### Requirement: The console presents orders and stock as two navigable pages

The console SHALL expose exactly two routed pages: an orders page and a stock
page. The orders page SHALL be the default route. A shared header SHALL be
present on both pages and SHALL display the partition-key strategy currently in
effect, so a viewer can tell whether the running system uses the order-identifier
strategy or the stock strategy without leaving the console.

**The header SHALL show the backend's value verbatim, and SHALL derive its explanatory
label by comparing against that value's exact literal.** The comparison SHALL be written
against a named constant matching the backend's own, not an inline string repeated at the
point of use.

**An unrecognised value SHALL be reported as the non-single-writer behaviour.** The header
states which guarantee is in force, and the two directions of being wrong are not
symmetric: reporting single-writer when it is not active claims a guarantee the system
does not have, while reporting its absence merely understates a capability. A value the
console does not recognise is, by definition, one whose guarantees it cannot vouch for.

This is not hypothetical. The setting's value was renamed from `sku` to `stock` while the
console kept comparing against `sku`; the comparison then failed for every input, and the
header labelled the single-writer strategy as the optimistic-lock one. A failed string
comparison raises nothing and logs nothing — which is why both branches of the comparison
SHALL be covered by tests rather than left to inspection.

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
| `stock` | the stock strategy, labelled as single-writer |
| anything else | the order-identifier behaviour — never single-writer |

#### Scenario: An unrecognised strategy value is not reported as single-writer

- **GIVEN** the backend reports a strategy value the console does not know
- **WHEN** the header renders
- **THEN** it does not claim single-writer, and it still shows the value it was given

#### Scenario: A failed strategy request yields no strategy conclusion

- **GIVEN** the configuration request failed
- **WHEN** the header renders
- **THEN** it reports the failure and states neither strategy, because naming one would
  be indistinguishable from having read it from the backend

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
