# Demo-02 FIFO replenishment batch demo 實作紀錄

狀態：已完成

完成日期：2026-07-25

## 實作範圍

Demo-02 延續 Demo-01（`add-hot-sku-concurrency-demo`）Non-Goals 中提到的下一個示範：
把既有的 FIFO 補貨批次配置驗證從 2 張訂單放大到 1,000 張，證明循序（非併發）
`StockReplenishedIntegrationEvent` 喚醒排隊中的 backorder 佇列後，`StrictFifoAllocationPolicy`
在有意義的量體下依然嚴格遵守 head-of-line blocking——遇到補不滿的訂單就整批停止，
不會跳過去配置後面數量更小、原本配得起的訂單；並且在後續補貨到位後，先前被卡住的
訂單能正確恢復配置，完整驗證「喚醒佇列」的兩個階段，不只是第一波卡住就結束驗證。

本次不驗證併發競爭（多個補貨事件同時到達，Demo-01 已覆蓋併發面向）、不隨機化訂單
數量分布、不變更 allocation policy、Kafka topics 或 Integration Event 契約。

## 新增檔案

### `../../order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationFifoReplenishmentBatchIntegrationTest.java`

使用完整 Spring application context 與 PostgreSQL Testcontainers，新增一個情境：

1. 建立一個 on-hand=0 的 `FIFO-SKU` StockPool，以及 1,000 張已排隊的 BACKORDERED
   Order（直接以 `Order.rehydrate(...)` 種入，不經過真正的下單配置流程）。每張的
   `backorderedSince` 逐筆遞增 1 毫秒，保證 FIFO 排序穩定，與 production 的
   `ORDER BY backordered_since ASC, id ASC` 對齊。額外記住三個關鍵位置的
   orderId（最早、blocker、最晚），供後續逐筆身分驗證使用。
2. 數量分布固定、可手算：FIFO 最前面 500 張 quantity 皆為 1，第 501 張是刻意
   補不滿的 blocker（quantity 999），後 499 張 quantity 皆為 1。固定數量分布是
   刻意取捨：期望結果可以手算寫死斷言，測試不需要在自己內部重新實作一次
   `StrictFifoAllocationPolicy` 的演算法來推導預期值，保持測試對 production
   邏輯的獨立性。
3. 送出第一個 `StockReplenishedIntegrationEvent`，quantity 精準等於前 500 張的
   總和（500）。對帳第一階段：500 張 Order `ALLOCATED`、500 張仍 `BACKORDERED`
   （含 blocker 與其後 499 張未被跳過配置的小單）；500 筆 ACTIVE
   StockReservation、總量 500、`order_id` 全相異；StockPool on-hand=500、
   reserved=500、ATP=0；`event_inbox` 恰 1 筆 claim；`event_outbox` 恰 500 筆
   `OrderAllocatedIntegrationEvent`。額外用 orderId 直接驗證：最早那張已
   `ALLOCATED`，blocker 與最晚那張仍是 `BACKORDERED`。
4. 送出第二個（循序、非併發）`StockReplenishedIntegrationEvent`，quantity 等於
   blocker 與其後 499 張的總和（1,498），驗證「喚醒佇列」的後半段——先前被
   head-of-line blocking 卡住的訂單，補貨到位後應該能正確恢復配置。對帳第二
   階段：全部 1,000 張 Order 均為 `ALLOCATED`；1,000 筆 ACTIVE
   StockReservation、總量 1,998；StockPool on-hand=1,998、reserved=1,998、
   ATP=0；`event_inbox` 累積 2 筆 claim；`event_outbox` 恰 1,000 筆
   `OrderAllocatedIntegrationEvent`。額外用 orderId 驗證 blocker 與最晚那張
   這時也都變成 `ALLOCATED`。

只看聚合數字（總張數、總量）無法分辨 FIFO 有沒有選對「哪幾張」，因為除了 blocker
外每張訂單 quantity 都是 1，彼此在聚合統計上互相可替代。用 `firstOrderId`／
`blockerOrderId`／`lastOrderId` 三個關鍵位置做逐筆身分驗證，才能證明 blocker
精準卡在第一階段、精準在第二階段恢復，而不只是「選對了 500 張『某些』訂單」。

與 Demo-01 不同，本次不需要 virtual-thread wave、test-only AOP synchronizer 或
redelivery 迴圈——單執行緒依序完成種 fixture、送兩個循序事件、對帳即可，因為驗證的
是批次配置演算法本身在單一 transaction 內的正確性，不涉及多方競爭同一筆資料。

## 變更檔案

### `../stock-reservation-design.md`

- 在 Demo-01 之後新增「Demo-02 — FIFO 補貨批次劇本」小節，說明其依賴 SR-07／SR-17、
  驗證範圍與明確的範圍邊界（不含併發競爭、不隨機化數量分布、不是 production
  benchmark）。與 Demo-01 一樣不佔用 SR-01～SR-18 的編號序列。

## 驗證結果

聚焦 Demo-02 SIT：

```bash
./gradlew :order-promising:sit \
  --tests '*AllocationFifoReplenishmentBatchIntegrationTest' \
  --no-daemon
```

結果：`BUILD SUCCESSFUL`，`1 test completed`（`time="1.11"` 秒，含 1,000 筆 fixture
種入與兩次循序補貨）；以 `--rerun` 額外重跑一次同樣通過。

完整驗證：

```bash
./gradlew :order-promising:test --no-daemon
./gradlew :order-promising:sit --no-daemon
```

結果：兩組皆為 `BUILD SUCCESSFUL`，SIT 共 `49 tests completed`（較 Demo-01 完成時的
48 個增加 1 個，即本次新增的 Demo-02 情境），全部 0 failures／0 errors。

## 開發環境注意事項

- 這是循序（非併發）補貨事件、逐一 transaction 完成的批次配置決策驗證，不模擬
  併發、不啟動 Kafka broker，也不是 production throughput/latency benchmark。
- 固定數量分布（而非隨機）是刻意設計：blocker quantity（999）遠大於第一次補貨量
  （500）與該時點剩餘 ATP（0），任何正數的 blocker 都足以觸發 head-of-line
  blocking；選用明顯偏大的數字只是讓測試意圖在程式碼與文件中更好讀。
- 只驗證「第一波補貨不足時正確卡住」不足以證明「喚醒佇列」完整，因為如果
  backorder 查詢或 StockPool 狀態在第一次 transaction 後沒有正確反映最新狀態，
  即使第一階段斷言通過，第二次補貨仍可能重複配置、漏配置或選錯順序；因此加入
  第二次循序補貨與逐筆 orderId 驗證，覆蓋喚醒佇列的完整生命週期。
