# Eventuate Tram-aligned Messaging — Gate A Baseline

> 日期：2026-08-09
> 狀態：完成；允許進入 Gate B
> 例外：完整 `order-promising:test` 有一個已確認與 messaging 無關的既有測試失敗，詳見「測試結果與已知基線問題」。

## 1. 結論

Gate A 的四個停止條件皆未成立：

- Inbox claim、business mutation 與 consumer-side Outbox append 可在同一 transaction commit／rollback。
- JPA business write 與 JDBC Inbox／Outbox write 使用目前 primary `PlatformTransactionManager` 時，可觀察到相同 PostgreSQL transaction ID，且一起 commit／rollback。
- Debezium `EventRouter` 可在不自製 CDC 或 SMT 的情況下，把 serialized generic headers JSON 當成一個 Kafka header relay。
- 由既有 `ConcurrentKafkaListenerContainerFactory` programmatically 建立的 container，可承接目前 ack mode、error handler、concurrency 與 start／stop lifecycle。

因此 Gate B 可以開始，但 Gate B 不得改變本文件固定的 delivery、transaction、deduplication 與 retry／DLT 行為。A14 的 `headers` 欄位只存在於 test fixture；正式 DDL migration 仍屬 Gate C。

## 2. A1～A16 驗證矩陣

| Task | 結果 | 固定的行為或證據 |
|---|---|---|
| A1 | PASS | 本文件第 3 節記錄目前 module dependency graph。 |
| A2 | PASS | `OutboxAggregateQueryIntegrationTest` 經由 `PlaceOrderUsecase` 保存訂單並查得對應 Outbox event chain；A15 另直接證明 business／Outbox commit 原子性。 |
| A3 | PASS | `InboxRepoOutboxPersistenceIntegrationTest.shouldRollbackBusinessChangeAndTranslatedOutboxTogether`。 |
| A4 | PASS | `InboxRepoOutboxPersistenceIntegrationTest.shouldClaimInboxEventOncePerSubscriber`：相同 `(subscriber_id, event_id)` 第二次 claim 為 false。 |
| A5 | PASS | `InboundCommandTransactionIntegrationTest` 的失敗案例確認 Inbox claim、business mutation 與 Outbox 一起 rollback。 |
| A6 | PASS | `AllocationRetryTransactionIntegrationTest` 與 concurrency SIT 確認每次 optimistic-lock retry 使用新 transaction，失敗 claim 不殘留，後續可重新處理。 |
| A7 | PASS | `InboxRepoOutboxPersistenceIntegrationTest.shouldClaimInboxEventOncePerSubscriber`：不同 subscriber 可各自 claim 同一 event ID。 |
| A8 | PASS | `OutboxCdcIntegrationTest` 固定 payload、`id`／`eventType` headers、physical topic 與 `partition_key` record key。 |
| A9 | PASS | `KafkaIntegrationEventDispatcherTest` 固定 unknown contract、缺 `id`、缺 `eventType`、ID mismatch 與 event-type mismatch 的 fail-fast 行為。 |
| A10 | PASS | 本文件第 5 節記錄 application-owned retry、DLT、ack mode 與 concurrency。 |
| A11 | PASS | 本文件第 6 節記錄 Inbox／Outbox DDL、Debezium configuration 與 Kafka record contract。 |
| A12 | PASS | 本文件第 4 節列出 subscriber ID、listener ID、consumer group 與 topic mapping。 |
| A13 | PASS | 本文件第 3.2 節固定目前 all-in-one starter 的 dependency baseline。 |
| A14 | PASS | `OutboxCdcIntegrationTest.shouldRelaySerializedGenericHeadersWithoutACustomSmt` 覆蓋 custom、empty 與 reserved collision fixtures。 |
| A15 | PASS | `InboxRepoOutboxPersistenceIntegrationTest.shouldCommitAndRollbackJpaBusinessWithJdbcInboxAndOutboxAtomically` 比對 `txid_current()`，並驗證 commit／rollback。 |
| A16 | PASS | `ProgrammaticKafkaContainerFeasibilityTest` 從 factory 建立 container，驗證 policy propagation 與 lifecycle；沒有移除 `@KafkaListener`。 |

## 3. 現行 module dependency baseline

### 3.1 Direct dependency graph

