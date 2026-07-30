# Stock Reservation 設計

狀態：已確認，待實作

日期：2026-07-22

## 目標

在現有 Order Promising 服務中加入實務上的庫存 reservation，清楚區分：

- `onHandQuantity`：目前實際在庫數量。
- `reservedQuantity`：已保留給訂單、尚未交由下游履約完成的數量。
- `availableToPromise`（ATP）：可再承諾給新訂單的數量，為衍生值。

```text
availableToPromise = onHandQuantity - reservedQuantity
```

本設計的成功終點是建立有效 reservation、將訂單標記為 `ALLOCATED`，並可靠發布 `OrderAllocatedIntegrationEvent`。後續 Fulfillment、WMS 與實際出庫不在本次範圍。

## 範圍

本次包含：

- 單一倉庫。
- 一張訂單只有一個 SKU。
- 一張訂單必須一次完整取得 reservation，不支援部分 reservation。
- 庫存不足時建立 backorder。
- 補貨後依嚴格 FIFO 重試 backorder。
- 訂單取消時釋放 reservation。
- JPA optimistic locking 與有限次數重試。
- Inbox／Outbox 事件邊界。
- Dev profile 測試資料。

本次不包含：

- 多倉選擇與 `fulfillmentNodeId`。
- 跨倉拆單。
- 多品項訂單。（`order_lines` 這張表已在 `add-owner-and-order-line-model` 建立，收單仍限定
  恰好一行；放寬到多行是 R8。）
- `safetyStockQuantity`。
- Reservation expiration。
- `CONSUMED` reservation 狀態。
- Fulfillment Order、揀貨、包裝、Shipment 與實際出庫。
- 儲位、批號、序號與 inventory movement ledger。
- Redisson distributed lock 或資料庫 pessimistic lock。
- DLQ、Email、Slack 或 PagerDuty 整合。

## 實作任務

執行原則：

- 任務依編號與相依關係逐步進行；使用者可一次只指定一項。
- 除非使用者明確要求，執行某項任務時不提前實作後續任務。
- 一項任務只有在實作、對應測試與必要驗證都完成後，才能將 `[ ]` 更新為 `[x]`。
- 若實作發現設計需要改變，先更新本文件並取得確認，不自行擴張範圍。

目前進度：18 / 18

目前所有 SR-01～SR-18 任務均已完成。

主要相依路徑：

```text
Domain and application flow
SR-01 ─┐
SR-02 ─┼─> SR-04 ─> SR-05 ─┬─> SR-06 ─┐
SR-03 ─┘                   ├─> SR-07 ─┼─> SR-14 ─┬─> SR-15 ─┐
                             │                       └─> SR-17 ─┼─> SR-18
                             └─> SR-12 ─> SR-13 ┘

Independent infrastructure foundation
SR-08 ─> SR-09 ─┐
      ├> SR-10 ─┼─> SR-11 ─┐
      └─────────┴─> SR-12 ─┼─> SR-14
                              └> SR-16
```

### Domain layer（內圈）

- [x] **SR-01 — StockPool ATP domain model**（可獨立執行）
  - 將 `available` 改為 `onHandQuantity`。
  - 新增 `reservedQuantity` 與衍生的 `availableToPromise()`。
  - 將 `tryAllocate()` 改為 `canReserve()`／`reserve()`，並加入 `release()`。
  - 驗證 quantity 與 `reservedQuantity <= onHandQuantity` invariant。
  - 更新 StockPool domain unit tests。

- [x] **SR-02 — StockReservation domain model**（可獨立執行）
  - 新增 `StockReservation`、`ReservationStatus.ACTIVE/RELEASED`。
  - 測試建立、釋放、重複釋放及非法狀態轉換。

- [x] **SR-03 — Order lifecycle and domain event contracts**（可獨立執行）
  - 補齊 Order 的取消行為、狀態 invariant 與 `version`。
  - 明確分離 bounded context 內部 Domain Event 與跨邊界 Integration Event。
  - 明確定義並補齊 `OrderPlacedIntegrationEvent`、`OrderAllocatedIntegrationEvent`、`BackorderCreatedIntegrationEvent`、`OrderCancelledIntegrationEvent` 與 `StockReplenishedIntegrationEvent` payload。
  - Integration Event 依本文件建立 immutable payload，且每個事件都有不可變的 `eventId`；Domain Event 不承擔 messaging identity。
  - 移除或明確取代目前空白且未使用的 `StockAllocated`。
  - 增加 Order 狀態轉換與 event contract unit tests。

- [x] **SR-04 — Allocation domain service and strict policy**（依賴 SR-01～SR-03）
  - 重構 `AllocationPolicy` 與 `AllocationService`，只接受完整 reservation，不支援部分成功。
  - `AllocationPolicy` 提供 `StrictFifoAllocationPolicy` 與 `MaximizeFulfilledOrdersPolicy`；預設使用嚴格 FIFO。
  - 使用 generic immutable Context 與 `AllocationContextFactory` 配對 Policy；`AllocationService` 只依賴非泛型 selector facade。
  - 將 ATP 不足建模為業務結果，不將 optimistic lock conflict 混為 backorder。
  - 建立嚴格 FIFO domain policy：第一張無法滿足就停止，不跳過後單。
  - 以純 domain unit tests 驗證 allocation、backorder、release 與 head-of-line blocking。

### Application layer

- [x] **SR-05 — Allocate Order application flow**（依賴 SR-01～SR-04）
  - 定義／補齊 Order、StockPool 與 StockReservation ports；`AllocateOrderUsecase` 的輸入改為 `AllocateOrderCommand(orderId)`，而非直接處理 Integration Event。
  - 重構 `AllocationService`、`OrderAllocationCoordinator` 與 `AllocateOrderUsecase`。
  - 成功時更新 StockPool、建立 ACTIVE reservation、標記 Order ALLOCATED，並發布完整的 `OrderAllocationCompleted` Domain Event。
  - ATP 不足時不建立 reservation，標記 Order BACKORDERED，並發布 `OrderBackordered` Domain Event。
  - 作為 Integration Event 來源的 Domain Event 必須帶齊 translator 所需的業務資料；translator 不額外查詢 Repository 拼裝 payload。
  - 使用 mocked ports 完成成功、不足、非 PENDING Order 的 application tests；Integration Event、Inbox 與 Outbox 的測試屬於 SR-12／SR-14。

- [x] **SR-06 — Cancellation and reservation release application flow**（依賴 SR-01～SR-03、SR-05）
  - 新增取消 Order use case，發布 `OrderCancelled` Domain Event；SR-12 的 translator 再將其轉為 `OrderCancelledIntegrationEvent` 並寫入 Outbox。
  - 新增處理 `OrderCancelledIntegrationEvent` 的 release use case。
  - ACTIVE reservation 改為 RELEASED，並將數量從 `reservedQuantity` 釋放。
  - PENDING／BACKORDERED 取消或重複事件維持合法 no-op。
  - 釋放量大於 `reservedQuantity` 時拋出錯誤並 rollback，不以歸零掩蓋不一致。
  - 使用 mocked ports 測試各種取消狀態與冪等行為。

- [x] **SR-07 — Replenishment application flow**（依賴 SR-01、SR-03～SR-05）
  - `StockReplenishedIntegrationEvent` 僅接受正向增量並增加 `onHandQuantity`。
  - 透過 FIFO repository port 取得穩定排序的 backorders，交由 SR-04 policy 執行。
  - 第一張無法完整 reservation 時停止。
  - 每張成功配置的 backorder 建立 ACTIVE `StockReservation`；由 Coordinator 統一保存 StockPool、Orders 與 Reservations，並發布完整的 `OrderAllocationCompleted` Domain Event。
  - 使用 mocked ports 測試增量冪等、FIFO、head-of-line blocking 與未知 SKU 錯誤。

### Independent infrastructure foundation（可與內圈平行）

