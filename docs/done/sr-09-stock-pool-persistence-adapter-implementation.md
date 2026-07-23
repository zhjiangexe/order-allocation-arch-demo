# SR-09 StockPool Persistence Adapter 實作紀錄

狀態：已完成

完成日期：2026-07-23

## 實作範圍

SR-09 將 SR-01 的 StockPool ATP domain model 接到 PostgreSQL persistence，未提前建立 Order、StockReservation、Inbox 或 Outbox persistence。

- 將 JPA entity 從 legacy `available` 改為 `onHandQuantity` 與 `reservedQuantity`。
- 保留並驗證 JPA optimistic locking `@Version`。
- 新增由 Hibernate 維護的 `updatedAt`。
- 移除 reflection mapper 與 SR-01 的 deprecated compatibility bridge。
- 新增 `stock_pools` Flyway migration 與 PostgreSQL constraints。
- 預設 profile 關閉 Flyway，只有 dev／SIT 明確啟用，避免 production startup 自動執行 V2 DDL。
- 新增 mapper unit tests 與真實 PostgreSQL repository SIT。

## 資料庫 Schema

### `../../order-promising/src/main/resources/db/migration/V2__create_stock_pools.sql`

建立 `stock_pools` table：

| 欄位 | PostgreSQL 型別 | 限制 |
|---|---|---|
| `id` | `BIGINT` | Primary key |
| `sku` | `VARCHAR(255)` | `NOT NULL`, unique |
| `on_hand_quantity` | `INTEGER` | `NOT NULL`, `>= 0` |
| `reserved_quantity` | `INTEGER` | `NOT NULL DEFAULT 0`, `>= 0` |
| `version` | `BIGINT` | `NOT NULL DEFAULT 0` |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` |

命名 constraints：

- `uq_stock_pools_sku`
- `ck_stock_pools_on_hand_non_negative`
- `ck_stock_pools_reserved_non_negative`
- `ck_stock_pools_reserved_not_above_on_hand`

ATP 不存入資料庫，仍由 domain 以 `onHandQuantity - reservedQuantity` 即時計算。

## 變更檔案

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/entity/StockPoolEntity.java`

- 明確映射到 `stock_pools` table 與 snake_case columns。
- 將 legacy `available` 替換為 `onHandQuantity`、`reservedQuantity`。
- 保留 `@Version Long version`。
- 新增 `@UpdateTimestamp Instant updatedAt`；insert 與每次 Hibernate update 都會更新。
- 使用明確 constructor 與 getters，不再由 mapper 反射修改 private fields。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/mapper/StockPoolMapper.java`

- 移除 reflection mapping。
- `toEntity()` 完整映射 ID、SKU、on-hand、reserved 與 version。
- `toDomain()` 使用 SR-01 的五參數 constructor 還原完整 ATP 基礎量與 version。
- mapper 改為不可實例化的 stateless utility class。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/repository/StockPoolRepositoryImpl.java`

