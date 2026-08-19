# e2e/spec

這裡是 messaging 的 full-path correctness 規格。它啟動真實 PostgreSQL、Kafka、Debezium
Connect 與 `order-promising` Spring application，驗證：

```text
HTTP business transaction
  → event_outbox
  → PostgreSQL WAL
  → Debezium Outbox Event Router
  → Kafka
  → event_inbox + consumer use case
  → follow-up event_outbox
  → Kafka
  → downstream use case
```

測試不使用 in-process Outbox drain，也不 mock broker／CDC。容器、topic、database 與隨機 HTTP
port 皆由 Testcontainers 隔離，不依賴 `../perf` 的長駐 Compose project。

## 執行

前置條件只有可用的 Docker daemon；第一次執行會下載 PostgreSQL、Kafka、Debezium 與 Ryuk
images。

```bash
./gradlew :bootstrap:correctnessE2e --no-daemon
```

測試報告位於：

```text
order-promising/build/reports/tests/correctnessE2e/index.html
```

`correctnessE2e` 是獨立的重型 CI layer，刻意不掛在一般 `check`。一般 pull request 可以先跑
unit／SIT，再由 Docker-capable job 執行本 task；release gate 則應要求三層全部通過。

## 目前情境

1. 真實下單 transaction 經兩輪 Outbox／CDC／Kafka／Inbox，最後將訂單推進為
   `ALLOCATED`。
2. application restart 後重送原 Kafka record，原 message ID 由相同 subscriber Inbox 判定為
   duplicate，不重複預留、建立搬運或發布結果事件。
3. Debezium Connect 停機期間提交 Outbox；重啟後由 PostgreSQL WAL／Kafka offset 追趕，並可
   繼續處理新交易。
4. Kafka container 暫停期間 business transaction 仍可提交；Kafka 恢復後 CDC 與 consumer
   追上，不遺失事件。
5. 在 consumer transaction 內、business writes 之後注入 optimistic-lock failure，證明每次
   失敗的 Inbox、庫存預留、搬運與 follow-up Outbox 全部 rollback。
6. `3 local attempts × 5 Kafka deliveries = 15` 次耗盡後進 DLT；DLT 保留 message ID、key、
   event type、generic headers 與原 topic／partition／offset。解除故障並 replay 後只成功一次。

## 與 performance E2E 的分工

- `e2e/spec`：低資料量、強斷言、可重複的 correctness gate。
- `e2e/perf`：固定 host ports、Compose、k6 與高併發量測；不取代 correctness assertions。

測試 source 留在 repository-level `e2e/spec`，classpath 與 Gradle lifecycle 則由實際被測的
`order-promising` deployable 擁有；因此不需要為 E2E 再建立一個假 application module。