- [x] **SR-08 — Database migration and PostgreSQL test foundation**（可獨立執行）
  - 導入 Flyway，建立可重複驗證的 baseline migration。
  - 導入 Testcontainers PostgreSQL；並行與 constraint tests 不使用行為不同的 in-memory database 取代。
  - 提供 dev／test datasource 基礎設定，production 不自動建立或灌入測試資料。
  - 加入 migration 啟動與 rollback-on-test-failure smoke test。

### Infrastructure adapters（外圈）

- [x] **SR-09 — StockPool persistence adapter**（依賴 SR-01、SR-08）
  - 更新 `StockPoolEntity`、`StockPoolMapper` 與 Repository adapter。
  - 保留 `@Version`，新增 `updatedAt`。
  - 以 migration 加入 `stock_pools` table、SKU unique、非負數與 reserved 不超過 on-hand 的資料庫限制。
  - 確認補貨、reserve 與 release 都會更新 `updatedAt`。
  - 增加 persistence mapping／repository tests。

- [x] **SR-10 — Order persistence adapter and FIFO query**（依賴 SR-03、SR-08）
  - 建立目前缺少的 Order JPA entity、mapper 與 repository adapter。
  - 持久化 `version` 並實作 `findBackordersBySkuInFifoOrder()`。
  - 以 migration 加入 `orders` table、quantity constraint 與 `(sku, status, backordered_since, id)` index。
  - 增加 mapping、狀態還原與穩定 FIFO repository tests。

- [x] **SR-11 — StockReservation persistence adapter**（依賴 SR-02、SR-08～SR-10）
  - 新增 JPA entity、mapper、repository adapter 與 `@Version`。
  - 以 migration 加入 `stock_reservations` table、`order_id` unique、foreign keys、quantity 與狀態限制。
  - 實作 `findActiveByOrderId()` 並增加 mapping、constraint 與 repository tests。

- [x] **SR-12 — Domain Event translation and transactional Inbox/Outbox adapters**（依賴 SR-03、SR-05、SR-08）
  - 補齊 typed Inbox／Outbox entities、repositories 與 migrations。
  - Inbox 以 `event_id` unique／primary key 保證 claim idempotency。
  - Outbox 保存 event identity、aggregate reference、type、payload 與 occurred timestamp；row 寫入後不可由應用程式標記發布狀態。
  - 實作 application-layer translator listener，將 Domain Event 映射為 Integration Event 並 append 至 Outbox；business Coordinator 不直接建立或寫入 Integration Event。
  - Integration Event 必須逐一寫入 Outbox，且 Domain Event translation、業務更新與 Outbox 寫入能在同 transaction rollback。
  - 修正 event list 被當成單一事件發布的問題，增加 persistence 與 rollback tests。

- [x] **SR-13 — Debezium Outbox CDC to Kafka**（依賴 SR-12）
  - 新增 `event_outbox.route`，由 translator 依 Integration Event 生產端寫入既定 Kafka topic；保留 `aggregatetype` 的 Aggregate 語意，不以它決定 topic。
  - 設定 PostgreSQL logical replication 與 Debezium connector，僅擷取 `event_outbox` 的已 commit row，並以 Outbox Event Router 的 `route.by.field=route` 發布至 Kafka。
  - 不實作 application polling relay，也不回寫 `publishedAt`、`attempts` 或 `lastError`；connector offset、重試與故障資訊由 Kafka Connect／Debezium 營運。
  - CDC delivery 為 at-least-once；相同 `eventId` 可能重送，consumer 必須以 Inbox 保證冪等。
  - 以 Testcontainers 啟動 PostgreSQL、Kafka、Kafka Connect／Debezium，驗證 committed Outbox row 會送達 Kafka。
  - 移除跨 context 流程對同步 `ApplicationEventPublisher` chaining 的依賴；內部 Domain Event translator 仍在原 transaction 同步寫入 Outbox。
  - 增加 committed row 發布、正常 restart 依 offset 接續、snapshot 回放與重複 delivery 的整合測試。

### Composition and verification（最外圈）

- [x] **SR-14 — Transaction wiring and Integration Event entrypoints**（依賴 SR-05～SR-13）
  - 將 application ports 接到 JPA、Inbox 與 Outbox adapters。
  - 確保 Aggregate 更新、Reservation 寫入、Domain Event translation 與 Outbox 寫入位於同一個 transaction；Inbox claim 與接收 command 的業務更新位於同一個 transaction。
  - 接回 Kafka Integration Event consumers：先將 Debezium／Kafka record 反序列化為 typed Integration Event，再映射為本服務的純業務 Command；不得讓 use case 依賴 Kafka record、Debezium envelope 或外部 JSON。
  - 建立 `InboundCommand<C>`，封裝 `command` 與 SR-12 的 `MessageMetadata(eventId, eventType)`；consumer 是 metadata 的唯一來源。
  - message-driven use case 以 `handle(InboundCommand<C>)` 作 transaction boundary，先 claim Inbox 再執行業務邏輯；同步 HTTP／內部操作 use case 不強制使用此 wrapper。
  - 初始映射：`OrderPlacedIntegrationEvent` → `InboundCommand<AllocateOrderCommand>`、`OrderCancelledIntegrationEvent` → `InboundCommand<ReleaseReservationCommand>`、`StockReplenishedIntegrationEvent` → `InboundCommand<ReplenishStockCommand>`。
  - 以 `order_id` unique constraint 作為同一訂單只能建立一筆 reservation 的最後防線。
  - 增加 allocation、cancel、replenishment transaction rollback integration tests。

- [x] **SR-15 — Optimistic-lock retry and observability**（依賴 SR-14）
  - 以獨立 `AllocationRetryExecutor` 與 Spring `RetryTemplate` 包住 Transactional Usecase，最多嘗試三次。
  - 每次重試重新讀取 Order、StockPool、Reservation 與 FIFO 清單。
  - 重試耗盡時拋出 `AllocationConcurrencyExhaustedException`，不得轉成 BACKORDERED。
  - 增加 structured error log 與 `order_allocation_retry_exhausted_total` metric。
  - 測試每次 retry 使用新 transaction，以及 exhausted rollback 行為。

- [x] **SR-16 — Dev-only consistent seed data**（依賴 SR-09～SR-11；可獨立於 SR-12～SR-15 執行）
  - 新增 `@Profile("dev")`、idempotent 的 `ApplicationRunner`。
  - 建立 `SKU-AVAILABLE` 與 `SKU-EMPTY` StockPools。
  - `SKU-PARTIALLY-RESERVED` 必須同時建立對應的 ALLOCATED Order 與 ACTIVE StockReservation，不得只設定孤立的 `reservedQuantity`。
  - 驗證重啟不會重複建立，且 test／production profile 不載入 seed。

- [x] **SR-17 — End-to-end business workflow verification**（依賴 SR-01～SR-14；SR-16 不阻擋）
  - 執行並修正全部既有測試。
  - 使用 SR-08 的 PostgreSQL test environment，驗證 allocation、取消、補貨、嚴格 FIFO 與 Inbox／Outbox 的端到端流程。
  - 確認未引入多倉、shipment、WMS、expiration 或 safety stock。

- [x] **SR-18 — Retry and concurrency end-to-end verification**（依賴 SR-15、SR-17）
  - 使用 SR-08 的 PostgreSQL test environment，新增兩筆訂單競爭同一 StockPool 的整合測試，確認不會超賣。
  - 驗證 optimistic-lock conflict 會重新讀取資料並依 retry policy 收斂為正確的 allocation 或 backorder 結果。
  - 驗證 retry exhausted 時拋出 `AllocationConcurrencyExhaustedException`、所有嘗試均 rollback，且不會將技術衝突錯誤建模為 BACKORDERED。
  - 驗證 retry metric 與 structured log 可辨識 operation、eventId 與 attempt。
  - 所有驗證通過後，將文件狀態改為「已實作」。

### Demo-01 — 熱門 SKU 併發劇本（不在 SR-01～SR-18 編號內）

