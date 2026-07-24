# SR-13 Debezium Outbox CDC to Kafka 實作紀錄

狀態：已完成

完成日期：2026-07-24

## 實作範圍

SR-13 將 transactional Outbox 接到 PostgreSQL logical replication、Debezium Outbox Event Router 與 Kafka，並以真實容器驗證端到端 delivery。

- Outbox 新增 `route`，由 Domain Event translator 依 Integration Event 生產端寫入目標 topic。
- `aggregatetype` 保留來源 Aggregate 的語意，例如 `Order`；Debezium 不再用它決定 Kafka topic。
- 使用 Kafka `4.1.2` 與 Debezium Connect `3.5.2.Final`；此組合為 Debezium 3.5.2 官方測試的版本。
- 不新增 application polling relay，也不在 Outbox row 寫入 `publishedAt`、attempts 或 last error。
- CDC 仍為 at-least-once；相同 event identity 的重送由接收端 Inbox 去重。

## 新增檔案

### `../../order-promising/src/main/resources/db/migration/V5__create_event_inbox_and_outbox.sql`

- 系統尚未上線，因此直接調整尚未固定的 V5 baseline，於 `event_outbox` 建表時加入 `route VARCHAR(255) NOT NULL`。
- 不建立 V6 migration，也沒有既有 row 需要回填；所有 Outbox row 都由更新後的 translator 寫入 route。

### `../../order-promising/src/sit/java/com/flowzati/archone/common/outbox/OutboxCdcIntegrationTest.java`

- 啟動 PostgreSQL 16（`wal_level=logical`）、Kafka `4.1.2` 與 Debezium Connect `3.5.2.Final`。
- 透過 Kafka Connect REST API 註冊 PostgreSQL connector，僅擷取 `public.event_outbox`。
- 使用 Outbox Event Router：`route.by.field=route`、`route.topic.replacement=${routedByValue}`。
- 將 `type` 寫入 Kafka header `eventType`；`aggregateid` 是 Kafka key，`id` 是 header `id`。
- 驗證已 commit 的 Outbox row 送往指定 route、Connect restart 後依 offset 接續，以及新 connector initial snapshot 會重送既有 event identity。

## 變更檔案

### Outbox model 與 persistence

- `Outbox`、`OutboxEntity`、`OutboxRepoImpl` 與 `OutboxAppender` 增加不可為空的 `route`。
- Ordering translator 寫入 `ordering.order-events`。
- Allocation translator 寫入 `promising.allocation-events`。
- 更新 Outbox translation 與 persistence tests，驗證 route 已被保存。

### `../../gradle/libs.versions.toml` 與 `../../order-promising/build.gradle`

- SIT source set 新增 Testcontainers Kafka module 與 Kafka client。
- 不使用 Debezium Testcontainers helper：其 3.5 版本需要 Java 21，而專案 toolchain 是 Java 17。
- 改以 Testcontainers `GenericContainer` 啟動 Debezium Connect，並從 Java 17 測試透過其 REST API 註冊 connector；這不改變實際 CDC pipeline。

### Connector 設定要點

```text
connector.class=io.debezium.connector.postgresql.PostgresConnector
plugin.name=pgoutput
table.include.list=public.event_outbox
slot.name=order_promising_outbox_slot
publication.autocreate.mode=filtered
transforms=outbox
transforms.outbox.type=io.debezium.transforms.outbox.EventRouter
transforms.outbox.route.by.field=route
transforms.outbox.route.topic.replacement=${routedByValue}
transforms.outbox.table.fields.additional.placement=type:header:eventType
```

`event_outbox.timestamp` 保持 `TIMESTAMPTZ` 作為稽核與 payload 的發生時間；Outbox Event Router 的 `table.field.event.timestamp` 只接受 `INT64`，故本次不設定。Kafka record timestamp 使用 CDC 發生時間。

### `../stock-reservation-design.md`

- SR-13 checkbox 更新為完成。
- 整體進度更新為 `13 / 17`，可立即執行項目更新為 SR-14、SR-16。

## 驗證結果

執行 unit tests：

```bash
./gradlew :order-promising:test
```

結果：`BUILD SUCCESSFUL`。

執行全部 SIT：

```bash
./gradlew :order-promising:sit --rerun-tasks
```

結果：`BUILD SUCCESSFUL`，共 `34 tests completed`。

其中 CDC integration test：

```bash
./gradlew :order-promising:sit --tests com.flowzati.archone.common.outbox.OutboxCdcIntegrationTest
```

結果：`1 test completed`。

驗證內容：

- PostgreSQL logical replication 的 committed Outbox insert 可送達 Kafka。
- `route=ordering.order-events` 會發布至同名 topic，而不是 `outbox.event.Order`。
- `aggregateid` 成為 Kafka key，Outbox `id` 與 `type` 分別保留為 `id`、`eventType` headers。
- Debezium Connect restart 後從 Kafka Connect offset 繼續，新的 Outbox event 正常發布。
- 以新的 slot 建立 connector 時 initial snapshot 重送既有 event，證明 consumer 必須以 Inbox 的 event identity 去重。

## 開發環境注意事項

- CDC SIT 需要可用的 Docker daemon，並會拉取 PostgreSQL、Kafka 與 Debezium Connect images。
- Kafka topic、Kafka Connect offset topic、replication slot 與 publication 都由測試容器建立；不會修改本機既有 Kafka／PostgreSQL。
- 正式部署應將上述 connector settings 交由 Kafka Connect 的部署設定管理，並保留相同的 route、slot、publication 與 retention 原則。
