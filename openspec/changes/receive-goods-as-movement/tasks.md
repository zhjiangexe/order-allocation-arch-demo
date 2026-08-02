## 0. 前置

- [x] 0.1 動手前跑一次 `test` 與 `sit`，記下支數（281 / 137）。這個 change **會**新增測試，所以收工時支數要增加而不是持平——增加了哪些列在 5.6。

  **收工實測：`test` 288（+7）、`sit` 139（+2）、前端 73（不變）。**

  | 新增 | 哪裡 |
  | --- | --- |
  | 收貨的三支（單據不帶訂單、不查庫存、沒有入庫類型要拋錯） | `MovementRecorderTest` |
  | 完成的六支 | `MovementCompleterTest`（其中兩支是從 `ReplenishmentUsecaseTest` 搬過來的五維識別） |
  | 明細指向別的庫存列要拒絕、在庫量沒有不帶明細的入口 | `StockPoolTest` |
  | 補貨留下已完成的入庫搬運、入庫的搬運不得被喚醒配貨 | `AllocationWorkflowEndToEndIntegrationTest` |

## 1. 主檔：入庫作業類型

- [x] 1.1 依 `stock-movement` 的 **Goods arriving are a movement from a supplier**，種子資料為每個倉加一個 `INBOUND` 作業類型，起訖為 `Vendors → 該倉的內部位置`。

  `stock_picking_types` 的 CHECK 早就允許 `INBOUND`，值域一次定完的那個決定在這裡兌現——不必動 migration。

  `DevSeedDataInitializer` 的註解要改：現在寫的是「只建出庫：入庫與內部調撥還沒有產生者」，入庫有了。

- [x] 1.2 `OrderFixtures.seedCatalog` 同樣補上 `INBOUND` 類型，供 SIT 使用。虛擬的供應商位置已經在（`FIXTURE/Vendors`），至今無讀者。

## 2. 建立入庫搬運

- [x] 2.1 依同一 requirement，`MovementRecorder` 新增 `recordInbound(...)`，建一張 `INBOUND` 單據（**`orderId` 為空**）與一段搬運（`orderLineId` 為空），起訖取自作業類型的預設值。

  **兩個方法而不是一個帶方向參數的**——這是上一個 change 就定下的：兩邊的輸入本來就不同型，出庫收 `Demand`，入庫收的是「哪個貨主、哪個位置、哪個 SKU、幾件、什麼入庫日與效期」。

- [x] 2.2 倉沒有設 `INBOUND` 作業類型時拋錯，與出庫同一個判準。行為上：訊息處理失敗而不是靜默少建一張單。

## 3. 完成搬運：庫存在這裡才變

- [x] 3.1 依 `stock-movement` 的 **Stock on hand changes only through a completed movement line**，`StockPool.replenish(int)` 改為 `receive(StockMoveLine line)`，並驗證那條明細指向的正是這一列。

  依 design 的決策「動庫存的是明細，所以「加數量」要一條明細當憑證」。**這是這個 change 的核心。** 不變式從「約定」變成「型別」：沒有明細就加不了數量，而明細只有完成那一步會建。

  `consume(int)` **原樣留著**——它至今沒有生產者，而它的憑證應該是出貨的明細，那屬 R7。

- [x] 3.2 新增 `MovementCompleter`（`allocation/application/movement/`），`complete(List<StockMove>, Instant)`：找到或開庫存列 → 建明細 → 轉 `ASSIGNED` 再轉 `DONE` → `receive`。

  依 design 的決策「入庫也走 `CONFIRMED → ASSIGNED → DONE`，不直接建 `DONE`」：Odoo 的收貨也走完整段（`_should_bypass_reservation` 那條分支建明細但不動 quant，標成 `assigned`，`_action_done` 才動）。中間狀態在同一個交易內沒有人看得到。

- [x] 3.3 依 design 的決策「明細要指向一列已存在的庫存，所以「找到或開一列」在建明細之前」，五維識別的「找到或開一列」從 `ReplenishmentUsecase.upsertBatch` 搬進 `MovementCompleter`，且**新開的那一列數量為 0**。

  原本的 upsert 有兩條路：找到就加、找不到就用最終數量新建。**第二條正是繞過搬運的那一條**，這裡消滅它。

- [x] 3.4 `StockMove` 新增 `complete(Instant)`：只允許從 `ASSIGNED` 進入 `DONE`，冪等（已 `DONE` 回 `false`），`CANCELLED` 拋錯。

