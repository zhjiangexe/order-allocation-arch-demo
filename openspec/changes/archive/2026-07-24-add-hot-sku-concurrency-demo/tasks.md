## 1. 熱門 SKU 併發劇本

- [x] 1.1 實作 **Execute a hot-SKU concurrent submission burst** 與 **Submit 1,000 virtual-thread deliveries with bounded database concurrency**：建立 1,000 個同 SKU、quantity 為 1 的 PENDING Orders 和唯一 OrderPlaced events，透過同一 start gate 交付到 allocation Kafka entrypoint；以 `AllocationHotSkuConcurrencyIntegrationTest` 驗證 1,000 筆 submission 都被執行。
- [x] 1.2 實作 **Demonstrate a real optimistic-lock conflict** 與 **Force a real first-wave optimistic-lock conflict without a 1,000-party database barrier**：新增 test-only interceptor，於前兩筆讀取同一 StockPool 後同步釋放並記錄真實 optimistic-lock conflict；以 SIT assertion 驗證至少一次衝突且未注入 synthetic exception。

## 2. 重送與最終對帳

- [x] 2.1 實作 **Redeliver retry-exhausted events safely** 與 **Replay only retry-exhausted deliveries**：收集並僅重送 `AllocationConcurrencyExhaustedException` 對應的原 eventId，超過 recovery limit 或出現其他例外時使 SIT 失敗；以 exhausted delivery 的 Inbox rollback 與重送後成功結果驗證。
- [x] 2.2 實作 **Reconcile final allocation state** 與 **Reconcile persisted business invariants instead of only counting successful calls**：驗證 10 ALLOCATED、990 BACKORDERED、10 ACTIVE reservations／總量 10、StockPool ATP 0、1,000 Inbox claims，以及 1,000 筆對應 outcome Outbox records；以單一 SIT 的 persisted-state assertions 驗證不超賣、無遺失事件與無重複 reservation。

## 3. 驗證與文件

- [x] 3.1 為 Demo-01 提供可重複執行的命令與台灣中文 `@DisplayName`，使開發者能以 `./gradlew :order-promising:sit --tests '*AllocationHotSkuConcurrencyIntegrationTest' --no-daemon` 執行；以該命令與 `./gradlew :order-promising:test --no-daemon` 驗證。
- [x] 3.2 更新 `docs/stock-reservation-design.md` 與完成紀錄，說明 Demo-01 驗證的是 bounded database concurrency 下的 1,000 submissions，不是 production benchmark 或 Kafka broker E2E；以 `spectra validate --changes add-hot-sku-concurrency-demo` 與文件審閱驗證範圍一致。
