## Context

`event_outbox` 目前有七個欄位：`id`、`aggregatetype`、`aggregateid`、`type`、
`route`、`payload`、`timestamp`。傳遞一則 Kafka 訊息需要兩個決定——去哪個 topic、
用什麼 key 分區——但這張表只有前者的專屬欄位（`route`）。後者只能借用
`aggregateid`，而 `docs/stock-reservation-design.md:313` 也就照這個借用把它定義成
「Kafka message key」。

這個借用在 SR-13 當下是自洽的：`ordering.order-events` 的 message key 是 `orderId`
（`:572`），Order aggregate 的識別碼也是 `orderId`，兩個角色的值恆等，一欄裝兩角色
沒有可觀測差異。設計文件 `:573` 其實已經有一列反例——`inventory.stock-events` 的
message key 是 `sku` 而其 aggregate 是 `StockPool`——只是那個 topic 由外部上游生產、
不經本專案 outbox，所以矛盾沒有落到 schema 上。

v3（`archone.allocation.partition-key-strategy=sku`）讓兩個角色的值分岔。實作依
`:313` 的字面定義把 `sku` 寫進 `aggregateid`，於是 v3 模式下 outbox 對「這筆事件屬於
哪個 aggregate」會回答 SKU。

同一份文件 `:319` 已經對 `aggregatetype` 做過完全相同的判斷：為了「避免將 `Order`
等 Aggregate type 改作傳輸路由」而新增 `route` 欄位。本次是把這個推論延伸到
`aggregateid`——同一個模式的第二次套用，不是新的架構方向。

觸發時機是 `add-demo-console-api`：`GET /orders/{orderId}` 的事件時間軸需要
`aggregatetype = 'Order' AND aggregateid = ?` 這條查詢條件，而它在 v3 模式下目前不成立。
反過來說也成立——若不做時間軸，`aggregatetype` 與 `aggregateid` 會繼續是兩個沒有任何
程式讀取的欄位（`route.by.field=route` 已經覆蓋掉 Debezium 用 `aggregatetype` 推導
topic 的預設行為），這次改動也就沒有必要。

## Goals / Non-Goals

**Goals:**

- 讓 `event_outbox` 用獨立欄位表達 Kafka message key，`aggregateid` 回歸單一語意。
- 在不改變任何 Kafka 傳遞行為的前提下完成語意修正：改動前後訊息落在哪個 partition、
  key 是什麼完全一致。
- 讓「以 aggregate identity 查詢 outbox」在 v1 與 v3 兩種策略下都成立。
- 在測試中釘住核心行為：`aggregateid` 與 `partition_key` 不同時，Kafka record key
  取後者。

**Non-Goals:**

- 不改變 Integration Event 契約、Kafka topic 名稱、payload 結構或 consumer 端 inbox
  冪等機制。
- 不改變 `partition-key-strategy` 的既有行為或預設值，也不新增策略。
- 不把 `sku` 策略擴張到 `promising.allocation-events`。
- 不實作 `GET /orders/{orderId}` 的事件時間軸本身（屬於 `add-demo-console-api`）。
- 不改動 outbox 的保留政策或新增清理機制。
- 不新增 outbox 發布狀態欄位或 DLQ table。

## Decisions

### 新增 `partition_key` 欄位，而不是改用 payload 查詢或另建投影表

考慮過三個做法：

**（甲）新增 `partition_key` 欄位**——傳輸決策拿到自己的欄位，`aggregateid` 回歸
aggregate identity。

**（乙）不動 schema，時間軸改查 `payload->>'orderId'`**——加一個 jsonb index 即可，
不碰 Debezium 設定與既有 SIT。但這等於承認 schema 答不出「這筆事件屬於哪個
aggregate」，改用 payload 內容繞過去；而這個問題本來就發生在 schema 層。查詢條件會
綁死在 payload 欄位名上，而 payload 是對外契約的一部分，反而更難改。語意債原封不動
留著，`aggregateid` 在 v3 下繼續說謊。

**（丙）新增 `order_event_log` 投影表**——由 domain event listener 在同一 transaction
寫入。由於因果鏈不追補貨觸發來源（見 `add-demo-console-api`），這張表的內容會跟
outbox 逐筆重複、零新增資訊，卻多一條要維護的寫入路徑；而 `aggregateid` 的語意衝突
依舊存在，只是被繞過。