```text
messaging-api
├── messaging-events
├── messaging-producer-outbox ── Spring Data JPA
└── messaging-consumer-inbox  ── Spring Data JPA

messaging-consumer-kafka
├── messaging-api
├── messaging-events
└── kafka-clients

messaging-spring-boot-autoconfigure
├── messaging-api
├── messaging-events
├── messaging-producer-outbox
├── messaging-consumer-inbox
├── messaging-consumer-kafka
├── Spring Data JPA
└── Spring Kafka

messaging-spring-boot-starter
├── messaging-spring-boot-autoconfigure
├── Spring Data JPA
├── Spring Kafka
└── Jackson JSR-310
```

| Module | 現行責任 | 必須注意的邊界問題 |
|---|---|---|
| `messaging-api` | `Message`、`MessageProducer`、`MessageMetadata`、暫時性的 `InboundCommand` | production code 無 framework dependency，但 `Message` 尚非 generic headers envelope。 |
| `messaging-events` | typed Integration Event serialization／dispatch contracts | 依賴 Jackson；未拆 producer／consumer common orchestration。 |
| `messaging-producer-outbox` | JPA Outbox adapter | 同時含 producer persistence 與 JPA 技術選擇。 |
| `messaging-consumer-inbox` | subscriber-aware JPA Inbox adapter | 名稱未表達 JPA；尚未有 pure consumer common。 |
| `messaging-consumer-kafka` | Kafka record 到 typed Integration Event 的 dispatch adapter | 目前仍依賴 `messaging-events`，Gate B 目標是只保留 Kafka client + generic API。 |
| `messaging-spring-boot-autoconfigure` | 一次組裝 producer、consumer、JPA 與 Kafka beans | producer-only app 也會看到 consumer dependencies，反之亦然。 |
| `messaging-spring-boot-starter` | all-in-one dependency starter | 目前沒有窄 producer／consumer starter。 |

### 3.2 Starter dependency baseline

目前 `messaging-spring-boot-starter` 是 all-in-one artifact。其 runtime classpath 會同時引入：

- JPA Outbox producer；
- JPA Inbox consumer；
- Kafka consumer adapter；
- Spring Data JPA；
- Spring Kafka；
- Jackson JSR-310。

`order-promising` 除了依賴這個 starter，也直接宣告 `messaging-api`、`messaging-events`、`messaging-consumer-inbox` 與 `messaging-consumer-kafka`。Gate B／後續 starter Gate 必須用 dependency tests 防止窄 starter 重新形成這種交叉引入；在替代路徑驗證完成前，不應先移除現有依賴。

## 4. Subscription identity 與 topic baseline

| Bounded context | `subscriberId` | `@KafkaListener.id` | 實際 consumer group | Physical topic |
|---|---|---|---|---|
| allocation | `allocation-ordering-events` | `allocation-ordering-events` | `allocation-ordering-events` | `ordering.order-events` |
| allocation | `allocation-inventory-events` | `allocation-inventory-events` | `allocation-inventory-events` | `inventory.stock-events` |
| ordering | `ordering-allocation-events` | `ordering-allocation-events` | `ordering-allocation-events` | `promising.allocation-events` |

目前沒有 logical `ChannelMapping`，所以 destination 字串與 physical Kafka topic 相同。

`application.properties` 雖設定 `spring.kafka.consumer.group-id=order-promising-allocation`，但三個 listener 都只指定 `id`，且 Spring Kafka 的 `idIsGroup` 預設為 true；因此 listener ID 會成為實際 group ID，global group 設定沒有套到這三個 listener。另一方面，application 又把同一個常數傳給 dispatcher 當作 `subscriberId`，造成三種 identity **目前隱含相等**：

```text
subscriberId == listenerId == consumerGroupId
```

Gate B 必須把三者建模為不同欄位，即使初始值仍相同。尤其 consumer group 的部署調整不得改變 Inbox idempotency scope；`subscriberId` 也不得信任外部 message header。

## 5. Operational policy baseline