- `findById()` 與 `findBySku()` 都經由 typed mapper 還原 domain model。
- `save()` 將 domain version 帶回 detached entity，再交由 JPA merge／persist；Hibernate 以 `@Version` 防止 stale update。
- 移除原本「暫時維持」與 reflection mapping 的註解。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/domain/model/StockPool.java`

SR-09 已提供完整 persistence mapping，因此移除 SR-01 暫留的：

- 四參數 legacy constructor。
- `getAvailable()`。
- legacy `available` null validation helper。

production compile 不再產生 legacy mapper removal warnings。

### `../../order-promising/src/sit/java/com/flowzati/archone/DatabaseFoundationIntegrationTest.java`

- migration smoke test 從只期待 V1，更新為依序驗證 V1、V2 都已套用。
- Flyway validation 仍必須成功。

### Flyway profile 設定

- `../../order-promising/src/main/resources/application.properties`：`spring.flyway.enabled=false`。
- `../../order-promising/src/main/resources/application-dev.properties`：`spring.flyway.enabled=true`。
- `../../order-promising/src/sit/resources/application-test.properties`：`spring.flyway.enabled=true`。

因此 production 不會因 application startup 自動執行 migration；未來若部署 production，應由獨立 migration job 或明確的部署設定啟用。

### `../stock-reservation-design.md`

- SR-09 checkbox 更新為完成。
- 整體進度更新為 `3 / 17`。

## 新增測試

### `../../order-promising/src/test/java/com/flowzati/archone/allocation/infrastructure/mapper/StockPoolMapperTest.java`

新增 2 個 unit tests：

- Domain 到 entity 的 quantities 與 version mapping。
- Entity 到 domain 的 quantities、derived ATP 與 version mapping。

### `../../order-promising/src/sit/java/com/flowzati/archone/allocation/infrastructure/repository/StockPoolPersistenceIntegrationTest.java`

使用 Spring Data JPA slice、Flyway 與 SR-08 的 PostgreSQL Testcontainer，展開為 9 個 SIT cases：

- 透過 SKU 與 ID 寫入／讀回完整 StockPool。
- `tryReserve()` 後儲存會更新 quantities、`updatedAt` 與 `version`。
- `release()` 後儲存會更新 quantities、`updatedAt` 與 `version`。
- `replenish()` 後儲存會更新 quantities、`updatedAt` 與 `version`。
- stale version 寫回時由 optimistic locking 拒絕。
- duplicate SKU 由 unique constraint 拒絕。
- 負數 on-hand 由 check constraint 拒絕。
- 負數 reserved 由 check constraint 拒絕。
- reserved 超過 on-hand 由 check constraint 拒絕。

測試只啟用 `JpaStockRepository`，避免提前載入仍屬於 SR-12 的 raw `InboxStore`。

## `updatedAt` 與 `version` 行為

Domain mutation 本身不管理 infrastructure timestamp：

```text
StockPool.tryReserve／release／replenish
              │
              ▼
StockPoolRepository.save
              │
              ▼
Hibernate UPDATE
       ├─ @UpdateTimestamp 更新 updated_at
       └─ @Version 驗證並遞增 version
```

SIT 會先將 `updated_at` 設為固定舊時間，再逐一執行三種 mutation 並 flush，確認 timestamp 變新且 version 增加一。這避免依賴短暫 `sleep` 的不穩定測試。

## 驗證結果

執行 unit tests：

```bash
./gradlew :order-promising:test
```

結果：

```text
BUILD SUCCESSFUL
29 unit tests completed
```

執行 PostgreSQL SIT：

```bash
./gradlew :order-promising:sit
```

結果：

```text
BUILD SUCCESSFUL
11 SIT tests completed
```

執行完整 verification lifecycle：

```bash
./gradlew :order-promising:check
```

結果：`BUILD SUCCESSFUL`。

## 過程中修正的測試問題

- 新增 V2 後，SR-08 migration smoke test 的最新版從 V1 變成 V2，因此改為明確驗證已套用的完整版本序列。
- PostgreSQL JDBC 無法自行推斷 `Instant` query parameter，SIT 改用 `Timestamp.from(instant)` 設定舊基準時間。

這兩項都是測試基礎的相容調整，未改變 production business behavior。

另外，`sit` Gradle task 會在沒有既有 `DOCKER_HOST` 且 `/var/run/docker.sock` 不存在時，自動偵測 OrbStack／Docker Desktop user socket，因此本機不再需要每次手動設定環境變數。

## 後續任務注意事項

- SR-11 建立 `stock_reservations` foreign key 時，可直接參照 `stock_pools(id)`。
- SR-14 才負責將 StockPool、Order、Reservation 與 Inbox／Outbox 放入同一個 application transaction。
- SR-15 才加入 optimistic-lock bounded retry；SR-09 只提供並驗證 version 機制。
- `updatedAt` 屬於 persistence metadata，不加入 StockPool domain model。
