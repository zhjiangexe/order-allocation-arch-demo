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
- 多品項訂單與 `order_lines`。
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

目前進度：15 / 18

可立即執行：`SR-15`、`SR-17`。SR-15 為 optimistic-lock retry 與 observability；SR-17 驗證不依賴 retry 的端到端業務流程。

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

- [ ] **SR-15 — Optimistic-lock retry and observability**（依賴 SR-14）
  - 以獨立 Retrying Handler 包住 Transactional Usecase，最多嘗試三次。
  - 每次重試重新讀取 Order、StockPool、Reservation 與 FIFO 清單。
  - 重試耗盡時拋出 `AllocationConcurrencyExhaustedException`，不得轉成 BACKORDERED。
  - 增加 structured error log 與 `order_allocation_retry_exhausted_total` metric。
  - 測試每次 retry 使用新 transaction，以及 exhausted rollback 行為。

- [x] **SR-16 — Dev-only consistent seed data**（依賴 SR-09～SR-11；可獨立於 SR-12～SR-15 執行）
  - 新增 `@Profile("dev")`、idempotent 的 `ApplicationRunner`。
  - 建立 `SKU-AVAILABLE` 與 `SKU-EMPTY` StockPools。
  - `SKU-PARTIALLY-RESERVED` 必須同時建立對應的 ALLOCATED Order 與 ACTIVE StockReservation，不得只設定孤立的 `reservedQuantity`。
  - 驗證重啟不會重複建立，且 test／production profile 不載入 seed。

- [ ] **SR-17 — End-to-end business workflow verification**（依賴 SR-01～SR-14；SR-16 不阻擋）
  - 執行並修正全部既有測試。
  - 使用 SR-08 的 PostgreSQL test environment，驗證 allocation、取消、補貨、嚴格 FIFO 與 Inbox／Outbox 的端到端流程。
  - 確認未引入多倉、shipment、WMS、expiration 或 safety stock。

- [ ] **SR-18 — Retry and concurrency end-to-end verification**（依賴 SR-15、SR-17）
  - 使用 SR-08 的 PostgreSQL test environment，新增兩筆訂單競爭同一 StockPool 的整合測試，確認不會超賣。
  - 驗證 optimistic-lock conflict 會重新讀取資料並依 retry policy 收斂為正確的 allocation 或 backorder 結果。
  - 驗證 retry exhausted 時拋出 `AllocationConcurrencyExhaustedException`、所有嘗試均 rollback，且不會將技術衝突錯誤建模為 BACKORDERED。
  - 驗證 retry metric 與 structured log 可辨識 operation、eventId 與 attempt。
  - 所有驗證通過後，將文件狀態改為「已實作」。

## 資料模型

### `orders`

| 欄位 | 型別 | 限制／說明 |
|---|---|---|
| `id` | `UUID` | Primary key |
| `sku` | `VARCHAR` | `NOT NULL` |
| `quantity` | `INTEGER` | `NOT NULL`, `CHECK (quantity > 0)` |
| `status` | `VARCHAR` | `PENDING`, `ALLOCATED`, `BACKORDERED`, `CANCELLED` |
| `placed_at` | `TIMESTAMPTZ` | `NOT NULL` |
| `allocated_at` | `TIMESTAMPTZ` | Nullable |
| `backordered_since` | `TIMESTAMPTZ` | Nullable |
| `cancelled_at` | `TIMESTAMPTZ` | Nullable |
| `version` | `BIGINT` | `NOT NULL`, JPA `@Version` |

本次相較現有 `Order` 主要增加持久化用的 `version`。`placedAt`、`allocatedAt`、`backOrderedSince` 與 `cancelledAt` 已表達重要生命週期時間，因此不另加通用 `created_at`／`updated_at`。

### `stock_pools`

