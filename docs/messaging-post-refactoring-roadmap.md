# Messaging 整理後優化 Roadmap

> 狀態：Gate P0-A／P0-B 已完成；下一步為 Gate P0-C Full-path correctness E2E
> 更新日期：2026-08-11
> 適用範圍：`messaging/*`、`contracts`、使用 messaging 的 bounded-context runtime，
> 以及 PostgreSQL／Debezium／Kafka 的端到端驗證

## 1. 目的與邊界

[`eventuate-tram-aligned-messaging-roadmap.md`](eventuate-tram-aligned-messaging-roadmap.md)
已完成 Tram 風格 base Message、Outbox／Inbox、typed Integration Event、programmatic Kafka
consumer、Spring starters、retry／DLT 與 observability 的主要重構。本文件不重新拆一次
messaging modules，而是處理重構後才看得見的整合與 production readiness 問題。

本輪遵守以下原則：

1. 先修正實際 runtime 風險，再調整命名或抽象。
2. Spring Boot 全域設定只有一個基準來源；application-specific policy 必須是明確 override。
3. local optimistic-lock retry、Kafka redelivery 與 DLT 是三個不同邊界，不得無意乘算。
4. 用第二個 bounded context 驗證共用 messaging，而不是靠新增更多 framework abstraction 證明。
5. WMS domain／application 保持 pure Java；Spring、JDBC、Kafka wiring 留在 runtime／adapter。
6. Debezium、Kafka、DB 的部署健康由 platform／operations 負責，不塞進 application starter。
7. Command／Reply、Saga、Reactive 與多 broker 都不是本文件的預設施工項目。

相關文件：

- Tram 對齊歷程：[`eventuate-tram-aligned-messaging-roadmap.md`](eventuate-tram-aligned-messaging-roadmap.md)
- 功能落差：[`eventuate-tram-gap-analysis.md`](eventuate-tram-gap-analysis.md)
- Server 維運：[`messaging-server-operations-guide.md`](messaging-server-operations-guide.md)
- 故障操作：[`messaging-operations-runbook.md`](messaging-operations-runbook.md)

## 2. 已確認的改善點

| 優先度 | 問題 | 目前證據 | 目標 |
|---|---|---|---|
| P0 | Kafka listener 設定有雙重來源 | `e2e/perf/run.sh` 設 `spring.kafka.listener.concurrency=4`，programmatic runtime 卻以 `archone.messaging.consumer.kafka` 預設 `1` 覆寫 container | 建立單一基準與清楚的 override precedence |
| P0 | transient infrastructure failure 可能直接進 DLT | order-promising 目前只將 `OptimisticLockingRetryExhaustedException` 分為 retryable，其餘 fallback 為 non-retryable | 建立有界、可觀測的例外分類矩陣 |
| P0 | 缺少 application-to-application correctness E2E | connector 與 application 各段已有測試，但 `e2e/spec` 尚未完成 | 自動驗證 Outbox → Debezium → Kafka → Inbox → use case |
| P1 | 第二個 bounded context 尚未接入 | WMS 目前是 pure Java，尚無 repository/runtime/messaging adapter | 用 WMS vertical slice 驗證 starter 與邊界 |
| P1 | fulfillment contract 資料不足 | `OrderAllocatedIntegrationEvent` 只有 `orderId`／時間，不能建立 WMS Shipment | 定義不可變、可獨立消費的 fulfillment handoff snapshot |
| P1 | Integration Event governance 仍靠手動列舉 | contract test、golden JSON 與 application mapping 都要人工同步 | 讓漏註冊、重複 type、缺 fixture 在測試期失敗 |
| P1 | payload 沒有 producer-side byte limit | headers 已有上限；body 尚未在 Outbox INSERT 前阻擋 oversized message | 與 Kafka／Connect 限制一致並 fail fast |
| P2 | 維運能力多數仍是文件與人工流程 | 已有 runbook，但尚缺統一 dashboard、canary 與受控 DLT replay | 上線前完成可觀測、可演練的操作面 |

## 3. 建議執行順序