- [x] **Demo-01 — Hot-SKU concurrency demo**（依賴 SR-18）
  - SR-18 只用兩筆訂單證明 optimistic-lock retry 機制存在；Demo-01 將同一機制放大到 1,000 筆同 SKU、僅 10 件庫存的併發送出，觀察在既有 datasource connection pool 限制下最終是否仍收斂為正確結果。
  - 以同一個 start gate 釋放 1,000 個 virtual-thread 任務送出 `OrderPlacedIntegrationEvent`；datasource connection pool（預設 10 個連線）自然限制同時執行的 transaction 數，不代表宣稱 1,000 個 DB transaction 真的同時執行。
  - test-only interceptor 讓最先抵達的兩個 allocation attempt 在讀到同一版 StockPool 後才同時釋放，確保至少一次真實 JPA optimistic-lock conflict 是決定性發生，而不是仰賴機率性的自然碰撞；不注入合成例外。
  - 只收集 `AllocationConcurrencyExhaustedException` 對應的原始事件，於併發波次結束後以同一 `eventId` 重送，模擬 broker 的 at-least-once redelivery；不模擬 broker 的 backoff 或 DLT policy。
  - 對帳最終持久化狀態：10 張 Order `ALLOCATED`、990 張 `BACKORDERED`；10 筆 ACTIVE StockReservation 總量為 10；StockPool 的 ATP 為 0；1,000 個 eventId 均已於 Inbox claim；Outbox 記錄總數為 1,000 且對應最終 Order 結果。
  - **範圍邊界：** 本示範驗證的是 bounded database concurrency 下的 1,000 筆併發 submissions 最終收斂，**不是** production throughput/latency benchmark，也不啟動 Kafka broker、Debezium connector 或另一套 load-testing 工具；不變更 allocation policy、Kafka topics 或 Integration Event 契約；不實作 FIFO replenishment 或 read-model replay demo。

### Demo-02 — FIFO 補貨批次劇本（不在 SR-01～SR-18 編號內）

- [x] **Demo-02 — FIFO replenishment batch demo**（依賴 SR-07、SR-17；延續 Demo-01 Non-Goals 提到的下一個示範）
  - 既有的 `AllocationWorkflowEndToEndIntegrationTest` 只用兩張訂單驗證 FIFO 補貨，不足以在量體下暴露「跳過 head-of-line blocking 訂單」這類演算法錯誤。Demo-02 將排隊量體放大到 1,000 張同 SKU BACKORDERED 訂單，驗證循序（非併發）`StockReplenishedIntegrationEvent` 喚醒佇列後的批次配置決策。
  - 直接以 `Order.rehydrate(...)` 種入已排序穩定（`backorderedSince` 逐筆遞增）的 BACKORDERED fixture，不經過真正的下單配置流程；驗證的是補貨觸發批次配置這一段，下單配置路徑已由 Demo-01 覆蓋。
  - 數量分布固定、可手算：前 500 張 quantity 皆為 1，第 501 張是刻意補不滿的 blocker（quantity 999），後 499 張 quantity 皆為 1。不用隨機數量，避免測試自己重新實作一次 FIFO 演算法來推導期望值。
  - 第一次補貨量精準等於前 500 張總和（500），對帳：500 張 Order `ALLOCATED`、500 張仍 `BACKORDERED`（含 blocker 與其後 499 張未被跳過配置的小單）；500 筆 ACTIVE StockReservation 總量為 500；StockPool on-hand=500、reserved=500、ATP=0；1 個 eventId 已於 Inbox claim；Outbox 恰 500 筆 `OrderAllocatedIntegrationEvent`。
  - 接著送第二次（循序）補貨，量等於 blocker 與其後 499 張的總和（1,498），驗證「喚醒佇列」的後半段——先前卡住的訂單能正確恢復配置：全部 1,000 張變為 `ALLOCATED`、1,000 筆 ACTIVE StockReservation 總量 1,998、StockPool on-hand=1,998、reserved=1,998、ATP=0、Inbox 累積 2 筆 claim、Outbox 恰 1,000 筆 `OrderAllocatedIntegrationEvent`。
  - 除了聚合數字，額外用 `firstOrderId`／`blockerOrderId`／`lastOrderId` 三個關鍵位置的逐筆身分驗證，確認 blocker 在第一階段仍是 BACKORDERED、第二階段才變 ALLOCATED——因為除了 blocker 外每張訂單 quantity 都是 1，只看聚合數字無法分辨「選對哪幾張」，只能證明「選對幾張」。
  - **範圍邊界：** 本示範驗證的是循序補貨事件觸發的 FIFO 批次配置決策，**不含**併發競爭（多個補貨事件同時到達、補貨當下有新訂單插隊）；不隨機化數量分布；不變更 `StrictFifoAllocationPolicy`、Kafka topics 或 Integration Event 契約；不是 production benchmark；不實作 read-model replay demo。

## 資料模型

> 訂單層在 `add-owner-and-order-line-model` 之後改為五張表：`owners` → `products` → `skus`
> → `orders` → `order_lines`。`orders` 不再直接持有 `sku` 與 `quantity`——它們移到行上。
> 權威定義見 `V3__create_ordering_tables.sql`，該檔的註解記錄了每個取捨的理由。

### 主檔：`owners` / `products` / `skus` / `fulfillment_nodes` / `owner_nodes`

貨主是 3PL 的委託方——倉庫不擁有貨，貨屬於他們。商品分兩層：**款**（`products`，溫層屬這裡，
同一款的所有規格必然同溫層）與**規格**（`skus`，重量屬這裡，500ml 與 1L 重量不同）。

三張表都用代理鍵，唯一性由 constraint 表達：`UNIQUE (owner_id, code)`。但 `skus` 指向
`products`、`order_lines` 指向 `skus` 的外鍵**刻意仍走自然鍵** `(owner_id, product_code)`
與 `(owner_id, sku_code)`。在 3PL 裡編碼由貨主自訂、跨貨主必然撞號，走自然鍵的外鍵強制每
一次參照都帶上貨主，「款與規格必須屬於同一個貨主」因此由資料庫保證，不必在應用層檢查。

**貨主與倉庫都只有身分。** `owners` 是 `id`／`code`／`name`，`fulfillment_nodes` 是
`id`／`code`／`name`——兩者都沒有 `status`。停用在營運上是真的，但目前沒有任何決策讀它，
而一個沒有讀者的欄位會讓下一個人以為它有意義。等收單真的要擋停用貨主或停用倉時再加。

`owner_nodes`（PK `(owner_id, node_id)`）記錄貨主能從哪些倉出貨。它是多對多——一個貨主可以
有多個倉，**一個倉也服務多個貨主**，後者是 3PL 的定義性特徵。這張表沒有設定欄位；可否換
批號之類的設定屬 R3。

### `orders`

| 欄位 | 型別 | 限制／說明 |
|---|---|---|
| `id` | `UUID` | Primary key |
| `owner_id` | `UUID` | `NOT NULL`, FK → `owners` |
| `external_order_no` | `VARCHAR` | `NOT NULL`, `UNIQUE (owner_id, external_order_no)` |
| `ship_to_zone` | `VARCHAR` | `NOT NULL`。原為選點輸入，③ 移出範圍後只作為地址的一部分保留 |
| `ship_to_address` | `VARCHAR` | `NOT NULL`，履約與面單用，sourcing 不看 |
| `promised_delivery_date` | `DATE` | `NOT NULL` |
| `fulfillment_node_id` | `UUID` | **`NOT NULL`**，貨主在上游指定的出貨倉。與 `owner_id` 組成**複合外鍵**指向 `owner_nodes`——因此「倉存在但這個貨主沒掛」由資料庫擋下，不需應用層檢查。R3 之後配貨只在該倉的庫存裡進行 |
| `status` | `VARCHAR` | `PENDING`, `ALLOCATED`, `BACKORDERED`, `CANCELLED` |
| `placed_at` | `TIMESTAMPTZ` | `NOT NULL` |
| `allocated_at` | `TIMESTAMPTZ` | Nullable |
| `backordered_since` | `TIMESTAMPTZ` | Nullable |
| `cancelled_at` | `TIMESTAMPTZ` | Nullable |
| `version` | `BIGINT` | `NOT NULL`, JPA `@Version` |

