## 0. 這個 change 的驗收前提

**行為不變，所以驗收全靠既有測試。** 沒有新的行為要驗，就沒有新的測試能證明沒走偏——因此下
面每一段的完成條件都包含「既有測試全綠且斷言不改」。

- [x] 0.1 動手前先跑一次 `test` 與 `sit`，記下支數（278 / 137）。收工時要一模一樣。

  **收工實測：`test` 281、`sit` 137。**（含後來收進來的 4b，它不新增測試——`StockOperationRecorderTest` 改為斷言 `picking.orderId()`，`MovementAssignerTest` 的單據 stub 改回傳單據本身。） SIT 一支不多不少；單元測試 +3，來自
  `StockOperationRecorderTest`（6 支新的）減去 `AllocateOrderUsecaseTest` 移出的 3 支，
  以及 `OrderAllocationCoordinatorTest` 12 支拆成 `MovementAssignerTest` 10 支 +
  `MovementCancellerTest` 8 支、`ReleaseReservationUsecaseTest` 由 6 支縮成 2 支。

## 1. 共用的寫入全序

- [x] 1.1 依 `stock-allocation` 的 **Every writer of stock rows uses one global order**，新增 `StockWriteOrder`（`allocation/domain/`），持有排序鍵與 `saveInOrder(Collection<StockPool>, StockPoolRepository)`。`OrderAllocationCoordinator.WRITE_ORDER` 的註解整段搬過去。依 design 的決策「`WRITE_ORDER` 抽成共用常數」。

  放 domain 而不是 application：它是庫存這個聚合的寫入規則，不是協調細節。

  **先做這一項**，因為後面兩個元件都會用到它——反過來做的話，中間會短暫存在兩份複本，而那正是這條 requirement 要禁止的形狀。

## 2. 建立搬運

- [x] 2.1 依 `stock-movement` 的 **Recording a movement is a step of its own**，新增 `StockOperationRecorder`（`allocation/application/movement/`），把 `AllocateOrderUsecase.createMovements` 整段搬過去，方法名 `recordOutbound(Demand, Instant)`。

  依賴：`StockLocationRepository`、`PickingTypeRepository`、`StockPickingRepository`、`StockMoveRepository`。四個都是從 usecase 搬過來的，不新增。

- [x] 2.2 依同一 requirement 的第三個 scenario，`recordOutbound` **回傳它建立的搬運**（`List<StockMove>`），而不是 void 或單據 id。依 design 的決策「`StockOperationRecorder` 回傳它建立的搬運」。

  這是任務 3.2 消掉往返的前提。回傳單據 id 不夠——呼叫端要的是搬運本身。

- [x] 2.3 `AllocateOrderUsecase` 移除那四個依賴與 `createMovements`，改為呼叫 `StockOperationRecorder`。

  **不要在這一步就接上 `MovementAssigner`**：先讓收單路徑維持「建搬運 → 舊 coordinator」的形狀跑一次測試。中間狀態能跑，代表建立那一段搬對了；兩段一起改，錯了會分不出是哪一段。

## 3. 鎖定搬運

- [x] 3.1 依 design 的決策「`MovementAssigner` 接收搬運，不接收需求」，新增 `MovementAssigner`，把 `OrderAllocationCoordinator` 的 `allocateOrder`、`allocateBackorders`、`assign`、`persistAllocation`、`publishAllocationCompleted` 搬過去，並把 `WaitingDemandFinder.toDemands` 的投影搬進來當私有方法。

  **`AllocationService` 一個字都不改。** 投影只是換了住處，它產出的 `Demand` 形狀完全相同。

- [x] 3.2 依同一決策（`MovementAssigner` 接收搬運，不接收需求），兩個入口方法改為**接收搬運**：`assign(List<StockMove>, Instant)` 回 `AllocationOutcome`、`assignAll(List<StockMove>, Instant)` 回配到的 `List<Demand>`。

  連帶消掉 `findByOrderLineIds` 這條路徑上的往返，以及 `No movement exists for order line` 那個失敗模式——搬運由呼叫端傳入之後，它在型別上就不存在了。

  `StockMoveRepository.findByOrderLineIds` 若因此沒有呼叫端，**一併移除**：留著一個沒有讀者的查詢，下一個人會以為它還有用途。

- [x] 3.3 依 design 的決策「取批查詢歸 `MovementAssigner`」，取批查詢（含補貨那段「候選單的 SKU 聯集」）搬進 `MovementAssigner`。`AllocateOrderUsecase` 與 `ReplenishmentUsecase` 都不再持有 `StockPoolRepository`⋯⋯

  ⋯⋯**除了 `ReplenishmentUsecase` 的 upsert 與守門查詢**。那兩件事是補貨自己的，不屬於鎖定：upsert 是「把貨加進來」，守門是「這一輪需不需要開始」。因此 `ReplenishmentUsecase` 仍持有 `StockPoolRepository`，但只為那兩件事。