```text
Gate P0-A  Kafka 設定單一來源
    ↓
Gate P0-B  Consumer failure policy
    ↓
Gate P0-C  Full-path correctness E2E
    ↓
Gate P1-D  WMS messaging vertical slice
    ↓
Gate P1-E  Contract governance
    ↓
Gate P1-F  Payload protection
    ↓
Gate P2-G  Production operations
    ↓
Gate P2-H  DLT replay／retention automation（有需求再啟用）
```

P0-C 可以在 P0-A／P0-B 的 characterization tests 建立後開始準備，但必須用修正後的
設定與 failure policy 做最後驗收。P1-E 與 P1-F 應跟第一個 WMS event 一起完成，避免先做
沒有 adopter 驗證的通用框架。

## 4. Gate P0-A — Kafka 設定單一來源

### Tasks

- [x] A1. Characterize `spring.kafka.listener.*`、`archone.messaging.consumer.kafka.*`、
  `ConcurrentKafkaListenerContainerFactory` 與 per-subscription policy 的目前 precedence。
- [x] A2. 將 Spring Boot listener properties／factory 設定作為全域 baseline；未明確設定的
  messaging property 不得用自有 default 覆寫 baseline。
- [x] A3. 保留 application／subscriber 的必要 override，但以 optional override 或 resolver
  明確表達，不建立第二套全域設定。
- [x] A4. 明確定義 concurrency、ack mode、missing-topics-fatal、observation、shutdown timeout
  與 auto-startup 的 precedence，並寫入 `messaging/README.md`。
- [x] A5. 補 property binding／container tests，涵蓋 Boot baseline、subscriber override、未設定
  與非法值 fail-fast。
- [x] A6. 更新 `e2e/perf`，以 consumer-group/runtime metrics 或 container state 證明實際
  concurrency，不只依啟動參數推定。

### Exit criteria

- 設 `spring.kafka.listener.concurrency=4` 時，沒有 override 的 subscription 實際 concurrency
  必須為 4。
- 明確 per-subscription override 必須可覆寫 baseline，且 precedence 有測試。
- `e2e/perf` 不再宣稱與實際 runtime 不一致的 concurrency。

### 2026-08-10 implementation evidence

- 已刪除重複的 `MessagingKafkaConsumerProperties` 與其 configuration metadata；
  `archone.messaging.consumer.kafka.enabled` 僅保留 capability 語意。
- `KafkaSubscriptionPolicy.defaults()` 現在是不含任何 override 的 immutable policy；programmatic
  container 先完整繼承 shared factory，再套用 resolver 明確提供的欄位。
- `order-promising` 的 missing-topic 設定已遷移為
  `spring.kafka.listener.missing-topics-fatal=false`；`e2e/perf` 原有
  `spring.kafka.listener.concurrency=4` 不再被 runtime 預設值蓋回 1。
- ApplicationContext test 證明 Spring Boot properties 會把 shared factory/container 設為
  concurrency 4、RECORD ack、missing-topic false、observation true、auto-start false；runtime test
  另證明 default policy 保留上述 factory 值，而 subscriber policy 可獨立 override。
- A6 verifier 已落在 `e2e/perf/run.sh`：runtime 會記錄 effective container state，script 自己
  啟動 app 時必須核對 `allocation-ordering-events` 的實際 concurrency，參數被覆蓋就直接失敗。
  以隔離的 `order-promising-e2e-p0a` Compose project 實跑後，effective concurrency 已確認為 4，
  Debezium connector 亦進入 RUNNING；驗證後只刪除該 disposable project。

## 5. Gate P0-B — Consumer failure policy

### Tasks

- [x] B1. 建立 exception matrix，至少區分 optimistic contention、transient DB／connection、
  timeout、contract／deserialization、business rejection 與未知 programming error。
- [x] B2. 只對確認可安全重試的 transient infrastructure exceptions 設定 bounded retry；
  contract／validation failure 維持 non-retryable。
- [x] B3. 固定 local optimistic retry 與 Kafka retry 的總 attempt budget，避免兩層無意乘算。
- [x] B4. 以 transaction integration test 證明每次失敗時 Inbox、business changes 與 follow-up
  Outbox 一起 rollback。