`placedAt`、`allocatedAt`、`backOrderedSince` 與 `cancelledAt` 已表達重要生命週期時間，
因此不另加通用 `created_at`／`updated_at`。

地址內嵌於此而不另開 `addresses` 表：地址逐單指定、不可重用，獨立一張表只會多一層 join。

### `order_lines`

| 欄位 | 型別 | 限制／說明 |
|---|---|---|
| `id` | `UUID` | Primary key |
| `order_id` | `UUID` | `NOT NULL`, FK → `orders`, `UNIQUE (order_id, line_no)` |
| `line_no` | `INTEGER` | `NOT NULL`，上游單的行號 |
| `owner_id` | `UUID` | `NOT NULL`，反正規化自 header |
| `sku_code` | `VARCHAR` | `NOT NULL`, FK `(owner_id, sku_code)` → `skus` |
| `quantity` | `INTEGER` | `NOT NULL`, `CHECK (quantity > 0)` |
| `status` | `VARCHAR` | 隨整張單走（ship-complete） |
| `backordered_since` | `TIMESTAMPTZ` | Nullable，恆等於 header 的值 |

`owner_id` 反正規化到行上不是為了省一次 join，而是為了建得出 FK——`(owner_id, sku_code)`
才是 `skus` 的自然鍵。它的值不可變（一張單的貨主不會改變），因此沒有同步成本。

`backordered_since` 同樣是為了 index 而非領域事實：採 ship-complete 後所有行在同一交易內
一起配到或一起缺貨，此欄恆等於 header。它存在純粹是為了讓 FIFO 佇列查詢能走單表 index。
對應地**不建**行層級的 `allocated_at`——它同樣恆等於 header，但沒有任何 index 需要它。

**收單目前限定恰好一行**，由 `Order.place()` 強制。放寬多行是 R8 的工作；在那之前，
`getDemand()` 這類以集合運算取值的入口已經是多行安全的，`OrderingArchitectureTest`
則把「取第一行」這種寫法變成建置失敗。

### 兩項已知的中間狀態——**都已於 R3 收尾**

`add-owner-and-order-line-model` 把貨主帶進了訂單層而沒有帶進庫存層，這裡曾記錄兩件刻意留下
的不一致。`add-batch-stock-and-fefo`（R3）把兩件都收了，保留原文的結論以便對照：

**一、跨貨主隔離**已生效。`stock_pools` 的唯一鍵是
`(owner_id, node_id, sku_code, in_date, expiry_date)`，並有 `(owner_id, sku_code)` → `skus`
的複合外鍵——跨貨主的錯誤組合從「配不到貨」變成「寫不進去」。操作台的庫存頁不再有「補貨要
選貨主、查詢不用」那個不對稱，兩邊都要選。

**二、Kafka partition key** 改了，但**不是原文預測的形狀**。原文寫「R3 必須把 key 改成
`ownerId + "/" + nodeId + "/" + skuCode`」，R3 的 review 期間否決了那個三維版本，改成
`(貨主, 倉)`——設定值也由 `sku` 改名為 `stock`。

理由是那個三維 key 有到期日，而下一段（原文自己寫的）已經指出了它：per-SKU 的 key 與
ship-complete 根本衝突。**把 SKU 拿掉就沒有那個到期日**——一張單不管跨幾個 SKU 都只屬於一個
`(貨主, 倉)`，一個 writer 看得到整張單。代價是過度序列化（同貨主同倉、不同 SKU 的訂單也
排隊），而那是刻意選的方向：太粗只是慢但正確，太細則直接失去 single-writer。

因此原文結尾「這個策略最終仍會退場，記在 R8」**不再成立**，R8 的對應任務已標記為由 R3 解決。

### `stock_pools`

**一列是一批貨，不是一個 SKU 的池。** 表名與類別名都還叫 pool（改名的取捨見
`docs/dom-order-intake-scope.md`），但一列承載的是「某貨主在某倉、某日到貨、某效期的那一批」。

| 欄位 | 型別 | 限制／說明 |
|---|---|---|
| `id` | `UUID` | Primary key（代理鍵；身分是下面五個維度） |
| `owner_id` | `UUID` | `NOT NULL`，身分維度 |
| `node_id` | `UUID` | `NOT NULL`, FK → `fulfillment_nodes`，身分維度 |
| `sku_code` | `VARCHAR(64)` | `NOT NULL`，身分維度 |
| `in_date` | `DATE` | `NOT NULL`，身分維度；同效期時決定 FEFO 的先後 |
| `expiry_date` | `DATE` | `NOT NULL`，身分維度；FEFO 的主排序鍵 |
| `on_hand_quantity` | `INTEGER` | `NOT NULL`, `CHECK (on_hand_quantity >= 0)` |
| `reserved_quantity` | `INTEGER` | `NOT NULL DEFAULT 0`, `CHECK (reserved_quantity >= 0)` |
| `version` | `BIGINT` | `NOT NULL`, JPA `@Version` |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` |

```sql
CONSTRAINT uq_stock_pools_batch
    UNIQUE (owner_id, node_id, sku_code, in_date, expiry_date)
CONSTRAINT fk_stock_pools_sku
    FOREIGN KEY (owner_id, sku_code) REFERENCES skus(owner_id, sku_code)
CHECK (reserved_quantity <= on_hand_quantity)
CREATE INDEX idx_stock_pools_fefo
    ON stock_pools (owner_id, node_id, sku_code, expiry_date, in_date, id)
