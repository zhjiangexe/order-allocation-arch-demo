# Demo-01 Hot-SKU concurrency demo 實作紀錄

狀態：已完成

完成日期：2026-07-24

## 實作範圍

Demo-01 將 SR-18 已證實的 optimistic-lock retry 機制放大到有意義的送出量：1,000 張同一 SKU、
quantity 為 1 的訂單競爭 10 件庫存。測試從 Kafka Integration Event consumer 進入，經過
retry handler、transactional use case、Coordinator、JPA、Inbox 與 Outbox，驗證在既有
datasource connection pool 限制下，1,000 筆併發送出的事件最終都會收斂為正確且不超賣的結果。

本次不變更 allocation policy、Kafka topics 或 Integration Event 契約，也不啟動 Kafka broker、
Debezium connector 或另一套 load-testing 工具；不是 production throughput/latency benchmark。

## 前置變更

### `../../gradle/libs.versions.toml`

- `java` 由 `17` 升到 `25`。design.md 要求以 virtual threads（`Executors.newVirtualThreadPerTaskExecutor()`）
  送出 1,000 筆併發請求，但該 API 需要 Java 21 以上；專案 toolchain 原本釘在 17 完全沒有這組 API。
  本機預設 JDK 為 Temurin 25，可承載此升級；升版後重跑既有 unit tests 與 SIT 均維持
  `BUILD SUCCESSFUL`，未觀察到相容性問題。

## 新增檔案

### `../../order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationHotSkuConcurrencyIntegrationTest.java`

使用完整 Spring application context 與 PostgreSQL Testcontainers，新增一個情境：

1. 建立一個 `HOT-SKU` StockPool（`onHandQuantity = 10`）與 1,000 張 PENDING Order（各 quantity 1），
   搭配 1,000 個唯一 `eventId` 的 `OrderPlacedIntegrationEvent`。
2. 以一個 `CountDownLatch` 作為 start gate，透過 `Executors.newVirtualThreadPerTaskExecutor()`
   建立 1,000 個 virtual-thread 任務，全數等待同一個 start gate 後才送出事件；既有的
   HikariCP connection pool（預設 10 個連線）自然限制同時執行的 DB transaction 數量，這代表
   1,000 筆併發「送出」，不是宣稱 1,000 個 DB transaction 真的同時執行。
3. `FirstWaveConflictSynchronizer`（test-only `@Aspect`，環繞
   `OrderAllocationCoordinator.allocateOrder(..)`）讓最先抵達的兩個 allocation attempt 都讀到
   同一版 StockPool 後才同時釋放，逼出一次決定性的真實 JPA optimistic-lock conflict，不注入
   合成例外。測試以 `invocations() > 1000` 佐證確實發生了額外的重試（也就是真實衝突觸發了
   retry），而不是單純比對固定次數。
4. 只收集 `AllocationConcurrencyExhaustedException` 對應的原始事件，於併發波次全部完成後以
   同一 `eventId` 依序重送，模擬 broker 的 at-least-once redelivery；以 bounded recovery limit
   （最多 5 輪）避免無限重試，若仍有殘留即視為測試失敗並回報件數。
5. 對帳最終持久化狀態：10 張 Order `ALLOCATED`、990 張 `BACKORDERED`；10 筆 ACTIVE
   StockReservation、總量 10 且 `order_id` 全部相異；StockPool 的 `onHandQuantity = 10`、
   `reservedQuantity = 10`、`availableToPromise = 0`；`event_inbox` 恰有 1,000 筆 claim；
   `event_outbox` 恰有 1,000 筆記錄，其中 10 筆為 `OrderAllocatedIntegrationEvent`、990 筆為
   `BackorderCreatedIntegrationEvent`。

test-only AOP synchronizer 僅存在於 SIT 的 nested `@TestConfiguration`，不會進入 production
application，與 SR-18 的 `AllocationConflictInjector` 屬同一種測試技巧，差別在於本次不提供
「強制連續失敗」的合成注入能力。

## 變更檔案

### `../stock-reservation-design.md`

- 在 SR-18 之後新增「Demo-01 — 熱門 SKU 併發劇本」小節，說明其依賴 SR-18、驗證範圍與明確的
  範圍邊界（不是 production benchmark、不啟動 Kafka broker）。Demo-01 不佔用 SR-01～SR-18 的
  編號序列，因為它是既有機制的擴大驗證，不是新的 SR 任務。

## 驗證結果

聚焦 Demo-01 SIT：

```bash
./gradlew :order-promising:sit \
  --tests '*AllocationHotSkuConcurrencyIntegrationTest' \
  --no-daemon
```

結果：`BUILD SUCCESSFUL`，`1 test completed`，重跑 3 次（`--rerun`）均通過，未觀察到 flaky 現象。

完整驗證：

```bash
./gradlew :order-promising:test --no-daemon
./gradlew :order-promising:sit --no-daemon
```

結果：兩組皆為 `BUILD SUCCESSFUL`，共 `99 unit tests completed` 與 `48 SIT tests completed`
（較 SR-18 完成時的 47 個 SIT tests 增加 1 個，即本次新增的 Demo-01 情境）。

## 開發環境注意事項

- 這是 bounded database concurrency 下的 submission burst 展示，不是資料庫容量或延遲的
  production benchmark；HikariCP 連線數上限（預設 10）決定了實際同時執行的 transaction 數。
- 測試未啟動 Kafka broker、Debezium connector，也未引入額外的 load-testing 工具；事件送達仍是
  直接呼叫既有的 `AllocationKafkaIntegrationEventConsumer`，與其餘 allocation SIT 一致。
- Java toolchain 升級影響整個 `order-promising` 子專案（目前是唯一的子專案）；若後續新增其他
  子專案或 CI pipeline 指定 JDK 17，需要同步確認相容性。
