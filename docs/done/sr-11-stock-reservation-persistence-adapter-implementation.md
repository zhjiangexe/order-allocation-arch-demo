# SR-11 StockReservation Persistence Adapter 實作紀錄

狀態：已完成

完成日期：2026-07-24

## 實作範圍

SR-11 將 SR-02 的 `StockReservation` domain model 接到 PostgreSQL persistence；未提前建立 SR-05 的 allocation transaction、SR-06 release Usecase 或 SR-12 Inbox／Outbox adapters。

- 新增 `StockReservationRepository` domain port。
- 新增 JPA entity、typed mapper、Spring Data repository 與 persistence adapter。
- 新增 `@Version`，保留 reservation 的 optimistic-lock conflict detection。
- 新增 `stock_reservations` Flyway migration，包含 `order_id` unique、Order／StockPool foreign keys、quantity、status 與 released-state constraints。
- 實作 `findActiveByOrderId()`，只還原 `ACTIVE` reservation。
- 新增 mapper unit tests 與 PostgreSQL repository SIT。

## 資料庫 Schema

### `../../order-promising/src/main/resources/db/migration/V4__create_stock_reservations.sql`

建立 `stock_reservations` table：

| 欄位 | PostgreSQL 型別 | 限制 |
|---|---|---|
| `id` | `UUID` | Primary key |
| `order_id` | `UUID` | `NOT NULL`、unique、FK to `orders(id)` |
| `stock_pool_id` | `BIGINT` | `NOT NULL`、FK to `stock_pools(id)` |
| `quantity` | `INTEGER` | `NOT NULL`、`> 0` |
| `status` | `VARCHAR(32)` | 僅允許 `ACTIVE`／`RELEASED` |
| `reserved_at` | `TIMESTAMPTZ` | `NOT NULL` |
| `released_at` | `TIMESTAMPTZ` | `ACTIVE` 時必須為 null；`RELEASED` 時必須存在且不早於 `reserved_at` |
| `version` | `BIGINT` | `NOT NULL DEFAULT 0`，JPA optimistic locking |

命名 constraints：

- `uq_stock_reservations_order_id`
- `fk_stock_reservations_order`
- `fk_stock_reservations_stock_pool`
- `ck_stock_reservations_quantity_positive`
- `ck_stock_reservations_status`
- `ck_stock_reservations_released_state`

`order_id` unique 表達目前每張 Order 最多一筆 reservation；沒有在 reservation 重複保存 SKU，仍透過 `stock_pool_id` 關聯 StockPool。

## 變更檔案

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/domain/repository/StockReservationRepository.java`

- 新增 domain persistence port。
- `save()` 保存 aggregate state。
- `findActiveByOrderId()` 以 `Optional` 忠實表達查無 active reservation 的結果。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/entity/StockReservationEntity.java`

- 明確映射 `stock_reservations` table 與 snake_case columns。
- 將 `ReservationStatus` 以 `EnumType.STRING` 保存，避免 enum ordinal 與資料庫值耦合。
- 使用 `UUID` reservation／order identity、`Long` stock-pool identity 與 `Instant` lifecycle timestamps。
- 保留 `@Version Long version`，由 Hibernate 驗證並遞增版本。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/mapper/StockReservationMapper.java`

- `toEntity()` 映射完整 reservation state，包括 RELEASED 時的 `releasedAt` 與 version。
- `toDomain()` 透過 `StockReservation.rehydrate(...)` 還原 aggregate，因此 persistence state 仍經過 domain invariant 驗證。
- mapper 為不可實例化的 stateless utility class。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/repository/jpa/JpaStockReservationRepository.java`

- 以 `JpaRepository<StockReservationEntity, UUID>` 提供 typed JPA access。
- `findByOrderIdAndStatus(...)` 供 adapter 實作 active-reservation query。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/infrastructure/repository/StockReservationRepositoryImpl.java`

- `save()` 以 mapper 將 domain aggregate 交給 JPA persist／merge。
- `findActiveByOrderId()` 固定以 `ReservationStatus.ACTIVE` 查詢，再映射回 domain；不將缺少結果轉成 application exception。

### `../../order-promising/src/sit/java/com/flowzati/archone/DatabaseFoundationIntegrationTest.java`

Flyway migration smoke test 更新為驗證 V1～V4 都已套用，讓 database foundation test 持續代表完整 schema 基線。

### `../stock-reservation-design.md`

- SR-11 checkbox 更新為完成。
- 整體進度更新為 `8 / 17`。
- SR-16 的 SR-09～SR-11 dependency 已滿足，因此列入可立即執行項目。

## 新增測試

### `../../order-promising/src/test/java/com/flowzati/archone/allocation/infrastructure/mapper/StockReservationMapperTest.java`

新增 2 個 unit tests：

- 完整 `RELEASED` domain reservation 映射至 entity。
- `ACTIVE` entity 還原為同一 lifecycle state 與 version 的 domain reservation。

### `../../order-promising/src/sit/java/com/flowzati/archone/allocation/infrastructure/repository/StockReservationPersistenceIntegrationTest.java`

新增 12 個 PostgreSQL SIT cases：

- 寫入／還原完整 ACTIVE reservation 與初始 version。
- RELEASED state 保存後，`releasedAt` 正確還原且 version 遞增。
- `findActiveByOrderId()` 只回傳 ACTIVE reservation，查無時回傳 empty Optional。
- stale reservation 寫回時由 optimistic locking 拒絕。
- `order_id` unique constraint 拒絕第二筆 reservation。
- quantity check 拒絕 0 與負數。
- status 與 released-state constraints 拒絕非法 lifecycle data。
- 兩個 foreign keys 分別拒絕不存在的 Order 與 StockPool reference。

PostgreSQL 在同一 transaction 遇到 constraint violation 後會標記 transaction aborted，因此每個預期失敗的 constraint case 都設計成獨立 test transaction，避免第二個 assertion 只得到 aborted-transaction 錯誤。

## 驗證結果

執行 unit tests：

```bash
./gradlew :order-promising:test
```

結果：

```text
BUILD SUCCESSFUL
80 unit tests completed
```

執行 PostgreSQL SIT：

```bash
./gradlew :order-promising:sit
```

結果：

```text
BUILD SUCCESSFUL
30 SIT tests completed
```

執行完整 verification lifecycle：

```bash
./gradlew :order-promising:check
```

結果：`BUILD SUCCESSFUL`。

驗證內容：

- Java production、unit test 與 SIT source 均成功編譯。
- Flyway V1～V4 成功套用並通過 validation。
- PostgreSQL Testcontainer 驗證 reservation mapping、repository query、optimistic locking 與所有 SR-11 schema constraints。
- 既有 domain／application unit tests 與所有 persistence SIT 全部通過。

## 後續任務注意事項

- SR-05 負責在 allocation 成功時建立並保存 `StockReservation`；SR-11 只提供 persistence adapter。
- SR-06／SR-14 才將 reservation release、StockPool、Order 與 application transaction 串接。
- SR-15 才在 application retry boundary 處理 `@Version` conflict；repository 不會將其轉換為 ATP 不足。
- SR-16 建立部分 reservation seed data 時，必須同時建立對應的 ALLOCATED Order、ACTIVE StockReservation 與 StockPool reserved quantity。