```

三件不是顯而易見的事：

**`expiry_date` 是 `NOT NULL`。** PostgreSQL 的 unique 把 NULL 視為互不相同，可空會讓同日到貨
的無效期商品各成一列而非合併，而那是安靜發生的。

**`in_date` 參與身分而不只是屬性。** 這讓補貨要嘛完全命中一列、要嘛新開一列，因此沒有合併
規則要定義，也就沒有規則會定錯。

**刻意沒有 `CHECK (expiry_date >= in_date)`。** 貨可能到貨時就已經過期（運輸延誤、上游出錯），
而倉庫實體上就是收到了那批貨；加上那個約束，要把它記進系統就得在兩個日期裡挑一個造假。

`available_to_promise` 不存入資料庫——存了就是第二個真相來源，而它遲早會與第一個不合。Domain
model 提供：

```java
public int availableToPromise() {
  return onHandQuantity - reservedQuantity;
}
```

**「能不能配」不是這一列的屬性。** `StockPool.isExpired(today)` 只回答「過期了沒有」；配貨拿
得到什麼還要看有沒有量，那個判斷在查詢裡（`findAllocatableBatchesInFefoOrder`，兩個篩選條件：
`expiry_date >= today` **且** `on_hand_quantity > reserved_quantity`）。分開是刻意的——「有 100
件但一件都出不了」與「什麼都沒有」在畫面上要引導出不同的動作。

### `stock_reservations`

**粒度是訂單行 × 批次**，不是訂單。一條行的需求跨三個批就是三筆預留。

| 欄位 | 型別 | 限制／說明 |
|---|---|---|
| `id` | `UUID` | Primary key，獨立的 reservation identity |
| `order_line_id` | `UUID` | `NOT NULL`, FK → `order_lines(id)` |
| `stock_pool_id` | `UUID` | `NOT NULL`, FK → `stock_pools(id)` |
| `quantity` | `INTEGER` | `NOT NULL`, `CHECK (quantity > 0)` |
| `status` | `VARCHAR` | `ACTIVE`, `RELEASED`, `CONSUMED` |
| `reserved_at` | `TIMESTAMPTZ` | `NOT NULL` |
| `released_at` | `TIMESTAMPTZ` | Nullable；只有 `RELEASED` 時有值 |
| `version` | `BIGINT` | `NOT NULL`, JPA `@Version` |

```sql
CONSTRAINT uq_stock_reservations_line_pool UNIQUE (order_line_id, stock_pool_id)
CREATE INDEX idx_stock_reservations_line ON stock_reservations (order_line_id);
CREATE INDEX idx_stock_reservations_pool ON stock_reservations (stock_pool_id);
```

唯一鍵是「行 × 批」而不是「行」：同一條行對**不同**批的第二筆是合法的，對**同一**批的第二筆
才是重複。原本的 `UNIQUE (order_id)` 表達的是「一張單一筆預留」，分批之後那條規則不再成立。

曾考慮「一條行一筆預留，內含批次清單」——否決，因為釋放與消耗都是逐批發生的（出貨時某一批
先被揀完），一筆多批的預留表達不了部分消耗，而那正是履約層兩本帳要對的東西。

Reservation 不重複保存 `sku_code`，而是透過 `stock_pool_id` 取得。也不加入通用
`created_at`／`updated_at`；`reserved_at`、`released_at` 與 `version` 已足以表達生命週期。

### `event_inbox`（技術表）

| 欄位 | 型別 | 限制／說明 |
|---|---|---|
| `event_id` | `UUID` | Primary key；重複事件無法再次 claim |
| `event_type` | `VARCHAR` | `NOT NULL` |
| `processed_at` | `TIMESTAMPTZ` | `NOT NULL`；與業務 transaction 一起 commit |

Inbox row 的存在代表該事件已隨業務更新成功 commit；若業務 transaction rollback，Inbox claim 也必須 rollback。

### `event_outbox`（技術表）

| 欄位 | 型別 | 限制／說明 |
|---|---|---|
| `id` | `UUID` | Primary key；Debezium event identity |
| `aggregatetype` | `VARCHAR` | `NOT NULL`；來源 Aggregate type，例如 `Order` |
| `aggregateid` | `VARCHAR` | `NOT NULL`；來源 Aggregate 的識別碼，例如 Order 事件為 `orderId` |
| `type` | `VARCHAR` | `NOT NULL`；Integration Event type |
| `route` | `VARCHAR` | `NOT NULL`；目標 Kafka topic，例如 `ordering.order-events` |
| `partition_key` | `VARCHAR` | `NOT NULL`；Kafka message key，支援 UUID／SKU 等表示 |
| `payload` | `JSONB` | `NOT NULL` |
| `timestamp` | `TIMESTAMPTZ` | `NOT NULL`；Integration Event 發生時間 |

本專案採用 Debezium Outbox Event Router 的 canonical column names，並額外加入 `route` 與 `partition_key`。`aggregatetype` 與 `aggregateid` 保留來源 Aggregate 的語意，回答「這筆事件屬於哪個 aggregate」；`route` 與 `partition_key` 表達傳輸決策，回答「這則訊息去哪個 topic、用什麼 key 分區」。Debezium 以 `route.by.field=route` 決定 topic、以 `table.field.event.key=partition_key` 決定 message key，不讀取 aggregate 欄位。

**Aggregate 欄位不得兼任傳輸決策。** 這條規則同時適用於 `aggregatetype` 與 `aggregateid`：前者不改作傳輸路由（因此有 `route`），後者不改作 message key（因此有 `partition_key`）。傳遞一則 Kafka 訊息需要 topic 與 key 兩個決定，兩者各有專屬欄位。`aggregateid` 尤其不可兼任——`archone.allocation.partition-key-strategy=sku` 時 message key 是 SKU 而 aggregate 仍是 Order／`orderId`，兩者的值會分岔，一個欄位無法同時給出正確答案。Debezium 的 `table.field.event.key` 預設值雖是 `aggregateid`，但該設定存在本身就表示框架預期兩者可以分離。`route` 與 `partition_key` 都是 application／infrastructure 的 delivery metadata，不屬於 Domain Event。本專案不在 Outbox row 保存發布狀態。Debezium 從 PostgreSQL WAL 取得已 commit 的變更並以 connector offset 追蹤進度；consumer 以 Inbox 承受可能的重複發布。Outbox row 最少保留 30 天，並且只有在 Debezium replication slot lag 位於安全範圍時才可依 `timestamp` 清理；不得只因資料變舊就刪除。Kafka Connect error handling 與 DLQ policy 屬於 SR-13 的部署／營運設定，不另建 DLQ table。

目前 `timestamp` 是 PostgreSQL `TIMESTAMPTZ`，因此不設定 Outbox Event Router 的 `table.field.event.timestamp`（該設定要求 `INT64`）。Kafka record timestamp 使用 CDC 發生時間；原始 Integration Event 發生時間仍保留在 Outbox row 與 payload 中。

Debezium 正常 restart 時依 Kafka Connect offset 與 PostgreSQL WAL 接續，不重新掃描 Outbox。第一次建立 connector，或 offset 遺失後重建 connector 時，會 snapshot 當時仍在 retention 範圍內的 Outbox rows；consumer Inbox 必須能安全忽略因此重送的相同 `event_id`。

## 狀態轉換

### Order

```text
PENDING ──reservation 成功──> ALLOCATED
PENDING ──庫存不足─────────> BACKORDERED
PENDING ──取消─────────────> CANCELLED
BACKORDERED ──補貨後成功───> ALLOCATED
BACKORDERED ──取消─────────> CANCELLED
ALLOCATED ──取消───────────> CANCELLED
CANCELLED ──再次取消───────> no-op
```

### StockReservation

```text
建立成功：ACTIVE
ACTIVE ──訂單取消──> RELEASED
ACTIVE ──出貨────> CONSUMED
RELEASED ──重複取消──> no-op
```

`CONSUMED` 已加入，但**本階段不產生它**——它為履約層的出貨扣帳準備（`StockPool.consume()`
同時扣 `reservedQuantity` 與 `onHandQuantity`，與只扣前者的 `release()` 語意不同）。

必須現在就存在的理由是 R4 的 `demand_lines` view 會用 `status IN ('ACTIVE','CONSUMED')` 當
「已滿足」的謂詞，而 R4 緊接在 R3 之後。**日後再擴充這個 enum 時必須同步檢查那個 view**
——漏掉會讓已出貨的訂單重新出現在待配佇列，而當下沒有任何測試會發現。

## 核心流程

### 建立 reservation

接收端與業務處理的流程如下：

1. Kafka listener 收到 `OrderPlacedIntegrationEvent`。
2. listener 轉為 `AllocateOrderCommand(orderId)`，並以 `eventId` 作為獨立 `messageId` 呼叫 use case。
3. use case transaction 先以 `messageId` claim Inbox，成功後重新讀取 `Order`，並確認 Order 為 `PENDING`。
4. 以 `findAllocatableBatchesInFefoOrder(ownerId, nodeId, skuCode, today)` 取得**已依 FEFO
   排序的可配批次**。篩選（未過期、還有量）與排序（`expiry_date, in_date, id`）都在資料庫做
   ——批數隨營運時間成長，把配不到的載進記憶體只為了丟掉是錯的方向。「今天」由
   `BusinessCalendar` 依營運時區決定，不是 UTC。
5. **先把整張單的取用計畫算完**（跨批依序取用直到湊滿），確認每一條行都湊得滿，才真正動
   `reserve()`。規劃與套用分開是 ship-complete 的實作機制：邊算邊扣的話，需求 80 而可配只有
   50 時會先扣掉 50 才發現配不到，那 50 件就被一張出不了貨的單鎖住。
6. 成功時每一個取用建立一筆 `ACTIVE` `StockReservation`（行 × 批），並將 Order 標記為
   `ALLOCATED`。**批次的寫入依 `(sku_code, expiry_date, in_date, id)` 排序**，不可依賴集合的
   自然順序——兩個交易以相反順序鎖同一組列就會死鎖。
7. allocation flow 發布 `OrderAllocationCompleted` Domain Event；同 transaction 的 translator listener 將它轉成 `OrderAllocatedIntegrationEvent` 並寫入 Outbox。**那則對外事件只帶 `orderId` 與時間**，不帶批次清單——它描述的是會被取消釋放的狀態，而取消事件在另一個 topic、沒有順序保證；下游要知道配到哪些批就回頭讀 `stock_reservations`。
8. Commit；commit 時由 `@Version` 偵測並行衝突。

若可配總量不足（或一批可配的都沒有）：

1. **不修改任何一批** StockPool——跨批的總量不足時，一批都不預留。
2. 不建立 StockReservation。
3. 將 Order 標記為 `BACKORDERED` 並發布 `OrderBackordered` Domain Event。
4. translator listener 將它轉成 `BackorderCreatedIntegrationEvent` 並寫入 Outbox。

`StockPool` 以 `canReserve()` 表達單一批的 ATP capability query，並以 `reserve()` 執行預留；跨批的整籃判斷在 `AllocationService.planPicks()`。

### 取消並釋放 reservation

Ordering 在取消 transaction 中將 Order 改為 `CANCELLED` 並發布 `OrderCancelled` Domain Event；translator 將其寫成 `OrderCancelledIntegrationEvent` Outbox event。Kafka delivery 後，Allocation listener 將事件轉為 release command，並以 `eventId` 作為 `messageId` 呼叫 use case；use case 在同一個 command transaction claim Inbox，並執行：

1. 依 `orderId` 讀出訂單，取得它每一條行的 id。`stock_reservations` 指向 `order_lines`，
   用訂單直接查就得 join 到 ordering 的表，而那個方向的依賴不該由 allocation 的 repository
   建立。
2. 以 `findActiveByOrderLineIds(...)` 取得**所有**有效預留。一條行跨三批就有三筆，**全部**都
   要釋放——只放第一筆會讓其餘批的量永遠鎖著，而且不會有任何錯誤浮現。
3. 找不到時視為合法 no-op；PENDING／BACKORDERED 訂單本來就沒有 reservation。
4. 每一筆改為 `RELEASED` 並設定 `releasedAt`。
5. 對每一批呼叫 `StockPool.release(quantity)`，減少 `reservedQuantity`；批次的寫入同樣依
   `(sku_code, expiry_date, in_date, id)` 排序。
6. Commit。

**釋放不會喚醒缺貨佇列**——那是一個已知的缺口，記在
[`execution-roadmap.md`](execution-roadmap.md) 的「已識別但未排程」。

不得以 `Math.max(0, reserved - quantity)` 隱藏資料不一致；若釋放量大於 `reservedQuantity`，應拋出錯誤並 rollback。

### 補貨與 backorder FIFO

`StockReplenishedIntegrationEvent` 帶**五個維度加數量**：貨主、倉、SKU、入庫日、效期、數量。
數量是正向增量，必須大於零。補貨依五維鍵 upsert：

```text
命中既有列 → onHandQuantity += quantity
沒有命中   → 新開一列
```

沒有「差不多就併進去」的規則，因為根本沒有規則要定——五個維度全等才是同一批。

事件以 `eventId + Inbox` 保證不會重複加庫存。目前不支援負數 replenishment、盤點覆蓋或
`StockAdjusted`。

**喚醒有張數上限**（`archone.allocation.replenishment-wake-limit`），超出時經 outbox 發一則
續做事件（同 topic 同 partition key）。終止條件是「本輪**真正配到**的張數 < 上限即不續做」
——判準必須是「配到幾張」而不是「讀到幾張」，兩者只在 head-of-line blocker 卡住時不同，而那
正是會無限續做的情形。

Backorder 查詢 contract：

```java
List<Order> findBackordersBySkuInFifoOrder(String sku);
```

查詢須穩定排序：

```sql
SELECT *
FROM orders
WHERE owner_id = :ownerId
  AND sku_code = :skuCode
  AND status = 'BACKORDERED'
