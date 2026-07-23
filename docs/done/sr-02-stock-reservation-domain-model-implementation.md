# SR-02 StockReservation Domain Model 實作紀錄

狀態：已完成

完成日期：2026-07-23

## 實作範圍

SR-02 只建立 StockReservation aggregate 與對應的 pure domain unit tests，不提前實作 repository port、JPA entity、Flyway migration、allocation application flow 或 cancellation handler。

- 新增 `StockReservation` aggregate。
- 新增 `ReservationStatus.ACTIVE/RELEASED`。
- 使用 `create()` 建立 ACTIVE reservation。
- 使用 `release()` 表達 ACTIVE 到 RELEASED 的狀態轉換。
- 重複 release 為冪等 no-op，不會覆寫第一次的 `releasedAt`。
- 建立、還原與釋放均會驗證 identity、quantity、status 與 lifecycle timestamp invariants。

## 新增檔案

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/domain/model/ReservationStatus.java`

定義目前 bounded context 支援的兩種 reservation 狀態：

- `ACTIVE`：庫存仍保留給訂單。
- `RELEASED`：訂單取消後已釋放保留量。

本任務不加入 `CONSUMED`、expiration 或其他尚未定義的狀態。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/domain/model/StockReservation.java`

Aggregate 保留設計文件已定義的 domain state：

| 欄位 | 說明 |
|---|---|
| `id` | reservation 的獨立 UUID identity |
| `orderId` | 被保留庫存的 Order identity |
| `stockPoolId` | 保留量所屬 StockPool identity |
| `quantity` | 必須大於零的保留數量 |
| `status` | `ACTIVE` 或 `RELEASED` |
| `reservedAt` | 建立 reservation 的時間 |
| `releasedAt` | 只有 RELEASED 狀態才有值 |
| `version` | 預留給 SR-11 persistence adapter 的 optimistic-lock snapshot；新建 aggregate 為 `null` |

Domain API：

```java
StockReservation.create(id, orderId, stockPoolId, quantity, reservedAt);
reservation.release(releasedAt);
StockReservation.rehydrate(
    id,
    orderId,
    stockPoolId,
    quantity,
    status,
    reservedAt,
    releasedAt,
    version
);
```

`create()` 與 `rehydrate()` 分開，避免一般建立流程直接指定 `RELEASED`、`releasedAt` 或 persistence version。

## Domain Invariants

建立或還原 StockReservation 時必須符合：

- `id` 不得為 `null`。
- `orderId` 不得為 `null`。
- `stockPoolId` 不得為 `null`。
- `quantity > 0`。
- `status` 不得為 `null`。
- `reservedAt` 不得為 `null`。
- ACTIVE reservation 的 `releasedAt` 必須為 `null`。
- RELEASED reservation 的 `releasedAt` 必須有值。
- `releasedAt` 不得早於 `reservedAt`。

`StockReservation` 不重複管理 StockPool 的 `reservedQuantity <= onHandQuantity` invariant；實際保留與釋放數量仍由 `StockPool.tryReserve()` 與 `StockPool.release()` 負責。

## 狀態轉換與冪等性

```text
建立成功：ACTIVE
ACTIVE   ── release(releasedAt) ──> RELEASED
RELEASED ── release(...)          ──> no-op
```

`release()` 回傳值明確表達是否發生轉換：

- 第一次從 ACTIVE 釋放回傳 `true`。
- 已是 RELEASED 時回傳 `false`，並保留原本的 `releasedAt`。
- `releasedAt == null` 或早於 `reservedAt` 時拒絕轉換，aggregate 保持 ACTIVE。

## 新增測試

### `../../order-promising/src/test/java/com/flowzati/archone/allocation/domain/model/StockReservationTest.java`

新增 11 個 unit test cases：

- 建立後為 ACTIVE，且 identity、quantity 與 `reservedAt` 完整保留。
- 第一次 release 改為 RELEASED 並設定 `releasedAt`。
- 重複 release 為 no-op，不覆寫原釋放時間。
- 拒絕早於 `reservedAt` 的釋放時間，且失敗後仍為 ACTIVE。
- 拒絕 `null` 釋放時間。
- 拒絕零或負數 quantity。
- 拒絕缺少 reservation ID、Order ID、StockPool ID 或 `reservedAt`。
- 拒絕 ACTIVE 搭配非空 `releasedAt` 的非法狀態。
- 拒絕 RELEASED 缺少 `releasedAt` 或 timestamp 順序錯誤的非法狀態。
- 可還原包含 version 的完整 RELEASED reservation。

## 未在 SR-02 實作的項目

- StockReservation repository port。
- JPA entity、mapper 與 repository adapter。
- `stock_reservations` migration、unique constraint 或 foreign keys。
- Order allocation 成功時建立 reservation。
- Order cancellation 時連動 `StockPool.release()`。
- Inbox、Outbox、transaction boundary 與 optimistic-lock retry。

上述項目分別留給 SR-05、SR-06、SR-11、SR-14 與 SR-15。

## 驗證結果

執行 StockReservation domain tests：

```bash
./gradlew :order-promising:test \
  --tests 'com.flowzati.archone.allocation.domain.model.StockReservationTest'
```

結果：`11 tests completed`，`BUILD SUCCESSFUL`。

執行完整 verification lifecycle：

```bash
./gradlew :order-promising:check
```

結果：

```text
BUILD SUCCESSFUL
47 unit tests completed
11 SIT tests completed
```

驗證內容：

- 新增 domain model 與 unit tests 成功編譯。
- StockReservation 建立、釋放、冪等釋放與非法狀態全部通過。
- 既有 StockPool、allocation 與 replenishment unit tests 全部通過。
- SR-08／SR-09 PostgreSQL、Flyway、repository 與 optimistic-lock SIT 全部通過。

## 後續任務注意事項

- SR-04 負責將建立 reservation 納入 strict allocation domain policy。
- SR-05 負責在 allocation application flow 中建立 ACTIVE StockReservation。
- SR-06 負責將釋放 reservation 與 `StockPool.release(quantity)` 放入同一個 use case transaction。
- SR-11 應使用 `rehydrate()` 還原 persistence state，並以 `@Version` 維護 optimistic locking。
- 一張 Order 只能有一筆 reservation 仍由 SR-11 database unique constraint 與 SR-05 application flow 共同保護。
