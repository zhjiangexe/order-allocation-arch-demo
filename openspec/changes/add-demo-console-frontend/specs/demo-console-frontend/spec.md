## ADDED Requirements

### Requirement: The console presents orders and stock as two navigable pages

The console SHALL expose exactly two routed pages: an orders page and a stock
page. The orders page SHALL be the default route. A shared header SHALL be
present on both pages and SHALL display the partition-key strategy currently in
effect, so a viewer can tell whether the running system uses the order-identifier
strategy or the SKU strategy without leaving the console.

The orders page's list SHALL show every field of the order representation, so that
inspecting one order requires no further navigation, modal, or detail view.

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

### Requirement: Placing an order shows the result in the list on the same page

The orders page SHALL provide a form taking a SKU and a quantity. On success the
newly created order SHALL become visible in the recent-orders list on that same
page without navigation. The submitted quantity SHALL be sent as a number, and the
form SHALL prevent submission of a non-positive quantity or an empty SKU rather
than relying on the backend to reject it.

#### Scenario: A submitted order appears in the list as PENDING

- **WHEN** the viewer submits the form with a valid SKU and quantity
- **THEN** the recent-orders list on the same page shows that new order with
  status `PENDING`

##### Example: form validation before submission

| SKU | Quantity | Result |
| --- | --- | --- |
| `HOT-SKU` | 1 | submitted |
| `HOT-SKU` | 0 | blocked in the form, no request sent |
| `HOT-SKU` | -3 | blocked in the form, no request sent |
| empty | 1 | blocked in the form, no request sent |

### Requirement: Stock state and replenishment share one page keyed by SKU

The stock page SHALL take a SKU and report that SKU's on-hand, reserved, and
available-to-promise quantities. From the same page and for the same SKU, the
viewer SHALL be able to trigger a replenishment.

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

#### Scenario: A triggered replenishment is reported as accepted, not completed

- **WHEN** the viewer triggers a replenishment for the queried SKU
- **THEN** the page reports acceptance with the returned event identifier and
  states that the allocation outcome is observed by querying again, and it does
  not claim any order has been allocated

##### Example: what appears and what must not appear after triggering

| Shown | Not shown |
| --- | --- |
| the replenishment was accepted | "N orders allocated" |
| the returned event identifier | any order status change |
| that the outcome requires querying again | a predicted count of woken orders |

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

### Requirement: Data is fetched only in response to a user action

Every backend request SHALL be traceable to an explicit user action: opening a
page, submitting a form, pressing a query control, or pressing a refresh control.
The console SHALL NOT poll, SHALL NOT install timers that refetch, and SHALL NOT
refresh in the background.

The orders page SHALL provide a refresh control, since the list becomes stale
whenever the backend completes an asynchronous allocation.

#### Scenario: An idle console issues no requests

- **GIVEN** the orders page has finished loading
- **WHEN** the viewer takes no action for an extended period
- **THEN** no further backend requests are issued

#### Scenario: Refreshing after an asynchronous allocation shows the new statuses

- **GIVEN** a replenishment has been triggered and the backend has since
  allocated the queued orders
- **WHEN** the viewer presses the refresh control on the orders page
- **THEN** the list reflects the updated statuses

### Requirement: Backend failures are surfaced rather than swallowed

Every action SHALL surface its own failure to the viewer, identifying which action
failed. A failed request SHALL NOT leave previously loaded data displayed as
though it were the result of the failed action.

#### Scenario: A failed replenishment is reported and does not imply success

- **GIVEN** the backend cannot publish the replenishment event
- **WHEN** the viewer triggers a replenishment
- **THEN** the page reports that the replenishment failed, and does not display
  an event identifier or any indication of acceptance

### Requirement: The console reaches the backend through a development proxy

The console SHALL call the backend through a same-origin path that the dev server
proxies to the backend, so that the browser makes no cross-origin request and the
backend requires no CORS configuration. The backend origin SHALL be configured in
the dev server only, and SHALL NOT be hardcoded in application source.

#### Scenario: Requests are same-origin from the browser's perspective

- **WHEN** the console issues any backend request
- **THEN** the request targets the dev server's own origin and is forwarded to
  the backend by the proxy, and no CORS preflight occurs

##### Example: one request seen from each side

- **GIVEN** the dev server serves the console and the backend runs separately
- **WHEN** the console requests the recent-orders list
- **THEN** the browser records a same-origin request to the dev server's own
  origin, the backend receives the corresponding request at its own origin, no
  `OPTIONS` preflight is recorded, and the backend origin appears only in dev
  server configuration — never in application source