- [x] B5. 為 retry、exhausted、direct-DLT 與 exception category 提供低基數 metrics／logs。

### Exit criteria

- 短暫 DB 故障不會在第一次失敗時直接送入 DLT。
- malformed payload／unsupported version 不會反覆重試。
- retry attempt 上限、backoff 與 DLT 結果均有 deterministic tests。

### Exception matrix

| Failure stage／代表型別 | Category | Local retry | Kafka policy | 最大 processing attempts |
|---|---|---:|---|---:|
| Kafka record → generic Message：`MessageMappingException` | `MAPPING` | 0 | direct DLT | 1 |
| Integration Event headers／payload：`IntegrationEventContractException` | `CONTRACT` | 0 | direct DLT | 1 |
| optimistic contention：`OptimisticLockingFailureException` | `HANDLER` | 2 retries、100 ms fixed delay | local exhausted 後 4 次 delayed redelivery | 15 |
| Spring transient／recoverable data access、query timeout | `INFRASTRUCTURE` | 0 | 4 次 delayed redelivery | 5 |
| DB connection／resource unavailable、transaction begin failure | `INFRASTRUCTURE` | 0 | 4 次 delayed redelivery | 5 |
| transaction timeout、`SQLTransientException`／`SQLRecoverableException` | `INFRASTRUCTURE` | 0 | 4 次 delayed redelivery | 5 |
| permanent DB／SQL failure，例如 constraint violation | `INFRASTRUCTURE` | 0 | direct DLT | 1 |
| application-specific business rejection | `HANDLER` | 0 | direct DLT（預設） | 1 |
| unknown programming error | `HANDLER` | 0 | direct DLT（預設） | 1 |

Kafka backoff 固定為 1s、2s、4s、8s。`DataAccessException`、`TransactionException`、
`SQLException` 本身不是全面 retryable；只有表中明列的安全子型別覆寫為 retryable。JPA／Hibernate
將 JDBC connection failure 轉成 `DataAccessResourceFailureException`，因此它也納入短而有界的
redelivery window；若服務中斷超過約 15 秒，仍會進 DLT，不在此 Gate 偷渡 long-running retry topic。

optimistic contention 的 attempt 上限是刻意固定的乘積：

```text
(1 initial + 2 local retries) × (1 initial delivery + 4 Kafka redeliveries)
= 3 × 5
= 15 maximum transactional handler attempts
```

其他 transient infrastructure failure 不進 local optimistic retry，所以最多為 5 次 processing
attempt；若 failure 發生在 transaction begin，該次嘗試甚至不會建立 transaction。

### 2026-08-11 implementation evidence

- `OrderPromisingConsumerFailurePolicy` 集中定義 local settings、Kafka backoff、exception taxonomy
  與兩種最大 attempt budget；兩個 Spring configuration 只負責 wiring，不再各自藏一半政策。
- Kafka transport mapping 與 typed Integration Event contract 現在有 stage-specific exception；
  application handler 原始例外不會被 transport／contract wrapper 吞掉。
- `KafkaConsumerFailureContext` 把 classification 與 delivery attempt 傳到 observation；retry／DLT
  observation 新增低基數 `messaging.failure.category`、`messaging.failure.retryable`，DLT 另以
  `messaging.dlt.disposition=direct|retry_exhausted` 區分結果。message ID、key、partition、offset
  仍只在 high-cardinality trace fields。
- `AllocationTransactionalMessageChainIntegrationTest` 已證明每次 local attempt 使用新 transaction，
  exhaustion 時 Inbox、JPA business data 與 JDBC Outbox 全部 rollback；
  `IntegrationEventSubscriberTransactionIntegrationTest` 另覆蓋 production dispatcher path 的同一邊界。
- deterministic tests 覆蓋 exception matrix、cause-chain SQL failure、1/2/4/8 秒 backoff、15／5
  attempt caps、mapping／handler exception boundary、retry exhausted／direct DLT 與 observation tags。
- `./gradlew test :order-promising:sit --no-daemon --rerun-tasks` 已完整通過（108 tasks）；最後的
  attempt-budget 命名調整後亦再次通過 `:order-promising:test` 與 `:order-promising:sit`。

