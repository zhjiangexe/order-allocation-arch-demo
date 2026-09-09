# 配貨巡檢基準測試（2026-09-08）

本機 macOS 26.2、PostgreSQL 16 Alpine Testcontainers、Spring Boot test profile；單執行緒，沒有其他配貨工作競爭。
這是開發環境量測，不是正式環境 SLA 或硬體容量承諾。沒有隔離主機其他負載。

## 測試方式

- 直接呼叫真實 StockOperationBacklogReconciler，使用真實 Candidate Store、Planner、Committer、交易、資料庫約束及 outbox 寫入。
- Kafka listener、正式 scheduler 不啟動；不包含訂單建立、事件傳遞、後續 WMS 工作。
- 一貨主、一來源位置；每張 StockOperation 一行、一 SKU；每 SKU 一個庫存批次；DISPATCH_DATE_FIRST。
- DISTRIBUTED：每張需求不同 SKU，因此 1,000／10,000 張對應同數量的 queue。
- HOT：所有需求使用同一 SKU／同一庫存批次，因此只有一個 queue。這是串行集中需求，不是併發鎖競爭測試。
- SHORTAGE：每張不同 SKU，排序前半 queue 需求量 2、庫存 1；後半需求量 1、庫存 1。
- 暖機 100 張；每種規模、情境與限制各跑三次，交替先跑 200 次或提高上限，減少固定順序偏差。
- 各輪重新準備資料並 ANALYZE；資料建立、清理、統計讀取與結果驗證均排除於 elapsed_ms。
- SQL 統計取自 pg_stat_statements；sql_calls 為資料庫記錄的 statement 呼叫數，不等同 JDBC 網路往返數。sql_exec_ms 為記錄到的執行時間加總，不含所有應用程式／網路／交易成本，也不應當成互斥的耗時分解。
- 每輪驗證：無配貨例外、成功數等於預留數及配貨明細數、無超額預留、缺貨需求未被部分配貨。
- 45 秒是在嘗試之間判斷期限，單次執行或查詢可能讓整輪略超時。
- 每次量測使用新 Usecase 實例，測的是單次執行；未測跨輪 scanCursor 的延續。

## 45 秒預算比較

耗時為三次中位數；成功數若有差異，列範圍。

| 需求張數 | 情境 | 200 次：秒／成功 | 提高次數上限：秒／成功 |
| --- | --- | --- | --- |
| 1,000 | 分散 queue | 1.569／200 | 6.706／1,000 |
| 1,000 | 同 SKU | 1.468／200 | 9.492／1,000 |
| 1,000 | 前半缺貨 | 0.188／0 | 2.983／500 |
| 10,000 | 分散 queue | 1.876／200 | 45.005／4,502–5,332 |
| 10,000 | 同 SKU | 2.529／200 | 45.007／2,737–3,383 |
| 10,000 | 前半缺貨 | 0.430／0 | 45.006／3,282–3,920 |

提高上限使用 2 × 需求數 + 2，讓成功後再查一次空 queue 也有額度。
1,000 張三情境均耗盡本輪候選後結束；10,000 張提高上限的各輪均因時間停止。
現行 query limit 與 attempt limit 使用同一參數；提高次數同時提高了候選 queue 上限，並非只改 commit 次數。
正式演算法沒有修改。

## 已確認的含意

- 200 次很早就截斷工作，這批測試中沒有用滿 45 秒。
- 45 秒對千筆有餘裕；萬筆則確實會成為停止條件。不能把千筆結果線性外推成萬筆。
- 測到的萬筆單執行緒吞吐量尚未達到「30 秒配完一萬張」。
- 缺貨 queue 不必先由複雜 SQL 完全排除；往後繼續掃即可處理後段有庫存需求。
- 分散 queue 配完後，現行 fair rounds 還會逐一查一次空 queue；因此 1,000 次成功會有 2,000 次嘗試。
- 同 SKU 案例的高成本 statement 包括 predecessor 查詢，以及 assert_stock_pool_reservation 的三種呼叫位置。
  V29 中該函式會彙總同 stock_pool 的 ASSIGNED 明細，再與 reserved_quantity 比較；此處值得獨立分析，
  不能為了加速直接移除一致性約束。

## 提高預算後的完整巡檢（舊版，單次測量）

| 萬筆情境 | 完成秒數 | 成功張數 |
| --- | --- | --- |
| 分散 queue | 80.781 | 10,000 |
| 同 SKU | 173.825 | 10,000 |
| 前半缺貨 | 64.326 | 5,000 |

本組用舊版真實 Usecase，將次數提高至 20,002、時間提高至 600 秒。三組均在預算內完成。
這支持低頻完整巡檢的方向，但尚不代表所有多 SKU／多批次／併發情境都能在相同時間內完成。
CSV 的 repeat=4 為這組；repeat=0 為暖機，其餘為 45 秒比較。

## 新版與重跑

實作已移除正式環境的總次數／時間額度，改成每頁 200 個 queue、方法內 cursor 持續掃完，
排程完成後等待 15 分鐘。上面的舊版比較保留為歷史量測；目前 benchmark 改為驗證新版完整巡檢，
不另外複製已移除的 production 演算法。

預設 SIT 跳過本測試，必須明確設定環境變數：

    ARCHONE_ALLOCATION_BENCHMARK=true ./backend/gradlew -p backend :deployments:monolith:sit --tests '*AllocationReconciliationBenchmarkTest.measureFullSweep' --info --rerun-tasks

預設 10,000 張，可用 ARCHONE_ALLOCATION_BENCHMARK_SIZE=1000 改為千筆。
每次先暖機 100 張，再量測三種分布；新版輸出 SWEEP 依序為 size、scenario、repeat、
elapsed_ms、attempts、assigned、sql_calls、sql_exec_ms；BENCH_SQL 為執行時間前五名 statement。
測試會驗證所有可配需求已完成，缺貨需求未部分預留。

## 新版分頁巡檢驗證

新版千筆三情境各跑一次：分散 queue 10.716 秒（成功 1,000）、同 SKU 10.571 秒（成功 1,000）、
前半缺貨 4.198 秒（成功 500）。這次與其他 SIT 同一 Gradle 執行，非三次比較樣本，不用來判定新版相對舊版的效能差異。
分散與缺貨案例均跨越多個 200 queue 頁面，證明單輪巡檢可處理後頁；所有可配需求均完成，
預留量與明細驗證通過。數據見 allocation-reconciliation-paged-sweep.csv。