ORDER BY backordered_since ASC, id ASC;
```

查詢條件帶貨主，因為 SKU 代碼跨貨主撞號——只憑代碼決定不了要喚醒誰的佇列。實作是
`findBackordersBySkuInFifoOrder(ownerId, skuCode)`，走 `order_lines` 而非 `orders`。

索引：

```sql
CREATE INDEX idx_order_lines_backorder_fifo
ON order_lines (owner_id, sku_code, backordered_since, id);
```

**刻意不含 `status`**，這是與被它取代的 `idx_orders_backorder_fifo` 唯一的實質差異。R4 之後
待配佇列不能依 `status` 過濾——ordering 的配貨狀態落後於 allocation 的決策，拿它當閘門會
重複預留。而 `status` 若夾在 `sku_code` 與 `backordered_since` 之間，index 掃出的列會先按
status 分組再按時間排序，查詢不篩 status 時仍得排序一次，index 等於白建。

採用嚴格 FIFO：若第一張欠單無法完整取得 reservation，立即停止，不跳過它處理後面的較小訂單。

## 並行控制與重試

第一版採用 PostgreSQL transaction 與 JPA `@Version`，不加入 Redisson、pessimistic lock 或原生條件更新。

下列表格都保留 `version`：

- `orders`
- `stock_pools`
- `stock_reservations`

Optimistic lock conflict 不代表庫存不足，不能直接將 Order 標記為 `BACKORDERED`。應 rollback 整個 transaction，重新讀取所有資料後再試。

重試邊界：

```text
Event Listener / Consumer
  → AllocationRetryExecutor（RetryOperations、耗盡時轉換例外與觀測）
    → Transactional Usecase
      → Coordinator
        → Repositories
```

`AllocationRetryExecutor` 是 application port，`SpringAllocationRetryExecutor` 是其 infrastructure 實作；兩者與 Transactional Usecase 應為不同 Spring Bean。`AllocationRetryConfiguration` 以 `RetryPolicy` 建立 `RetryOperations` bean（目前實作為 `RetryTemplate`）；每次 retry 都重新呼叫 Transactional Usecase 的 proxy，建立新的 transaction，不能在已標記 rollback-only 的 transaction 內繼續。策略僅重試 optimistic-lock conflict，初始呼叫加最多兩次 retry。

重試耗盡時：

- 拋出 `AllocationConcurrencyExhaustedException`。
- 保持 transaction rollback。
- 不得偽裝成 `BACKORDERED`。
- 寫 structured error log，至少包含 `eventId`、`orderId`、`sku`、attempts 與 exception type。
- 增加 Micrometer counter：`order_allocation_retry_exhausted_total`。

目前不由 business code 直接寄送 Email 或 Slack，也不新增 DLQ。未來有正式 message broker 時，再由 redelivery、DLQ 與監控平台負責通知。

## 事件模型

Domain Event 與 Integration Event 明確分離：

```text
Aggregate behavior / allocation flow
  → Domain Event（bounded context 內部業務事實，沒有 eventId）
  → 同 transaction 的 application-layer translator listener
  → Integration Event（跨 context/process 契約，有 eventId）
  → Outbox
  → PostgreSQL WAL
  → Debezium CDC connector
  → Kafka
  → Integration Event listener
  → Command
  → use case transaction（Inbox claim + 業務處理）
