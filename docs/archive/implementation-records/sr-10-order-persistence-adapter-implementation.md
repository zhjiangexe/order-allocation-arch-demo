# SR-10 Order Persistence Adapter 實作紀錄

狀態：已完成

完成日期：2026-07-23

## 實作範圍

SR-10 將 SR-03 的 Order aggregate 接到 PostgreSQL persistence，並提供補貨流程需要的穩定 FIFO backorder query；未提前建立 StockReservation、Inbox 或 Outbox persistence。

- 建立 Order JPA entity、typed mapper 與 repository adapter。
- 完整持久化 Order lifecycle timestamps 與 `version`。
- 將原本語意不正確的 `getPendingBySku()` port 收斂為只讀取 `BACKORDERED` orders。
- 以 `backorderedSince ASC, id ASC` 提供時間相同時仍可重現的穩定 FIFO。
- 新增 `orders` Flyway migration、正數 quantity constraint 與 FIFO composite index。
- 新增 mapper unit tests 與真實 PostgreSQL repository SIT。

## 資料庫 Schema

### `../../order-promising/src/main/resources/db/migration/V3__create_orders.sql`

建立 `orders` table：

| 欄位 | PostgreSQL 型別 | 限制 |
|---|---|---|
| `id` | `UUID` | Primary key |
| `sku` | `VARCHAR(255)` | `NOT NULL` |
| `quantity` | `INTEGER` | `NOT NULL`, `> 0` |
| `status` | `VARCHAR(32)` | `NOT NULL` |
| `placed_at` | `TIMESTAMPTZ` | `NOT NULL` |
| `allocated_at` | `TIMESTAMPTZ` | nullable |
| `backordered_since` | `TIMESTAMPTZ` | nullable |
| `cancelled_at` | `TIMESTAMPTZ` | nullable |
| `version` | `BIGINT` | `NOT NULL DEFAULT 0` |

新增命名 constraint：

- `ck_orders_quantity_positive`

新增 stable FIFO index：

```sql
CREATE INDEX idx_orders_backorder_fifo
    ON orders (sku, status, backordered_since, id);
```

查詢先以 `sku` 與 `status` 篩選，再以 `backordered_since` 排序；`id` 是相同時間的 deterministic tie-breaker。

## 新增檔案

### `../../order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/entity/OrderEntity.java`

- 明確映射至 `orders` table 與 snake_case timestamp columns。
- `id` 沿用 aggregate 的 `UUID`，不由 JPA 另行產生。
- `status` 以 `EnumType.STRING` 保存，避免 enum ordinal 變動破壞既有資料。
- 以 `@Version Long version` 提供 optimistic locking。
- 只有 persistence constructor 與 getters，不對外提供 setters。

### `../../order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/mapper/OrderMapper.java`

- `toEntity()` 完整映射 ID、SKU、quantity、status、四個 lifecycle timestamps 與 version。
- `toDomain()` 統一呼叫 `Order.rehydrate(...)`，由 aggregate 驗證還原資料是否符合 lifecycle invariants。
- rehydrate 不產生 Domain Event，讀取 persistence 不會被誤認為新業務行為。

### `../../order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/repository/OrderRepositoryImpl.java`

- `save()` 將 domain version 帶入 detached entity，再交由 JPA persist／merge。
- `findById()` 經由 mapper 還原 aggregate，並以 `Optional<Order>` 忠實表達查詢可能沒有結果。
- adapter 不決定「找不到 Order」的業務處理；目前由 `AllocateOrderUsecase` 選擇拋出 `IllegalStateException`。
- `findBackordersBySkuInFifoOrder()` 固定加入 `OrderStatus.BACKORDERED` 篩選並映射結果。

### `../../order-promising/src/main/java/com/flowzati/archone/ordering/infrastructure/repository/jpa/JpaOrderRepository.java`

Spring Data query 同時指定：

```text
WHERE sku = ? AND status = BACKORDERED
ORDER BY backordered_since ASC, id ASC
```

因此同一秒或完全相同 timestamp 的 orders 不依賴 PostgreSQL 未定義的自然回傳順序。

## 變更檔案

