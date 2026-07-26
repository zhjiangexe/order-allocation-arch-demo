# e2e/perf

`order-promising` 的真實端到端壓測基礎設施：PostgreSQL + Kafka（KRaft）+ Kafka
Connect/Debezium，讓下單真的走完整條路徑——Outbox → Debezium CDC → Kafka →
allocation consumer，不是 `sit` source set 繞過 Kafka 的 in-process 捷徑。

App **不**容器化在這裡，直接跑在 host 上吃 `dev` profile 預設值（`localhost:5432`、
`localhost:9092`）。

## 怎麼跑

`./run.sh` 用 subcommand 分工（像 `docker compose`／`git` 那樣），把基礎設施、app、
Debezium connector、種庫存、跑 k6 串成一個 `up`，每步都會偵測「已經在跑／已經註冊過」
再決定要不要跳過，可以放心重複執行：

```bash
SKU=HOT-SKU STOCK=500 VUS=1000 ./e2e/perf/run.sh up   # 或直接 ./e2e/perf/run.sh，up 是預設
```

exit code 就是 k6 的 exit code（見 `k6/hot-sku-burst.js` 的 `thresholds`）：0 代表這次
跑的結果全部符合預期（不超賣、無逾時、延遲在門檻內），不用自己讀摘要判斷。結果 JSON
存到 `k6/results/`。

`docker compose up` 也會順便啟動 [Kafbat UI](http://localhost:8081)（純觀察用，不影響
測試或壓測本身），可以直接在瀏覽器裡看 topic 訊息實際落在哪個 partition、key 是什麼——
例如要肉眼核對 `PARTITION_KEY_STRATEGY=sku` 時，同一個 SKU 的訊息是不是真的都收斂進
同一個 partition，不用再靠 `kafka-console-consumer` 那種命令列方式。

其他 subcommand：

```bash
./e2e/perf/run.sh verify HOT-SKU              # 衝突率／重試率／DB 最終狀態，Prometheus、log、DB 三方對照
./e2e/perf/run.sh seed HOT-SKU-2 200          # 單獨種／重置一筆 StockPool 庫存
./e2e/perf/run.sh check-dlt ordering.order-events-dlt   # 撈 DLT topic 內容核對 orderId
./e2e/perf/run.sh down                        # 拆除
```

## 現況

整條路徑（`POST /orders` → Outbox → Debezium → Kafka → 消費）與 `GET /orders/{orderId}`
已即時驗證通過。v1 baseline（4-partition／concurrency=4、庫存 500 件，原始輸出見
[`k6/results/hot-sku-burst-v1.log`](k6/results/hot-sku-burst-v1.log)）：1,000 張訂單收斂為
500 `ALLOCATED`／500 `BACKORDERED`，`checks_total` 全過、不超賣、不漏單，
`order_decision_latency_ms` p99 2.88s。跟 Demo-01 SIT（`AllocationHotSkuConcurrencyIntegrationTest`
的 test-only 屏障強制製造衝突）是互補的驗證方式，都能穩定量到真實 optimistic-lock 衝突。

重試與 DLT 架構（`AllocationConcurrencyExhaustedException` → 4 次指數退避重送 →
`DeadLetterPublishingRecoverer`）的設計與取捨見
[`AllocationKafkaErrorHandlingConfiguration`](../../order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/configuration/AllocationKafkaErrorHandlingConfiguration.java)
的 Javadoc。兩輪即時驗證：

| 情境 | app 層重試用盡 | container 層退避救回 | 真正落 DLT |
| --- | --- | --- | --- |
| 乾淨跑法（1,000 VUs／庫存 500 件） | 6 | 6 | **0** |
| DB trigger 刻意撐爆（UPDATE 延遲 0.3s） | 114 | 82 | **32** |

DLT 落地驗證：`./e2e/perf/run.sh check-dlt ordering.order-events-dlt` 撈出的 32 筆
訊息，跟 DB 卡住的 32 筆 `PENDING` 訂單逐一核對 orderId 完全對上。

### v3：SKU 分區 single-writer 對比（`archone.allocation.partition-key-strategy=sku`）

同一劇本（1,000 VUs／庫存 500）分別跑 v1（`orderId` 分區）與 v3（`sku`
分區）：

| 情境 | 吞吐量（訂單／秒） | 重試次數 | 重試用盡次數 |
| --- | --- | --- | --- |
| v1（orderId 分區） | 203.3 | 15 | 6 |
| v3（sku 分區） | 270.8 | 0 | 0 |

v1、v3 的正式數據都是在各自 process 先跑過一輪拋棄式暖機 burst（同樣 1,000
VUs／庫存 500，結果與 Prometheus 計數皆捨棄不計）之後才重新種庫存、量測，避免
JIT／連線池／consumer-group 暖機程度不對稱污染吞吐量對比。

對比圖：[`throughput_comparison.png`](k6/results/throughput_comparison.png)、
[`conflict_comparison.png`](k6/results/conflict_comparison.png)。

v3 只解決「下單 vs 下單」的衝突；「下單 vs 補貨」跨 consumer group 的殘留對撞
不在這次範圍內，見
[`docs/superpowers/specs/2026-07-26-v3-single-writer-design.md`](../../docs/superpowers/specs/2026-07-26-v3-single-writer-design.md)。

如果訊息卡在退避重送中，直接 `./e2e/perf/run.sh down` 重來，不用 debug 被污染的
partition——反正是拋棄式的本機環境。
