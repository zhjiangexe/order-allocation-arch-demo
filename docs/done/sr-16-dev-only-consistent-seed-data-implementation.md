# SR-16 Dev-only consistent seed data 實作紀錄

狀態：已完成

完成日期：2026-07-24

## 實作範圍

SR-16 建立只在 `dev` profile 載入的示範資料，供本機手動驗證 ATP、保留與取消流程使用。

- 使用固定 UUID，讓資料可辨識且每次啟動可安全重跑。
- 使用單一 transaction 建立所有資料；不透過 use case 發送 Domain Event，因此不會在 dev 啟動時產生 Inbox 或 Outbox message。
- 不在 `test` 或未啟用 `dev` 的 profile 載入。

## 新增檔案

### `../../order-promising/src/main/java/com/flowzati/archone/bootstrap/DevSeedDataInitializer.java`

- 使用 `@Component`、`@Profile("dev")` 與 `ApplicationRunner`，只在 dev application startup 執行。
- `run()` 為 `@Transactional`，防止初始化途中失敗後留下部分資料。
- 以 SKU 與固定 Order ID 檢查是否已建立，重複執行不新增重複 row。
- 建立以下資料：

| SKU | onHandQuantity | reservedQuantity | 關聯資料 |
| --- | ---: | ---: | --- |
| `SKU-AVAILABLE` | 10 | 0 | 無 reservation |
| `SKU-EMPTY` | 0 | 0 | 無 reservation |
| `SKU-PARTIALLY-RESERVED` | 20 | 5 | 一筆 `ALLOCATED` Order 與一筆 quantity 5 的 `ACTIVE` StockReservation |

最後一組的 Order、StockPool 與 Reservation 使用對應固定 UUID，確保 `reservedQuantity` 有真實 reservation 支撐，而不是孤立數字。

### `../../order-promising/src/sit/java/com/flowzati/archone/bootstrap/DevSeedDataIntegrationTest.java`

- 使用 PostgreSQL Testcontainers 與 `dev` profile 啟動完整 application context。
- 驗證三組 StockPool 數量、部分保留的 ALLOCATED Order 與 ACTIVE Reservation 一致。
- 在同一資料庫再次執行 initializer，確認仍為 3 個 StockPools、1 個 Order、1 個 Reservation。

### `../../order-promising/src/sit/java/com/flowzati/archone/bootstrap/DevSeedDataProfileIntegrationTest.java`

- 使用 `test` profile 啟動完整 application context。
- 驗證 `DevSeedDataInitializer` bean 不存在，且沒有建立 StockPool seed row。

## 變更檔案

### `../stock-reservation-design.md`

- SR-16 checkbox 更新為完成。
- 整體進度更新為 `15 / 17`；可立即執行項目更新為 SR-15。

## 驗證結果

執行 SR-16 聚焦 SIT：

```bash
./gradlew :order-promising:sit \
  --tests '*DevSeedData*IntegrationTest' \
  --no-daemon
```

結果：`BUILD SUCCESSFUL`，共 `2 tests completed`。

完整驗證：

```bash
./gradlew :order-promising:test --no-daemon
./gradlew :order-promising:sit --rerun-tasks --no-daemon
```

結果：`BUILD SUCCESSFUL`，共 `96 unit tests completed` 與 `40 SIT tests completed`。

驗證內容：

- dev profile 只建立預期的三組 StockPool seed data。
- 部分保留資料的 Order、Reservation 與 StockPool 數量互相一致。
- 再次執行 initializer 不會重複新增資料。
- test profile 沒有 initializer bean，也不會灌入 seed data。

## 開發環境注意事項

- seed data 只在啟動時啟用 `dev` profile 才會建立，例如 `SPRING_PROFILES_ACTIVE=dev`。
- seed 使用既有 dev datasource 與 Flyway 設定；不會建立測試／production 專用 datasource 或修改 migration。
- seed 資料是手動驗證用途，不應被當作正式交易資料；不會透過 Kafka、Inbox 或 Outbox 發送事件。
