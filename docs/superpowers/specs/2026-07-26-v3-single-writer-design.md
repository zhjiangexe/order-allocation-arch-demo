# v3：SKU 分區 single-writer 設計

狀態：已確認，待實作

日期：2026-07-26

## 目標

延續 v1（optimistic-lock + 有限重試）的 hot-SKU 併發場景，實作 v3：把 Kafka 的
partition key 從 `orderId` 換成 `sku`，讓同一個 SKU 的下單事件全部落在同一個
partition，利用 Kafka「同一個 consumer group 內，同一個 partition 同時只有一個
consumer thread 在讀」的保證，達成 single-writer——衝突在架構上不可能發生，不是
靠重試補救。

最終交付：v1／v3 用同一支 k6 劇本（`e2e/perf/k6/hot-sku-burst.js`）各跑一次，產出
兩張真實數字的對比圖（吞吐量、衝突率），供履歷/面試使用。**數字必須是真的跑出來
的，不是估算。**

## 背景：這不是修正 v1 的錯誤

`docs/stock-reservation-design.md`（2026-07-22）從一開始就規劃 Kafka + Outbox +
Debezium，不是中途改道。該文件的「Kafka topics 與 partition key」一節（570-578
行）顯示：`inventory.stock-events`（補貨事件）從最初就用 `sku` 當 key，理由是
「維持同 SKU 的 partition 內順序」——跟 v3 要用的機制一致，只是套用在別的 topic、
為了別的理由（FIFO 順序，不是防衝突）。

`ordering.order-events` 選 `orderId` 當 key，也是文件裡「並行控制與重試」一節
（419-451 行）明講的**刻意的第一版策略**：先用 PostgreSQL transaction + JPA
`@Version`，明確排除 Redisson／pessimistic lock 這類更重的做法（見「本次不包含」
清單）。「第一版」這個用詞本身已經留了空間給後續版本採用不同做法。

v3 做的事，是把文件裡已經用在 `inventory.stock-events` 的 SKU-key 手法，延伸套用
到 `ordering.order-events`，接上文件自己留的伏筆——不是推翻 v1 的設計，是它的下一步。

## 機制

> **已被取代（superseded）**：下方流程描述的是「partition key 寫進 `aggregateid`
> 欄位」這條路徑。`fix-outbox-partition-key-semantics` 已把傳輸決策拆到獨立的
> `partition_key` 欄位——partition 行為完全不變，但取值來源改變。實際流程請見
> `openspec/changes/fix-outbox-partition-key-semantics/design.md` 與
> `docs/stock-reservation-design.md` 的 `event_outbox` 欄位表。此段保留作為 v3 當時
> 決策脈絡的紀錄，不要依它重建實作。

```
OutboxAppender.append(event, aggregateType, aggregateId, topic, occurredAt)
  → 寫進 event_outbox.aggregate_id 欄位
  → Debezium Outbox EventRouter：aggregate_id 欄位值當作 Kafka record key
  → Kafka 預設 partitioner：partition = hash(key) % numPartitions
  → 同一個 consumer group 內，同一個 partition 同時只有一個 consumer thread 讀
```

只要同一個 SKU 的所有下單事件都用同一把 key（sku），就會確定性地（不是機率性）
落在同一個 partition，被同一條 thread 序列處理——不可能有兩個 thread 同時讀到
同一個 `StockPool` 的同一個 version。

## 範圍邊界

**v3 只解決「下單 vs 下單」這一種對撞**（`ordering.order-events` 內部，也就是
`hot-sku-burst.js` 這個劇本實際在測的情境）。

**「下單 vs 補貨」這種跨 consumer group 的對撞，明確不在這次範圍內**：
`allocation-ordering-events` 與 `allocation-inventory-events` 是兩個獨立的
consumer group，各自的 partition-exclusivity 保證不互相涵蓋。就算兩個 topic 都用
sku 當 key，一個 thread 在處理 HOT-SKU 的補貨、另一個 thread 同時處理 HOT-SKU 的
下單分配，仍然可能同時碰觸同一個 `StockPool` row。要徹底補上這個縫，需要把兩條
消費路徑合併成同一個 consumer group——是更大、更侵入性的改動，跟這次「用同一劇本
對比 v1/v3 下單吞吐與衝突率」的目標關聯不大，明確排除。

**因此 optimistic lock、bounded-context allocation retry、`DefaultErrorHandler` + DLT
這整套機制在 v3 底下完全保留、不刪除**——它們不是變成死路徑，是繼續擔任「下單 vs
補貨」這個殘留對撞情境的唯一防線。`hot-sku-burst.js` 全程不觸發補貨事件，所以這個
已知缺口不會污染這次的對比數字，但必須誠實寫進文件，不能含糊帶過或宣稱 v3 是
「完整的 single-writer」。

## 改動內容

1. **新增設定**：`archone.allocation.partition-key-strategy=order-id|sku`（預設
   `order-id`，維持 v1 現況零風險）。
