## Why

上一個 change 把搬運的四張表與四個狀態建了起來，但**操作那些表的程式碼還散在三個不同的層**。

`AllocateOrderUsecase` 現在有十個依賴，其中四個只為一件事存在：

| 職責 | 依賴 |
| --- | --- |
| 協調收單後的處置 | `inboxRepo`、`demandRepository`、`stockPoolRepository`、`allocationCoordinator`、`clock`、`businessCalendar` |
| **為這張需求開一張出庫作業單** | `stockLocationRepository`、`pickingTypeRepository`、`stockPickingRepository`、`stockMoveRepository` |

而 `OrderAllocationCoordinator` 有四個方法，其中 `releaseMoves` **完全不碰
`allocationService`**——一個只被四分之三的方法用到的依賴，是類別該拆的訊號。取批那支查詢則在
收單與補貨兩條入口各寫了一次。

**這不是「參數太多」的計數問題。** Odoo 19 把搬運的操作全放在 `stock.move` 上，四個動詞：
`_action_confirm` / `_action_assign` / `_action_done` / `_action_cancel`。本系統其實已經有其中
三個，只是被拆散在 usecase、coordinator 與 domain service 之間。

**決定性的理由是下一個 change。** 入庫要做的是同一個「建立搬運」——解析作業類型、建單據、建
搬運——但它**完全不配貨**：沒有 `Demand`、沒有 ATP 判斷、沒有整籃檢查。建立搬運若繼續內聯在
配貨的 usecase 裡，入庫只剩兩條路：複製一份，或把入庫硬塞進一支名為「配貨」的 usecase。

## What Changes

**照搬運的動作切，不照「usecase / coordinator / service」切。** 新增三個元件於
`allocation/application/movement/`：

| 元件 | Odoo | 做什麼 |
| --- | --- | --- |
| `StockOperationRecorder` | `_action_confirm` | 位置 → 倉 → 作業類型 → 建單據 → 建 `CONFIRMED` 搬運 |
| `MovementAssigner` | `_action_assign` | 取批 → `AllocationService` → 轉 `ASSIGNED`、寫明細 → 寫入 → 發事件 |
| `MovementCanceller` | `_action_cancel` | 找單據 → 濾掉已完成 → 還量 → 取消 → 刪明細 |

**`OrderAllocationCoordinator` 消失**，`WaitingDemandFinder` 也消失（它的投影搬進
`MovementAssigner`）。三支 usecase 退回真正的 usecase——冪等、讀輸入、決定後續動作。

**搬運不再被讀兩次。** 現況是收單建完搬運就丟掉、`assign` 再以 `order_line_id` 讀回；補貨也
一樣，佇列查詢讀了搬運、投影成 `Demand`、丟掉，然後再讀一次。`MovementAssigner` 改為**接收
搬運**而不是 `Demand`，兩條路徑的呼叫端手上本來就有它們。

**`WRITE_ORDER` 從私有欄位變成共用的具名常數。** 防死鎖的全序是全系統的規則，而這個 change
之後有兩個元件寫 `stock_pools`，R7 的完成動作會是第三個。

## Impact

- Affected specs: `stock-movement`（新增「建立搬運是獨立的一步」）、`stock-allocation`（新增
  「所有寫入庫存列的地方共用同一個全序」）
- Affected code: `allocation/application/`——新增三個元件，移除 `OrderAllocationCoordinator`
  與 `WaitingDemandFinder`，三支 usecase 瘦身
- **不動**：資料庫 schema、對外契約、前端、`AllocationService`、任何行為

## 不做的事

| 不做 | 理由 |
| --- | --- |
| 動 `AllocationService` | 它已經是純決策、不碰 IO。這個 change 改的是誰去呼叫它 |
| 把 `backorderOrder` 併進 `MovementAssigner` | 收單配不到要掛帳，補貨配不到不重發——兩條路徑的處置不同，併進共用元件會逼出一個布林參數 |
| 合併 `clock` 與 `businessCalendar` | 兩個時間來源確實可以收成一個，但那會把 `BusinessCalendar` 的意思從「今天是幾號」擴成「現在幾點」，是獨立的決定 |
| 加 `MovementCompleter` | 出貨屬 R7，現在建一個沒有呼叫端的元件只會腐爛 |
| 改名 `ReleaseReservationUsecase` | 命名收斂集中在第四個 change，這裡改會讓對外事件的處理鏈被改兩次 |
