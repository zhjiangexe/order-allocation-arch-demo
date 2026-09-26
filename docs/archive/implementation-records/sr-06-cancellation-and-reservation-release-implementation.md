# SR-06 Cancellation and Reservation Release 實作紀錄

狀態：已完成

完成日期：2026-07-24

## 實作範圍

SR-06 建立取消訂單與釋放 reservation 的 application flow。

- 取消訂單會儲存 `CANCELLED` 狀態，並發布 `OrderCancelled` Domain Event。
- 接收 `OrderCancelledIntegrationEvent` 時，以 Inbox 先取得訊息處理權，確保至少一次傳遞下仍可冪等執行。
- 若訂單存在 ACTIVE reservation，將 reservation 改為 `RELEASED`，並同步減少 StockPool 的 `reservedQuantity`。
- 若沒有 ACTIVE reservation，流程為合法 no-op；這涵蓋 PENDING／BACKORDERED 訂單取消，以及重複到達的取消訊息。
- Domain Event 到 Integration Event、Outbox 的同步翻譯屬於 SR-12；Kafka consumer entrypoint 屬於 SR-14。本次 listener 只負責把 Integration Event 映射為 release command。

## 新增檔案

### `../../order-promising/src/main/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecase.java`

- 讀取 Order、執行 `Order.cancel(...)`、儲存狀態，並逐一發布 Order 的 Domain Events。
- 重複取消已取消訂單時不再寫入或發布事件。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/application/command/ReleaseReservationCommand.java`

- 以 `orderId` 表達釋放 reservation 的 application command。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/application/usecase/ReleaseReservationUsecase.java`

- 在同一 transaction 中先 `Inbox.claimIfNew(messageId)`。
- 查詢訂單的 ACTIVE reservation；查無資料時直接結束。
- 查詢對應 StockPool 後交由 `OrderAllocationCoordinator` 執行釋放與統一保存。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/entrypoint/listener/ReleaseReservationListener.java`

- 將 `OrderCancelledIntegrationEvent` 映射為 `ReleaseReservationCommand`，並以 Integration Event 的 `eventId` 作為 Inbox message identity。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/application/configuration/AllocationConfiguration.java`

- 以 application composition configuration 提供 `AllocationService` bean，避免 domain service 直接依賴 Spring annotation。

### 測試

新增：

- `../../order-promising/src/test/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecaseTest.java`
- `../../order-promising/src/test/java/com/flowzati/archone/allocation/application/usecase/ReleaseReservationUsecaseTest.java`

涵蓋取消事件發布、重複取消、重複訊息、無 ACTIVE reservation、成功交由 coordinator 釋放，以及 reservation 指向不存在 StockPool 的失敗情境。

## 變更檔案

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/application/coordinator/OrderAllocationCoordinator.java`

- 新增 `releaseReservation(...)` 與私有 `release(...)`：釋放規則暫時與協調流程放在同一類別，只有成功釋放後才保存 StockPool 與 StockReservation。
- 數量不一致會在任何 aggregate 變更前拋出錯誤；transaction 因此 rollback，且不會以歸零掩蓋資料不一致。

### 既有 coordinator 建構測試

- `OrderAllocationCoordinatorTest` 增加成功、重複與數量不一致的 release 行為驗證。

### `../stock-reservation-design.md`

- SR-06 checkbox 更新為完成。
- 整體進度更新為 `10 / 17`。

## 驗證結果

執行完整 verification lifecycle：

```bash
./gradlew :order-promising:check
```

結果：`BUILD SUCCESSFUL`。

- `89` 個 unit tests 全部通過。
- `30` 個 PostgreSQL/Testcontainers SIT 全部通過。
- production 與 test source 均成功編譯。

驗證包含：

- 取消 Order 的儲存與 `OrderCancelled` Domain Event 發布。
- 重複取消與重複 Integration Event 的冪等行為。
- 沒有 ACTIVE reservation 時的合法 no-op。
- ACTIVE reservation 的釋放協調路徑。
- 釋放量超過 `reservedQuantity` 時，兩個 aggregate 均維持原狀。