## 6. Gate P0-C — Full-path correctness E2E

### Tasks

- [ ] C1. 建立可在 CI 分層執行的 PostgreSQL + Debezium Connect + Kafka + application 測試環境。
- [ ] C2. 驗證 business transaction → Outbox → CDC → Kafka → Inbox → consumer use case。
- [ ] C3. 驗證 duplicate delivery、application restart 與 connector restart 後仍維持冪等。
- [ ] C4. 驗證 handler exception 時 Inbox／business／follow-up Outbox rollback，後續可 redeliver。
- [ ] C5. 驗證 Kafka／Connect 暫停後可從 WAL／offset 正確追趕。
- [ ] C6. 驗證 retry exhausted → DLT，原 message ID、key、type、headers 與 source metadata
  均保留。

### Exit criteria

- 至少有一條真實 business flow 不以手動 drain Outbox 取代 Debezium。
- correctness E2E 與較重的 performance test 分開執行，前者可以成為 CI gate。

## 7. Gate P1-D — WMS messaging vertical slice

### 建議邊界

```text
order-promising transaction
  → fulfillment handoff Integration Event + Outbox
  → Debezium / Kafka
  → WMS runtime Inbox
  → WMS application use case
  → WMS repository changes + optional WMS Outbox
```

### Tasks

- [ ] D1. 保持 `wms` 為 pure domain/application module；建立 WMS runtime／adapter module 承接
  Spring、transaction、JDBC repository 與 messaging starter。
- [ ] D2. 決定 fulfillment handoff 語意：優先評估新增
  `FulfillmentRequestedIntegrationEvent` 或
  `AllocationCommittedForFulfillmentIntegrationEvent`，不要只因欄位不足就任意膨脹舊事件。
- [ ] D3. 契約至少提供 stable allocation identity、order／owner／facility、committed allocation
  lines、source location、quantity、dispatch-by 與實際演算法使用的 priority facts。
- [ ] D4. 由 WMS 自己保存 order-to-shipment correlation；整單取消以 `orderId` 找到並取消可取消的
  Shipments，不要求 order-promising 知道 WMS `shipmentId`。
- [ ] D5. WMS 使用自己的 Inbox／Outbox schema ownership 與 transaction boundary，不跨 bounded
  context 查 order-promising repository。
- [ ] D6. WMS 只在跨邊界有價值時發布 Integration Event；Pick／Pack／Stage 內部每次狀態改變
  不必全部送 Kafka。
- [ ] D7. 加入 producer contract test、WMS consumer integration test 與 full-path E2E。

### Exit criteria

- WMS adapter 只依賴 contracts、messaging starter 與 WMS application API。
- 相同 event 重送不會建立第二張 Shipment 或重複執行 work。
- WMS 不需要讀取 order-promising database 才能處理 handoff。

## 8. Gate P1-E — Integration Event contract governance

### Tasks

- [ ] E1. 建立 test-time contract catalog／completeness rule；每個公開 event 都必須有唯一 stable
  type、version、golden JSON 與 round-trip test。
- [ ] E2. 驗證 producer 與每個 deployable consumer 只註冊自己有責任的 contracts；不得以 runtime
  classpath scanning 全域收集 handlers。
- [ ] E3. 在有外部 consumer 前決定是否採 namespaced wire type；已發布的 type 不得直接 rename，
  必須走雙讀／版本遷移。
- [ ] E4. 為 WMS handoff 建立 consumer-driven compatibility test，固定 required／optional fields
  與 unknown-field policy。
- [ ] E5. 檢查 published Gradle metadata；若 public Java API 暴露 Jackson types，依 library 邊界
  選擇宣告 `api` dependency 或隱藏 Jackson-specific constructor。

### Exit criteria

- 新增 contract 卻漏 golden fixture、type mapping 或 version 時，build 必須失敗。
- application 可只註冊需要的 events，不被迫依賴全部 bounded-context contracts。

## 9. Gate P1-F — Payload protection

### Tasks