### `../../order-promising/src/main/java/com/flowzati/archone/ordering/domain/repository/OrderRepository.java`

- 移除 `getPendingBySku()`。
- 新增 `findBackordersBySkuInFifoOrder()`，以業務語言表達 backorder 集合與 FIFO contract，不暴露 persistence 欄位及排序方向。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/application/usecase/ReplenishmentUsecase.java`

- 改用 stable FIFO repository port。
- 補貨流程現在只會載入真正的 `BACKORDERED` orders，不再以 `PENDING` 命名模糊代表等待補貨。

### `../../order-promising/src/test/java/com/flowzati/archone/allocation/application/usecase/ReplenishmentUsecaseTest.java`

- fixtures 改為先由 `PENDING` 合法轉成 `BACKORDERED`。
- mocks 與 verifications 改用新的 FIFO port。
- 庫存不足而未分配的 order 明確保持 `BACKORDERED`。

### `../../order-promising/src/sit/java/com/flowzati/archone/DatabaseFoundationIntegrationTest.java`

- migration smoke test 更新為依序驗證 V1、V2、V3。
- Flyway validation 仍必須成功。

### `../stock-reservation-design.md`

- SR-10 checkbox 更新為完成。
- 整體進度更新為 `6 / 17`。
- SR-10 完成後，SR-11 的 persistence 相依條件已滿足。

## 新增測試

### `../../order-promising/src/test/java/com/flowzati/archone/ordering/infrastructure/mapper/OrderMapperTest.java`

新增 2 個 unit tests：

- Domain 到 entity 的完整 lifecycle state 與 version mapping。
- Entity 到 domain 以 `rehydrate(...)` 還原 cancelled state，且不產生 Domain Event。

### `../../order-promising/src/sit/java/com/flowzati/archone/ordering/infrastructure/repository/OrderPersistenceIntegrationTest.java`

使用 Spring Data JPA slice、Flyway 與 SR-08 的 PostgreSQL Testcontainer，新增 7 個 SIT cases：

- 寫入並讀回完整 BACKORDERED Order 與初始 version。
- 找不到 Order 時回傳 empty `Optional`，不由 adapter 拋出例外。
- 只查指定 SKU 的 BACKORDERED orders。
- 相同 `backordered_since` 時以 UUID `id ASC` 穩定排序。
- stale version 寫回時由 optimistic locking 拒絕。
- quantity 為 `0` 或負數時由 database constraint 拒絕。
- migration 確實建立 `(sku, status, backordered_since, id)` index。

測試只啟用 `JpaOrderRepository`，避免提前載入仍屬於 SR-12 的 raw Inbox／Outbox repositories。

## `version` 與 ID 行為

- 新 Order 的 UUID 仍由 application 邊界產生並傳入 aggregate；JPA entity 不重複生成 ID。
- Domain、JPA 與 PostgreSQL 都直接使用 UUID，不轉成 String。
- 新 entity 的 version 可為 `null`，由 Hibernate 初始化為 `0`。
- rehydrate 後的 version 會在寫回時參與 optimistic-lock compare-and-increment。

## 驗證結果

執行 unit tests：

```bash
./gradlew :order-promising:test
```

結果：

```text
BUILD SUCCESSFUL
67 unit tests completed
```

執行 PostgreSQL SIT：

```bash
./gradlew :order-promising:sit
```

結果：

```text
BUILD SUCCESSFUL
18 SIT tests completed
```

完整 verification lifecycle：

```bash
./gradlew :order-promising:check
```

結果：`BUILD SUCCESSFUL`。`check` 已確認同時涵蓋 unit tests 與 SIT。

## 後續任務注意事項

- SR-11 可讓 `stock_reservations.order_id` foreign key 直接參照 `orders(id)`。
- SR-14 才負責將 Order、StockPool、Reservation 與 Inbox／Outbox 寫入置於同一 application transaction。
- SR-15 才加入 optimistic-lock bounded retry；SR-10 只提供並驗證 version conflict detection。
- SR-04／SR-07 負責嚴格 FIFO 的 head-of-line blocking policy；SR-10 只保證 repository 輸入順序穩定，不決定 allocation 是否跳單。
