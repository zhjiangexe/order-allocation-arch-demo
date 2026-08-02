## Context

`docs/dom-stock-movement-scope.md` 記錄的第三個 change，也是整串的目的：**庫存數字不可被任意
寫**。前三個 change 建好了位置、四張搬運表、以及操作它們的三個動作；補貨仍然直接改
`stock_pools.on_hand_quantity`，那是最後一條繞過搬運的路。

查證基準是 Odoo 19.0 的原始碼，兩處直接引用：

- `stock.move.line._action_done()` 的註解：「This method is called during a move's
  `action_done`. It'll actually move a quant」——**動 quant 的是明細**
- 它的核心是三行：放掉來源的預留、來源減、目的加，而 `in_date` 從來源帶到目的地

## Goals / Non-Goals

**Goals**

- 補貨產生 INBOUND 單據與搬運，並留下明細說明貨進了哪一列庫存
- 在庫量的增加**在型別上**只能由一條明細驅動
- `MovementCompleter` 就位，介面照 R7 的出貨也能用的形狀設計

**Non-Goals**

- 不做出貨完成、不做退貨、不做盤點、不做兩段收貨
- 不改對外契約：`StockReplenishedIntegrationEvent` 一個欄位都不動
- 不碰配貨與取消兩條路徑

## 決策

### 動庫存的是明細，所以「加數量」要一條明細當憑證

現在 `StockPool.replenish(int quantity)` 只要一個數字。任何人拿到 repository 都能加。

改成：

```java
public void receive(StockMoveLine line) {
    if (!line.stockPoolId().equals(id)) {
        throw new IllegalArgumentException(...);   // 這條明細不是給這一列的
    }
    onHandQuantity += line.quantity();
}
```

**這不是防禦性程式設計，是把不變式移進型別。** 「庫存只能由搬運寫」現在是編譯期就成立的事——
沒有明細就叫不動它，而明細只有 `MovementCompleter` 會建。用架構測試守同一件事也可以，但那是
在事後抓，而不是讓錯誤的寫法表達不出來。

`consume(int)` 暫時原樣留著：它至今沒有任何生產者（只有測試），而它的憑證應該是**出貨**的
明細——那屬 R7。同一個 change 裡只有一半有呼叫端的對稱設計，另一半會腐爛。

### 入庫也走 `CONFIRMED → ASSIGNED → DONE`，不直接建 `DONE`

`ck_stock_moves_assigned_at` 要求 `DONE` 的搬運必須有 `assigned_at`，所以直接建 `DONE` 會撞
約束。但那條約束不是這個決定的理由，只是它的結果——

Odoo 的收貨也走完整段：`_action_assign()` 對來源是供應商的搬運走 `_should_bypass_reservation()`
那條分支，**建明細但不動 quant**，然後標成 `assigned`；`_action_done` 才讓明細去動 quant。

我們照同一個順序，差別只在中間狀態在同一個交易內沒有人看得到（與收單即配相同）。

### 明細要指向一列已存在的庫存，所以「找到或開一列」在建明細之前

`stock_move_lines.stock_pool_id` 是 NOT NULL 且有外鍵。一批全新的貨（五維鍵沒有命中）在建明細
之前必須先有那一列，因此順序是：

```text
找到或開一列（數量 0） → 建明細 → 轉 DONE → receive(明細)
```

開一列數量為 0 的庫存再加上去，看起來多此一舉，但它讓「明細指向真實的庫存列」與「數量只由
明細改」兩件事同時成立。原本的 upsert 是「找到就加、找不到就用最終數量新建」——那個第二條
路徑正是繞過搬運的那一條。

**五維識別的規則一個字都不變**（貨主、位置、SKU、入庫日、效期），變的只是誰去執行它：從
`ReplenishmentUsecase.upsertBatch` 搬進 `MovementCompleter`。

### 只記內部側，不在虛擬位置上留負數

Odoo 的 quant 會在供應商位置留下負數，全域總量因此守恆。我們不會——`stock_pools` 有
`CHECK (location_usage = 'INTERNAL')`，那是第一個 change 就定下的。

所以我們的入庫是**純粹的加**，而 Odoo 是一減一加。寫下來是因為下一個拿 Odoo 對照的人會問
「為什麼供應商位置上沒有 quant」，而答案不是遺漏：3PL 不擁有貨，記錄供應商手上有多少沒有
意義，而那個位置存在只是為了讓搬運的兩端都說得出來。

代價要說清楚：**我們驗不了「總量守恆」這條不變式**。能驗的是較弱的一條——每一次在庫量的
變動都有一條明細對得上。

### `MovementCompleter` 的介面照兩邊都能用的形狀

```java
public void complete(List<StockMove> moves, Instant now);
```

不叫 `completeInbound`：完成就是完成，差別在搬運的兩端。入庫的目的地是內部位置（要加），
出貨的來源是內部位置（要減）。R7 接上時加的是「來源是內部位置就 `consume`」那一半，不是
第二個方法。

**但這個 change 只實作加的那一半**，另一半遇到就拋錯——留一個沒有測試、沒有呼叫端的分支，
比沒有它更糟。

### 補貨仍然在同一個交易內喚醒佇列

`ReplenishmentUsecase` 的結構變成：

```text
inbox 冪等
  → recordInbound + complete      （庫存在這裡才變）
  → 守門查詢（這個 SKU 有量可配嗎）
  → 佇列 → assignAll → 續做判斷
```

**同交易不是效能取捨，是 FIFO 的實作機制**——這一點不變。改的只是「庫存怎麼加進去」。

## Risks / Trade-offs

**這個 change 讓補貨從一次寫入變成四次。** 單據、搬運、庫存列、明細，加上原本就有的喚醒。
補貨是熱路徑（`AllocationFifoReplenishmentBatchIntegrationTest` 會連續送很多次），所以這裡
要盯的是那支測試還跑不跑得完，而不是只看它綠不綠。

**`stock_pickings` 第一次出現 `order_id` 為空的列。** `MovementAssigner.toDemands` 已經處理
了這個情形（單據查得到、`orderId` 是空的就跳過），而那條路徑至今**只有單元測試走過**——這個
change 讓它第一次有真實資料。SIT 要有一支專門驗「入庫的搬運不會被補貨喚醒配貨」。

**`MoveState.DONE` 第一次有產生者。** 它一直在值域裡是為了讓 `demand_lines` 的謂詞一次寫對
（「這條行有沒有 move」，已完成的也算有）。入庫的搬運沒有 `order_line_id`，所以不影響那個
view——但這是那個預留第一次被兌現的一半，值得在測試裡確認 view 沒有跟著變。
