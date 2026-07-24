## Context

`ReplenishmentUsecase` 已經在單一 transaction 內完成：claim Inbox、讀取穩定排序的
`findBackordersBySkuInFifoOrder(sku)`、呼叫 `AllocationService.allocateBackorders(...)`
（內部用 `StrictFifoAllocationPolicy` 選單）、由 `OrderAllocationCoordinator` 統一持久化
StockPool、Orders、Reservations 並發布 `OrderAllocationCompleted` Domain Event。這條路徑
也跟 allocation 路徑共用同一個 `AllocationRetryExecutor`。

目前唯一驗證這條路徑的 `AllocationWorkflowEndToEndIntegrationTest` 只排了兩張訂單，
不足以在量體下暴露「跳過 head-of-line blocking 訂單」這類演算法錯誤——兩張訂單的情境
很容易因為程式碰巧正確而通過，即使實作有 off-by-one 或誤用 sorted-then-continue
（而非 strict break）的 bug。

## Goals / Non-Goals

**Goals:**

- 在 1,000 張排隊 backorder 的量體下，驗證單一 `StockReplenishedIntegrationEvent`
  觸發的 FIFO 批次配置決策精準、可預期。
- 用固定數量分布讓期望結果可以手算並寫死斷言，不在測試裡重新實作 FIFO 演算法。
- 對帳批次配置後的 Order／StockPool／StockReservation／Inbox／Outbox 持久化狀態。
- 驗證「喚醒佇列」的完整生命週期：不只是第一波補貨不足時正確卡住，後續（循序）
  補貨到位後，先前被 head-of-line blocking 卡住的訂單也要能正確恢復配置。

**Non-Goals:**

- 併發競爭（多個補貨事件同時到達、補貨當下有新訂單插入競爭同一 StockPool）。
- 隨機化訂單數量分布。
- 變更 `StrictFifoAllocationPolicy`、`AllocationService`、Kafka topics 或 Integration
  Event 契約。
- Production throughput/latency benchmark 或新的 load-testing 工具。
- Read-model replay demo。

## Decisions

### 直接以 BACKORDERED 狀態種入 fixture，不經過真正的下單配置流程

測試 SHALL 用 `Order.rehydrate(...)` 直接建立 1,000 張 BACKORDERED Order，`backorderedSince`
依序遞增（例如逐筆 +1 毫秒），而不是先送 1,000 筆 `OrderPlacedIntegrationEvent` 讓它們
自然變成 backorder。

這與現有 `AllocationWorkflowEndToEndIntegrationTest` 的 `backorderedOrder` helper 手法
一致：本次要驗證的是「補貨觸發批次配置」這一段，不是「訂單如何先變成 backorder」
（Demo-01 已經涵蓋下單配置路徑）。直接種資料能讓 fixture 建置時間可預期，且不會意外
把兩個不同的驗證範圍混在同一個測試裡。

### 固定數量分布，包含一張刻意補不滿的 blocker 訂單

前 500 張（FIFO 排序中最早的 500 張）quantity 皆為 1，第 501 張是 quantity 明顯偏大
（999）、必定補不滿的 blocker，其後 499 張 quantity 皆為 1。`StockReplenishedIntegrationEvent`
的 quantity 精準等於前 500 張的總和（500）。

若改用隨機數量，測試必須自己重新算一次「嚴格 FIFO 下補貨量能吃到第幾張」，等於在測試
裡重寫一份 production 的 `StrictFifoAllocationPolicy` 演算法；一旦 production 邏輯本身有
bug，測試很可能用同樣錯的邏輯算出「一致」的期望值而錯放行。固定數量讓期望結果
（500 張 ALLOCATED、500 張仍 BACKORDERED）可以直接手算並寫死斷言，測試對 production
邏輯保持獨立性。blocker 之後刻意保留 499 張「個別都配得起」的小單，用來精準驗證
`StrictFifoAllocationPolicy.selectOrders(...)` 的 `break`（而非 `continue`／skip）語意在
量體下依然成立。

### 只驗證循序補貨，不引入併發協調機制

測試 SHALL 用單一執行緒依序完成：種 fixture → 送第一個 `StockReplenishedIntegrationEvent`
→ 對帳第一階段 → 送第二個（循序、非併發）`StockReplenishedIntegrationEvent` → 對帳第二
階段。不需要 virtual-thread start gate、test-only AOP synchronizer 或 redelivery 迴圈
——這些都是 Demo-01 為了製造「真實 optimistic-lock conflict」而需要的機制，本次驗證的
是批次配置演算法本身在單一 transaction 內的正確性，不涉及多方競爭同一筆資料。