```

Domain Event 只表達 bounded context 內已發生的業務事實，不直接作為 Inbox／Outbox 訊息 identity。SR-12 的 application-layer translator listener 同步接收 Domain Event，建立 Integration Event 並透過 `Outbox` port append；Outbox adapter 必須和 Aggregate 更新參與同一個 transaction。Translator 是純轉換器，不額外查詢 Repository；因此作為 Integration Event 來源的 Domain Event 必須帶齊目標 payload。`eventId` 只在 translator 建立 Integration Event 時產生。

Order Aggregate 目前的內部 Domain Events：

| Domain Event | Payload |
|---|---|
| `OrderPlaced` | `orderId`, `ownerId`, `fulfillmentNodeId`, `shipToZone`, `promisedDeliveryDate`, `lines`, `placedAt` |
| `OrderAllocated` | `orderId`, `ownerId`, `allocatedAt` |
| `OrderBackordered` | `orderId`, `ownerId`, `lines`, `backorderedSince` |
| `OrderCancelled` | `orderId`, `ownerId`, `fulfillmentNodeId`, `cancelledAt` |

需求以 `lines` 表達而不是單一 `sku` 與 `quantity`——訂單的形狀本來就是行的集合。`OrderCancelled`
是例外，**它不帶 `lines`**：取消是整單行為，事件要說的是「哪張單、什麼時候」，行的內容不構成
這個事實的一部分。

`ownerId` 與 `fulfillmentNodeId` 在領域事件上，是因為 translator 要用它們算 partition key。
領域事件是 in-process 的，帶著它們沒有契約成本。

Domain Events 不包含 `eventId`、retry count、serialization type 或 Outbox metadata。

Allocation flow 另發布下列跨 Aggregate 的 Domain Event。它不是 `Order` Aggregate 的事件，而是 Order、StockPool 與 StockReservation 已共同完成 allocation 的業務事實：

| Domain Event | Payload | Translator 輸出 |
|---|---|---|
| `OrderAllocationCompleted` | `orderId`, `allocatedAt` | `OrderAllocatedIntegrationEvent` |

它與 `OrderAllocated` 的差別在時機：後者由聚合根在狀態轉換時發出，前者由 coordinator 在三個
聚合根都寫入之後發出。對外事件只能由後者觸發。

**四則訂單生命週期的對外事件都只帶 `(eventId, orderId, 時間戳)`**——`OrderPlaced`、
`OrderCancelled`、`OrderAllocated`、`BackorderCreated`。消費端拿 `orderId` 回頭讀整張單，它反正
得讀（收件資訊、交期、行的內容都不在事件裡），payload 抄一份只是多一個會與訂單不一致的來源。
例外是**來自系統外部**的事件（`StockReplenishedIntegrationEvent`、
`BackorderWakeRequestedIntegrationEvent`）：那裡沒有本地聚合根可讀，事實只存在於訊息裡。

`OrderAllocationCompleted` 由 allocation flow 在完整配置完成後發布。`OrderAllocated` 仍可供 bounded context 內部使用，但不作為 `OrderAllocatedIntegrationEvent` 的翻譯來源；這可避免為了帶入 `reservationId` 而讓 `Order` Aggregate 耦合 StockReservation 的識別字。

完整翻譯對應如下：

| Domain Event | Integration Event |
|---|---|
| `OrderPlaced` | `OrderPlacedIntegrationEvent` |
| `OrderAllocationCompleted` | `OrderAllocatedIntegrationEvent` |
| `OrderBackordered` | `BackorderCreatedIntegrationEvent` |
| `OrderCancelled` | `OrderCancelledIntegrationEvent` |

`StockReplenishedIntegrationEvent` 是 Inventory 發出的輸入事件，Promising consumer 將它轉為 replenish command；Promising 不反向發布同名事件。

## Integration Event 契約

### `OrderPlacedIntegrationEvent`

```text
eventId
orderId
sku
quantity
placedAt
```

目前假設能產生此事件的訂單已符合本專案所需的履約前置條件；付款、風控與地址驗證不在範圍內。

### `OrderAllocatedIntegrationEvent`

```text
eventId
orderId
reservationId
sku
quantity
allocatedAt
```

此事件是目前專案的成功輸出，也是未來 Fulfillment context 的輸入。因為目前沒有付款、風控或地址驗證，假設能產生 `OrderPlacedIntegrationEvent` 的訂單已符合履約前置條件。

### `BackorderCreatedIntegrationEvent`

```text
eventId
orderId
sku
quantity
backorderedSince
```

此事件只代表業務上的 ATP 不足；optimistic lock conflict 或其他技術失敗不得發布此事件。

### `OrderCancelledIntegrationEvent`

```text
eventId
orderId
cancelledAt
```

Allocation 以此事件釋放 ACTIVE reservation。重複事件由 Inbox 與 reservation 狀態共同保護。

### `StockReplenishedIntegrationEvent`

```text
eventId
sku
quantity
```

`quantity` 為正向增量，不能用負值表示盤點修正。

## Kafka topics 與 partition key

Topic 命名採用 `{事件生產端 bounded context}.{事件主題}-events`。它描述的是**誰擁有並發布這份跨邊界契約**，不是目前程式部署在哪個 application，也不是 Java package 或 Aggregate 名稱。故即使目前 `Ordering` 與 `Promising` 同在 `order-promising` 專案中，仍保留各自的 topic prefix；日後拆成獨立服務時，topic 契約不必因此改名。

目前的 bounded context 邊界如下：`Ordering` 擁有訂單生命週期事件；外部 `Inventory` 擁有庫存異動事件；`Promising` 擁有配置結果事件。`allocation` 是目前 Promising 內部的核心能力，不是已獨立對外發布契約的 bounded context，因此其輸出使用 `promising.allocation-events`，不使用 `allocation.*`。`inventory.stock-events` 中的 `inventory` 是生產端 context，`stock` 是事件業務主題，兩者不是同義詞。

Topic 依生產端 bounded context 劃分，而非每個 event type 一個 topic。每則訊息仍保留 `eventType`，consumer 依 type 分派；未來若個別事件有不同吞吐、權限或 SLA，再拆出獨立 topic。

下表的 Message key 對應 Outbox row 的 `partition_key` 欄位（本專案生產的 topic），不是 `aggregateid`。

| Topic | 生產端 bounded context | 生產端事件 | Message key（`partition_key`） |
|---|---|---|---|
| `ordering.order-events` | Ordering | `OrderPlacedIntegrationEvent`、`OrderCancelledIntegrationEvent` | `orderId`；`partition-key-strategy=sku` 時為 `sku` |
| `inventory.stock-events` | Inventory（外部上游） | `StockReplenishedIntegrationEvent` | `sku` |
| `promising.allocation-events` | Promising | `OrderAllocatedIntegrationEvent`、`BackorderCreatedIntegrationEvent` | `orderId` |

`promising.allocation-events` 不套用 `partition-key-strategy`。`sku` 策略的目的是讓同一 SKU 的下單事件收斂進同一 partition，使 allocation consumer 成為該 SKU 的 single writer；該 topic 目前沒有 consumer，沒有需要被保護的寫入端。要改動這點，先確認它已有 consumer 且確實需要 per-SKU 順序保證。

Java 常數名稱以 `*_TOPIC` 結尾，明確表示其值是 Kafka topic，例如 `ORDERING_ORDER_EVENTS_TOPIC`。topic 字串中的 `-events` 為複數，表示一條可承載多個同類事件的事件串流。現階段不加 `.v1`；只有發生無法相容的契約變更，且無法以平滑演進處理時，才新增版本化 topic 並規劃 consumer 遷移。

目前不使用複合 key。`orderId` 已是全域 UUID；`StockPool` 目前以 SKU 識別，因此補貨事件以 `sku` 維持同 SKU 的 partition 內順序。未來若引入多倉且 StockPool 識別改為 `(warehouseId, sku)`，再改用 `stockPoolId` 或 `warehouseId:sku`。

## 事件與 Transaction 邊界

Integration Event 應逐一寫入 Outbox，不可在 transaction commit 前直接執行外部副作用。Domain Event 可在 bounded context 內作為 application/domain policy 的內部通知，但不直接當作對外契約。Aggregate 更新、Reservation 寫入、Domain Event translation 與 Outbox 寫入必須位於同一個 transaction，失敗時一起 rollback；接收端的 Inbox claim 則與該 command 的業務更新位於同一個 transaction。

目標流程為：

```text
業務 transaction
  → Aggregate behavior
  → publish Domain Event
  → translator listener
  → Integration Event + Outbox append
  → commit
  → PostgreSQL WAL
  → Debezium CDC connector
  → Kafka
  → Integration Event listener
  → Command
  → use case 的新業務 transaction（Inbox claim + 業務處理）