- [x] 3.4 `WaitingDemandFinder` **移除**。`ReplenishmentUsecase` 改為直接呼叫 `StockMoveRepository.findWaitingInFifoOrder` 取得搬運，再交給 `MovementAssigner`。

- [x] 3.5 依 design 的決策「`backorderOrder` 留在 usecase」，`backorderOrder` 不進 `MovementAssigner`：`AllocateOrderUsecase` 直接發 `OrderBackorderRecorded`，因此持有 `ApplicationEventPublisher`。

  兩條路徑對「配不到」的處置不同，合併會逼出布林參數。

## 4. 取消搬運

- [x] 4.1 依 design 的決策「`MovementCanceller` 吸收查詢組裝」，新增 `MovementCanceller`，把 `coordinator.releaseMoves` 與 `ReleaseReservationUsecase` 的四段查詢組裝一起搬過去，對外只有 `cancelFor(UUID orderId, Instant)`。

- [x] 4.2 `ReleaseReservationUsecase` 只剩冪等與一次呼叫，依賴降到三個。

- [x] 4.3 `OrderAllocationCoordinator` **移除**。

  它的四個方法散到三個地方之後，剩下的是一個空殼；而它的名字從一開始就沒說清楚自己是什麼。

## 5. 測試

- [x] 5.1 `OrderAllocationCoordinatorTest` 拆成 `MovementAssignerTest` 與 `MovementCancellerTest`。**逐條對照，每一條原本在驗的性質都要找得到新的落點**，對照表寫進本檔。

  被拆掉的測試最容易連同它守的性質一起消失。上一個 change 的 4.1a 就是靠逐條對照才發現「取消即時生效」被縮減了。

  | 原本的十二支 | 新的落點 |
  | --- | --- |
  | 配到貨時應把搬運轉為已鎖定、寫出明細，並統一儲存批次 | `MovementAssignerTest` 同名 |
  | 一條行吃到兩批時應產生兩條明細 | `MovementAssignerTest` 同名 |
  | 批次寫入應依 (SKU, 效期, 入庫日, id) 排序 | `MovementAssignerTest` 同名 **＋ `MovementCancellerTest` 新增一支**——取消現在是第二個寫入者，而它的集合來源是明細而不是 FEFO 查詢 |
  | 配貨算出要動一條沒有搬運的行時應拋錯 | `MovementAssignerTest`「沒有搬運被傳入」——訊息由 `No movement exists` 改為 `No movement was supplied`，因為來源從查詢變成參數 |
  | 單筆訂單分配失敗時不應儲存未變更的批次 | `MovementAssignerTest` 同名 |
  | 沒有任何可售批時不應寫入任何東西 | `MovementAssignerTest` 同名 |
  | ATP 不足時只發缺貨的事實，不寫任何表 | **移到 `AllocateOrderUsecaseTest`**（三支：配到不發、無可售批要發、量不足要發）——缺貨的事實現在由 usecase 發 |
  | 喚醒時沒有任何 backorder 就不應寫入任何東西 | `MovementAssignerTest`「佇列是空的」 |
  | 釋放一張單的多條明細時應全部釋放 | `MovementCancellerTest` 同名 |
  | 重複釋放時應為 no-op | `MovementCancellerTest`「重複取消」 |
  | 釋放量超過已預留數量時應拒絕 | `MovementCancellerTest` 同名 |
  | 明細指向的批沒被帶進來時應拒絕 | `MovementCancellerTest`「批已不存在」——批不再由呼叫端傳入，改為查不到 |

  **新增而非搬移的四支**：搬運由呼叫端傳入（守這次消掉的往返）、投影回需求並依單據分組、
  沒有訂單的單據不是需求（入庫不得被喚醒）、取消的寫入全序。

- [x] 5.2 新增 `StockOperationRecorderTest`：無庫存仍建搬運且狀態為還在等貨、起訖取自作業類型、倉沒有出庫類型要拋錯、回傳的搬運與寫入的一致。

  前三條從 `AllocateOrderUsecaseTest` 搬過來（那裡的四支新測試現在測的是別人的責任），第四條是新的——任務 2.2 的回傳值沒有測試就等於沒有保證。

- [x] 5.3 `AllocateOrderUsecaseTest` 瘦身：只留「冪等」「查無需求是 no-op」「建搬運在配貨之前」「配不到要掛帳」。建搬運的細節歸 5.2。

- [x] 5.4 `ReplenishmentUsecaseTest` 的 stub 由 `WaitingDemandFinder` 換成 `StockMoveRepository` 與 `MovementAssigner`。

- [x] 5.5 `ReleaseReservationUsecaseTest` 的斷言改為「委派給 `MovementCanceller`」；原本那些關於查詢組裝與失敗路徑的斷言搬到 `MovementCancellerTest`。

