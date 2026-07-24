# SR-05 Allocate Order Application Flow 實作紀錄

狀態：已完成

完成日期：2026-07-24

## 實作範圍

SR-05 建立 Promising bounded context 的單筆訂單配置 application flow。入口使用純業務 command；use case 在同一 transaction 中先以訊息 identity claim Inbox，再協調 Order、StockPool 與 StockReservation。

- ATP 足夠時建立一筆 `ACTIVE` StockReservation、更新 StockPool、將 Order 標為 `ALLOCATED`。
- ATP 不足時不建立 reservation，將 Order 標為 `BACKORDERED`。
- 成功時發布跨 Aggregate 的 `OrderAllocationCompleted` Domain Event。
- 不直接建立 Integration Event 或寫入 Outbox；Domain Event translator、Outbox persistence 與 Kafka delivery 分別保留給 SR-12～SR-14。

## 新增檔案

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/application/command/AllocateOrderCommand.java`

- 定義 `AllocateOrderCommand(UUID orderId)`。
- command 只表達 Promising 的業務意圖，不帶 Integration Event 或 Inbox metadata。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/domain/event/OrderAllocationCompleted.java`

- 表達 Order、StockPool 與 StockReservation 已完成完整配置的跨 Aggregate Domain Event。
- payload 包含 `orderId`、`reservationId`、`sku`、`quantity` 與 `allocatedAt`，讓後續 SR-12 translator 可不查詢 Repository 就建立 `OrderAllocatedIntegrationEvent`。

## 變更檔案

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/application/usecase/AllocateOrderUsecase.java`

- 將入口從 `OrderPlacedIntegrationEvent` 改為 `handle(AllocateOrderCommand command, UUID messageId)`。
- `messageId` 是獨立的傳遞層冪等鍵；use case 在 transaction 開頭以它 claim Inbox。
- 成功與 ATP 不足的持久化／Domain Event 發布皆委派給 `OrderAllocationCoordinator`，use case 不再直接發送 Integration Event。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/application/coordinator/OrderAllocationCoordinator.java`

- 單筆成功配置後統一保存 StockPool、Order 與 StockReservation。
- 以 `IdGenerator.nextId()` 建立 reservation identity。
- 發布既有 `OrderAllocated` Domain Event 與新的 `OrderAllocationCompleted` Domain Event。
- 新增 `backorderOrder()`，統一保存 BACKORDERED Order 並發布 `OrderBackordered` Domain Event。
- 既有補貨流程維持原行為；補貨配置建立 reservation 的完整處理由 SR-07 負責。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/listener/AllocateOrderListener.java`

- 暫時的本地 Integration Event listener 只負責轉換 `OrderPlacedIntegrationEvent` 為 `AllocateOrderCommand`，並將 event identity 傳為 `messageId`。
- 真正 Kafka consumer adapter 與 Inbox persistence wiring 仍屬 SR-14。

### 測試

- `AllocateOrderUsecaseTest`：驗證重複 message、非 PENDING、找不到 Order／StockPool、成功配置與 ATP 不足委派。
- `OrderAllocationCoordinatorTest`：驗證成功時三個 Aggregate 的持久化與 `OrderAllocationCompleted`，以及 ATP 不足時的 BACKORDERED Domain Event。
- `ReplenishmentUsecaseTest`：更新 Coordinator dependency fixture，維持既有補貨範圍。

### `../stock-reservation-design.md`

- SR-05 checkbox 更新為完成。
- 整體進度更新為 `9 / 17`。
- 可立即執行項目更新為 SR-06、SR-07、SR-12、SR-16。

## 驗證結果

執行完整 verification lifecycle：

```bash
./gradlew :order-promising:check
```

結果：

```text
BUILD SUCCESSFUL
81 unit tests completed
30 SIT tests completed
```

驗證內容：

- Java production、unit test 與 SIT source 均成功編譯。
- SR-05 application flow 的 command／Inbox claim／成功配置／欠單路徑皆由 unit tests 驗證。
- 既有 PostgreSQL persistence SIT 全數通過。

## 尚未處理項目

- SR-12：Domain Event translator、typed Inbox／Outbox persistence 與同 transaction rollback tests。
- SR-13：Debezium Outbox CDC to Kafka 與 Testcontainers end-to-end 驗證。
- SR-14：Kafka consumer 將 Integration Event 轉為 command 的正式 entrypoint，以及實際 Inbox adapter wiring。
- SR-07：補貨後配置 backorders 時建立對應 StockReservation 的完整流程。
