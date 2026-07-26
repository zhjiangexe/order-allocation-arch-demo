## Why

`event_outbox` 缺少表達「Kafka partition key」的欄位，所以這個決定只能借用
`aggregateid`。SR-13 的設計本身就把這個借用寫進了文件——
`docs/stock-reservation-design.md:313` 把 `aggregateid` 定義為「Kafka message key，
支援 UUID／其他 aggregate id 表示」，讓一個欄位同時承載領域身分與傳輸決策兩個角色。

v1 之下兩個角色的值恆等（都是 `orderId`），衝突不可觀測。v3 引入
`archone.allocation.partition-key-strategy=sku` 之後兩者分岔：partition key 必須是
`sku`，但事件所屬的 aggregate 仍是 Order。一個欄位裝不下兩個值，實作
（`OrderingDomainEventTranslator:63`）依 `:313` 的字面定義寫入 `sku`，於是
`aggregateid` 在 v3 模式下對「這筆事件屬於哪個 aggregate」會回答錯誤。

同一份設計文件 `:319` 已經對 `aggregatetype` 做過完全相同的判斷——為了不讓
aggregate 欄位兼任傳輸路由，特地新增 `route` 欄位。這個推論從未被延伸到
`aggregateid`，因為當時不需要。本次補上同一個模式的第二次套用。

現在做的理由：`add-demo-console-api` 需要一條誠實的事件時間軸查詢條件
（`aggregatetype = 'Order' AND aggregateid = ?`），而該條件在 v3 模式下目前不成立。

## What Changes

- `event_outbox` 新增 `partition_key` 欄位，專門承載 Kafka message key；直接修改
  `V5__create_event_inbox_and_outbox.sql`，不新增 migration（此 schema 尚未部署至
  任何環境，`spring.flyway.enabled` 只在 dev profile 開啟，`application-uat.properties`
  為空檔）。
- `aggregateid` 回歸單一語意：事件所屬 aggregate 的識別碼，Order aggregate 事件
  一律為 `orderId`，不再受 `partition-key-strategy` 影響。
- Debezium Outbox Event Router 新增 `table.field.event.key=partition_key`，取代預設的
  `aggregateid`。兩處設定必須逐字一致：`OutboxCdcIntegrationTest` 與
  `e2e/perf/kafka-connect/register-outbox-connector.sh`。
- 新增 `OutboxDelivery(route, partitionKey)` record，`OutboxAppender.append(...)` 以它
  取代原本的 `route` 單一參數，讓「領域三欄 vs 傳輸兩欄」的分界在程式碼中可見。
- `OrderingDomainEventTranslator` 的 `aggregateId(orderId, sku)` 更名為
  `partitionKey(orderId, sku)`，策略判斷邏輯不變；`aggregateid` 位置固定填入 `orderId`。
- `AllocationDomainEventTranslator` 明確以 `orderId` 作為 partition key，並在程式碼中
  記錄理由：`promising.allocation-events` 在本 repo 沒有 consumer，single-writer 要
  保護的是 allocation consumer 那一端。
- **BREAKING（僅限本機開發環境）**：修改既有 migration 會造成 Flyway checksum
  不符。既有 dev／perf 資料庫必須先 `./e2e/perf/run.sh down`（`docker compose down -v`，
  會移除 volume）再重建。無正式環境受影響。

## Capabilities

### New Capabilities

- `outbox-event-delivery`: Outbox row 將領域身分（aggregate type 與 aggregate id）與傳輸決策（目標 topic 與 message key）分欄表達，兩者互不兼任；Debezium 只從傳輸欄位取得 topic 與 message key，不讀取領域欄位，因此 partition 策略切換不影響 outbox 對事件所屬 aggregate 的答案。

### Modified Capabilities

- None.

## Impact

- Schema：`event_outbox` 新增 `partition_key VARCHAR(255) NOT NULL`（改
  `V5__create_event_inbox_and_outbox.sql`）。
- Production code：`Outbox`、`OutboxDelivery`（新增）、`OutboxAppender`、
  `OrderingDomainEventTranslator`、`AllocationDomainEventTranslator`。
- Connector 設定兩處：`OutboxCdcIntegrationTest`、
  `e2e/perf/kafka-connect/register-outbox-connector.sh`。
- 測試：`DomainEventTranslatorTest`、`OutboxCdcIntegrationTest`、
  `InboxRepoOutboxPersistenceIntegrationTest`。
- 文件：`docs/stock-reservation-design.md` 的 `:313`、`:319`、`:570`；
  `docs/superpowers/specs/2026-07-26-v3-single-writer-design.md:38-40` 補 superseded 註記。
- **無行為變更**：Kafka 訊息落在哪個 partition、key 是什麼，改動前後完全一致；
  變的只是該值從哪一欄取得。風險集中於 connector 設定與 migration，不在業務邏輯。
- 不影響 Integration Event 契約、Kafka topic 名稱、consumer 端 inbox 冪等機制。
