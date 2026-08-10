# Messaging operations runbook

> 適用：PostgreSQL `event_outbox`／`event_inbox`、Debezium Outbox Event Router、Kafka programmatic
> consumers。正常 publication 固定走 Outbox → Debezium → Kafka；只有 DLT recovery／replay 可以
> 使用受控的 Kafka producer。
>
> Server sizing、PostgreSQL WAL／slot、Kafka Connect memory、Kafka durability、統一監控與
> application 程式碼邊界，見 [messaging-server-operations-guide](./messaging-server-operations-guide.md)。

## 1. Rolling deployment：generic headers

部署順序不可顛倒：

1. 先套用 additive DB migration，讓 `event_outbox.headers` 存在且舊 writer 可省略；不刪欄位。
2. 部署可同時接受「沒有 `messageHeaders`」與新 serialized-header envelope 的 consumer mapper。
3. 更新 Debezium connector：

   ```text
   transforms.outbox.table.fields.additional.placement=
     type:header:eventType,headers:header:messageHeaders
   ```

4. 等 connector／tasks 都為 `RUNNING`，再部署會寫入 correlation／causation／trace headers 的 producer。
5. 驗證新舊 records 都能處理，並監看 consumer failure、retry、DLT 與 trace continuity。

回退採反向順序，但保留 DB 欄位：先停用新 producer header 行為，再還原 connector mapping；consumer
保持 tolerant reader，直到 Kafka retention window 內不再存在舊 records。不要做 destructive down
migration。

## 2. Subscriber ID／consumer group 更名

`subscriberId` 決定 `(subscriber_id, event_id)` Inbox uniqueness；`consumerGroupId` 決定 Kafka
offset ownership。預設做法是讓 subscriber ID 永久穩定，只透過
`archone.messaging.consumer.groups.<subscriber-id>` 調整 group mapping。

更名前：

1. 停止該 subscription 並確認沒有 in-flight transaction。
2. 記錄舊 group 每個 topic/partition 的 committed offset、source topic retention 與 DLT backlog。
3. 決定新 group 的起始 offsets；Kafka 不提供語意上的 group rename，新 group 未設定 offsets 時可能從
   `auto.offset.reset` 重播。
4. 確認 Inbox 保存期覆蓋可能重播的最舊 record。
5. 以單一 instance 啟動新 group，驗證 duplicate outcome、lag、error rate 後再擴容。

若 business rename 迫使 subscriber ID 也更名，這不是單純設定調整：新 ID 會形成新的 Inbox scope。
必須另開 migration，先把仍可能重播期間內的舊 claims 複製到新 subscriber namespace，再切換；不得
直接改字串後期待舊 Inbox 自動去重。

## 3. DLT replay

1. 先修正 root cause，並暫停自動大量 replay。
2. 檢查 DLT record 保有 original topic、partition、offset、timestamp、key、message ID、event type、
   `messageHeaders`、subscriber ID 與 consumer group ID。
3. 使用 `KafkaDeadLetterReplayRecordFactory` 重建 original record；不可產生新的 message ID，也不可把
   DLT headers 當成 application headers帶回去。
4. 先 replay 單筆，再確認 Inbox outcome：原處理 rollback 的 record 應成為 `PROCESSED`；已提交過的
   record 應成為 `DUPLICATE`。
5. 按 partition 小批次 replay，監看 lag、retry、DLT re-entry 與 business invariant。

若 DLT 缺少必要 original-record metadata，停止 replay；人工猜測 topic、key 或 message ID 會破壞
ordering 與 idempotency。

## 4. Debezium connector rollback／故障

發生 connector failure 時，先停止 Outbox cleanup，保留 replication slot 與 connector identity；檢查：

- connector 與 task status／trace；
- PostgreSQL replication slot lag、WAL disk pressure；
- `topic.prefix`、`slot.name` 是否與其他 connector 唯一；
- `table.include.list=public.event_outbox`；
- route、partition key、payload expansion 與 additional header placement 是否符合 golden config。

