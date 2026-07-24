## 1. FIFO 補貨批次劇本

- [x] 1.1 實作 **Wake a queued backorder list on StockReplenished**：建立 1,000 張 FIFO 排序穩定（`backorderedSince` 遞增）的同 SKU BACKORDERED Orders，與一個 on-hand=0 的 StockPool；送出單一 `StockReplenishedIntegrationEvent` 到 allocation Kafka entrypoint；以 `AllocationFifoReplenishmentBatchIntegrationTest` 驗證情境在單一 transaction 內完成。
- [x] 1.2 實作 **Strict FIFO batch decision respects head-of-line blocking at volume**：固定數量分布——前 500 張 quantity=1、第 501 張 blocker quantity=999、後 499 張 quantity=1，補貨量精準等於前 500 張總和；以斷言驗證 blocker 之後即使個別配得起也不被配置。

## 2. 對帳與驗證

- [x] 2.1 實作 **Reconcile final batch allocation state**：驗證 500 ALLOCATED、500 BACKORDERED、500 ACTIVE reservations／總量 500、StockPool on-hand 500／reserved 500／ATP 0、Inbox 恰 1 筆 claim、Outbox 恰 500 筆 `OrderAllocatedIntegrationEvent`；以單一 SIT 的 persisted-state assertions 驗證不超賣、無跳單、無遺失事件。
- [x] 2.2 為 Demo-02 提供可重複執行的命令與台灣中文 `@DisplayName`，使開發者能以 `./gradlew :order-promising:sit --tests '*AllocationFifoReplenishmentBatchIntegrationTest' --no-daemon` 執行；以該命令與 `./gradlew :order-promising:test --no-daemon` 驗證。
- [x] 2.3 更新 `docs/stock-reservation-design.md` 與完成紀錄，說明 Demo-02 驗證的是循序補貨事件的 FIFO 批次配置決策，不含併發競爭、不是 production benchmark；以 `spectra validate --changes add-fifo-replenishment-demo` 與文件審閱驗證範圍一致。

## 3. 佇列恢復驗證

- [x] 3.1 實作 **A subsequent sequential replenishment resumes the queue correctly**：在第一次補貨完成後，於同一測試方法接續送出第二個（循序、非併發）`StockReplenishedIntegrationEvent`，quantity 等於 blocker 與其後 499 張的總和（1,498）；以斷言驗證這批全部依 FIFO 順序被配置，blocker 排在正確位置。額外用 `firstOrderId`／`blockerOrderId`／`lastOrderId` 三個關鍵位置做逐筆身分驗證，不只看聚合數字。
- [x] 3.2 對帳第二階段最終狀態：驗證全部 1,000 張 Order 均為 ALLOCATED、1,000 筆 ACTIVE reservations／總量 1,998、StockPool on-hand 1,998／reserved 1,998／ATP 0、Inbox 恰 2 筆 claim、Outbox 恰 1,000 筆 `OrderAllocatedIntegrationEvent`；以該測試方法的 persisted-state assertions 驗證不超賣、無跳單、無遺失事件。
