# Messaging server 維運指南

> 適用範圍：本專案的 PostgreSQL transactional Outbox／Inbox、Debezium PostgreSQL
> connector、Kafka Connect、Kafka，以及使用 `messaging-*` modules 的 application。
>
> 本文件處理容量、監控、部署、故障與程式碼邊界；rolling deployment、subscriber
> 更名、DLT replay、header recovery 與 retention SQL 的操作細節，另見
> [messaging-operations-runbook](./messaging-operations-runbook.md)。
>
> 最後核對：2026-08-10。Production 數值必須依實測 throughput、payload、RPO／RTO
> 與基礎設施版本調整，不可直接照搬本機 `e2e/perf` 設定。

## 1. 先記住的六件事

1. 正常 producer 路徑是「business data + `event_outbox` 同一個 DB transaction」，
   不是 application 直接呼叫 Kafka producer。
2. `event_outbox` 沒有 `status`／`published_at` 是刻意的：Debezium 從 PostgreSQL WAL
   讀取已 commit 的 INSERT；application 不負責逐列標記已發送。
3. Debezium／Kafka 發生故障時可能重送，因此端到端語意要當成 at-least-once；
   consumer 必須以 `(subscriber_id, event_id)` Inbox claim 保證冪等。
4. Kafka Connect heap 裡的 queue 只是短暫緩衝。Kafka 停擺時 queue 填滿會形成
   backpressure，真正持久保留未送變更的是 PostgreSQL replication slot 所保留的 WAL。
5. 不可為了釋放磁碟直接 drop replication slot、刪 Connect offset topic、換 connector
   identity 或刪尚未確認安全的 Outbox rows；這些動作可能造成遺漏或大量重送。
6. 「consumer lag 很小」不代表整條鏈健康；至少要同時觀察 Outbox age、WAL slot lag、
   Connect queue／source lag、Kafka partition health、consumer lag age、Inbox 與 DLT。

## 2. 端到端資料路徑與責任

```text
Application transaction
  ├─ update business tables
  └─ INSERT event_outbox
          │ commit
          ▼
PostgreSQL WAL
  → logical replication slot / publication
          ▼
Debezium PostgreSQL connector
  → bounded in-memory queue
  → Outbox Event Router SMT
          ▼
Kafka topic / partition
          ▼
Programmatic Kafka consumer
  → observation / optimistic-lock retry
  → transaction
      ├─ INSERT event_inbox ... ON CONFLICT DO NOTHING
      ├─ application handler / use case
      ├─ update business tables
      └─ optional INSERT event_outbox
  → transaction commit
  → Kafka offset acknowledgment
          │ failure
          └─ Kafka redelivery → exhausted → DLT
```

| 階段 | 耐久狀態 | 主要 owner | 重點保證 |
|---|---|---|---|
| Producer | business tables、`event_outbox` | application + PostgreSQL | 同 commit／rollback |
| CDC backlog | WAL、replication slot、Connect offsets | PostgreSQL + Kafka Connect | connector 可從已知 LSN 恢復 |
| Transport | Kafka logs、partitions、replicas | Kafka | key 內 ordering、retention 內可 replay |
| Consumer | `event_inbox`、business tables、optional Outbox | application + PostgreSQL | claim、handler、後續 Outbox 同 transaction |
| Failure recovery | retry state、DLT record | messaging runtime + operator | 保留 original identity 與來源 metadata |

### 2.1 為什麼 Outbox 不需要 status

目前 `event_outbox` 是 Debezium CDC source，不是 application polling queue。資料列只有
event identity、aggregate identity、route、partition key、payload、timestamp 與 headers；
Debezium 依 WAL 位置與 Kafka Connect offset 判定進度。

因此：

- 不要增加每列 `NEW/SENT` 狀態並讓 application 與 Debezium同時管理 publication；
- Outbox row 留在表中不代表還沒送出；
- 是否追上要看 connector LSN／lag，而不是查 `status`；
- row cleanup 是 retention 維運，不能當 publication acknowledgment；
- 若未來改成 polling publisher，才需要另立狀態模型、claim／lease 與 recovery，不應混進
  現有 CDC 路徑。

## 3. 本專案目前 baseline 與 production 落差

