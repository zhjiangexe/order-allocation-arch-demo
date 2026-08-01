## REMOVED Requirements

### Requirement: Stock is held per owner, warehouse, arrival and expiry
**Reason**: 標題本身斷言身分含**倉**，而身分的那一維換成了位置，因此無法只改內容。倉不能當
異動的端點——`supplier` 與 `customer` 不是這個系統經營的倉，卻必須是移動的合法另一端，否則
入庫與出庫表達不出來。

**Migration**: 由 **Stock is held per owner, location, arrival and expiry** 取代。**保留的**：
身分仍是五個維度、效期仍不可為空且理由不變、到貨日仍參與身分、數量仍逐列持有且可承諾量仍為
讀取時導出而不儲存。**改變的**：第二維由倉換成位置，且該位置的 usage 必須是 `internal`。

## ADDED Requirements

### Requirement: Stock is held per owner, location, arrival and expiry

A stock row SHALL be identified by its owner, its location, its SKU code, the date the
goods arrived, and the date they expire. Two rows differing in any one of those five are
different stock and SHALL NOT be merged.

**The location SHALL have usage `internal`.** Stock is what the company holds, and only
internal locations count towards that. A row in a virtual location would be quantity the
system claims to hold in a place it does not operate.

The expiry date SHALL be mandatory. A nullable expiry inside a uniqueness constraint is
a trap in PostgreSQL, where NULLs compare as distinct: two same-day arrivals of a
non-perishable SKU would become two rows rather than one, silently.

The arrival date SHALL participate in identity rather than being a mere attribute.
Keeping it in identity means every replenishment either matches an existing row exactly
or creates a new one — there is no merge rule to define, and therefore none to get
wrong.

Quantities SHALL be held per row: on-hand and reserved. Available-to-promise SHALL be
derived from them at read time and SHALL NOT be stored, because a stored derivation is
a second source of truth that can disagree with the first.

**The quantities SHALL remain materialised on the row rather than summed from movements.**
This holds even once movements exist: a stock row is a balance that movements write, not a
view over them. The optimistic-lock version guarding that balance is the mechanism by which
replenishment and queue-waking serialise against concurrent orders — see the second of the
three replenishment decisions in `docs/dom-promising-scope.md`. Deriving the balance at
read time would remove the row that lock is taken on.

#### Scenario: Two owners holding the same SKU code hold separate stock

- **GIVEN** two owners each hold stock of the same SKU code in the same location
- **WHEN** one owner's order consumes that stock
- **THEN** the other owner's available-to-promise is unchanged

#### Scenario: Same-day arrivals of different expiry stay separate

- **WHEN** two deliveries of one SKU arrive at one location on the same day with
  different expiry dates
- **THEN** they are held as two rows, each carrying its own expiry

#### Scenario: An identical arrival adds to the existing row

- **GIVEN** stock exists for an owner, location, SKU, arrival date and expiry date
- **WHEN** a replenishment arrives naming all five identically
- **THEN** its quantity is added to that row and no second row is created

#### Scenario: Stock cannot be held in a virtual location

- **WHEN** a stock row is written against a location whose usage is not `internal`
- **THEN** the write is refused

---

### Requirement: Allocation draws stock from a location, and demand is published with one

Allocation SHALL select candidate stock by owner, **location**, and SKU code. It SHALL NOT
select by warehouse.

The two coincide while a warehouse has one internal location, and that is exactly why the
distinction has to be stated now: a query keyed on the warehouse would keep passing every
test until a second internal location appeared, and would then draw on stock the order was
never meant to reach.

**The published demand SHALL carry the location, resolved from the order's warehouse.**
Orders name warehouses; allocation speaks locations. Resolving it where the two meet keeps
each side to one vocabulary — were the demand to publish a warehouse, allocation would have
to understand both, and every query would carry the conversion.

The FEFO ordering SHALL be unchanged — expiry date, then arrival date, then row identity —
and the covering index SHALL keep that column order, with location in the position
warehouse held.

**The scope of a backorder queue SHALL likewise be keyed to the location.** A queue that
spans locations spends its bound on orders that were never candidates, which is the same
reason it was scoped to one warehouse before.

#### Scenario: Allocation ignores stock in another location

- **GIVEN** an owner holds allocatable stock of one SKU in two internal locations
- **WHEN** an order sourced from one of them is allocated
- **THEN** only that location's stock is drawn on, and the other location's
  available-to-promise is unchanged

#### Scenario: FEFO order is unchanged by the move to locations

- **GIVEN** several batches of one SKU in one location with differing expiry and arrival
  dates
- **WHEN** they are listed for allocation
- **THEN** they appear ordered by expiry date, then arrival date, then identity
