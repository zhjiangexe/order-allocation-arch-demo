# Messaging operations

更新日期：2026-09-26

適用範圍：PostgreSQL `event_outbox`／`event_inbox`、Debezium Outbox Event Router、Kafka Connect、Kafka consumers
與 DLT。模組 API 與設定說明見 `backend/archone-framework/messaging/README.md`。

## Delivery model

```text
business transaction
  → business rows + event_outbox commit
  → Debezium reads logical WAL
  → Outbox Event Router writes Kafka record
  → consumer transaction
       → Inbox claim
       → handler / use case
       → optional business rows + follow-up Outbox
       → commit, then offset acknowledgement
```

- 正常 publication 固定走 Outbox；只有受控 DLT recovery／replay 可以直接使用 Kafka producer。
- Producer mapping 或 Outbox write 失敗時，business transaction 必須一起 rollback。
- Consumer handler 失敗時，Inbox、business changes 與 follow-up Outbox 必須一起 rollback。
- `(subscriberId, messageId)` 是 Inbox idempotency key；兩個值都必須長期穩定。
- `aggregateId` 表達 domain identity；`partitionKey` 表達 ordering／contention boundary。
- retry 與 DLT replay 保留原 message ID、key、topic metadata 與 application headers。

## Baseline responsibilities

| Component | 必須保存／保證 |
| --- | --- |
| PostgreSQL | logical WAL、connector slot、Outbox／Inbox durability、可恢復備份 |
| Kafka Connect／Debezium | connector identity、offset、route／key／payload／header mapping |
| Kafka | topic durability、partition ordering、source／DLT retention |
| Application | transaction boundary、idempotent handler、bounded payload、stable subscriber ID |
| Operations | capacity budget、retention cleanup、monitoring、replay approval 與 reconciliation |

Connector name、`topic.prefix`、`slot.name`、publication、captured table、Connect internal topics 與 SMT mapping 都是
持久資料 identity。變更它們需要 migration／cutover plan，不是普通 restart。

## Deployment order

契約或 header 變更採 expand-and-contract：

1. additive database migration；
2. tolerant consumer；
3. connector／SMT mapping；
4. producer；
5. canary event 端到端驗證；
6. 觀察至少一個 traffic／retention window；
7. 舊 records 全部過 retention 後才做 destructive cleanup。

Rollback 採反向順序，但保留 additive database column 與 tolerant reader，直到 retention window 內不再有舊資料。

## Subscriber or group rename

- 優先保持 `subscriberId` 不變，只調整 `archone.messaging.consumer.groups.<subscriber-id>` 的 group mapping。
- Kafka consumer group 沒有語意上的 rename；新 group 必須明確決定起始 offsets。
- `subscriberId` 改名會建立新的 Inbox namespace。切換前必須把可能重播期間內的 claims 遷移到新 namespace。
- 切換前保存舊 offsets、topic retention、DLT backlog；先以單一 instance 驗證再擴容。

## DLT replay

1. 先修 root cause，暫停大量自動 replay。
2. 確認 record 保有 original topic、partition、offset、timestamp、key、message ID、event type、headers、subscriber
   ID 與 consumer group ID。
3. 使用 repository 提供的 replay record factory 重建 original record；不產生新 message ID，不回帶 DLT-only
   headers。
4. 先 replay 單筆，確認 Inbox outcome 是 `PROCESSED` 或已提交過的 `DUPLICATE`。
5. 依 partition 小批次 replay，同時監看 lag、retry、DLT re-entry 與 business reconciliation。

必要 metadata 不完整時停止 replay；不可人工猜測 topic、key 或 identity。

## Retention and cleanup

| Store | 未另定時的起始值 | 必要條件 |
| --- | --- | --- |
| Inbox | 14 天，且至少覆蓋 source retention、DLT replay SLA 與 planned replay window，再加安全餘裕 | 沒有需要更早 claims 的 replay／rename migration |
| Outbox | 7 天，且至少覆蓋 connector outage recovery 與 reconciliation window，再加安全餘裕 | connector／tasks `RUNNING`、lag 收斂、沒有 incident／rebuild |

Cleanup owner 是 application／database operations，不是 messaging library。使用 timestamp index 與 1,000～10,000
rows 的小批次 delete，觀察 lock、WAL、replication lag、dead tuples 與 autovacuum；不要以 row count 當唯一 cutoff，
也不要在 connector lagging 時刪 Outbox。

## Health checks

### PostgreSQL

至少監看：logical slot `active`／`wal_status`、retained WAL、oldest Outbox age、Inbox／Outbox size、dead tuples、
long-running transaction 與 filesystem free space。`slot.drop.on.stop` 在 production 保持 `false`。

### Kafka Connect

```bash
curl -fsS http://<connect-host>:8083/connectors
curl -fsS http://<connect-host>:8083/connectors/<connector-name>/status
curl -fsS http://<connect-host>:8083/connectors/<connector-name>/config
```

保存 failure trace 與 redacted config 後才 restart。Connect REST API 不得無驗證暴露到公網。

### Kafka

```bash
kafka-topics.sh --bootstrap-server <broker-list> --describe
kafka-consumer-groups.sh --bootstrap-server <broker-list> --describe --group <consumer-group>
```

監看 offline／under-replicated partitions、broker disk、produce latency、consumer lag age、DLT count／oldest age 與
hot partition。增加 consumer concurrency 前先確認 partition 數與 database capacity；hot key 不會因增加 concurrency
而分散。

### Application

至少驗證：

- `archone.messaging.producer`: `appended`／`failed`；
- `archone.messaging.consumer`: `processed`／`duplicate`／`ignored_unhandled`／`failed`；
- `archone.messaging.consumer.retry` 與 `archone.messaging.consumer.dlt`；
- trace headers 能從 Outbox 經 Debezium 傳到 consumer；
- message ID、partition key 與 correlation ID 只進 structured logs／traces，不作為高基數 metric tags。

## Incident playbook

| Symptom | First actions | 禁止的捷徑 |
| --- | --- | --- |
| Connect OOM／restart loop | 停止 Outbox cleanup；保存 heap／GC、queue bytes、record size 與 connector trace；檢查 Kafka latency | 只加 heap、換新 connector identity |
| Kafka unavailable | 計算 DB／WAL 可支撐時間；恢復 broker／network／ACL；控制恢復後 catch-up | 無限放大 Connect queue |
| WAL rapidly growing | 查 slot、connector task、queue、Kafka produce path；必要時先擴磁碟 | 直接 drop slot |
| Consumer lag／DLT surge | 找 hot key；分類 DB、optimistic conflict、contract error；確認 retry 沒有乘法 | 未修 root cause 就大量 replay |
| Duplicate business effect | 以 message ID 查 Kafka、Inbox 與 business audit；檢查 subscriber rename／retention／transaction split | 刪 Inbox row 再試 |

若 slot 已 `lost`、backup 與 connector offset 不一致，或 DLT metadata 不完整，事件已升級為資料恢復問題；必須採
受控 rebuild 加 business reconciliation，不以「服務已恢復」取代完整性證明。

## Pre-production checklist

- PostgreSQL logical settings、slot capacity、WAL disk、backup／restore／failover 已演練。
- Kafka business、DLT、Connect internal topics 已明確設定 partitions、replication、retention 與 cleanup policy。
- Connector config、slot、publication、route、key、payload 與 headers mapping 已版控。
- Inbox／Outbox cleanup owner、schedule、retention 與告警已指定。
- DLT replay 權限、runbook 與 reconciliation 已驗證。
- Production-like failure／recovery、duplicate delivery、hot key 與 graceful shutdown tests 已通過。
