# SR-18 Retry and concurrency end-to-end verification 實作紀錄

狀態：已完成

完成日期：2026-07-24

## 實作範圍

SR-18 驗證 SR-15 retry infrastructure 在實際 allocation business flow 中的行為。測試不只模擬 repository exception，而是從 Kafka Integration Event consumer 進入，經過 retry handler、transactional use case、Coordinator、JPA、Inbox 與 Outbox。

## 新增檔案

### `../../order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationConcurrencyEndToEndIntegrationTest.java`

使用完整 Spring application context 與 PostgreSQL Testcontainers，新增兩個情境：

1. 兩張各需 3 件的訂單同時競爭 `onHandQuantity = 3` 的同一個 StockPool。
   - test-only AOP barrier 讓兩個 transaction 都先讀到同一版 StockPool，再同時進入 Coordinator，使 optimistic-lock conflict 可重現。
   - retry 後只能有一張訂單為 `ALLOCATED` 並建立 ACTIVE Reservation；另一張重新讀取 ATP 後成為 `BACKORDERED`。
   - 驗證 `reservedQuantity <= onHandQuantity`、兩筆 Inbox 均已提交，且 Outbox 含 `OrderAllocatedIntegrationEvent` 與 `BackorderCreatedIntegrationEvent`。

2. 強制前三次 allocation attempt 在 Coordinator 完成後拋出 `OptimisticLockingFailureException`。
   - consumer 收到 `AllocationConcurrencyExhaustedException`，不將訂單誤建模為 `BACKORDERED`。
   - Order 保持 `PENDING`、StockPool 保持未保留、無 Reservation、Inbox 與 Outbox 均 rollback。
   - 驗證 `order_allocation_retry_exhausted_total{operation="allocate-order"}` 增加一次，且注入器確實執行三次。

test-only AOP injector 僅存在於 SIT 的 nested `@TestConfiguration`，不會進入 production application。

## 變更檔案

### `../stock-reservation-design.md`

- SR-18 checkbox 更新為完成。
- 整體進度更新為 `18 / 18`。
- 標記 SR-01～SR-18 全部完成。

## 驗證結果

聚焦 SR-18 SIT：

```bash
./gradlew :order-promising:sit \
  --tests '*AllocationConcurrencyEndToEndIntegrationTest' \
  --no-daemon
```

結果：`BUILD SUCCESSFUL`，共 `2 tests completed`。

完整驗證：

```bash
./gradlew :order-promising:test --no-daemon
./gradlew :order-promising:sit --no-daemon
```

結果：兩組皆為 `BUILD SUCCESSFUL`，共 `98 unit tests completed` 與 `47 SIT tests completed`。

## 開發環境注意事項

- 此測試使用 PostgreSQL 的實際 optimistic locking，不以 sleep 或機率性並行期待衝突；AOP barrier 是為了確保兩個 transaction 在相同讀取版本上競爭。
- SR-18 驗證的是目前單一 StockPool、單一 SKU、完整 reservation 的 scope；不引入多倉、部分配置、shipment 或 WMS。