| 項目 | 目前 repo baseline | Production 判斷 |
|---|---|---|
| PostgreSQL | `postgres:16-alpine`，只顯式設 `wal_level=logical` | 補齊 slot／sender 容量、磁碟、備援、備份、監控 |
| Kafka | `apache/kafka-native:4.1.2`，單節點 KRaft、4 default partitions | 多 broker、持久 volume、replication、min ISR、安全與容量規劃 |
| Kafka durability | offsets／transaction topics RF=1、min ISR=1 | RF=1 只可用於 local／test |
| Kafka security | PLAINTEXT，固定開發用密碼 | TLS／SASL、ACL、secret management、網路隔離 |
| Kafka Connect | `quay.io/debezium/connect:3.5.2.Final`，單 worker | distributed mode、持久且高可用的 internal topics、明確 heap／container limits |
| Debezium connector | pgoutput + Outbox Event Router，只抓 `public.event_outbox` | 補 queue byte limit、heartbeat 決策、metrics、DR 與 config promotion |
| Consumer | concurrency 預設 1、BATCH ack、shutdown timeout 10s | 依 partitions、DB contention、處理時間與 termination grace 調整 |
| Offset fallback | `auto-offset-reset=earliest` | 新 group 會 replay retention 內全部資料；需與 Inbox retention 一起決策 |
| Missing topic | `missing-topics-fatal=false` | 本機方便；production 要有 topic provisioning／啟動檢查與告警 |
| Table cleanup | 沒有 library-owned scheduler | 由 application／DB operations owner 依 runbook 小批次執行 |

`e2e/perf/docker-compose.yml` 是可重現的本機／壓測環境，不是 production template。尤其
單節點、RF=1、PLAINTEXT、沒有顯式 persistent volumes、沒有 Connect heap 與 container
memory limits，都不能直接帶上 production。

## 4. 先做容量規劃，不先猜記憶體

先量出以下數據：

- normal／peak events per second；
- payload、headers、Kafka record 與 Outbox row 的 average、p95、p99、max bytes；
- PostgreSQL normal／peak WAL bytes per second；
- connector 可容忍最長 outage；
- Kafka source／DLT retention 與最長 replay window；
- consumer average／p95 latency、DB transaction latency、retry ratio；
- hot partition／hot business key 的最高流量。

### 4.1 三種 backlog 要分開算

| Backlog | 初始估算 | 用途 |
|---|---|---|
| PostgreSQL WAL | `observed peak WAL bytes/s × connector outage seconds × safety factor` | connector／Kafka 不可用時保住 CDC 位置 |
| Kafka topic disk | `produced bytes/s × retention seconds × replication factor × safety factor` | consumer outage、replay 與 DLT |
| Inbox／Outbox table | `rows/s × average row bytes × retention seconds × index/TOAST factor` | idempotency window、audit 與安全 cleanup |

WAL 量不能只用 event payload 推估，因為同一資料庫的其他交易、full-page writes、indexes
與 checkpoint 行為都會產生 WAL；必須以 production-like load 的 `pg_wal_lsn_diff` 或
平台 metrics 實測。

### 4.2 Kafka Connect memory

Connect container memory 至少要容納：

- JVM heap；
- metaspace、thread stacks、direct/network buffers 與 connector native overhead；
- JMX／agent overhead；
- termination、rebalance 或 GC 期間的安全餘裕。

設定原則：

1. 明確設定 container memory limit 與 `KAFKA_HEAP_OPTS=-Xms... -Xmx...`，兩者不可相等；
   container limit 還要留 non-heap／native headroom。
2. `max.queue.size` 必須大於 `max.batch.size`。
3. 同時設定正值 `max.queue.size.in.bytes`，避免少數大 payload 讓 record-count queue
   吃光 heap；Debezium 預設為 0，代表沒有 byte limit。
4. queue byte budget 要由負載測試決定，不能拿整個 heap 當 queue。
5. 監看 after-GC heap、GC pause、`CurrentQueueSizeInBytes`、
   `QueueRemainingCapacity` 與 container OOM/restart。

