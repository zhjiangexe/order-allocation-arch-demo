## 1. Outbox schema 與傳輸欄位

- [ ] 1.1 實作 **Outbox rows separate aggregate identity from delivery metadata**：依「直接修改 `V5__create_event_inbox_and_outbox.sql`，不新增 migration」的決定，在 `event_outbox` 的 `CREATE TABLE` 加入 `partition_key VARCHAR(255) NOT NULL`，使每筆 outbox row 能同時表達所屬 aggregate 與 Kafka message key 兩件互不兼任的事。此為「新增 `partition_key` 欄位，而不是改用 payload 查詢或另建投影表」的落地。以乾淨資料庫啟動 dev profile 後 `\d event_outbox` 顯示該欄位、且 `./gradlew :order-promising:test` 通過驗證。
- [ ] 1.2 實作「以 `OutboxDelivery` record 承載兩個傳輸決定」：新增 `OutboxDelivery(String route, String partitionKey)`，`Outbox` record 加入 `partitionKey` 並納入既有 blank 檢查，`OutboxAppender.append(...)` 以 `OutboxDelivery` 取代原 `route` 參數位置，使呼叫端在簽章上就能區分領域參數與傳輸參數。行為上：`partitionKey` 為 null 或空字串時建構即拋 `IllegalArgumentException`，不允許寫入無法決定分區的 row。以 `Outbox` 的建構驗證單元測試與全專案編譯通過驗證。

## 2. Translator 的傳輸決策

- [ ] 2.1 實作 **Partition key strategy selects only the delivery key**：`OrderingDomainEventTranslator` 的 `aggregateId(orderId, sku)` 更名為 `partitionKey(orderId, sku)`（策略判斷邏輯不變），`aggregateid` 位置固定填入 `orderId`。行為上：`partition-key-strategy=sku` 時 `OrderPlaced`／`OrderCancelled` 的 row `aggregateid` 為 `orderId`、`partition_key` 為 SKU；預設策略下兩者皆為 `orderId`。以 `DomainEventTranslatorTest` 新增的雙策略案例驗證。
- [ ] 2.2 實作 **Allocation outcome events key by order identity**：依「`promising.allocation-events` 維持以 orderId 為 partition key」的決定，`AllocationDomainEventTranslator` 兩處明確以 `orderId` 作為 `partition_key`，不套用 `partition-key-strategy`，並在該決策位置以註解記錄理由（本 repo 無該 topic 的 consumer，套用 `sku` 策略屬投機性擴張）。以 `DomainEventTranslatorTest` 中「`sku` 策略下 `OrderAllocated`／`BackorderCreated` 的 `partition_key` 仍為 `orderId`」的案例驗證。

## 3. Debezium 設定與 CDC 行為

- [ ] 3.1 實作 **Debezium derives the Kafka message key from partition_key**：依「兩處 connector 設定必須逐字一致」的決定，在 `OutboxCdcIntegrationTest` 的 connector 設定與 `e2e/perf/kafka-connect/register-outbox-connector.sh` 同時加入 `transforms.outbox.table.field.event.key = partition_key`。行為上：Kafka record key 取自 `partition_key`、不再取自 `aggregateid`，topic 仍由 `route` 決定。以 `OutboxCdcIntegrationTest` 新增的案例驗證——寫入一筆 `aggregateid` 與 `partition_key` 刻意不同的 row，斷言發出的 record key 等於 `partition_key`、topic 等於 `route`；缺少此案例則本次改動的核心行為未被驗證。
- [ ] 3.2 更新 `OutboxCdcIntegrationTest` 既有 INSERT 與 `InboxRepoOutboxPersistenceIntegrationTest` 的 `new Outbox(...)`，使其提供 `partition_key`，讓既有 CDC 與持久化驗證在新 schema 下維持原有斷言意義。以 `./gradlew :order-promising:sit` 通過驗證。

## 4. 查詢能力與端到端驗收

- [ ] 4.1 驗證 **Outbox rows are queryable by aggregate identity**：以 `partition-key-strategy=sku` 建立一張經歷 placed → backordered → allocated 的訂單，確認 `WHERE aggregatetype = 'Order' AND aggregateid = ?` 依 `timestamp` 回傳該訂單的全部三筆 Integration Event，且查詢不依賴 payload 欄位名或當前策略。以 SIT 中的持久化狀態斷言驗證（此查詢條件即 `add-demo-console-api` 的事件時間軸所依賴者）。
- [ ] 4.2 重建本機基礎設施並重跑壓測：執行 `./e2e/perf/run.sh down` 移除既有 Postgres volume（不可略過，否則 Flyway checksum 不符會導致啟動失敗，且不得以 `flyway repair` 掩蓋），再以 `PARTITION_KEY_STRATEGY=sku ./e2e/perf/run.sh up` 重建並跑一次。行為上：既有 k6 thresholds 全數通過，代表無行為變更。以兩項核對驗證——Kafbat UI 中同一 SKU 的 `ordering.order-events` 訊息仍收斂於同一 partition；`SELECT DISTINCT aggregateid FROM event_outbox WHERE aggregatetype='Order'` 回傳值全為 UUID、不含 SKU。

## 5. 文件同步

- [ ] 5.1 更新 `docs/stock-reservation-design.md`：`event_outbox` 欄位表拆出 `aggregateid`（aggregate identity）與 `partition_key`（Kafka message key）兩列；其後說明段落把「避免將 Aggregate 欄位改作傳輸用途」的規則明確寫成同時適用於 `aggregateid`；「Kafka topics 與 partition key」表格的 Message key 欄註明對應 `partition_key`。以文件審閱確認三處與實作一致、且不再有任何段落將 `aggregateid` 描述為 message key。
- [ ] 5.2 於 `docs/superpowers/specs/2026-07-26-v3-single-writer-design.md` 描述 `aggregateId` → Kafka key 流程的段落加註 superseded，指向本 change，使後續讀者不會依舊文件重建已被取代的路徑。以文件審閱驗證。
