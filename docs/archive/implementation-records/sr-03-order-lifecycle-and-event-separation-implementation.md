# SR-03 Order Lifecycle 與事件分層實作紀錄

狀態：已完成

完成日期：2026-07-23

## 實作範圍

SR-03 補齊 Order Aggregate lifecycle，並將原本混用的 Domain Event 與 Integration Event 明確分離。

- Order Aggregate 以具名行為維護狀態，不提供通用 setter。
- 新建 Aggregate 時由 application layer 傳入 UUID 與時間。
- persistence mapper 未來可透過 `rehydrate(...)` 還原狀態，且不產生新事件。
- Domain Event 是 bounded context 內部業務事實，不含 `eventId`。
- Integration Event 是跨 context／process 契約，具有 immutable `eventId` 與完整 payload。
- 下單與建立欠單流程目前由 application use case 明確建立並同步發布 Integration Event；transactional Outbox mapping 留給 SR-12。

## 事件分層

```text
Aggregate behavior
  -> Domain Event（內部、沒有 eventId）
  -> Application orchestration
  -> Integration Event（跨邊界、有 eventId）
  -> Outbox（SR-12）
```

### Domain Events

新增 immutable Java records：

- `OrderPlaced`
- `OrderAllocated`
- `OrderBackordered`
- `OrderCancelled`

`DomainEvent` 改為 marker interface，不再包含可變的 messaging identity。

### Integration Events

新增 `IntegrationEvent` base class，`eventId` 為 `private final UUID`，只能由 constructor 傳入，沒有 setter。

Ordering integration contracts：

- `OrderPlacedIntegrationEvent(eventId, orderId, sku, quantity, placedAt)`
- `OrderCancelledIntegrationEvent(eventId, orderId, cancelledAt)`

Allocation integration contracts：

- `OrderAllocatedIntegrationEvent(eventId, orderId, reservationId, sku, quantity, allocatedAt)`
- `BackorderCreatedIntegrationEvent(eventId, orderId, sku, quantity, backorderedSince)`
- `StockReplenishedIntegrationEvent(eventId, sku, quantity)`

舊的空白 Domain Event implementations 已由具 payload 的 immutable records 取代；舊的 allocation domain `StockReplenished` 與未使用的 `StockAllocated` 亦已移除。Domain Event 使用純業務名稱，Integration Event 類別統一以 `IntegrationEvent` 結尾。

## Order Aggregate

### 建立與還原

- `Order.place(id, sku, quantity, placedAt)` 建立 `PENDING` Order 並記錄 `OrderPlaced`。
- `Order.rehydrate(...)` 驗證並還原 persistence state，不記錄 Domain Event。
- `id`、`sku`、`quantity`、`placedAt` 與 `version` 沒有 setter。

### 行為與狀態轉換

- `PENDING -> ALLOCATED`
- `PENDING -> BACKORDERED`
- `BACKORDERED -> ALLOCATED`
- `PENDING | BACKORDERED | ALLOCATED -> CANCELLED`
- 重複取消為 idempotent no-op，回傳 `false` 且不重複產生事件。
- 不合法的重複配置、欠單轉換與逆向 lifecycle timestamps 會被拒絕。

### State invariants

- Order ID、SKU、status 與 placed time 必填。
- quantity 必須大於零，version 不可為負數。
- transition timestamp 不可早於 placed time 或已存在的 lifecycle history。
- `PENDING` 不可帶 transition timestamps。
- `ALLOCATED` 必須有 allocated time。
- `BACKORDERED` 必須有 backordered time，且不可同時為 allocated／cancelled。
- `CANCELLED` 必須有 cancelled time，並允許保留先前的 allocation／backorder history。

## Application flow 調整

### Place Order

`PlaceOrderUsecase` 現在：

1. 在 Aggregate 外產生 order ID 與 placed time。
2. 儲存 Order。
3. 逐一發布內部 `OrderPlaced`。
4. Application use case 建立並同步發布 `OrderPlacedIntegrationEvent`。
5. 回傳實際的 UUID order ID；REST controller 同步改為回傳 UUID。

### Allocate／Backorder

- Allocation listener 與 use case 改為接收 `OrderPlacedIntegrationEvent`。
- ATP 不足時，Order 記錄 `OrderBackordered`；application use case 建立並同步發布 `BackorderCreatedIntegrationEvent`。
- Coordinator 配置成功時目前只發布 `OrderAllocated`。
- `OrderAllocatedIntegrationEvent` 需要真實 `reservationId`，將由 SR-05 建立 `StockReservation` 後產生；SR-03 不使用假 ID 提前發布。

### Replenishment

- Replenishment listener 與 use case 改為接收 `StockReplenishedIntegrationEvent`。
- quantity contract 於建構時要求正值。

目前仍使用同步 `ApplicationEventPublisher` 作為過渡 wiring，逐一發布 Domain Event 與 Integration Event。SR-12／SR-13 會改成 transactional Outbox 與 commit 後 delivery；Domain Event 本身不寫入 Outbox。

## 測試

新增與調整的測試涵蓋：

- Order 建立、配置、欠單、取消與合法 transition。
- 重複取消 no-op、非法 transition 及 lifecycle timestamp ordering。
- `rehydrate(...)` 不產生事件且拒絕不一致的 persistence state。
- Domain Event 沒有 `eventId`。
- Integration Event 的 immutable event ID、完整 payload 與輸入驗證。
- Place Order 與 Backorder flow 依序發布 Domain Event 與 Integration Event。
- Coordinator 成功配置發布內部 `OrderAllocated`。
- 既有 replenishment、allocation service 與 application tests 配合新契約調整。

## 驗證結果

執行 unit tests：

```bash
./gradlew :order-promising:test
```

結果：`64 tests completed`，`BUILD SUCCESSFUL`。

執行完整 verification lifecycle：

```bash
./gradlew :order-promising:check
```

結果：`BUILD SUCCESSFUL`，包含：

- `64` unit tests。
- `11` PostgreSQL SIT tests。
- production、unit test 與 SIT sources 編譯成功。
- `git diff --check` 通過。

## 後續任務邊界

- SR-04：嚴格 FIFO allocation policy。
- SR-05：建立 `StockReservation` 並由完整成功結果產生 `OrderAllocatedIntegrationEvent`。
- SR-06：建立 cancellation application flow 與 `OrderCancelledIntegrationEvent`。
- SR-10：Order JPA entity、mapper、migration 與 FIFO query。
- SR-12／SR-13：Integration Event transactional Outbox 與 post-commit delivery。
