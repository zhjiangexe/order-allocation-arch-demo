## REMOVED Requirements

### Requirement: A reservation is held per order line and per stock row
**Reason**: 標題斷言的實體不再存在——預留成為搬運的一個階段，而不是與搬運平行的另一本帳。
保留的是**粒度**：一條行取用三批仍然是三列，而那三列現在是同一段搬運的三條明細。

**Migration**: 由 `stock-movement` 的 **Reservation is a stage of a movement, not a parallel
ledger** 與 **Every movement of goods is recorded with both of its ends** 共同取代。
**保留的**：粒度是「行 × 批」、拒絕「一筆預留內含批次清單」的理由（釋放與消耗都逐批發生）。
**改變的**：釋放是刪除明細而不是把它標成已釋放，因為一條被釋放的預留不表達任何事實。

### Requirement: Outstanding demand is decided by reservations, not by an order's status
**Reason**: 標題斷言判準是預留。判準換成了搬運的存在，而那不只是換一張表——它讓 view 的
角色從「還欠什麼」變成「哪些行還沒被接手」，兩者的消費者也不同。

**Migration**: 由下方的 **Outstanding demand is decided by whether a movement exists** 取代。
**保留的**：不看訂單自己的配貨狀態、取消由 ordering 同步寫入因此即時正確、值域必須一次寫對
（`CONSUMED` 的角色由搬運的完成狀態接手）、謂詞只表達在一個地方。
**改變的**：判準由「有無有效預留」變成「有無 move」；而「刻意不含 `ol.status`」那條理由
**消失了**——執行層讀的是自己寫的資料，沒有非同步的時間差。

## ADDED Requirements

### Requirement: Outstanding demand is decided by whether a movement exists

An order line SHALL count as outstanding while no movement exists for it, and SHALL
disappear from the published demand once one does — **whatever state that movement is in**.

This changes what the published view answers. It no longer says "what is still owed",
because that question now has a better home: a movement that needs goods says so itself.
What the view answers is narrower — **which lines execution has not yet taken up** — and it
exists because execution has no other way to learn them. The event announcing a new order
carries only its identifier, by a decision this system pins with a test, and the opposite
direction would have ordering writing execution's tables.

**A completed movement SHALL count as existing.** Nothing completes movements yet; the
predicate is written against the full set now for the same reason it always was — were
completion added later, every shipped order would reappear as untaken demand, and no test
would fail on the day the mistake was made.

Cancellation SHALL continue to be excluded by the order's own cancellation record, which
ordering owns and writes synchronously, so it is immediately correct. That division is
unchanged: ordering is authoritative for what was ordered and whether it was cancelled.

**The reason for excluding the line's own status disappears.** It was excluded because
ordering's copy lagged allocation's decision; the new predicate reads a table execution
writes itself, so there is no lag to guard against.

#### Scenario: A line with no movement is untaken

- **GIVEN** an order line for which no movement has been created
- **WHEN** the published demand is read
- **THEN** that line appears

#### Scenario: A line whose movement is still waiting for goods is not untaken

- **GIVEN** an order line whose movement needs goods and has not got them
- **WHEN** the published demand is read
- **THEN** that line does not appear, because execution has taken it up

#### Scenario: A line whose movement completed is not untaken

- **GIVEN** an order line whose movement has completed
- **WHEN** the published demand is read
- **THEN** that line does not appear

#### Scenario: A cancelled order holds no untaken demand

- **GIVEN** a cancelled order whose lines have no movement
- **WHEN** the published demand is read
- **THEN** none of its lines appear

---

### Requirement: Allocation satisfies movements, not orders directly

Allocation SHALL draw its queue from movements needing goods, ordered as it ordered demand
before, and SHALL satisfy them by assigning stock and recording which batches were drawn on.

The batch selection, the strict ordering, the whole-order rule and the bound on how many
orders one replenishment wakes SHALL all be unchanged. **What changes is the shape of the
input and the output**: the queue is a set of movements rather than a derived view, and the
result is an assigned movement rather than a reservation beside it.

**The queue SHALL remain scoped to one owner, one location and one SKU.** Widening it costs
the same as before: candidates that this replenishment cannot satisfy consume the bound and
are then skipped.

#### Scenario: Replenishment wakes movements in the same order as before

- **GIVEN** several orders waiting for the same goods in one location
- **WHEN** stock arrives
- **THEN** they are satisfied in the order they arrived, stopping at the first that cannot
  be satisfied in full

#### Scenario: An assigned movement names the batches it drew on

- **GIVEN** a movement satisfied from two batches
- **WHEN** it is read
- **THEN** it carries one line per batch, and their quantities sum to what was needed