### 用第二次循序補貨驗證佇列能正確恢復配置

第一次補貨只釋放前 500 張、刻意讓 blocker 與其後 499 張卡住之後，測試 SHALL 送出
第二個 `StockReplenishedIntegrationEvent`，quantity 等於 blocker 與其後 499 張的
總和（`999 + 499 = 1,498`），驗證這次全部剩餘 500 張都能依 FIFO 順序被配置——blocker
排在第 501 個位置被配置，不是被跳過或因為快取/過期狀態而配置錯誤的訂單。

只驗證「第一波正確卡住」不足以證明「喚醒佇列」這個能力完整：如果 backorder 查詢
或 StockPool 版本狀態在第一次 transaction 後沒有正確反映最新狀態，即使第一階段
斷言通過，第二次補貨仍可能重複配置、漏配置或選錯順序。這一步驗證的是循序
（sequential）補貨的正確恢復，跟 Demo-01 驗證的「多個事件同時競爭」是不同性質，
不算引入併發。

## Implementation Contract

**Behavior:** 執行 SIT 情境時，種入 1,000 張 FIFO 排序穩定的 BACKORDERED Order 與一個
`onHandQuantity = 0` 的 StockPool；送出第一個 `StockReplenishedIntegrationEvent(sku, 500)`
後，情境在單一 transaction 內完成第一階段配置並可被驗證；接著送出第二個（循序）
`StockReplenishedIntegrationEvent(sku, 1498)`，情境在另一個 transaction 內完成第二
階段配置並可被驗證。

**Interface / data shape:** 使用既有 `StockReplenishedIntegrationEvent` 記錄型別，透過
`AllocationKafkaIntegrationEventConsumer.consumeInventoryEvent(...)` 進入；不新增
public commands、events、topics 或 persistence tables。

**Failure modes:** 任何非預期例外（例如 optimistic-lock 相關）直接讓情境失敗；不吞掉
或重試——單一執行緒循序送出兩個事件，兩者之間不應該有自然競爭來源。任一對帳斷言
不符即回報觀察到的實際狀態。

**Acceptance criteria:** 情境在 PostgreSQL Testcontainers 上執行。第一階段（補貨
quantity=500）後：恰好 500 張 Order 變為 ALLOCATED、500 張仍為 BACKORDERED（含 blocker
與其後 499 張未被跳過配置的小單）；StockPool on-hand=500、reserved=500、ATP=0；恰
500 筆 ACTIVE StockReservation（總量 500、`order_id` 全相異）；Inbox 恰 1 筆 claim；
Outbox 恰 500 筆 `OrderAllocatedIntegrationEvent`。第二階段（再補貨 quantity=1498）
後：全部 1,000 張 Order 均為 ALLOCATED（0 張 BACKORDERED）；StockPool on-hand=1998、
reserved=1998、ATP=0；恰 1,000 筆 ACTIVE StockReservation（總量 1998、`order_id` 全
相異）；Inbox 恰 2 筆 claim；Outbox 恰 1,000 筆 `OrderAllocatedIntegrationEvent`。全程
0 筆 `BackorderCreatedIntegrationEvent`。可用既有的 `:order-promising:sit` task 搭配
測試 filter 執行。

**Scope boundaries:** 本次涵蓋「循序補貨事件觸發的 FIFO 批次配置決策」，包含第一波
不足時的 head-of-line blocking 與後續補貨到位後的正確恢復；不涵蓋多個補貨事件
**同時**到達的併發競爭、broker 傳輸、下單配置路徑本身（Demo-01 已覆蓋）或
read-model replay。

## Risks / Trade-offs

- [1,000 張 fixture 的種入時間會拉長 SIT 執行時間] → 直接以 repository 寫入
  BACKORDERED 狀態，不透過 Kafka entrypoint 逐筆送 1,000 個下單事件。
- [固定數量分布可能被質疑「不夠真實」] → 這是刻意取捨：測試目的是驗證演算法在量體
  下的正確性，不是模擬真實流量分布；design 已在 Decisions 說明理由。
- [blocker 數量選得不夠大，恰好被後續實作變動吃下] → blocker quantity（999）遠大於
  補貨量（500）與剩餘 ATP（此時為 0），任何正數的 blocker 都足以觸發 head-of-line
  blocking；選用明顯偏大的數字只是讓測試意圖在程式碼與文件中更好讀。
- [只驗證第一波卡住，可能掩蓋「佇列無法正確恢復」的 bug] → 加入第二次循序補貨
  （quantity=1498），驗證 blocker 與其後 499 張最終都能依 FIFO 順序被配置，完整
  覆蓋「喚醒佇列」的兩個階段。
