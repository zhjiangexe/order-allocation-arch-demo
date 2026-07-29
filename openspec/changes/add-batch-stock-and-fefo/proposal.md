## Why

`stock_pools` 一個 SKU 一列，沒有貨主、沒有倉、沒有批。這造成三個已經記錄在案的缺口：

**跨貨主隔離不成立。** 兩個貨主的同碼 SKU 共用同一列，甲貨主下單會吃掉乙貨主的貨，而資料庫
不會報錯。收單與缺貨佇列早在 R1 就按貨主分開了，庫存還沒有——**佇列分開了，庫存還沒分開**。

**倉庫維度懸空。** R2 建了 `fulfillment_nodes` 與 `orders.fulfillment_node_id`，但庫存不分倉，
所以「這張單從北倉出」目前不影響任何配貨決策。R2 的存在理由就是為本 change 準備參照對象。

**系統沒有在算任何東西。** ③ Sourcing 已移出範圍（出貨倉由上游指定），配貨因此是本系統
**唯一真正在算的決策**——而現在它只是「夠不夠、扣掉」。加上批次與效期之後，它才成為一個
有內容的演算法：篩掉不可售的批、依效期排序、跨批取用直到湊滿。

現在做的另一個理由是**遲了會更貴**。每晚一個 change，就多一批在「一個 SKU 一列」前提下寫成、
且在該前提下完全正確的程式碼——`findBySku` 回傳單一結果、配貨只碰一列、預留對應一個池。
它們沒有任何測試會失敗，要等到分批之後才以「配到已過期的貨」或「跨貨主偷吃」的形式浮現。

## What Changes

- **BREAKING**：`stock_pools` 的唯一鍵由 `(sku)` 改為
  `(owner_id, node_id, sku_code, in_date, expiry_date)`。同一列的語意從「該 SKU 的可用量」
  變成「某貨主在某倉、某日到貨、某效期的那一批」。既有列無法自動遷移。
- **BREAKING**：`stock_pools` 加 `(owner_id, sku_code)` → `skus` 的外鍵。庫存指向不存在的
  SKU 從「配不到貨」變成「寫不進去」。
- **BREAKING**：`stock_reservations` 的外鍵由 `order_id` 改為 `order_line_id`，粒度變成
  **訂單行 × 批次**——一條行吃到三個批就是三筆預留。`ReservationStatus` 加 `CONSUMED`。
- **配貨改為 FEFO**：篩掉已過期的批 → 依 `(expiry_date, in_date, id)` 排序 → 依序取用直到
  湊滿。決策層級仍是整張訂單（ship-complete），不做部分配貨。
- **BREAKING**：`ReplenishStockCommand` 加 `nodeId`、`inDate`、`expiryDate`，補貨改為依五維鍵
  upsert。`/demo/replenish` 的 request body 隨之改變。
- **BREAKING**：`archone.allocation.partition-key-strategy=sku` 的 key 由裸 `skuCode` 改為
  `ownerId/nodeId/skuCode`。庫存分開之後，同碼不同貨主的訊息不再競爭，卻仍被擠進同一個
  partition——這一項**必須與唯一鍵的改動在同一個 change**，理由見 design。
- **補貨喚醒加上批次上限**：一次補貨涉及的批次數量由佇列內容而非事件決定，鎖範圍不可預測。
  超出上限時發一則續做事件（同 topic 同 partition key）。
- `OrderAllocatedIntegrationEvent` 加批次清單（每批對應的 `orderLineId`）。
- 庫存查詢與操作台的庫存頁改為批次列表，含「為何不可售」的落選理由。

## Capabilities

### New Capabilities

- `stock-allocation`——庫存如何被識別、如何被配給訂單。這是系統唯一在算的決策，目前散落在
  `fifo-replenishment-demo` 與 `hot-sku-concurrency-demo` 兩份 demo spec 的暗示裡，沒有自己的
  規格。本 change 把它獨立出來：庫存的身分與維度、可售性的判準、FEFO 的取用順序、預留的粒度。

### Modified Capabilities

- `order-promising-http-api`——庫存查詢由「一個 SKU 三個數字」改為批次列表。
- `demo-only-probes`——補貨探針的命令加倉別、入庫日、效期。
- `outbox-event-delivery`——`sku` 分區策略的 key 改為三維。
- `fifo-replenishment-demo`——補貨喚醒加上批次上限與續做事件；FIFO 佇列的既有保證不變。
- `hot-sku-concurrency-demo`——熱點的定義由「一個 SKU」變成「一個批次」，既有測試的前提失效。
- `demo-console-frontend`——庫存頁改批次列表；訂單詳細顯示配到哪些批。

## Impact

**Migration**：改寫 `V2__create_stock_pools.sql` 與 `V4__create_stock_reservations.sql`。
兩者皆尚未部署至任何環境，沿用前三個 change 的判準。既有 Postgres volume 必須移除重建。

**Domain**：`StockPool` 加四個維度與 `consume()`；`StockReservation` 粒度改為行 × 批；
`AllocationService` 的核心迴圈重寫；`AllocationOutcome` 要能承載「哪一條行的哪個 SKU 卡住」。

**測試的前提失效**：`AllocationHotSkuConcurrencyIntegrationTest`、
`AllocationFifoReplenishmentBatchIntegrationTest`、`AllocationConcurrencyEndToEndIntegrationTest`
三支都建立在「一個 SKU 一列庫存」上，須重新設計而非微調。

**壓測**：`run.sh seed` 要種批次；k6 的補貨請求要帶新欄位。partition key 換成三維之後，
v3 對比的數字要重測。

**可刪**：`DevSeedDataIntegrationTest` 中「每個庫存池的 SKU 都存在於主檔」那支——外鍵取代它。

**六件動工前事項已全數定案**，見
[execution-roadmap.md](../../../docs/execution-roadmap.md) 的 R3 段落。本 change 不重新討論，
只在 design 記錄與實作直接相關的部分。
