# SR-17 End-to-end business workflow verification 實作紀錄

狀態：已完成

完成日期：2026-07-24

## 實作範圍

SR-17 驗證不依賴 optimistic-lock retry 的端到端業務流程。測試從本服務的 Kafka Integration Event entrypoint 開始，經過 handler strategy、Inbox、transactional use case、Coordinator、JPA adapters 與 Domain Event translator，最後確認資料庫狀態與 Outbox。

Kafka broker 與 Debezium CDC delivery 本身已由 SR-13 的 container integration test 驗證；本任務直接建立帶有相同 `id`／`eventType` headers 的 `ConsumerRecord`，聚焦驗證 consumer 之後的 bounded-context 業務流程。

## 新增檔案

### `../../order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationWorkflowEndToEndIntegrationTest.java`

使用完整 Spring application context、PostgreSQL Testcontainers 與關閉自動 Kafka listener startup 的 test profile，涵蓋以下情境：

1. `OrderPlacedIntegrationEvent` 進入 ordering topic 後，Order 成為 `ALLOCATED`、StockPool 增加 reserved quantity、建立 ACTIVE reservation、寫入 Inbox 與 `OrderAllocatedIntegrationEvent` Outbox row。
2. `OrderCancelledIntegrationEvent` 進入 ordering topic 後，ACTIVE reservation 成為 `RELEASED`、StockPool 釋放 reserved quantity、寫入 Inbox；取消的 allocation context 不額外產生 Outbox event。
3. `StockReplenishedIntegrationEvent` 進入 inventory topic 後，依嚴格 FIFO 只配置可完整滿足的第一張 backorder；後續無法完整滿足的訂單維持 `BACKORDERED`，不會被跳過或部分配置。

每個測試皆清除 Inbox、Outbox、Reservation、Order 與 StockPool table，避免資料相互影響。

## 變更檔案

### `../stock-reservation-design.md`

- SR-17 checkbox 更新為完成。
- 整體進度更新為 `16 / 18`。
- 可立即執行項目更新為 SR-15；SR-18 繼續等待 SR-15 的 retry 實作。

## 驗證結果

執行 SR-17 聚焦 SIT：

```bash
./gradlew :order-promising:sit \
  --tests '*AllocationWorkflowEndToEndIntegrationTest' \
  --no-daemon
```

結果：`BUILD SUCCESSFUL`，共 `3 tests completed`。

完整驗證：

```bash
./gradlew :order-promising:test --no-daemon
./gradlew :order-promising:sit --rerun-tasks --no-daemon
```

結果：`BUILD SUCCESSFUL`，共 `96 unit tests completed` 與 `43 SIT tests completed`。

驗證內容：

- Kafka consumer 以 typed Integration Event、Kafka headers 與 handler strategy 正確進入 application command。
- Inbox、業務資料與 Outbox 按各流程預期提交。
- allocation 成功會建立 Reservation 並發出 allocation Outbox event。
- cancellation 正確釋放 Reservation 與 ATP。
- replenishment 採用嚴格 FIFO，不會跳過第一張無法完整滿足的 backorder。

## 開發環境注意事項

- SR-17 不啟動 Kafka broker；consumer 以程式內 `ConsumerRecord` 模擬已由 Kafka 收到的訊息。
- SR-13 的 `OutboxCdcIntegrationTest` 仍負責驗證 PostgreSQL committed Outbox row 經 Debezium 發送至真實 Kafka topic。
- optimistic-lock retry、retry exhausted 與競爭同一 StockPool 的驗證不屬於 SR-17，保留給依賴 SR-15 的 SR-18。