| Policy | 現況 |
|---|---|
| Listener concurrency | 沒有 application override，現行 effective concurrency 為 1。 |
| Ack mode | 沒有 application override，使用 Spring Kafka container 的 `BATCH`。 |
| Auto commit | properties 未設定；Spring Kafka listener container 在這種情況下使用 `enable.auto.commit=false`。 |
| Offset reset | `earliest`。 |
| Missing topics | `spring.kafka.listener.missing-topics-fatal=false`。 |
| Application optimistic-lock retry | initial attempt + 2 retries，固定延遲 100 ms，只處理 `OptimisticLockingFailureException`；每次 attempt 是新 transaction。 |
| Container retry | 只將 `AllocationConcurrencyExhaustedException` 視為 retryable，最多 4 次，指數退避 1s、2s、4s、8s，上限 10s。 |
| 其他 consumer exception | `DefaultErrorHandler.defaultFalse()`，不做 container retry，直接交給 recoverer。 |
| DLT | `DeadLetterPublishingRecoverer` 發到預設 `<topic>-dlt`；這是目前唯一直接使用 Kafka producer 的例外路徑。正常 producer 仍是 DB Outbox → Debezium → Kafka。 |
| Policy scope | 單一 global `CommonErrorHandler` 套用到 allocation 與 ordering 的所有 `@KafkaListener`。 |

A16 證明 programmatic container 能承接同一個完整 `DefaultErrorHandler` instance、`BATCH`、concurrency 與 lifecycle；它不是完整 DLT broker round-trip test。後續以動態 subscription 取代 `@KafkaListener` 時，仍須保留既有 retry／DLT integration tests。

## 6. Persistence 與 Debezium golden contract

### 6.1 Inbox DDL

Flyway V5 建立 Inbox，V7 演進成 subscriber-aware schema。最終重要契約為：

```sql
event_inbox (
  subscriber_id VARCHAR(255) NOT NULL,
  event_id       UUID NOT NULL,
  event_type     VARCHAR(255) NOT NULL,
  processed_at   TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (subscriber_id, event_id)
)
```

V7 對歷史 row 的 subscriber backfill：

| Event type | Backfilled subscriber |
|---|---|
| `OrderPlacedIntegrationEvent`, `OrderCancelledIntegrationEvent` | `allocation-ordering-events` |
| `StockAvailabilityIncreasedIntegrationEvent` | `allocation-inventory-events` |
| `OrderAllocatedIntegrationEvent`, `BackorderCreatedIntegrationEvent` | `ordering-allocation-events` |
| `ConfirmStockReceiptRequest` | `stock-receipt-requests` |
| 未知歷史類型 | `legacy-global` |

### 6.2 Outbox DDL

Gate A 開始前的 production schema 為：

```sql
event_outbox (
  id            UUID PRIMARY KEY,
  aggregatetype VARCHAR(255) NOT NULL,
  aggregateid   VARCHAR(255) NOT NULL,
  type          VARCHAR(255) NOT NULL,
  route         VARCHAR(255) NOT NULL,
  partition_key VARCHAR(255) NOT NULL,
  payload       JSONB NOT NULL,
  timestamp     TIMESTAMPTZ NOT NULL
)
```

`aggregateid` 是領域 identity，`partition_key` 是 transport ordering／contention decision；兩者不得合併。A14 在 ephemeral test database 加上 `headers TEXT NOT NULL DEFAULT '{}'` 只用來驗證 connector feasibility，**不是 production migration**。

### 6.3 Debezium EventRouter baseline

| Setting | Value／semantics |
|---|---|
| Connector | Debezium PostgreSQL connector |
| Test image | `quay.io/debezium/connect:3.5.2.Final` |
| Included table | `public.event_outbox` |
| SMT | `io.debezium.transforms.outbox.EventRouter` |
| Route field | `route` |
| Topic replacement | `${routedByValue}` |
| Kafka record key | `partition_key` |
| JSON payload | `table.expand.json.payload=true` |
| Additional placement | production baseline：`type:header:eventType` |

Kafka record golden contract：

```text
topic       = event_outbox.route
key         = event_outbox.partition_key
value       = expanded event_outbox.payload JSON
header[id]  = event_outbox.id       # EventRouter built-in event ID placement
header[eventType] = event_outbox.type
```

A14 驗證未來可加入：

```text
headers:header:messageHeaders
```

其結果是 `event_outbox.headers` 的 JSON text 被 relay 成一個名為 `messageHeaders` 的 Kafka header；consumer 再負責解析 object、驗證 reserved headers 並套用 merge precedence。fixture 已固定：