- [x] 3.5 依 design 的決策「`MovementCompleter` 的介面照兩邊都能用的形狀」，`complete` 遇到來源是內部位置的搬運（出貨）**拋錯**。

  留一個沒有測試、沒有呼叫端的分支比沒有它更糟。R7 接上時加的是那一半的實作，不是第二個方法。

- [x] 3.6 依 `stock-allocation` 修改後的 **Stock is held per owner, warehouse, arrival and expiry**，庫存列的寫入走 `StockWriteOrder`——完成是第三個寫入者，而全序只有一份定義。

## 4. 補貨改走搬運

- [x] 4.1 `ReplenishmentUsecase` 的 `upsertBatch` 換成 `recordInbound` + `complete`。依 design 的決策「補貨仍然在同一個交易內喚醒佇列」——**喚醒仍在同一個交易內**——那不是效能取捨，是 FIFO 的實作機制。

- [x] 4.2 `handleWake`（續做）**完全不動**：它本來就不加庫存。

## 5. 測試

- [x] 5.1 `MovementRecorderTest` 補 `recordInbound`：單據的 `orderId` 為空、搬運的 `orderLineId` 為空、起訖是 `Vendors → 內部位置`、倉沒有入庫類型要拋錯。

- [x] 5.2 新增 `MovementCompleterTest`：五維命中就加到那一列、沒命中就開一列（**開的時候數量為 0**）、明細指向那一列、搬運轉 `DONE`、出貨方向拋錯。

- [x] 5.3 `StockPoolTest` 的 `replenish` 測試改為 `receive`，並補一支「明細指向別的庫存列時拒絕」。

- [x] 5.4 `ReplenishmentUsecaseTest` 的 upsert 斷言改為「委派給 recorder 與 completer」，順序（先補後喚醒）保留。

- [x] 5.5 SIT 新增一支：**入庫的搬運不得被補貨喚醒配貨**。

  ~~`MovementAssigner.toDemands` 早就處理了「單據沒有訂單就跳過」，但那條路徑至今只有單元測試走過——這個 change 讓它第一次有真實資料。~~

  **這個預期是錯的，而測試把它抓出來了。** 拿掉那句跳過，測試**照樣綠**——因為入庫的搬運在同一個交易裡就完成了，狀態是 `DONE`，而佇列只取還在等貨的，它根本進不了佇列。

  真正守住這件事的是**佇列的狀態篩選**：拿掉它，這支測試與「取消的單不得被喚醒」一起變紅（red check 做過）。註解已改成這個版本。

  那句跳過仍然留著——它守的是「`CONFIRMED` 的入庫搬運」，而那在收貨與完成分兩段之後（多段收貨、或收貨失敗重試）就會出現。

- [x] 5.6 SIT 新增一支：補貨之後 `stock_moves` 有一段 `DONE`、`stock_move_lines` 有一條指向那一列庫存，而 `demand_lines` **完全不受影響**（入庫的搬運沒有 `order_line_id`）。

  `DONE` 一直在值域裡是為了讓那個 view 的謂詞一次寫對。這是那個預留第一次被兌現，值得確認 view 沒有跟著變。

## 6. 驗證

- [x] 6.1 三支護欄測試全綠且**斷言一行未改**。以 `git diff` 過濾 `assert` 為空作為佐證。

- [x] 6.2 `AllocationFifoReplenishmentBatchIntegrationTest` **跑得完**。補貨從一次寫入變成四次，而那支測試連續送很多次補貨——這裡要盯的是它還跑不跑得完，不是只看綠不綠。

- [x] 6.3 `AllocationBoundaryArchitectureTest` 全綠。

- [x] 6.4 前端不動、Kafka 事件與 REST 契約一個欄位都不改。

- [x] 6.5 全文檢查：`stock_pools.on_hand_quantity` 的增加**只剩一個入口**。

  ```
  StockPool.java:71    建構子
  StockPool.java:136   consume——減，且至今無生產者
  StockPool.java:160   receive(line)  ← 唯一的增加入口
  StockPoolEntity.java:81  持久化映射
  ```

  `replenish(int)` 已不存在。`StockPoolTest` 另有一支用反射守著「沒有任何以 `int` 增加在庫量的公開方法」——那條在有人日後加回一個便利方法時會紅。

- [x] 6.6 更新 `docs/dom-stock-movement-scope.md`：把「五個 change 的順序」表中第 3 列標記為已交付，並依 design 的決策「只記內部側，不在虛擬位置上留負數」，在「不做的事」補上那個取捨——我們驗不了總量守恆，能驗的是較弱的一條：每一次在庫量的變動都有一條明細對得上。
