# e2e/perf

`order-promising` 的真實端到端壓測基礎設施：PostgreSQL + Kafka（KRaft）+ Kafka
Connect/Debezium，讓下單真的走完整條路徑——Outbox → Debezium CDC → Kafka →
allocation consumer，不是 `sit` source set 繞過 Kafka 的 in-process 捷徑。

App **不**容器化在這裡，直接跑在 host 上吃 `dev` profile 預設值（`localhost:28291`、
`localhost:28292`）。

**host port 一律 `2829x`**：`28290` app、`28291` postgres、`28292` kafka、`28293`
kafka-connect、`28294` kafka-ui、`28295` 前端 dev server。容器內部的 port 不動，只有 host
對應與 Kafka 的 advertised listener 跟著改——**advertised 必須是 host 看得到的那個 port**，
漏改它 client 會拿到 metadata 之後連回不存在的 9092。

## 怎麼跑

`./run.sh` 用 subcommand 分工（像 `docker compose`／`git` 那樣），每步都會偵測「已經在跑／
已經註冊過」再決定要不要跳過，可以放心重複執行：

```bash
./e2e/perf/run.sh up                                    # 基礎設施 + app + Debezium connector
SKU=HOT-SKU STOCK=500 VUS=1000 ./e2e/perf/run.sh perf   # up ＋ 種庫存 ＋ 跑 k6
```

`up` 與 `perf` 分開，是因為代價差一個數量級：只想開操作台看畫面的人不該被迫跑一輪上千
VUS 的壓測。`up` 是預設的 subcommand，因此直接 `./e2e/perf/run.sh` 只會把系統起來。

三步的順序不能換：connector 要讀 `event_outbox`，而那張表是 app 啟動時由 Flyway 建的。
**app 不在 compose 裡**，所以 `docker compose up` 起不出一套完整的系統——少了 connector
註冊，`OrderPlaced` 會卡在 outbox 出不去，訂單就永遠停在 `PENDING`。

`perf` 的 exit code 就是 k6 的 exit code（見 `k6/hot-sku-burst.js` 的 `thresholds`）：0 代表這次
跑的結果全部符合預期（不超賣、無逾時、延遲在門檻內），不用自己讀摘要判斷。結果 JSON
存到 `k6/results/`。

`docker compose up` 也會順便啟動 [Kafbat UI](http://localhost:28294)（純觀察用，不影響
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
[`k6/results/hot-sku-burst-r2-warehouse.log`](k6/results/hot-sku-burst-r2-warehouse.log)）：
1,000 張訂單收斂為 500 `ALLOCATED`／500 `BACKORDERED`，`checks_total` 全過、不超賣、
不漏單，`order_decision_latency_ms` p99 5.11s。跟 Demo-01 SIT
（`AllocationHotSkuConcurrencyIntegrationTest` 的 test-only 屏障強制製造衝突）是互補的
驗證方式，都能穩定量到真實 optimistic-lock 衝突。

上面這組數字是在訂單加上必填倉別、且該欄位帶複合外鍵之後，於空資料庫上重新套用 migration
再量的。歷次記錄：加倉別前 5.09s 與 4.14s，更早的行模型改造前 4.5s 與 4.77s。**三組互相
重疊，沒有證據顯示任何一次資料模型改造使延遲退化**。這是預期的——每張單多一次指向
`owner_nodes` 的複合外鍵檢查是微秒級；配置延遲由 Kafka 傳遞與單一 `StockPool` row 的樂觀鎖
競爭主導。要拿延遲數字做跨版本比較，必須在同一次 session、同樣的機器負載下量測。

同一輪的重試計數（`run.sh verify HOT-SKU`）：嘗試 23 次、用盡 6 次，與下方表格的
「乾淨跑法」一列一致；DB 的狀態分布也是 500／500，與 k6 端計數對得上。

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

這張對比表是在 `POST /orders` 合約改動之前量的，未隨之重測——兩個版本跑的是同一支
腳本、承受同樣的合約成本，因此相對比較仍然成立；但表中的絕對吞吐量不可與改動後的
數字並列。要更新它必須把暖機方法論完整重跑一輪（v1 與 v3 各一次暖機加一次量測）。

對比圖：[`throughput_comparison.png`](k6/results/throughput_comparison.png)、
[`conflict_comparison.png`](k6/results/conflict_comparison.png)。

**這組數字量的是刻意製造衝突的劇本**（1,000 VUs 搶同一個 SKU），因此代表的是「熱點 SKU 下
的上界」，不是一般流量的預期改善。真實訂單分散在多個 SKU 時衝突本來就少，兩個策略的差距會
明顯縮小——這也是預設仍為 `order-id` 的理由：策略的價值取決於流量形狀，不是一律開啟。

v3 只解決「下單 vs 下單」的衝突；「下單 vs 補貨」跨 consumer group 的殘留對撞
不在這次範圍內，見
[`docs/superpowers/specs/2026-07-26-v3-single-writer-design.md`](../../docs/superpowers/specs/2026-07-26-v3-single-writer-design.md)。

如果訊息卡在退避重送中，直接 `./e2e/perf/run.sh down` 重來，不用 debug 被污染的
partition——反正是拋棄式的本機環境。
