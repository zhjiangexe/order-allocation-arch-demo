## REMOVED Requirements

### Requirement: Stock state and replenishment share one page keyed by SKU
**Reason**: 這條 requirement 把查詢與補貨綁在**一個 SKU 欄**上，理由是它們是一個連續動作（查到可承諾量是 0、補一批、再查一次）。那個連續動作仍然成立，但它的軸錯了：要知道一個倉有沒有東西，你得先知道有哪些 SKU，而那份名單這一頁根本沒有。標題本身斷言了 "keyed by SKU"，因此無法只改內容。

**Migration**: 由 **Stock state and replenishment share one page keyed to a warehouse** 取代。原封不動保留的：批帶倉別以外的每一個欄位、過期的批要顯示並標記而不是隱藏、順序照後端回的不重排、查詢與補貨都要貨主、補貨要湊滿一列的識別（入庫日與效期）才放行。改變的：查詢軸換成貨主＋倉別、列的單位從批換成規格（批降為展開後的第二層）、補貨從那一列的視窗進去而不是同一張表單。

## ADDED Requirements

### Requirement: Stock state and replenishment share one page keyed to a warehouse

The stock page SHALL query by owner and warehouse, and SHALL replenish from the row of
the goods being replenished. Query and replenishment remain one continuous action — see
that nothing can be promised, add stock, look again — but the question the page answers
is now the one a warehouse asks: what is held here, and what is missing.

**The result SHALL be a line per specification, not per batch.** Each line SHALL show its
goods and four quantities: on-hand, reserved, available-to-promise, and expired.

**On-hand SHALL include expired batches and available-to-promise SHALL NOT.** On-hand is
a physical fact about the warehouse; available-to-promise is what allocation can actually
honour, and allocation never draws on an expired batch. The two therefore need not sum
with reserved, and the expired column SHALL account for the difference — that difference
is what separates "write it off" from "order more".

**Each line SHALL be expandable into its batches**, and the batches SHALL carry arrival
date, expiry date, on-hand, reserved, available-to-promise and whether they have expired.
Expired batches SHALL be shown with the reason rather than hidden — hiding them makes "we
hold stock we cannot ship" look identical to "we hold nothing", and those call for
different actions.

**The batches SHALL be listed in the order the backend returned them, and the page SHALL
NOT re-sort them.** That order runs earliest-expiry-first, which is the order allocation
would draw on them — so the page answers "which of these goes first" without the viewer
reconstructing the rule. Re-sorting client-side would state a consumption order of its
own, which could disagree with the one allocation follows while looking equally
authoritative; the tie-breaks reach down to batch identity, so the page could not
reproduce the real order even if it tried.

**Every specification the owner has SHALL be listed, including those the warehouse holds
none of**, showing zero. Those zeros are the answer to "what is this warehouse missing",
and they are what makes the replenish action reachable for goods that have never been
stocked here.

**Stock held under a SKU code the catalog does not know SHALL also be listed**, named by
its code alone. The catalog and the stock are separate records with no reference between
them, so a warehouse can hold goods the catalog has never heard of. Listing only what the
catalog knows would under-report what the warehouse physically holds — and the quantity
missing from the screen would be exactly the quantity nobody can account for.

**The lines SHALL be ordered by product then specification, and that order SHALL NOT
depend on the quantities.** Replenishment is observed by querying again, so a line that
moved because its numbers changed is a line whose change cannot be seen.

Replenishment SHALL open from a line, and SHALL show the owner, warehouse and goods as
fixed context that cannot be edited — they are already settled by the query and the line,
and letting them differ would mean writing to something other than what was clicked.
The arrival date, expiry date and quantity SHALL be the only inputs, and the two dates
SHALL be prefilled from that specification's earliest-expiring batch **that has not
expired**, so that submitting unchanged adds to an existing batch and changing them opens
a new one. Expired batches SHALL be skipped: they sort first by expiry, and adding stock
to one would put it where allocation can never reach it. A specification whose batches are
all expired, or which has none, SHALL leave the dates empty.

The form SHALL prevent submission when a date or the quantity is missing rather than
relying on the backend to reject it.

**Submitting SHALL close the window and SHALL NOT re-query.** Replenishment is accepted
rather than applied, so an immediate re-query can show numbers that have not moved yet,
and the viewer could not tell that from numbers that will not move. The page SHALL
instead state that the replenishment was accepted and that the result requires querying
again.

#### Scenario: A specification held in three batches shows one line that expands to three

- **WHEN** the viewer queries an owner and warehouse where one specification is held in
  three batches
- **THEN** one line appears for that specification, and expanding it shows three batches
  ordered as allocation would consume them

#### Scenario: Expired stock is visible without expanding

- **GIVEN** a specification is held in four batches, one of them expired
- **WHEN** the viewer queries that owner and warehouse
- **THEN** that line's expired quantity is non-zero, and its available-to-promise
  excludes the expired batch

#### Scenario: A specification the warehouse holds none of is still listed

- **WHEN** the viewer queries an owner and warehouse holding none of one of that owner's
  specifications
- **THEN** that specification appears with zero quantities and can still be replenished

#### Scenario: Stock under a code the catalog does not know is still listed

- **GIVEN** a warehouse holds stock under a SKU code absent from the owner's catalog
- **WHEN** the viewer queries that owner and warehouse
- **THEN** that stock appears as its own line, identified by its code

#### Scenario: Replenishment opens with the batch it will add to

- **GIVEN** a specification held in batches
- **WHEN** the viewer opens replenishment from that line
- **THEN** the owner, warehouse and goods are shown but not editable, and the dates are
  those of its earliest-expiring unexpired batch

#### Scenario: An expired batch is not offered as the one to add to

- **GIVEN** a specification whose earliest-expiring batch has expired
- **WHEN** the viewer opens replenishment from that line
- **THEN** the dates are those of the earliest batch that has not expired

#### Scenario: Submitting does not pretend the stock has changed

- **WHEN** the viewer submits a replenishment
- **THEN** the window closes, the page reports that it was accepted, and the listed
  quantities are unchanged until the viewer queries again

##### Example: how a line's four quantities relate

| Batches | On-hand | Reserved | Available | Expired |
| --- | --- | --- | --- | --- |
| 60 all reserved, 40 with 20 reserved, 30 free | 130 | 80 | 50 | 0 |
| the same plus an expired batch of 25 | 155 | 80 | 50 | 25 |
| none held here | 0 | 0 | 0 | 0 |

第二列是這個欄位存在的理由：在手多了 25 而可承諾一件都沒多——**那 25 件出不了貨**。
