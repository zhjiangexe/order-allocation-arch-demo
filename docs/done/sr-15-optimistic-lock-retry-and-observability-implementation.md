# SR-15 Optimistic-lock retry and observability 實作紀錄

狀態：已完成

完成日期：2026-07-24

## 實作範圍

SR-15 為 message-driven allocation flow 加入有限次數的 optimistic-lock retry 與耗盡觀測。retry 不再被誤判為 ATP 不足或 `BACKORDERED`；每次嘗試皆重新執行完整的 transactional use case。

採用 Spring Framework 7 內建的程式化 `RetryTemplate`，不使用 annotation proxy 或手寫 retry loop。

```text
Kafka Integration Event handler
  → AllocationRetryExecutor (application port)
    → SpringAllocationRetryExecutor (RetryOperations)
    → Transactional Usecase (@Transactional)
```

`AllocationRetryConfiguration` 以 `RetryPolicy` 建立具名的 `RetryOperations` bean；目前由 `RetryTemplate` 實作，設定為初始呼叫一次、最多額外重試兩次，總計最多三次。`AllocationRetryExecutor` 是 application port，Spring 實作與 use case 為不同 Spring Bean，因此每次呼叫 use case 時都會重新進入 Spring transaction proxy。

## 新增檔案

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/application/retry/AllocationRetryContext.java`

- 保存 `operation`、`eventId`、`orderId` 與 `sku` 的診斷資料。
- 補貨或取消事件若無法從 payload 提供某項資訊，以 `unknown` 保留欄位，不省略 structured log key。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/configuration/AllocationRetryConfiguration.java`

- 以 `RetryPolicy` 定義只重試 `OptimisticLockingFailureException`、最多兩次 retry 與 100 ms 間隔。
- 建立具名 `allocationRetryOperations` bean；目前實作為 Spring Framework 7 的 `RetryTemplate`。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/retry/SpringAllocationRetryExecutor.java`

- 依賴 `RetryOperations` 介面執行重試；預設 bean 由 Spring Framework 7 `RetryTemplate` 與 `RetryPolicy` 提供。
- 在 retry 耗盡後將最後一個 optimistic-lock exception 轉為 `AllocationConcurrencyExhaustedException`。
- 寫入 `order_allocation_retry_exhausted_total` Micrometer counter，並以 structured error log 記錄 `operation`、`eventId`、`orderId`、`sku`、`attempts` 與 `exceptionType`。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/application/retry/AllocationConcurrencyExhaustedException.java`

- 表示技術性競爭在三次嘗試後仍未收斂。
- 不轉換為 `BACKORDERED`，使 Kafka consumer 可依既有錯誤處理策略重送或轉交後續處理。

### 測試

- `src/test/.../SpringAllocationRetryExecutorTest` 驗證 RetryTemplate 的初始呼叫加兩次 retry 語意，以及耗盡時的專用例外與 metric。
- `src/sit/.../AllocationRetryTransactionIntegrationTest` 使用 PostgreSQL Testcontainers 驗證每次 retry 取得不同 `txid_current()`，並確認耗盡時每個 transaction 的 Inbox 寫入均 rollback。

## 變更檔案

### Kafka Integration Event handlers

- `OrderPlacedIntegrationEventHandler`、`OrderCancelledIntegrationEventHandler` 與 `StockReplenishedIntegrationEventHandler` 均改由 `AllocationRetryExecutor` 進入既有 transactional use case。
- retry 前不快取 Order、StockPool、Reservation 或 FIFO 清單；每次 use case invocation 皆重新由 Repository 讀取。

### `../stock-reservation-design.md`

- SR-15 checkbox 更新為完成。
- 進度更新為 `17 / 18`，可立即執行項目改為 SR-18。
- 明確記錄 annotation retry executor 與 transaction proxy 的邊界。

## 驗證結果

聚焦 unit tests：

```bash
./gradlew :order-promising:test --no-daemon
```

結果：`BUILD SUCCESSFUL`。

聚焦 retry SIT：

```bash
./gradlew :order-promising:sit \
  --tests '*AllocationRetryTransactionIntegrationTest' \
  --no-daemon
```

結果：`BUILD SUCCESSFUL`，共 2 tests completed。

完整驗證：

```bash
./gradlew :order-promising:test --no-daemon
./gradlew :order-promising:sit --no-daemon
```

結果：兩組皆為 `BUILD SUCCESSFUL`，共 `98 unit tests completed` 與 `45 SIT tests completed`。

## 開發環境注意事項

- RetryTemplate 是程式化呼叫，不依賴 annotation proxy 或 `@EnableResilientMethods`。
- retry executor 呼叫的目標仍必須是另一個 Spring transactional Bean，才能在每次嘗試建立新的 transaction。
- SR-18 仍須驗證真正兩筆訂單競爭同一 `StockPool` 時，retry 如何收斂為 allocation 或 backorder；SR-15 只驗證 retry infrastructure、transaction 邊界與耗盡 rollback。
