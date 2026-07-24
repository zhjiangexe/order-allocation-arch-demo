# SR-12 Domain Event Translation and Transactional Inbox/Outbox 實作紀錄

狀態：已完成

完成日期：2026-07-24

## 實作範圍

SR-12 建立 Domain Event 到 Integration Event 的同步翻譯，以及可與業務 transaction 一起提交或 rollback 的 Inbox／Outbox 持久化基礎。

- Domain Event 保持 bounded context 內的業務事實，不含 `eventId`。
- translator 同步接收 Domain Event，建立帶有新 `eventId` 的 Integration Event，序列化後 append 至 Outbox。
- Coordinator 與 use case 不直接建立或發布 Integration Event。
- Inbox 以 `event_id` primary key 的 `INSERT ... ON CONFLICT DO NOTHING` claim 去重；`event_type` 與 `processed_at` 一起保存。
- Outbox 僅保存 immutable event row，不保存 `publishedAt`、attempts 或應用程式發布狀態。
- Debezium、Kafka topic 與 consumer 不在本次範圍，留待 SR-13／SR-14。

> 後續設計決策：SR-13 將以新的 migration 新增 `event_outbox.route`，並以它作為 Debezium topic routing field；SR-12 已建立的 `aggregatetype` 保留 Aggregate type 語意（例如 `Order`），不回溯宣稱此欄位已用於 topic routing。

## 新增檔案

### Migration

`../../order-promising/src/main/resources/db/migration/V5__create_event_inbox_and_outbox.sql`

- 建立 `event_inbox`：`event_id` primary key、`event_type`、`processed_at`。
- 建立 `event_outbox`：採 Debezium Outbox Event Router canonical names（`id`、`aggregatetype`、`aggregateid`、`type`、`payload`、`timestamp`）。
- 建立 `idx_event_outbox_timestamp`，供日後 retention 維運與 CDC 查核使用；不代表 application polling relay。

### Inbox／Outbox adapters

- `InboxEntity`、型別化 `JpaEventInboxRepository` 與 `InboxRepoImpl`。
- `Outbox` immutable record、`OutboxRepo` port、`OutboxEntity`、`JpaOutboxRepository` 與 `OutboxRepoImpl`。
- `OutboxAppender` 使用 Spring `ObjectMapper` 將 Integration Event 序列化為 JSON payload。
- 加入 `jackson-datatype-jsr310`，確保包含 `Instant` 的事件可一致序列化。

### Domain Event translators

- `OrderingDomainEventTranslator`
  - `OrderPlaced` → `OrderPlacedIntegrationEvent`
  - `OrderCancelled` → `OrderCancelledIntegrationEvent`
- `AllocationDomainEventTranslator`
  - `OrderAllocationCompleted` → `OrderAllocatedIntegrationEvent`
  - `OrderBackordered` → `BackorderCreatedIntegrationEvent`

兩個 translator 都使用同步 `@EventListener`。因此在發布 Domain Event 的既有 transaction 內 append Outbox；不是 `@TransactionalEventListener(AFTER_COMMIT)`，避免 Outbox 與業務資料分離提交。

## 變更檔案

### `PlaceOrderUsecase`

- 移除直接建立並發布 `OrderPlacedIntegrationEvent` 的舊路徑。
- 現在只發布 `OrderPlaced` Domain Event，交由 translator 寫入 Outbox。

### 接收端 use cases

- `AllocateOrderUsecase`、`ReleaseReservationUsecase` 與 `ReplenishmentUsecase` 的 Inbox claim 改為接收 `MessageMetadata`。
- entrypoint listener 從原始 Integration Event 建立 `MessageMetadata(eventId, eventType)`；use case 仍保有 transaction，Inbox row 因而可保留正確的訊息契約型別，同時仍以 `eventId` 作唯一冪等鍵。
- `ReplenishmentListener` 另將 `StockReplenishedIntegrationEvent` 映射為 `ReplenishStockCommand`；三個 message-driven use case 因而一致採用 `handle(Command, MessageMetadata)`。

### 測試

- `DomainEventTranslatorTest`：驗證 Order 與 Allocation translator 產生正確 Integration Event type、aggregate reference 與 payload。
- `InboxRepoOutboxPersistenceIntegrationTest`：以真實 PostgreSQL 驗證 Inbox claim 只成功一次、Outbox JSONB persistence，以及業務 Order 寫入與 translator Outbox append 在同一 transaction rollback。
- 更新既有 use case mocks 與 database foundation migration 版本預期。

### `../stock-reservation-design.md`

- SR-12 checkbox 更新為完成。
- 整體進度更新為 `12 / 17`。

## 驗證結果

執行完整 verification lifecycle：

```bash
./gradlew :order-promising:check
```

結果：`BUILD SUCCESSFUL`。

驗證包含：

- Domain Event translation 與包含 `Instant` 的 JSON payload serialization。
- Inbox event identity 與 event type persistence。
- Outbox immutable row 的 JSONB persistence。
- 同一 transaction 中，Order 業務資料與 translator Outbox row 一起 rollback。
- 全部既有 unit tests 與 PostgreSQL/Testcontainers SIT。
