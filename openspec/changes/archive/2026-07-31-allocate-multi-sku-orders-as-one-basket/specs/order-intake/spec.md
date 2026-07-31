## REMOVED Requirements

### Requirement: Order intake accepts exactly one line per order
**Reason**: 這條政策本來就是暫時的——schema 允許任意行數、`rehydrate` 不設限、測試一直在造兩行的訂單走讀取路徑，都是為了讓放寬那天不必搬遷結構。配貨的整籃原子判斷完成後，限制沒有理由再留著。標題本身斷言了「恰好一行」，因此無法只改內容。
**Migration**: 由 **Order intake accepts one or more lines** 取代。「至少一行」保留（沒有需求的訂單不是儲存能持有的狀態），「至多一行」移除。收單與還原之間的不對稱因此消失，那個不對稱存在的唯一理由就是這條限制。

## ADDED Requirements

### Requirement: Order intake accepts one or more lines

Placing an order SHALL be rejected unless it carries at least one line. There SHALL be no
upper bound: an order may carry several lines, naming several SKU codes, and the same SKU
code may appear on more than one line.

An order without demand SHALL be rejected at intake and SHALL NOT be reconstructible from
storage either — it is not a state the system holds.

**The same SKU on two lines SHALL be legitimate**, not merely tolerated. Upstream systems
split a quantity across lines for their own reasons (different price tiers, different
customer references), and an order's demand is read as quantities aggregated per SKU, so
two lines naming one SKU are indistinguishable from one line carrying their sum.

#### Scenario: An order carrying two SKUs is accepted

- **WHEN** an order is placed with two lines naming different SKU codes
- **THEN** the order is persisted with both lines

#### Scenario: An order carrying the same SKU twice is accepted

- **WHEN** an order is placed with two lines naming the same SKU code
- **THEN** the order is persisted with both lines, and its demand for that SKU is the sum

#### Scenario: An order without lines is still rejected

- **WHEN** an order is placed with zero lines
- **THEN** the order is rejected and nothing is persisted

##### Example: line counts at intake and at rehydration

| Lines | Intake | Rehydration |
| --- | --- | --- |
| 0 | rejected | rejected — an order without demand is not a state storage can hold |
| 1 | accepted | accepted |
| 2, different SKUs | accepted | accepted |
| 2, same SKU | accepted | accepted |

**Intake and rehydration no longer disagree.** They differed only because of the
single-line policy; with it gone, both accept exactly the orders the schema permits.