選（甲）。它是 `route` 這個 precedent 的直接延伸：SR-13 為了不讓 `aggregatetype`
兼任 topic 決策而開了 `route`，本次為了不讓 `aggregateid` 兼任 key 決策而開
`partition_key`。Debezium 本身提供 `table.field.event.key` 設定、預設值才是
`aggregateid`——這個旋鈕存在就代表框架預期有些專案需要把兩者分開，所以這不是繞過
框架，是走它準備好的路。

### 直接修改 `V5__create_event_inbox_and_outbox.sql`，不新增 migration

此 schema 尚未部署至任何環境：`application.properties` 的
`spring.flyway.enabled=false` 是預設值，只有 `application-dev.properties` 打開它，
`application-uat.properties` 為空檔。跑過 migration 的只有本機 dev 與 `e2e/perf` 的
資料庫。

因此不為一個從未部署過的 schema 累積 migration 歷史，直接在 V5 的 `CREATE TABLE`
加上 `partition_key VARCHAR(255) NOT NULL`。同一理由套用到 `orders` 的查詢 index：
若後續 change 需要新 index，同樣併入 `V3__create_orders.sql`。

代價是 Flyway checksum 對既有本機資料庫不符，啟動會拋 `FlywayValidateException`。
這由 Migration Plan 的重建步驟處理，不用 `flyway repair` 掩蓋。

### 以 `OutboxDelivery` record 承載兩個傳輸決定

`OutboxAppender.append(...)` 現有五個參數，直接加第六個 `String partitionKey` 會讓
兩個相鄰的 `String` 參數（route、partitionKey）在呼叫端難以分辨，也讓「哪些參數屬於
領域、哪些屬於傳輸」在簽章上看不出來。

改以 `OutboxDelivery(String route, String partitionKey)` record 取代原本的 `route`
參數：

```
append(IntegrationEvent event, String aggregateType, String aggregateId,
       OutboxDelivery delivery, Instant occurredAt)
```

參數數量維持五個，而且這次改動的核心分界——領域三欄 vs 傳輸兩欄——在程式碼裡有了
名字，不只活在 schema 與文件裡。

### `promising.allocation-events` 維持以 orderId 為 partition key

`AllocationDomainEventTranslator` 兩處明確寫入 `orderId` 作為 `partition_key`，不套用
`partition-key-strategy`。

`sku` 策略存在的目的是讓同一 SKU 的下單事件收斂進同一 partition，使 allocation
consumer 成為該 SKU 的 single writer。`promising.allocation-events` 在本 repo 沒有任何
consumer，沒有需要被保護的寫入端；為了「一致性」而把策略套上去是投機性擴張，會製造
一個無人驗證、無人受益的行為分支。理由必須寫在決策發生的位置（該 translator 的
註解），而不是只留在本文件裡。

### 兩處 connector 設定必須逐字一致

`table.field.event.key = partition_key` 要同時加在 `OutboxCdcIntegrationTest` 的
connector 設定與 `e2e/perf/kafka-connect/register-outbox-connector.sh`。後者的檔頭註解
已經寫明「設定內容跟 `OutboxCdcIntegrationTest` 逐字一致，確保跟 SIT 套件已經驗證過的
行為維持一致」——若只改一處，SIT 綠燈但壓測跑的是另一套設定，而這正是這次改動唯一
真正的風險點（沒有業務邏輯變更，風險全在設定）。

## Implementation Contract

**Behavior:** Outbox row 以 `aggregatetype`／`aggregateid` 表達事件所屬 aggregate，
以 `route`／`partition_key` 表達傳輸決定。Order aggregate 的 Integration Event 一律以
`orderId` 作為 `aggregateid`，不受 `archone.allocation.partition-key-strategy` 影響。
Debezium 依 `route` 決定 topic、依 `partition_key` 決定 Kafka record key。
`partition-key-strategy=sku` 時，`ordering.order-events` 的 `partition_key` 為 SKU；
其他值時為 `orderId`。`promising.allocation-events` 的 `partition_key` 恆為 `orderId`。

**Interface / data shape:** `event_outbox` 新增 `partition_key VARCHAR(255) NOT NULL`。
`Outbox` record 新增對應欄位並納入既有的 blank 檢查。新增
`OutboxDelivery(String route, String partitionKey)` record，取代 `OutboxAppender.append`
的 `route` 參數位置。不新增、不移除、不修改任何 Integration Event 型別、Kafka topic
名稱或 payload 欄位。

**Configuration:** Debezium Outbox Event Router 新增
`transforms.outbox.table.field.event.key = partition_key`，於 SIT 與 `e2e/perf` 註冊
腳本兩處以相同值宣告。其餘 Event Router 設定不變。