| 欄位 | 型別 | 限制／說明 |
|---|---|---|
| `id` | `BIGINT` | Primary key |
| `sku` | `VARCHAR` | `NOT NULL`, `UNIQUE` |
| `on_hand_quantity` | `INTEGER` | `NOT NULL`, `CHECK (on_hand_quantity >= 0)` |
| `reserved_quantity` | `INTEGER` | `NOT NULL DEFAULT 0`, `CHECK (reserved_quantity >= 0)` |
| `version` | `BIGINT` | `NOT NULL`, JPA `@Version` |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` |

額外限制：

```sql
CHECK (reserved_quantity <= on_hand_quantity)
```

`available_to_promise` 不存入資料庫，避免它與兩個基礎量不一致。Domain model 提供：

```java
public int availableToPromise() {
  return onHandQuantity - reservedQuantity;
}
```

現有 `available` 欄位改為 `onHandQuantity`，並新增 `reservedQuantity` 與 `updatedAt`；既有 `version` 保留。

### `stock_reservations`

| 欄位 | 型別 | 限制／說明 |
|---|---|---|
| `id` | `UUID` | Primary key，獨立的 reservation identity |
| `order_id` | `UUID` | `NOT NULL`, FK to `orders(id)`, `UNIQUE` |
| `stock_pool_id` | `UUID` | `NOT NULL`, FK to `stock_pools(id)` |
| `quantity` | `INTEGER` | `NOT NULL`, `CHECK (quantity > 0)` |
| `status` | `VARCHAR` | `ACTIVE`, `RELEASED` |
| `reserved_at` | `TIMESTAMPTZ` | `NOT NULL` |
| `released_at` | `TIMESTAMPTZ` | Nullable；只有 `RELEASED` 時有值 |
| `version` | `BIGINT` | `NOT NULL`, JPA `@Version` |

`order_id` 的唯一限制表達目前的一張訂單只能有一筆 reservation。Reservation 不重複保存 `sku`，而是透過 `stock_pool_id` 取得。

Reservation 不加入通用 `created_at`／`updated_at`；`reserved_at`、`released_at` 與 `version` 已足以表達目前生命週期。

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
| `aggregateid` | `VARCHAR` | `NOT NULL`；Kafka message key，支援 UUID／其他 aggregate id 表示 |
| `type` | `VARCHAR` | `NOT NULL`；Integration Event type |
| `route` | `VARCHAR` | `NOT NULL`；目標 Kafka topic，例如 `ordering.order-events` |
| `payload` | `JSONB` | `NOT NULL` |
| `timestamp` | `TIMESTAMPTZ` | `NOT NULL`；Integration Event 發生時間 |

本專案採用 Debezium Outbox Event Router 的 canonical column names，並額外加入 `route`。`aggregatetype` 保留來源 Aggregate 的語意；Debezium 以 `route.by.field=route` 將 event 發布至對應 topic。這符合 topic 依生產端 bounded context 劃分的規劃，也避免將 `Order` 等 Aggregate type 改作傳輸路由。`route` 是 application／infrastructure 的 delivery metadata，不屬於 Domain Event。本專案不在 Outbox row 保存發布狀態。Debezium 從 PostgreSQL WAL 取得已 commit 的變更並以 connector offset 追蹤進度；consumer 以 Inbox 承受可能的重複發布。Outbox row 最少保留 30 天，並且只有在 Debezium replication slot lag 位於安全範圍時才可依 `timestamp` 清理；不得只因資料變舊就刪除。Kafka Connect error handling 與 DLQ policy 屬於 SR-13 的部署／營運設定，不另建 DLQ table。

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
RELEASED ──重複取消──> no-op
```

目前不加入 `CONSUMED`。成功的 reservation 在此 bounded context 中保持 `ACTIVE`，代表供給仍承諾給該訂單；未來串接下游後，再由 Fulfillment／Inventory 回饋事件完成後續狀態。

## 核心流程

### 建立 reservation

接收端與業務處理的流程如下：

1. Kafka listener 收到 `OrderPlacedIntegrationEvent`。
2. listener 轉為 `AllocateOrderCommand(orderId)`，並以 `eventId` 作為獨立 `messageId` 呼叫 use case。
3. use case transaction 先以 `messageId` claim Inbox，成功後重新讀取 `Order` 與 `StockPool`，並確認 Order 為 `PENDING`。
4. 呼叫 `StockPool.canReserve(quantity)` 確認 ATP，再以 `reserve(quantity)` 完整預留。
5. 成功時增加 `reservedQuantity`、建立 `ACTIVE` `StockReservation`，並將 Order 標記為 `ALLOCATED`。
6. allocation flow 發布 `OrderAllocationCompleted` Domain Event；同 transaction 的 translator listener 將它轉成 `OrderAllocatedIntegrationEvent` 並寫入 Outbox。
7. Commit；commit 時由 `@Version` 偵測並行衝突。

若 ATP 不足：

1. 不修改 StockPool。
2. 不建立 StockReservation。
3. 將 Order 標記為 `BACKORDERED` 並發布 `OrderBackordered` Domain Event。
4. translator listener 將它轉成 `BackorderCreatedIntegrationEvent` 並寫入 Outbox。

