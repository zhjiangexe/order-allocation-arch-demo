## Why

SR-07／SR-17 已經證明 FIFO 補貨配置的邏輯本身正確，但目前唯一的端到端驗證
（`AllocationWorkflowEndToEndIntegrationTest.shouldAllocateOnlyFifoPrefixWhenReplenishingFromKafkaIntegrationEvent`）
只用兩張訂單，不足以在有意義的排隊量體下證明：嚴格 FIFO 順序穩定、head-of-line
blocking 不會被跳過、且單一 `StockReplenishedIntegrationEvent` 觸發的批次配置決策
在整個 transaction 內正確、持久化狀態一致。Demo-01（`add-hot-sku-concurrency-demo`）
的 Non-Goals 明確把「FIFO replenishment demo」列為之後才做的項目，本次就是延續
那個系列、填上這一塊。

## What Changes

- 新增 Demo-02 FIFO 補貨批次配置情境：1,000 張已在排隊的同 SKU BACKORDERED 訂單，
  一個 `StockReplenishedIntegrationEvent` 到達後喚醒佇列，驗證嚴格 FIFO 批次決策。
- 用固定、可手算的數量分布（不是隨機數量）：前 500 張 quantity=1、第 501 張是刻意
  補不滿的 blocker、後 499 張 quantity=1，證明 head-of-line blocking 在量體下依然
  精準：blocker 之後即使數量更小、原本配得起，也必須被跳過。
- 接續同一批 fixture，送出第二個（循序、非併發）`StockReplenishedIntegrationEvent`，
  數量足以補滿 blocker 與剩餘所有訂單，驗證佇列在後續補貨到位後能正確恢復配置，
  不只是「第一波正確卡住」，也要證明「喚醒佇列」的後半段（恢復配置）同樣正確。
- 對帳兩個階段各自的 Order／StockPool／StockReservation／Inbox／Outbox 最終持久化狀態。

## Non-Goals

- 不驗證併發競爭（多個 `StockReplenishedIntegrationEvent` 同時到達，或補貨當下有
  新訂單同時競爭同一個 StockPool）；這是 Demo-01 已覆蓋、且性質不同的併發場景。
- 不隨機化訂單數量分布；期望結果必須能手算驗證，不在測試裡重新實作一次 FIFO
  演算法來推導預期值。
- 不變更 allocation policy、`StrictFifoAllocationPolicy` 演算法、Kafka topics 或
  Integration Event 契約。
- 不引入新的 load-testing 框架，也不是 production throughput/latency benchmark。
- 不實作 read-model replay demo（Demo-01 Non-Goals 提到的另一項，仍延後）。

## Capabilities

### New Capabilities

- `fifo-replenishment-demo`: 驗證 StockReplenished 事件在大量排隊 backorder 下，
  能正確喚醒佇列並依嚴格 FIFO 完成批次配置決策，不超賣、不跳單、持久化狀態一致；
  並驗證後續（循序）補貨到位後，先前被 head-of-line blocking 卡住的訂單能正確恢復配置。

### Modified Capabilities

- None.

## Impact

- 新增 allocation SIT 覆蓋與測試 fixture，不改動 domain 補貨規則、Kafka topics 或
  公開 Integration Event 契約。
- 種入 1,000 筆 BACKORDERED Order fixture 會比一般 SIT case 花更多時間，但單一事件、
  單一 transaction，不需要額外的併發協調機制。
