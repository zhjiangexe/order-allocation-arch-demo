# SR-14 Transaction wiring and Integration Event entrypoints 實作紀錄

狀態：已完成

完成日期：2026-07-24

## 實作範圍

SR-14 將既有的 application flow 接回 Kafka Integration Event entrypoint、Inbox 與 transactional Outbox。

- Kafka consumer 只負責讀取 Kafka record、取得 `id`／`eventType` headers，並反序列化為 typed Integration Event。
- consumer 以 handler strategy registry 將事件映射為純業務 Command；use case 不依賴 Kafka record、Debezium envelope 或 JSON。
- `InboundCommand<C>` 將 Command 與 `MessageMetadata(eventId, eventType)` 一起傳入 message-driven use case。
- use case 在同一 transaction 內先 claim Inbox，再執行 allocation、release 或 replenishment；既有 Domain Event translator 仍同步將 Integration Event 寫入 Outbox。
- 移除原本以 synchronous `ApplicationEventPublisher` 直接串接跨 context use case 的 listener，避免繞過 Kafka／Inbox 邊界。

## 新增檔案

### `../../order-promising/src/main/java/com/flowzati/archone/common/inbox/InboundCommand.java`

- 定義 generic immutable wrapper：`InboundCommand<C>(C command, MessageMetadata message)`。
- 拒絕 null Command 或 metadata，讓 consumer 到 use case 的訊息契約完整。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationKafkaIntegrationEventConsumer.java`

- 監聽 `ordering.order-events` 與 `inventory.stock-events`。
- `OrderPlacedIntegrationEvent` 映射為 `AllocateOrderCommand`。
- `OrderCancelledIntegrationEvent` 映射為 `ReleaseReservationCommand`。
- `StockReplenishedIntegrationEvent` 映射為 `ReplenishStockCommand`。
- 驗證 Kafka `id` header 與 payload 的 `eventId` 相同，避免 metadata 與 payload 被錯誤組合。
- 以 `IntegrationEventHandler<E>` strategy registry 取代 `switch`；每個 handler 宣告可處理的 event class 與 topic。共用的 `KafkaIntegrationEventDispatcher` 集中處理 metadata、反序列化、event ID 與 topic 驗證，consumer 僅保留 Spring listener 綁定。

### `../../order-promising/src/main/java/com/flowzati/archone/common/configuration/CommonConfiguration.java`

- 提供 UTC `Clock` 與支援 Java time 的 `ObjectMapper` bean，供 use case、Outbox serializer 與 Kafka consumer 共用。

### 測試

- `AllocationKafkaIntegrationEventConsumerTest` 驗證三種事件的 Command 映射與 event ID 不一致拒絕。
- `InboundCommandTransactionIntegrationTest` 使用 PostgreSQL Testcontainers 驗證 transaction 成功提交，以及 allocation、取消、補貨失敗時的完整 rollback。

## 變更檔案

### Message-driven use cases

- `AllocateOrderUsecase`、`ReleaseReservationUsecase`、`ReplenishmentUsecase` 改為 `handle(InboundCommand<C>)`。
- 三者皆以 `@Transactional` 作為 message handling 的 transaction boundary；Inbox claim 重複時立即 no-op。
- Command 與 metadata 在進入 use case 後立刻解包，後續業務邏輯只使用 domain／application 型別。

### Integration Event contracts

- `OrderPlacedIntegrationEvent`、`OrderCancelledIntegrationEvent`、`StockReplenishedIntegrationEvent` 補上 Jackson creator/property annotations，供 Kafka payload 安全反序列化。

### Runtime dependency 與設定

- 新增 Spring Kafka starter 與 `spring.kafka.*` consumer 設定。
- 新增共用 route `inventory.stock-events`。
- 保留 Spring Modulith core／insight；移除 JPA event-publication starter，避免專案自建 Inbox／Outbox 之外再啟用未建立 migration 的 `event_publication` table。

### 移除同步跨 context listener

- 移除 `AllocateOrderListener`、`ReleaseReservationListener`、`ReplenishmentListener`。
- Domain Event 至 Outbox 的 translator 維持同 transaction；跨 context command 改由 Debezium CDC → Kafka → consumer 進入。

### `../stock-reservation-design.md`

- SR-14 checkbox 更新為完成。
- 整體進度更新為 `14 / 17`；可立即執行項目更新為 SR-15、SR-16。

## 驗證結果

執行 unit tests：

```bash
./gradlew :order-promising:test --no-daemon
```

執行全部 SIT：

```bash
./gradlew :order-promising:sit --rerun-tasks --no-daemon
```

執行結果均為 `BUILD SUCCESSFUL`，共 `95 unit tests completed` 與 `38 SIT tests completed`。

其中 transaction integration test：

```bash
./gradlew :order-promising:sit \
  --tests '*InboundCommandTransactionIntegrationTest' \
  --no-daemon
```

結果：`4 tests completed`。

驗證內容：

- 成功配置時，Inbox row、Order ALLOCATED、StockReservation 與 Outbox row 同時提交。
- allocation 找不到 StockPool 時，Inbox claim 與 Order 變更均回滾。
- reservation release 的時間不合法時，Inbox claim、StockPool 與 reservation state 均回滾。
- replenishment 的分配失敗時，Inbox claim、StockPool 數量、Order 狀態與 reservation 寫入均回滾。
- Kafka consumer 正確建立三種 `InboundCommand`，並拒絕 header／payload event ID 不一致的訊息。

## 開發環境注意事項

- Kafka consumer 的 broker 由 `ORDER_PROMISING_KAFKA_BOOTSTRAP_SERVERS` 覆寫；預設為 `localhost:9092`。
- SIT 關閉 Kafka listener 自動啟動，僅使用 PostgreSQL Testcontainer 驗證 transaction；Kafka consumer 的 mapping 由 unit test 覆蓋。
- 正式執行時，Outbox message 由 SR-13 的 Debezium connector 發送至 Kafka；consumer 的 at-least-once 重送由 Inbox `eventId` 去重。