若新 connector config 需回退，先 pause connector，還原上一版設定，再 resume 並確認 lag 收斂。不要
刪除 slot 或用新 `topic.prefix` 取代原 connector，除非已制定 snapshot／offset migration；這兩個動作
會改變 CDC identity，可能造成遺漏或重送。

## 5. Header decode failure

`messageHeaders` 必須是 UTF-8 JSON object，最多 64 個 key、encoded size 最多 16 KiB，且 value
全部為 string。缺少 header 的歷史 record可走 legacy empty-header path；header 存在但格式錯誤則是
contract failure，必須 retry／DLT，不能當作 unknown event 忽略。

處理步驟：

1. 從 DLT 取得原始 `messageHeaders` bytes，不先修改 record。
2. 檢查是否為 JSON object、value 是否全為 string、大小／數量是否超限，以及 reserved identity
   headers 是否被 caller 偽造。
3. 對照 `event_outbox.headers` 與 connector additional placement，判斷錯誤在 writer、DB data、SMT
   或 consumer mapping。
4. 修正 producer／connector後，以原 message ID 依 DLT replay 程序重送。

## 6. Inbox／Outbox retention

Ownership：application/database operations owner 執行 cleanup；messaging library 只定義 schema 與
SQL contract，本輪不建立 scheduler。

建議下限：

| Store | 建議最小保存期 | 刪除前必要條件 |
|---|---|---|
| `event_inbox` | `max(source topic retention, DLT replay SLA, planned replay window) + 24h`；未另定時預設 14 天 | 沒有要 replay 更早的 source／DLT record；subscriber rename backfill 已完成 |
| `event_outbox` | `max(connector outage recovery objective, reconciliation/audit window) + 24h`；未另定時預設 7 天 | connector 與 task `RUNNING`、lag 已收斂、沒有 pending incident／rebuild |

不要以 row count 作為唯一 cutoff，也不要在 connector lagging 時刪 Outbox。Cleanup 每批建議
1,000～10,000 rows，批次間觀察 lock、WAL 與 replication lag：

```sql
WITH victims AS (
  SELECT ctid
    FROM event_inbox
   WHERE processed_at < :inbox_cutoff
   ORDER BY processed_at
   LIMIT :batch_size
   FOR UPDATE SKIP LOCKED
)
DELETE FROM event_inbox target
 USING victims
 WHERE target.ctid = victims.ctid;
```

```sql
WITH victims AS (
  SELECT ctid
    FROM event_outbox
   WHERE timestamp < :outbox_cutoff
   ORDER BY timestamp
   LIMIT :batch_size
   FOR UPDATE SKIP LOCKED
)
DELETE FROM event_outbox target
 USING victims
 WHERE target.ctid = victims.ctid;
```

先在 production-like environment 驗證 query plan；若 cleanup 持續掃描大量資料，再以獨立 migration
增加對應 timestamp index，不要在 runbook 中臨時改 schema。

監控門檻建議：

- connector/task 非 `RUNNING` 立即告警並停止 Outbox cleanup；
- replication slot lag 接近 PostgreSQL WAL 容量的 50% warning、75% critical；
- oldest Outbox age 超過 connector recovery objective 立即告警；
- Inbox／Outbox table bytes 或每日成長量超過容量預算 80% warning；
- DLT oldest age 接近 DLT retention 50% warning、75% critical。

## 7. Observability 驗證

application 的既有 `ObservationRegistry` 由 Spring Boot Actuator 接到 Prometheus／OTLP；messaging
auto-configuration只在 registry 存在時加入 producer interceptor 與 consumer decorator，不建立第二套
registry/exporter。

部署後至少確認：

- `archone.messaging.producer` 有 `appended`／`failed` outcome；
- `archone.messaging.consumer` 有 `processed`／`duplicate`／`ignored_unhandled`／`failed` outcome；
- `archone.messaging.consumer.retry` 與 `archone.messaging.consumer.dlt` 能反映 retry／DLT；
- metric tags 不含 message ID、partition key 或 correlation ID；這些只可作 trace high-cardinality fields；
- `traceparent`／`tracestate` 從 producer observation 寫入 Outbox headers，經 Debezium
  `messageHeaders` relay 後能被 consumer observation 讀取。