- custom：`correlation-id`、`traceparent` 原樣 round-trip；
- empty：`{}` round-trip；
- reserved collision：即使 JSON 內偽造 `message-id`，實體 `header[id]` 仍保持 Outbox UUID；Gate C consumer normalization 必須 fail fast，不能接受 caller 覆寫 reserved identity。

### 6.4 A14 實作注意事項

同一個 Kafka Connect worker 同時啟動兩個 PostgreSQL Debezium connectors 時，每個 connector 必須使用不同的 `topic.prefix`（Debezium server name）與 replication slot。A14 初次驗證曾因共用 `topic.prefix` 造成 Debezium JMX metric object-name collision，改成唯一 prefix 後通過。這是 connector instance identity 限制，不是 generic header relay 失敗。

## 7. Transaction baseline

目前 Inbox／Outbox JPA adapters 都使用 `@Transactional(propagation = MANDATORY)`，所以不允許 adapter 自行打開獨立 transaction；application use case／entry facade 擁有 transaction boundary。

A15 進一步使用目前 primary `PlatformTransactionManager`，在同一個 `TransactionTemplate` 中執行：

1. `EntityManager.persist()` business row；
2. `JdbcTemplate` insert Inbox row；
3. `JdbcTemplate` insert Outbox row；
4. 在兩個觀察點查詢 `txid_current()`。

commit case 的兩個 transaction ID 相同且三種 row 都存在；rollback case 的 transaction ID 仍相同且三種 row 全部不存在。這證明 Gate C 將 Inbox／Outbox persistence 改成 JDBC 時，不需要 XA 或第二個 transaction manager；前提是 adapter 必須使用 Spring-managed `DataSource`／`JdbcTemplate`，不得自行建立 unmanaged connection。

## 8. 測試結果與已知基線問題

### 8.1 PASS

以下驗證均於 2026-08-09 通過：

```text
./gradlew :messaging:messaging-api:test \
  :messaging:messaging-events:test \
  :messaging:messaging-producer-outbox:test \
  :messaging:messaging-consumer-inbox:test \
  :messaging:messaging-consumer-kafka:test \
  :messaging:messaging-spring-boot-autoconfigure:test \
  :messaging:messaging-spring-boot-starter:test

./gradlew :order-promising:sit --rerun-tasks
```

補充：`messaging-api` 與 `messaging-spring-boot-starter` 當時為 `NO-SOURCE` test task；其餘 messaging unit tests 與完整 SIT 都成功。A14、A15、A16 也各自以 targeted command 重跑通過。

### 8.2 已知、與本 Gate 無關的既有失敗

```text
./gradlew :order-promising:test --rerun-tasks
289 tests completed, 1 failed

ConfirmStockReceiptUsecaseTest.shouldRejectALocationOutsideTheFacility
Expecting code to raise a throwable.
```

該失敗可單獨穩定重現。原因是 test mock 了 `StockOperationRecorder`，但 facility／location 驗證目前已位於實際 `StockOperationRecorder.operationTypeFor(...)`；mock 不會執行該驗證，所以 use case 不會拋例外。Gate A 沒有修改這個 use case、recorder 或 test。

本文件將它記為 pre-existing baseline failure，不把它誤算成 messaging 重構回歸；但在宣稱 repository 全綠之前，仍應由對應 WMS／stock task 修正 test boundary 或驗證責任。

## 9. Gate B 進入規則

Gate B 可以開始，並須遵守：

- 先建立 pure contracts／common orchestration，不移動 persistence transaction ownership。
- 舊路徑與新路徑並存期間，使用 compatibility adapter 與同一組 golden tests 比對。
- `subscriberId`、`consumerGroupId`、listener/container identity 從型別上分離，不可再依賴三者同值。
- pure modules 不得 import Spring、JPA、Spring Data 或 Spring Kafka。
- 正常 producer 仍只寫 Outbox；Kafka producer 只保留 DLT recoverer 這個 operational exception。
- Gate C production migration 前，A14 的 test-only `headers` 欄位不得被 runtime code 假定已存在。
- 任一改動若破壞本文件的 transaction、deduplication、record key、retry／DLT 或 replay semantics，立即停止該 slice，不等後續 Gate 補救。