Debezium 的預設 `max.batch.size=2048`、`max.queue.size=8192`，queue 具有 backpressure；
設定 byte limit 後，record count 或 byte limit 任一先到就會阻塞寫入 queue。詳見
[Debezium PostgreSQL connector properties](https://debezium.io/documentation/reference/stable/connectors/postgresql.html#postgresql-property-max-queue-size)。

### 4.3 初始 connector production overlay

以下只表示「必須顯式決策」的欄位，不是建議數值：

```properties
tasks.max=1
max.batch.size=<load-tested-record-count>
max.queue.size=<greater-than-max-batch-size>
max.queue.size.in.bytes=<load-tested-byte-budget>
heartbeat.interval.ms=<0-or-a-positive-tested-interval>
slot.drop.on.stop=false
```

若同一 PostgreSQL instance 的整體 WAL 很活躍，但被捕捉的 database／Outbox 很安靜，
connector 可能沒有機會確認較新的 LSN。此時才評估 `heartbeat.interval.ms` 與
`heartbeat.action.query`；heartbeat table 必須納入 publication 與 connector capture
filter。不能看到 WAL 增長就盲目縮短 heartbeat。

## 5. PostgreSQL 維運

### 5.1 必要設定與 ownership

- `wal_level=logical`。
- `max_replication_slots` 至少涵蓋所有 logical connectors 並保留維運餘裕。
- `max_wal_senders` 至少涵蓋 `max_replication_slots`、physical replicas 與備份連線需求。
- 每個 Debezium connector 使用唯一 `slot.name` 與 publication；不可共用 slot。
- production 保持 `slot.drop.on.stop=false`。
- connector DB user 採 least privilege；自動建 publication／slot 與人工預建需要的權限不同。
- 為 `pg_wal`、business data、Inbox／Outbox、indexes 與備份保留獨立容量預算。
- DB connection budget 要納入所有 application instances 的 connection pools、Kafka consumer
  concurrency、Debezium connection、replicas／backup 與 operator reserve；不能只看單一 JVM
  的 pool size。

PostgreSQL 官方說明 replication slot 可能無限保留 WAL；`max_slot_wal_keep_size=-1`
也是無上限。若設有限制，可防止整顆磁碟被填滿，但超限可能讓 slot 進入 `lost`，
轉而變成資料恢復事件，而不是免費的安全閥。參考
[PostgreSQL replication settings](https://www.postgresql.org/docs/current/runtime-config-replication.html)
與 [logical replication configuration](https://www.postgresql.org/docs/current/logical-replication-config.html)。

### 5.2 每日／告警時查核 SQL

Replication slot：

```sql
SELECT slot_name,
       active,
       wal_status,
       restart_lsn,
       confirmed_flush_lsn,
       pg_size_pretty(
           pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)
       ) AS retained_wal,
       safe_wal_size
  FROM pg_replication_slots
 WHERE slot_type = 'logical'
 ORDER BY slot_name;
```

Outbox／Inbox age 與 table size：

```sql
SELECT COUNT(*) AS outbox_rows,
       MIN(timestamp) AS oldest_outbox,
       MAX(timestamp) AS newest_outbox,
       NOW() - MIN(timestamp) AS oldest_outbox_age,
       pg_size_pretty(pg_total_relation_size('event_outbox')) AS total_size
  FROM event_outbox;

SELECT COUNT(*) AS inbox_rows,
       MIN(processed_at) AS oldest_inbox,
       MAX(processed_at) AS newest_inbox,
       NOW() - MIN(processed_at) AS oldest_inbox_age,
       pg_size_pretty(pg_total_relation_size('event_inbox')) AS total_size
  FROM event_inbox;
```

Autovacuum 與 dead tuples：

```sql
SELECT relname,
       n_live_tup,
       n_dead_tup,
       last_autovacuum,
       last_autoanalyze,
       autovacuum_count,
       autoanalyze_count
  FROM pg_stat_user_tables
 WHERE relname IN ('event_outbox', 'event_inbox');
```

長交易會拖累 vacuum、WAL 回收與 transaction pool，事故時也要查
`pg_stat_activity.xact_start`。查詢與終止 transaction 要分開；不要把 kill query
寫成自動化第一步。

### 5.3 Cleanup 與 vacuum

- 只有 connector／task 都為 `RUNNING`、WAL lag 已收斂、沒有 snapshot／rebuild／incident
  時，才清 Outbox。
- Inbox retention 必須覆蓋 source topic retention、DLT replay SLA 與 planned replay window。
- 使用 timestamp index 與小批次 delete，觀察 lock、WAL、replication lag 與 dead tuples。
- 正常依靠 autovacuum；不要排程頻繁 `VACUUM FULL`。它會長時間取得 exclusive lock，
  並需要額外磁碟空間。
- 大規模 retention 若長期成為瓶頸，再評估 time partitioning；不要在事故中臨時改 schema。

目前建議保存期與 batch delete SQL見
[messaging-operations-runbook `6](./messaging-operations-runbook.md#6-inboxoutbox-retention)。

### 5.4 Backup、restore 與 failover

DB backup、logical slot 與 Kafka Connect offset 是互相關聯的三份狀態。只 restore database，
但讓 connector 沿用不相容的較新／較舊 offset，可能造成缺口或重送。

上線前必須演練：

1. primary failure 後 slot 是否存在、是否可用；
2. Connect internal topics 是否仍可恢復 connector config 與 LSN offset；
3. DB PITR 時如何選擇一致的 connector offset；
4. 無法保留 slot 時，如何停寫、重建 connector／snapshot、承受 duplicate 並做 reconciliation；
5. RPO 內資料完整性如何由 business reconciliation 證明。

目前 local baseline 是 PostgreSQL 16，不能假設 logical failover slot 自動跟隨 primary。
若 production 採支援 failover slots 的 PostgreSQL／Debezium 組合，仍需另行驗證
`slot.failover`、standby slot synchronization 與 promoted primary 流程。

## 6. Debezium 與 Kafka Connect 維運

### 6.1 Connector identity 是持久資料的一部分

下列值變更不是普通 restart：

- connector name；
- `topic.prefix`；
- `slot.name`；
- publication name／captured table；
- Connect `group.id`；
- config／offset／status storage topic；
- Outbox route、key、payload 或 header SMT mapping。

變更前要做 migration／cutover plan。不要藉由「刪掉重建」解決 config drift；新的 slot
與 snapshot 可能重播歷史 Outbox，新 offset 也可能從錯誤位置開始。

### 6.2 Distributed worker 與 internal topics

Production 使用 distributed mode，並在啟動 worker 前人工建立：

| Topic | 建議形狀 |
|---|---|
| `config.storage.topic` | single partition、replicated、`cleanup.policy=compact` |
| `offset.storage.topic` | 多 partitions、replicated、`cleanup.policy=compact` |
| `status.storage.topic` | 可多 partitions、replicated、`cleanup.policy=compact` |

Kafka Connect 4.1 對這三個 topic 的預設 replication factor 都是 3，但實際不能超過 broker
數；依賴 auto-create 也可能得到錯誤 partitions／cleanup policy。官方建議 production
預先建立，見 [Kafka Connect user guide](https://kafka.apache.org/41/kafka-connect/user-guide/)
與 [Connect configs](https://kafka.apache.org/41/configuration/kafka-connect-configs/)。

即使 PostgreSQL connector 的單一 task 不會因增加 worker 而線性加速，多 worker 仍可提供
worker failure 後的 task 接手能力。吞吐瓶頸應先從 WAL、queue、Kafka produce latency、
record size 與 hot partition 找原因。

### 6.3 Read-only health commands

```bash
curl -fsS http://<connect-host>:8083/connectors
curl -fsS http://<connect-host>:8083/connectors/<connector-name>/status
curl -fsS http://<connect-host>:8083/connectors/<connector-name>/config
curl -fsS http://<connect-host>:8083/connector-plugins
```

Mutation 操作必須在 incident ticket 記錄前後狀態：

```bash
curl -fsS -X PUT  http://<connect-host>:8083/connectors/<connector-name>/pause
curl -fsS -X PUT  http://<connect-host>:8083/connectors/<connector-name>/resume
curl -fsS -X POST \
  'http://<connect-host>:8083/connectors/<connector-name>/restart?includeTasks=true&onlyFailed=true'
```

先保存 `/status` trace 與 config，再 restart。禁止把 Connect REST API 無驗證地暴露到公網；
connector config 可能包含 DB credentials。

### 6.4 必看 metrics

- connector／task state、restart count、last failure trace；
- `Connected`、`MilliSecondsBehindSource`、`NumberOfErroneousEvents`；
- `QueueRemainingCapacity`、`CurrentQueueSizeInBytes`、`MaxQueueSizeInBytes`；
- events read／written rate、Kafka produce latency／error；
- Connect process heap after GC、GC pause、CPU、threads、file descriptors；
- PostgreSQL slot active／retained WAL；
- config／offset／status topics 的 ISR 與 availability。

Debezium 的 PostgreSQL streaming MBean 是
`debezium.postgres:type=connector-metrics,context=streaming,server=<topic.prefix>`。

## 7. Kafka 維運

### 7.1 Topic durability

一般 production 起始基準可採：

- 3 個以上 brokers；
- 重要 business、DLT 與 Connect internal topics 使用 replication factor 3；
- `min.insync.replicas=2`；
- source connector producer 使用 `acks=all`；
- 關閉可能導致資料遺失的 unclean leader election，除非已有明確風險決策；
- persistent volumes、跨 failure domain replica placement 與磁碟告警。

這是常見起始基準，不是所有環境的固定答案。Kafka 官方說明，RF=3、min ISR=2 配合
`acks=all` 可要求多數 replicas 持久化後才算成功，見
[Kafka broker/topic configuration](https://kafka.apache.org/41/configuration/)。

### 7.2 Partitions、key 與 concurrency

- ordering 只保證在同一 topic partition 內。
- `partition_key` 應選「需要順序與互斥的 business contention key」，不是為了平均而任意 UUID。
- consumer concurrency 高於 assigned partitions 不會增加吞吐。
- 增加 partitions 可能改變既有 key 的 partition mapping；變更前要評估跨切換點順序。
- hot key 永遠只落一個 partition；遇到 hot SKU／order 要優先縮短 transaction、
  合併寫入或重新設計 contention boundary，不是無限加 concurrency。
- consumer 最大處理時間必須低於對應 poll／liveness 邊界，否則會 rebalance 並重送。

### 7.3 Retention 與磁碟

Source topic retention 至少覆蓋：

```text
maximum consumer outage
+ incident diagnosis time
+ repair/deployment time
+ planned replay time
+ safety margin
```

DLT retention通常不應短於 source topic，且要覆蓋人工處理 SLA。容量估算要乘 replication
factor；監看每 broker／每 log directory，不能只看 cluster aggregate free disk。

同時核對 producer `max.request.size`、broker/topic `max.message.bytes` 與 consumer
`max.partition.fetch.bytes`。不要只放大其中一層；大 record 也會放大 Connect heap、
network、Kafka page cache、consumer heap 與 retry/DLT 成本。

### 7.4 Read-only health commands

```bash
kafka-topics.sh \
  --bootstrap-server <broker-list> \
  --describe

kafka-consumer-groups.sh \
  --bootstrap-server <broker-list> \
  --describe \
  --group <consumer-group>

kafka-configs.sh \
  --bootstrap-server <broker-list> \
  --entity-type topics \
  --entity-name <topic> \
  --describe
```

關鍵 signals：

- offline partitions 必須為 0；
- under-replicated partitions 平時應為 0；
- ISR shrink、unclean election、controller／broker restart；
- broker disk free、bytes in/out、request queue／latency；
- produce error／timeout、record-too-large；
- consumer records lag max 與 lag age；
- DLT rate、count、oldest age、re-entry rate。

Kafka 官方也建議同時監看 client message/byte rate、request latency、consumer max lag 與
minimum fetch rate，見 [Kafka monitoring](https://kafka.apache.org/41/operations/monitoring/)。

## 8. Application 與程式碼技巧

### 8.1 Producer：交易邊界必須由 use case 擁有

概念形狀：

```java
@Transactional
public void execute(Command command) {
  Aggregate aggregate = repository.load(command.aggregateId());
  aggregate.change(command);
  repository.save(aggregate);

  integrationEventPublisher.publish(toIntegrationEvent(aggregate));
}
```

目前 JDBC producer 會要求 caller 已有 active transaction，再 INSERT `event_outbox`。
這能避免「business commit、Outbox rollback」或反過來。不要：

- 在 transaction commit 後才 publish；
- 以 async executor 寫同一筆 Outbox；
- 正常路徑直接使用 Kafka producer；
- catch Outbox write exception 後仍讓 business transaction 成功；
- 從 mutable entity 延遲序列化，導致 payload 與 commit 時狀態不一致。

### 8.2 Consumer：不必在 Kafka listener 手寫 `@Transactional`

messaging starter 的 transaction/idempotency decorator 會建立下列範圍：

```text
one database transaction
  → Inbox claim
  → handler
  → application use case
  → optional follow-up Outbox
  → commit
```

如果 Inbox claim 衝突，該 `subscriberId + messageId` 已處理過，handler 不再執行。若
handler 拋出 exception，Inbox、business changes 與 follow-up Outbox 一起 rollback，
讓 Kafka redelivery 可以真正重試。

這只表示 message-consumer entry path 已提供 transaction boundary；同一 application use case
若也能由 HTTP、scheduler 或 CLI 直接呼叫，該入口仍須自行確保 transaction boundary。

程式碼必須遵守：

- handler 失敗要拋出，不可 log 後假裝成功；
- 不要在 handler 內以 `REQUIRES_NEW` 切開 Inbox 與 business transaction；
- 不要在 DB transaction 中直接做無法 rollback 的遠端 side effect；
  需要跨系統通知時寫新的 Outbox message；
- handler 要可被同一 message 重入，不能依 JVM in-memory flag 去重；
- `subscriberId` 是 Inbox idempotency namespace，必須長期穩定。

### 8.3 兩層 retry 不可混成一層

| Retry | 處理問題 | Transaction | Exhausted 後 |
|---|---|---|---|
| Local optimistic-lock retry | 同一 message 的短暫 DB optimistic conflict | 每次 attempt 都是新的完整 Inbox + handler transaction | 拋出 exhausted exception |
| Kafka redelivery／DLT | DB unavailable、bug、poison message 等整體 failure | 下一次 delivery 再跑完整 pipeline | 依 policy 進 DLT |

Local retry 要短、有限、帶 jitter／metrics；Kafka retry 處理較長或不可立即恢復的錯誤。
不分層會產生 retry 乘法，例如 local 5 次 × Kafka 10 次 = 50 次 DB transaction。

### 8.4 Message identity、route 與 partition key

- `id`：一次 logical message 的 immutable UUID；retry／DLT replay 保留原值。
- `type`：contract discriminator；不要依 Java FQCN 當 wire contract。
- `aggregatetype`／`aggregateid`：domain identity。
- `route`：目的 Kafka topic／logical channel。
- `partition_key`：ordering／contention boundary。
- correlation／causation／trace 放 headers，不要複製成可分岔的第二份 identity。

這些欄位刻意分開。尤其不要把 `aggregateid`、`route` 與 `partition_key` 合成一個欄位。

### 8.5 Payload、headers 與 contract evolution

- event 表達已發生的 business fact，避免把內部 entity graph 全部序列化；
- payload 應小、bounded、可版本演進；大量明細可用 reference + query API，但要考慮資料快照語意；
- consumer 先支援 additive schema，再部署 producer；
- required field 移除／改名要另開 event version 或 compatibility migration；
- headers 只放 transport metadata；本專案限制 `messageHeaders` 最多 64 keys、16 KiB，
  values 為 strings；
- PII／secret 不放 headers、metrics tags 或 DLT logs；
- message ID、correlation ID、partition key 可進 structured logs／trace，但不可當
  metrics high-cardinality tag。

### 8.6 Ack、offset 與 graceful shutdown

- Kafka offset 只能在 DB transaction成功後 ack／commit。
- BATCH ack 可能讓 failure recovery 重送同 batch 已成功 records；Inbox 必須能承受。
- deployment termination grace 必須大於 consumer shutdown timeout 加上最慢合理 transaction。
- readiness 先移除流量／停止接新工作，再 drain listeners；不要直接 kill -9。
- `auto.offset.reset=earliest` 只在 group 沒有有效 committed offset 時生效；新 group
  可能重播 topic retention 內全部資料，Inbox retention 要能覆蓋。

### 8.7 最低測試組合

1. business row 與 Outbox row success 時同 commit；
2. producer mapping／serialization failure 時兩者同 rollback；
3. 同 `subscriberId + eventId` 併發 delivery 只有一次 business effect；
4. handler failure 時 Inbox、business、follow-up Outbox 全 rollback；
5. connector restart／worker replacement後不遺漏，duplicate 被 Inbox 擋下；
6. Kafka unavailable 時 queue backpressure、WAL 增長與恢復符合容量預期；
7. partition key、event type、headers 與 payload 的 CDC golden contract；
8. DLT replay 保留 original message ID、key、topic metadata；
9. hot key、peak payload、optimistic conflict 與 graceful shutdown 負載測試。

## 9. 統一監控與初始告警

以下是第一版起點；穩定運行後應改成 SLA／burn-rate 與實際容量模型。

| Component | Signal | Warning 起點 | Critical 起點／動作 |
|---|---|---|---|
| PostgreSQL | filesystem free | 剩餘 < 20% | < 10%；立即擴容／降低非必要寫入，不先 drop slot |
| Replication slot | connector slot inactive | 非維護期持續 1–2 分鐘 | connector/task failed；停止 Outbox cleanup |
| Replication slot | retained WAL | CDC WAL budget 50% | 75% 或將在 RTO 前耗盡；恢復 Connect/Kafka、擴容 |
| Outbox | oldest row age | 接近 connector recovery objective | 超過 objective；查 slot／connector，而非先 delete |
| Inbox／Outbox | table bytes／growth | capacity budget 80% | retention cleanup 無法追上；查 plan、vacuum、partitioning |
| Connect | connector/task state | 非 `RUNNING` | 立即 page，保存 trace 後處理 |
| Connect | queue utilization | > 80% 持續 | > 95%／無下降；查 Kafka latency、large records、heap |
| Connect | heap／GC | after-GC heap持續上升 | OOM/restart 或長 GC；先查 queue bytes／record size |
| Kafka | offline partitions | 任一筆 | 立即 page |
| Kafka | under-replicated partitions | 非維護期 > 0 | 持續或同時 broker/disk failure |
| Kafka | broker disk | < 20% | < 10%；擴容／調整 retention，不手動刪 segment |
| Consumer | lag age | 超過 processing SLA 50% | 超過 SLA；查 hot partition、DB、retry |
| DLT | new records | 任一筆告警或 ticket | oldest age 達 retention 50%／持續快速增加 |
| Application | failed／retry ratio | 偏離 baseline | error budget burn、business invariant failure |

Dashboard 應使用同一時間軸排列：

```text
Outbox append rate / failure
→ WAL retained bytes / slot active
→ Debezium source lag / queue / errors
→ Kafka produce latency / partition health / disk
→ consumer lag age / retries / DLT
→ Inbox processed / duplicate / failed
→ business success and reconciliation
```

本專案至少要匯出並核對 `archone.messaging.producer`、
`archone.messaging.consumer`、`archone.messaging.consumer.retry` 與
`archone.messaging.consumer.dlt` outcomes。consumer retry／DLT 以低基數
`messaging.failure.category`、`messaging.failure.retryable` 分類；DLT 再以
`messaging.dlt.disposition=direct|retry_exhausted` 區分 poison message 與 retry exhaustion。
message ID、partition key、Kafka partition／offset 與 correlation ID 只進 structured
log／trace high-cardinality fields，不進 metric tags。

## 10. 事故處理決策表

### 10.1 Connect OOM／反覆 restart

1. 停止 Outbox cleanup。
2. 保存 container reason、heap／GC、queue bytes、record-size distribution、connector trace。
3. 查 Kafka produce latency、broker availability、DNS／TLS；不要只加 heap。
4. 確認 `max.queue.size.in.bytes` 是否為 0 或大於實際 memory budget。
5. 以原 connector name、slot、internal topics 恢復。
6. 恢復後監看 WAL lag 收斂、duplicate outcome 與 DLT。

### 10.2 Kafka 不可用

1. application 可以繼續 commit Outbox，但先確認 DB/WAL capacity 能撐過預估 outage。
2. Connect queue 填滿後 backpressure 是正常現象；不要無限放大 queue。
3. 優先恢復 broker／network／ACL；同時監看 slot retained WAL 與磁碟耗盡時間。
4. Kafka 恢復後限制 catch-up 對 DB、network、broker 與 consumers 的衝擊。

### 10.3 WAL 快速增長

1. 查 slot `active`、`wal_status`、`restart_lsn`、`confirmed_flush_lsn`。
2. 查 connector/task、Connect queue、Kafka produce path。
3. 確認是否為 high-traffic DB／low-traffic captured table 的 heartbeat 情境。
4. 必要時先擴磁碟或降低非必要 DB writes。
5. 不 drop slot；若 slot 已 `lost`，進入受控 rebuild + reconciliation。

### 10.4 Consumer lag／DLT 暴增

1. 依 topic／partition 找是否單一 hot key。
2. 分開統計 DB timeout、optimistic conflict、contract error、poison message。
3. 確認 local retry 與 Kafka retry 沒有形成乘法。
4. 只有 partitions 與 DB capacity 足夠時才增加 concurrency。
5. 先修 root cause，再依 runbook 小批次 replay DLT。

### 10.5 Duplicate business effect

1. 以 message ID 查 Kafka record、subscriber ID、`event_inbox` 與 business audit。
2. 確認 subscriber ID 是否更名、Inbox retention 是否過短。
3. 查 handler 是否使用 `REQUIRES_NEW`、吞 exception、遠端 side effect 或繞過 decorator。
4. 不刪 Inbox row來「再試一次」；使用可稽核 repair command／reconciliation。

## 11. Deployment 與 config change checklist

### 上線前

- [ ] PostgreSQL logical settings、slot capacity、WAL disk 與 backup／failover 已演練。
- [ ] Kafka topics 已人工建立並核對 partitions、RF、min ISR、retention、max message bytes。
- [ ] Connect config／offset／status topics 已 replicated + compacted。
- [ ] Connect heap、container memory、queue count／bytes、JMX 與 restart policy 已設定。
- [ ] connector name、slot、publication、topic prefix 與 SMT config 已版控。
- [ ] application、DB、Connect、Kafka 時鐘同步。
- [ ] Inbox／Outbox retention、cleanup owner 與 scheduler／job 已指定。
- [ ] DLT topic、retention、告警、replay 權限與 runbook 已驗證。
- [ ] secret 不在 repo、logs、Connect REST exposure 或 Kafka UI 公網介面。
- [ ] production-like failure／recovery test 已通過。

### Rolling change

1. additive DB migration；
2. tolerant consumer；
3. connector／SMT mapping；
4. producer；
5. canary event 端到端驗證；
6. 監看至少一個 retention／traffic observation window；
7. destructive cleanup 延後到所有舊 records 都過 retention。

詳細 generic headers 部署順序見
[messaging-operations-runbook `1](./messaging-operations-runbook.md#1-rolling-deploymentgeneric-headers)。

### 每次變更都保存

- Git revision／artifact version；
- connector config redacted snapshot；
- topic config snapshot；
- DB migration version；
- old/new subscriber IDs、consumer groups 與 offsets；
- canary message ID、topic／partition／offset；
- rollback trigger 與 owner。

## 12. Repo 內對照位置

| 目的 | 位置 |
|---|---|
| Local infra baseline | `e2e/perf/docker-compose.yml` |
| Debezium connector registration | `e2e/perf/kafka-connect/register-outbox-connector.sh` |
| Application Kafka defaults | `backend/deployments/monolith/src/main/resources/application.properties` |
| Inbox／Outbox schema | `backend/deployments/monolith/src/main/resources/db/migration/V5__create_event_inbox_and_outbox.sql` |
| Subscriber-aware Inbox | `backend/deployments/monolith/src/main/resources/db/migration/V7__make_event_inbox_subscriber_aware.sql` |
| Generic headers migration | `backend/deployments/monolith/src/main/resources/db/migration/V8__add_generic_headers_to_event_outbox.sql` |
| JDBC Outbox writer | `backend/messaging/messaging-producer-jdbc` |
| Transactional Inbox | `backend/messaging/messaging-consumer-jdbc` |
| Spring transaction adapters | `backend/messaging/messaging-spring-jdbc`、`backend/messaging/messaging-spring-consumer-jdbc` |
| Kafka retry／DLT | `backend/messaging/messaging-spring-consumer-kafka` |
| Messaging observability | `backend/messaging/messaging-spring-observability` 與 producer／consumer observation modules |
| Operational procedures | `docs/messaging-operations-runbook.md` |

## 13. 官方參考

- [Debezium PostgreSQL connector](https://debezium.io/documentation/reference/stable/connectors/postgresql.html)
- [Debezium Outbox Event Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)
- [Kafka Connect user guide](https://kafka.apache.org/41/kafka-connect/user-guide/)
- [Kafka Connect configuration](https://kafka.apache.org/41/configuration/kafka-connect-configs/)
- [Kafka monitoring](https://kafka.apache.org/41/operations/monitoring/)
- [PostgreSQL logical replication configuration](https://www.postgresql.org/docs/current/logical-replication-config.html)
- [PostgreSQL replication settings](https://www.postgresql.org/docs/current/runtime-config-replication.html)
- [PostgreSQL replication slots view](https://www.postgresql.org/docs/current/view-pg-replication-slots.html)
- [PostgreSQL VACUUM](https://www.postgresql.org/docs/current/sql-vacuum.html)