- [x] 5.6 SIT **一個字都不改**。它們走的是 Spring 接好的整條路徑，而這個 change 沒有改變那條路徑的任何一端。

  **若有任何一支 SIT 需要修改，那就是走偏了的訊號**——立刻停下來看是哪個行為變了。

  **實際上有三支需要改，而三次都不是行為變了：**

  1. `DevSeedDataIntegrationTest` 注入 `WaitingDemandFinder`。它不是端到端——它伸手進應用層叫一個具名的協作者。改為讀補貨路徑用的同兩支查詢（`findWaitingInFifoOrder` + `findOrderIdsByIds`），驗的性質一字未改。
  2. 兩支併發測試的 AOP 切點字串指向已刪除的 `OrderAllocationCoordinator.allocateOrder`。**切點指錯不會編譯失敗，只會靜默匹配不到任何東西**——症狀是重試次數變成 0，而每一條斷言照樣執行。改指 `MovementAssigner.assign`，那是流程裡的同一個位置（交易之內、庫存寫入之後），並在切點上方寫下這個失效模式。
  3. 這兩類都是**測試自己指名了實作**，不是被測行為改變。真正端到端的那些一個字都沒動。

## 4b. 單據型別與表對齊（執行中發現，收進本 change）

- [x] 4b.1 `StockPicking` 補上可空的 `orderId`，與 `StockMove.orderLineId` 對稱：入庫的單據沒有訂單，正如入庫的搬運沒有訂單行。

  表一直有 `order_id`（change 2 定案，對應 Odoo 的 `stock_picking.sale_id`），領域型別卻沒有。後果是三個，而且一個比一個嚴重：

  1. `toEntity(picking, orderId)` 用**旁路參數**傳，型別上無法保證傳進來的是這張單據的訂單
  2. `toDomain` **靜默丟掉** `order_id`——`findByOrderId(orderId)` 回傳的單據說不出自己屬於哪張訂單
  3. 於是需要 `findOrderIdsByIds` 這個特化方法，**只為了把型別自己扔掉的東西撈回來**

  型別的 javadoc 當時還寫著「沒有指向訂單的欄位」——那是 change 2 中途被推翻的決定留下的，表改了、型別與註解沒跟上。

- [x] 4b.2 `save(picking, orderId)` → `save(picking)`；`findOrderIdsByIds(...)` → `findByIds(...)` 回傳單據本身。

  現在補的成本最低：只有一個呼叫端。等第三個 change 有了入庫，就變成兩個，而且入庫得寫 `save(picking, null)`——那個 `null` 會逼人回頭問「為什麼要傳它」。

## 4c. `StockMove` 的工廠改名（執行中發現，收進本 change）

- [x] 4c.1 `StockMove.needing(...)` → `StockMove.confirmed(...)`，並刪掉沒有呼叫端的 `isWaitingForGoods()`。兩則例外訊息裡的 "needing goods" 一併換成 "confirmed"。

  三個理由，一個比一個根本：

  1. **`needing` 缺受詞。** `StockLocation.internal(...)` 讀作「一個內部位置」是完整的；`needing` 讀到一半就停了——needing 什麼？答案只在 javadoc 裡，簽章沒說。
  2. **`needingGoods` 也不對——它不是領域語彙。** 倉庫裡沒有人說「a move needing goods」，那是為了讓英文文法完整而發明的詞。ubiquitous language 反對的正是這種開發者自創的描述性英文；領域裡真的存在的詞是 `confirmed`（Odoo 的狀態值，也是 `MoveState` 的值）。
  3. **建立的意圖已經由 `StockOperationRecorder` 說了。** 它才是 DDD 意義上的 Factory——解析作業類型、組單據、決定起訖。實體上的靜態方法不必再說一次意圖，它只回答「這個實例從哪個狀態開始」，因此名字直接取自狀態值。

  **不叫 `confirm(...)`**：動詞會暗示一步轉換，而這裡沒有起點（沒有草稿階段）。形容詞沒有這個問題。

  **也不叫 `waitingForGoods(...)`**：那會把 Odoo 的 `waiting`（等另一段搬運）請回來，而那正是本系統刻意沒有的狀態。刪掉 `isWaitingForGoods()` 之後，`waiting` 這個詞從整個型別上消失。

## 6. 驗證

- [x] 6.1 三支護欄測試（`AllocationFifoGuaranteeScopeIntegrationTest`、`AllocationHotSkuConcurrencyIntegrationTest`、`AllocationFifoReplenishmentBatchIntegrationTest`）全綠且**斷言一行未改**。以 `git diff` 過濾 `assert` 為空作為佐證。

- [x] 6.2 `test` 與 `sit` 的支數與 0.1 記下的完全相同（新增的測試除外，且新增的要列在本檔）。

- [x] 6.3 `AllocationBoundaryArchitectureTest` 全綠——新元件都在 `allocation` 底下，那條邊界不受影響，但要確認沒有人在搬家時把 `orders` 帶進來。

- [x] 6.4 前端不動、Kafka 事件與 REST 契約一個欄位都不改。

- [x] 6.5 更新 `docs/dom-stock-movement-scope.md`：把「五個 change 的順序」表中 2.5 標記為已交付。