**Failure modes:** `partition_key` 為 null 或空字串時，`Outbox` 建構即拋
`IllegalArgumentException`，與既有欄位的驗證方式一致——不允許寫入一筆無法決定
分區的 outbox row。既有本機資料庫在 schema 變更後啟動會拋 `FlywayValidateException`，
這是預期行為，由 Migration Plan 的重建步驟解決，不以 `flyway repair` 略過。

**Acceptance criteria:**

1. 單元測試驗證 `partition-key-strategy=sku` 下，`OrderPlaced` 與 `OrderCancelled`
   的 outbox row `aggregateid` 為 `orderId`、`partition_key` 為 SKU；預設策略下兩者
   皆為 `orderId`。
2. 單元測試驗證 `OrderAllocated` 與 `BackorderCreated` 在任一策略下 `partition_key`
   皆為 `orderId`。
3. Outbox CDC 整合測試新增一個案例：寫入一筆 `aggregateid` 與 `partition_key` 刻意
   不同的 row，斷言 Debezium 發出的 Kafka record key 等於 `partition_key`、topic 等於
   `route`。缺少此案例則本次改動的核心行為未被驗證。
4. `PARTITION_KEY_STRATEGY=sku ./e2e/perf/run.sh up` 通過既有 k6 thresholds，且兩件事
   同時成立：Kafbat UI 中同一 SKU 的 `ordering.order-events` 訊息仍全部收斂於同一
   partition；`SELECT DISTINCT aggregateid FROM event_outbox WHERE aggregatetype='Order'`
   回傳的值全為 UUID，不含 SKU。
5. `./gradlew :order-promising:test` 與 `:order-promising:sit` 全數通過。

**Scope boundaries:** 涵蓋 outbox schema、`Outbox`／`OutboxDelivery`／`OutboxAppender`、
兩個 domain event translator、兩處 Debezium connector 設定、相關單元與整合測試，以及
`docs/stock-reservation-design.md` 中 `event_outbox` 欄位表、其後的說明段落與「Kafka
topics 與 partition key」表格的同步更新。不涵蓋 `GET /orders/{orderId}` 的事件時間軸
實作、任何 REST 端點、前端，以及 outbox 保留／清理政策。

## Risks / Trade-offs

- [只改一處 connector 設定，SIT 綠燈但壓測跑的是舊設定] → 兩處設定放進同一個 task
  完成，並在 Acceptance criteria 第 4 項用真實壓測跑一次 `sku` 策略核對 partition
  收斂，讓不一致無法通過驗收。
- [改既有 migration 造成本機資料庫 checksum 不符，開發者以 `flyway repair` 掩蓋] →
  Migration Plan 明列 `run.sh down`（`docker compose down -v`）重建步驟，並在
  Failure modes 明確禁止用 repair 略過。
- [`aggregateid` 回填問題] → 不適用。既有資料只存在於本機且會隨 volume 一併移除，
  不需要資料修正腳本；正式環境不存在。
- [`OutboxDelivery` 讓 `append` 簽章變動，波及所有呼叫端] → 呼叫端僅四處（兩個
  translator 各兩個 `@EventListener`）加測試 fixture 兩處，範圍封閉且編譯期即可
  全數暴露。
- [有人日後為了「一致性」把 `sku` 策略套到 `promising.allocation-events`] →
  在 `AllocationDomainEventTranslator` 的決策位置寫下理由註解，而非只留在設計文件。

## Migration Plan

1. 修改 `V5__create_event_inbox_and_outbox.sql`，於 `event_outbox` 的 `CREATE TABLE`
   加入 `partition_key VARCHAR(255) NOT NULL`。
2. 完成 production code、connector 設定與測試修改。
3. 執行 `./e2e/perf/run.sh down`（內含 `docker compose down -v`，移除 Postgres volume）
   拆除既有本機基礎設施——此步驟不可略過，否則下一次啟動會因 Flyway checksum
   不符而失敗。
4. 本機 dev 資料庫（若非由 `e2e/perf` 建立）同樣需重建。
5. `./gradlew :order-promising:test :order-promising:sit` 全綠。
6. `PARTITION_KEY_STRATEGY=sku ./e2e/perf/run.sh up` 重新建立基礎設施並跑一次壓測，
   核對 Acceptance criteria 第 4 項。

回滾：本次無正式環境部署，回滾即為 revert commit 後重複步驟 3。

## Open Questions

None.