- [ ] F1. 定義序列化後 payload byte limit，並與 Kafka broker、producer／Connect、consumer 與 DLT
  topic limits 對齊。
- [ ] F2. 在 Outbox INSERT 前 fail fast；不得等 transaction commit 後才由 Debezium／Kafka 發現
  oversized record。
- [ ] F3. 為 fulfillment lines 決定合理 aggregate/message 邊界；超大資料應重新切 business unit
  或使用 reference/snapshot strategy，而不是任意切 byte chunks。
- [ ] F4. 加入 boundary tests 與 observability，但 metrics 不記錄 payload、order ID 等高基數資料。

### Exit criteria

- oversized payload 不會留下無法 relay 的 committed Outbox row。
- message-size 設定在 application、Connect 與 Kafka 間有一份可部署的對照表。

## 10. Gate P2-G — Production operations

本 Gate 的詳細操作規範以
[`messaging-server-operations-guide.md`](messaging-server-operations-guide.md) 為準，本 roadmap
只追蹤落地項目。

- [ ] G1. 建立 production connector／topic／DB logical replication 的 versioned configuration。
- [ ] G2. 明確設定 Connect heap、container memory、queue byte limit、heartbeat 與 internal topics。
- [ ] G3. 建立 Outbox age、WAL slot lag、Connect lag／queue、Kafka partition、consumer lag age、
  Inbox、retry 與 DLT 的統一 dashboard／alerts。
- [ ] G4. 建立低流量 synthetic canary，驗證完整 publication 與 consumption 路徑。
- [ ] G5. 定期演練 connector restart、Kafka outage、application rollback 與 DLT recovery。

## 11. Gate P2-H — DLT replay 與 retention automation

以下不是 base runtime 的必要條件；接近 production 或事件量證明需要後才執行。

- [ ] H1. 建立受控 DLT replay CLI／job：支援 dry-run、驗證、限速、batch、original identity
  preservation 與 audit trail。
- [ ] H2. 不建立可任意重送所有 DLT 的公開 generic HTTP endpoint。
- [ ] H3. 依實際表量建立 Inbox／Outbox cleanup job；必須具有 connector health、WAL lag、保留期
  與 batch-size safety interlocks。
- [ ] H4. 以 recovery drill 證明 cleanup 不會破壞 Inbox deduplication window 或 CDC recovery。

## 12. 明確延後

| 能力 | 決策 |
|---|---|
| Standalone Command／async Reply | 保留在 Tram roadmap Gate J；只有出現真正的定向要求／非同步回覆與 correlation use case 才啟動 |
| Saga | 只有多 participant、長交易、補償與持久 orchestration 的實際需求才建立 |
| Reactive messaging | 目前 JDBC／JPA transaction path 是 blocking；不為 API 對稱另開 reactive artifacts |
| Non-blocking retry topics | 先使用 bounded container retry；只有 blocking/backlog 數據證明需要才導入 |
| 多 broker／多 framework adapter | 第二個 implementation 出現後才以既有 SPI 驗證，不預先建立空 modules |
| 再拆 messaging modules／BOM | 目前 artifact 邊界足夠；只有獨立發布與版本管理需求出現才處理 |

## 13. 本輪 Definition of Done

- [x] Kafka listener 的全域 baseline 與 subscriber override 只有一套可解釋、可測的 precedence。
- [x] transient／non-retryable failure matrix 與總 retry budget 有測試與 metrics。
- [ ] 至少一條 business flow 通過真實 Outbox → Debezium → Kafka → Inbox correctness E2E。
- [ ] WMS 以自己的 runtime／adapter、Inbox 與 repository 邊界完成第一條 integration flow。
- [ ] fulfillment event 可以獨立建立 WMS command，不需跨 context query。
- [ ] 新增 Integration Event 時，漏 type/version/golden fixture/mapping 會在 build 階段失敗。
- [ ] oversized payload 會在 Outbox commit 前失敗。
- [ ] production dashboard、alerts、connector/topic/db configuration 與 recovery drill 有 owner。
- [ ] 未因本輪工作提前導入 Command／Reply、Saga、Reactive、多 broker 或多餘 module。