2. **`OrderCancelled` 加 `sku` 欄位**：
   - `ordering/domain/event/OrderCancelled.java`：record 增加 `String sku`。
   - `Order.cancel()`：發事件時帶入 `this.sku`。
3. **`OrderingDomainEventTranslator`** 依設定值決定 `aggregateId`：
   - `translate(OrderPlaced event)`：`order-id` 策略傳 `event.orderId()`；
     `sku` 策略傳 `event.sku()`。
   - `translate(OrderCancelled event)`：同上，`sku` 策略傳 `event.sku()`。
4. **partition 數不變**，沿用 `e2e/perf/docker-compose.yml` 現有的
   `KAFKA_NUM_PARTITIONS=4`，確保 v1／v3 比較時基礎設施條件相同。

## 對比方法

`e2e/perf/run.sh up` 沿用現有的 `SKU`／`STOCK`／`VUS` 參數，新增一個
`PARTITION_KEY_STRATEGY` 環境變數，轉成 `--archone.allocation.partition-key-strategy`
啟動參數傳給 app。

```bash
# v1
SKU=HOT-SKU STOCK=500 VUS=1000 ./e2e/perf/run.sh up
# 記錄結果、down
./e2e/perf/run.sh down

# v3
SKU=HOT-SKU STOCK=500 VUS=1000 PARTITION_KEY_STRATEGY=sku ./e2e/perf/run.sh up
```

兩輪都用 `./e2e/perf/run.sh verify <SKU>` 記錄 Prometheus 的
`order_allocation_retry_attempts_total`／`_exhausted_total`（跑前跑後各查一次，
算 delta），k6 本身的 `--summary-export` JSON 記錄吞吐量（`http_reqs`／
`iterations` rate）與延遲分布。

## 圖表

新增 `e2e/perf/k6/plot_comparison.py`，用命令列參數接收輸入，不額外設計一個中間
資料檔格式：

```bash
python3 plot_comparison.py \
  --v1-summary hot-sku-burst-v1.json --v1-attempts 41 --v1-exhausted 10 \
  --v3-summary hot-sku-burst-v3.json --v3-attempts 0  --v3-exhausted 0
```

`--v{1,3}-summary` 指向 k6 `--summary-export` 的 JSON（取吞吐量／延遲）；
`--v{1,3}-attempts`／`--v{1,3}-exhausted` 是兩輪各自跑完後用
`./e2e/perf/run.sh verify <SKU>` 讀到的 Prometheus 數字，手動抄進命令列——這兩個
數字只在兩輪之間各記錄一次，不需要為此另外設計一個檔案格式或改動 `verify`
subcommand 去自動輸出結構化資料。用 `matplotlib` 畫兩張長條圖：

1. 吞吐量對比（v1 vs v3，req/s 或 iterations/s）。
2. 衝突率對比（v1 vs v3，retry attempts／exhausted 次數）。

程式碼註解全部用台灣中文。輸出兩張 PNG 存進 `e2e/perf/k6/results/`。這支 script
在兩輪 `run.sh up` 都跑完、資料都記錄下來之後手動執行，不整合進 `run.sh`
本身（避免把「跑壓測」跟「畫圖比較」這兩個時機點綁死在一起——v1／v3 是分兩次跑的，
圖要等兩邊都有數字才能畫）。

## 測試

- **單元測試**：`OrderingDomainEventTranslator` 依 `partition-key-strategy` 設定，
  正確選擇 `orderId`／`sku` 當 `aggregateId`（`OrderPlaced` 與 `OrderCancelled`
  都要覆蓋）。
- **即時 k6 對比**：v1、v3 各跑一次 `hot-sku-burst.js`（1,000 VUs／庫存 500），
  記錄真實吞吐量與衝突率，這是驗收標準的核心，不用估算數字替代。
- **可選**：仿照 `AllocationHotSkuConcurrencyIntegrationTest`（Demo-01 SIT）的
  手法，在 `sku` 策略下加一個 SIT 測試，斷言 1,000 筆同 SKU 併發送出後
  `order_allocation_retry_attempts_total` 恆為 0——用確定性測試佐證「不是機率上
  很少衝突，是結構上不可能衝突」，比單看 k6 一次性的數字更有說服力。是否納入這次
  範圍，留給實作計畫階段決定。

## 交付物

- `OrderingDomainEventTranslator`、`OrderCancelled`、`Order.cancel()` 的程式碼
  改動 + 對應單元測試。
- `run.sh` 支援 `PARTITION_KEY_STRATEGY` 參數。
- `e2e/perf/k6/plot_comparison.py`（含台灣中文註解）。
- v1、v3 各一份 k6 `--summary-export` JSON（真實跑出來的，存進
  `e2e/perf/k6/results/`）。
- 兩張對比圖（PNG，吞吐量、衝突率）。
- `e2e/perf/README.md` 補上 v3 的跑法與對比結果段落。
