# SR-15 Optimistic-lock retry and observability 實作紀錄

狀態：已完成

完成日期：2026-07-24

## 實作範圍

SR-15 為 message-driven allocation flow 加入有限次數的 optimistic-lock retry 與耗盡觀測。retry 不再被誤判為 ATP 不足或 `BACKORDERED`；每次嘗試皆重新執行完整的 transactional use case。

採用 Spring Framework 7 內建的程式化 `RetryTemplate`，不使用 annotation proxy 或手寫 retry loop。

```text
AllocationOptimisticLockRetryDecorator
  → RetryOperations
    → transactional Inbox decorator
      → Integration Event handler
        → Transactional Usecase
```

2026-08-10 messaging cohesion cleanup 後，retry 不再偽裝成 application port。它只由
message subscriber 使用，因此收斂在 `stock.entrypoint.messaging.retry`：configuration 建立
`RetryOperations` 與 decorator，decorator 位於 transactional Inbox handling 外層。設定仍為初始
呼叫一次、最多額外重試兩次，總計最多三次；每次 chain invocation 都建立新的 transaction。

## 目前檔案

### `../../order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/messaging/retry/AllocationOptimisticLockRetryConfiguration.java`

- 以 `RetryPolicy` 定義只重試 `OptimisticLockingFailureException`、最多兩次 retry 與 100 ms 間隔。
- 在同一個 configuration 內組裝 attempt metric listener 與 decorator，不暴露全域具名
  `RetryOperations` Bean。

### `../../order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/messaging/retry/AllocationOptimisticLockRetryDecorator.java`

- 只套用 Allocation 擁有的兩個 subscribers，Ordering subscriber 直接通過。
- 以 `RetryOperations` 的回傳值直接承接 `ProcessingOutcome`，不再以 `Runnable` +
  `AtomicReference` 搬運結果。
- 在 retry 耗盡後將最後一個 optimistic-lock exception 轉為
  `AllocationConcurrencyExhaustedException`。
- 寫入 `order_allocation_retry_exhausted_total` counter，並記錄 `operation`、`eventId`、
  `subscriberId`、`attempts` 與 `exceptionType`。

### `../../order-promising/src/main/java/com/flowzati/archone/stock/entrypoint/messaging/retry/AllocationConcurrencyExhaustedException.java`

- 表示 consumer-side 技術性競爭在三次嘗試後仍未收斂。
- 不轉換為 `BACKORDERED`，使 Kafka consumer 可依既有錯誤處理策略重送或轉交後續處理。

### 測試

- `src/test/.../AllocationOptimisticLockRetryDecoratorTest` 驗證 subscriber scope、初始呼叫加兩次
  retry、非 retryable exception、耗盡例外、metric 與 chain order。
- 原由 `src/sit/.../AllocationRetryTransactionIntegrationTest` 驗證；2026-08-10 messaging cleanup 後由更完整的 `AllocationTransactionalMessageChainIntegrationTest` 接手，除不同 `txid_current()` 外，也驗證 transactional Inbox／business／Outbox chain 一起 rollback。

## 變更檔案

### Integration Event consumers

- `AllocationOrderLifecycleEventConsumer` 與 `AllocationInventoryAvailabilityEventConsumer` 透過全域
  decorator chain 進入 bounded-context retry；handler 本身只做 event-to-command mapping。
- retry 前不快取 Order、StockPool、movement 或 FIFO 清單；每次 chain invocation 皆重新由
  Repository 讀取。

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
  --tests '*AllocationTransactionalMessageChainIntegrationTest' \
  --no-daemon
```

結果：`BUILD SUCCESSFUL`，共 2 tests completed。

完整驗證：

```bash
./gradlew :order-promising:test --no-daemon
./gradlew :order-promising:sit --no-daemon
```

結果：兩組皆為 `BUILD SUCCESSFUL`；2026-08-10 cohesion cleanup 後已重新執行完整 unit 與 SIT
regression，不固定易隨測試演進失真的總數。

## 開發環境注意事項

- RetryTemplate 是程式化呼叫，不依賴 annotation proxy 或 `@EnableResilientMethods`。
- retry executor 呼叫的目標仍必須是另一個 Spring transactional Bean，才能在每次嘗試建立新的 transaction。
- SR-18 仍須驗證真正兩筆訂單競爭同一 `StockPool` 時，retry 如何收斂為 allocation 或 backorder；SR-15 只驗證 retry infrastructure、transaction 邊界與耗盡 rollback。