```

## HTTP 表面

HTTP 端點分成兩類，界線不可模糊：**正式業務能力**不受 profile 限制，**dev-only 探針**只在 dev profile 註冊。

| 端點 | 類別 | 說明 |
|---|---|---|
| `POST /orders` | 業務 | 下單。JSON request body 帶貨主、上游單號、收件資訊、承諾到貨日與**行的清單**（`lines`），回 `200` 與訂單表示。狀態碼刻意維持 `200` 而非 `201`：k6 壓測腳本的 check 寫死 200，改它會多破壞一處而換不到這個 demo 需要的東西 |
| `GET /orders?limit=N` | 業務 | 最近訂單，依 `placed_at DESC, id DESC` 排序。`limit` 預設 20、範圍 1..100，超出回 `400` 而非靜默截斷——靜默截斷會讓呼叫方無法分辨「只有這麼多筆」與「被截斷」 |
| `GET /orders/{orderId}` | 業務 | 單筆訂單。未知 id 回 `404` |
| `GET /owners` | 業務 | 貨主清單 |
| `GET /owners/{ownerId}/products` | 業務 | 該貨主的款 |
| `GET /owners/{ownerId}/products/{productCode}/skus` | 業務 | 該款的規格 |
| `GET /stock-pool/{sku}` | 業務 | 該 SKU 的 on-hand、reserved、available-to-promise。欄位以領域語彙命名、不縮寫成 ATP。無 StockPool 回 `404`。**不帶貨主**——庫存池還沒有貨主維度，加上它等於報告一個資料裡不存在的區別 |
| `POST /demo/replenish` | dev-only 探針 | 見下方說明。要帶貨主——補貨喚醒的是某個貨主的缺貨佇列，而 SKU 代碼跨貨主撞號、決定不了是誰的 |
| `GET /demo/config` | dev-only 探針 | 回報目前生效的 `archone.allocation.partition-key-strategy`。只揭露不切換——該值在啟動時解析 |

三個訂單端點共用同一個訂單表示型別，客戶端因此只需要一個訂單模型，而不是「建立時拿到一種、查詢時拿到另一種」。該型別帶 `ownerId` 但**不帶貨主名稱或品名**——呼叫端為了下單表單的下拉選單本來就要載主檔，名稱從同一份資料解析即可。那是「整個畫面查一次」，不是每一列各查一次。

主檔的三支查詢巢狀在貨主之下，不是把貨主當可省略的篩選條件：在 3PL 裡編碼由貨主自訂、跨貨主撞號，貨主是款與規格得以存在的前提。

`GET /stock-pool/{sku}` 是 allocation 模組唯一的 REST entrypoint，且刻意只有唯讀查詢；命令仍然只從 Kafka entrypoint 進入，配置決策不開 HTTP 入口。

### 補貨探針為什麼直接發 Kafka、且不經 Outbox

`POST /demo/replenish` 扮演外部 Inventory bounded context 的上游 producer，向 `inventory.stock-events` 發布真實的 `StockReplenishedIntegrationEvent`，回 `202` 與該事件識別碼。它是本專案唯一直接作為 Kafka producer 的業務路徑（其餘對外發布一律走 Outbox → Debezium CDC）。

不直接呼叫 `ReplenishmentUsecase` 的理由：該 usecase 收的是含 `MessageMetadata` 的 inbound command，而那個 metadata 正是 Inbox 冪等所依據的憑證，直接呼叫等於自行偽造；繞過 Kafka 也會一併繞過重試、退避與 DLT 處理。

不經 Outbox 是正確的，不是違反本專案的 Outbox 原則：Outbox 解決的是「本地狀態變更」與「事件發布」的原子性，而這支探針不變更任何本地狀態——它扮演的是本專案並不擁有的上游 context——沒有需要對齊的 transaction。

探針回應不含「預期會喚醒幾張訂單」：那是發布前的快照，與實際結果可能不符。配置是非同步的，`202` 不代表配置已完成，結果只能由後續查詢觀察。

## Dev Seed Data

使用 `@Profile("dev")` 的 `ApplicationRunner`，以 idempotent 方式建立。建立順序固定為
主檔 → 庫存 → 訂單：`order_lines` 有 FK 指向 `skus`，主檔缺列時訂單根本插不進去。

**兩個貨主，刻意共用同一個 SKU 代碼。**

| 貨主 | 款 | 規格（`sku_code`） |
|---|---|---|
| `OWNER-A` 甲貨主（可拆單） | 烏龍茶（AMBIENT） | `SKU-AVAILABLE` 500ml／`SKU-EMPTY` 1L |
| `OWNER-A` | 冷凍水餃（FROZEN） | `SKU-PARTIALLY-RESERVED` 500g |
| `OWNER-B` 乙貨主（不可拆單） | 麥茶（AMBIENT） | `SKU-AVAILABLE` 600ml／`SKU-EMPTY` 1L |

兩個貨主的 `SKU-AVAILABLE` 是**完全不同的商品**（烏龍茶 520g／麥茶 610g），這是 3PL 撞號
的最小再現。它同時暴露上面說的中間狀態：`stock_pools` 的唯一鍵是 `(sku)`，所以這兩個商品
共用同一列庫存。

| SKU | onHand | reserved | ATP |
|---|---:|---:|---:|
| `SKU-AVAILABLE` | 10 | 0 | 10 |
| `SKU-PARTIALLY-RESERVED` | 20 | 5 | 15 |
| `SKU-EMPTY` | 0 | 0 | 0 |

`SKU-PARTIALLY-RESERVED` 必須同時 seed 一張 quantity 5、status `ALLOCATED` 的 Order，以及
一筆 quantity 5、status `ACTIVE` 的 StockReservation，確保 `reservedQuantity` 有可追溯來源。

另有一張乙貨主的 `BACKORDERED` 訂單（`SKU-EMPTY` × 2），它同時是撞號展示與 FIFO 佇列的
既有成員：補 `SKU-EMPTY` 時它會被喚醒，因此操作台 README 的 demo 流程對它成立。

它**刻意不是 `PENDING`**。`PENDING` 的語意是「還沒試過配置」，在真實系統裡是收單到消費之間
的毫秒級過渡；固化成種子資料等於展示一個穩定狀態下不存在的東西。更實際的問題是種子繞過下單
usecase 直接寫入資料庫，不會產生 `OrderPlaced` 事件——配置端從不知道它存在，而補貨只處理
`BACKORDERED`，所以一張種子 `PENDING` 訂單會**永遠不動**，補多少貨都一樣。`BACKORDERED`
則語意一致（`SKU-EMPTY` 的 ATP 是 0，「試過、沒貨」成立）且真的會被喚醒。

測試不得依賴 dev seed；每個自動化測試自行建立 fixture。Production 不載入測試 SKU。

StockPool 不由 `StockReplenishedIntegrationEvent` 偷偷建立。若事件中的 SKU 尚無 StockPool，應視為錯誤；正式 SKU onboarding 不在目前範圍。

## 必要測試

- ATP 足夠時建立一筆 ACTIVE reservation，增加 `reservedQuantity` 並將 Order 標為 `ALLOCATED`。
- ATP 不足時不改 StockPool、不建立 reservation，Order 進入 `BACKORDERED`。
- 同一 Order 不能建立兩筆 reservation。
- 取消 ALLOCATED Order 會釋放 reservation 並減少 `reservedQuantity`。
- 取消 PENDING／BACKORDERED Order 時，reservation handler 合法 no-op。
- 重複 `OrderCancelledIntegrationEvent` 不會重複釋放庫存。
- `StockReplenishedIntegrationEvent` 只接受正數，重複 event 不會重複增加 `onHandQuantity`。
- 補貨後依 `backorderedSince, id` 嚴格 FIFO；第一張不足即停止。
- 兩筆並行訂單競爭不足以同時滿足的 ATP 時，最多一筆 reservation 成功，且 `reservedQuantity <= onHandQuantity`。
- Optimistic lock conflict 會重新執行完整 transaction；重試耗盡不會被標為 backorder。

## 未來擴充點

未來串接 Fulfillment／Inventory 時再討論：

- `OrderAllocatedIntegrationEvent` 如何轉成 Fulfillment Order。
- 下游實際出庫後回饋的事件名稱與契約，例如 `StockIssued`。
- Reservation 是否增加 `CONSUMED` 與 `consumedAt`。
- Promising 的 on-hand projection 如何與 Inventory source of truth 對帳。
- 多倉選擇、`fulfillmentNodeId` 與跨倉拆單。
- 安全庫存、reservation expiration 與絕對值 inventory snapshot。