`StockPool` 以 `canReserve()` 表達 ATP capability query，並以 `reserve()` 執行完整預留。

### 取消並釋放 reservation

Ordering 在取消 transaction 中將 Order 改為 `CANCELLED` 並發布 `OrderCancelled` Domain Event；translator 將其寫成 `OrderCancelledIntegrationEvent` Outbox event。Kafka delivery 後，Allocation listener 將事件轉為 release command，並以 `eventId` 作為 `messageId` 呼叫 use case；use case 在同一個 command transaction claim Inbox，並執行：

1. 依 `orderId` 尋找 `ACTIVE` reservation。
2. 找不到時視為合法 no-op；PENDING／BACKORDERED 訂單本來就沒有 reservation。
3. 將 reservation 改為 `RELEASED` 並設定 `releasedAt`。
4. 呼叫 `StockPool.release(quantity)`，減少 `reservedQuantity`。
5. Commit。

不得以 `Math.max(0, reserved - quantity)` 隱藏資料不一致；若釋放量大於 `reservedQuantity`，應拋出錯誤並 rollback。

### 補貨與 backorder FIFO

`StockReplenishedIntegrationEvent.quantity` 是正向增量，必須大於零：

```text
onHandQuantity += quantity
```

事件以 `eventId + Inbox` 保證不會重複加庫存。目前不支援負數 replenishment、盤點覆蓋或 `StockAdjusted`。

Backorder 查詢 contract：

```java
List<Order> findBackordersBySkuInFifoOrder(String sku);
```

查詢須穩定排序：

```sql
SELECT *
FROM orders
WHERE sku = :sku
  AND status = 'BACKORDERED'
ORDER BY backordered_since ASC, id ASC;
```

建議索引：

```sql
CREATE INDEX idx_orders_backorder_fifo
ON orders (sku, status, backordered_since, id);
```

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
  → Retrying Handler（最多三次）
    → Transactional Usecase
      → Coordinator
        → Repositories
```

Retrying Handler 與 Transactional Usecase 應為不同 Spring Bean，確保每次重試都經過 proxy 並建立新的 transaction。不能在已標記 rollback-only 的 transaction 內繼續。

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
| `OrderPlaced` | `orderId`, `sku`, `quantity`, `placedAt` |
| `OrderAllocated` | `orderId`, `allocatedAt` |
| `OrderBackordered` | `orderId`, `sku`, `quantity`, `backorderedSince` |
| `OrderCancelled` | `orderId`, `cancelledAt` |

Domain Events 不包含 `eventId`、retry count、serialization type 或 Outbox metadata。

Allocation flow 另發布下列跨 Aggregate 的 Domain Event。它不是 `Order` Aggregate 的事件，而是 Order、StockPool 與 StockReservation 已共同完成 allocation 的業務事實：

| Domain Event | Payload | Translator 輸出 |
|---|---|---|
| `OrderAllocationCompleted` | `orderId`, `reservationId`, `sku`, `quantity`, `allocatedAt` | `OrderAllocatedIntegrationEvent` |

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

Topic 依事件生產端的 bounded context 劃分，而非每個 event type 一個 topic。每則訊息仍保留 `eventType`，consumer 依 type 分派；未來若個別事件有不同吞吐、權限或 SLA，再拆出獨立 topic。

| Topic | 生產端事件 | Message key |
|---|---|---|
| `ordering.order-events` | `OrderPlacedIntegrationEvent`、`OrderCancelledIntegrationEvent` | `orderId` |
| `inventory.stock-events` | `StockReplenishedIntegrationEvent` | `sku` |
| `promising.allocation-events` | `OrderAllocatedIntegrationEvent`、`BackorderCreatedIntegrationEvent` | `orderId` |

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

## Dev Seed Data

使用 `@Profile("dev")` 的 `ApplicationRunner`，以 idempotent 方式建立：

| SKU | onHand | reserved | ATP |
|---|---:|---:|---:|
| `SKU-AVAILABLE` | 10 | 0 | 10 |
| `SKU-PARTIALLY-RESERVED` | 10 | 7 | 3 |
| `SKU-EMPTY` | 0 | 0 | 0 |

`SKU-PARTIALLY-RESERVED` 必須同時 seed 一張 quantity 7、status `ALLOCATED` 的 Order，以及一筆 quantity 7、status `ACTIVE` 的 StockReservation，確保 `reservedQuantity` 有可追溯來源。

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
