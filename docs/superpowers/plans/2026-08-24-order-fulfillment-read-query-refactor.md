# Order Fulfillment Read Query Refactor 任務計畫

**狀態：** 待實作
**目標：** 保留 `OrderFulfillmentQueryService` 的跨 Context composition 結構，同時減少 Inventory 的多次
repository query、移除 WMS 的 lazy-loading N+1，並讓取消流程不再載入完整 `ShipmentView`。

## 設計結論

- 不把 Ordering、Inventory、WMS 與 Temporal 合併成一條跨 Context SQL。
- 暫不建立 `order_fulfillment_projection` table；目前事件不足以重建完整的 allocation、reservation、PickTask
  與 Workflow progress。
- 新增各 Context 自己擁有的 read query／read repository，直接產生 application view，不經過 domain aggregate。
- `OrderFulfillmentView` 的 JSON 契約保持不變。
- `FulfillmentWorkflowStateReader` 保持不變：EVENTS 模式不查 Temporal，TEMPORAL 模式仍執行一次 Workflow Query。
- 不改動 allocation scheduler 使用的 `findPendingQueueHeadId`、`findRequiredQueueHeads` 與
  `findPendingQueueKeysWithAvailableStock`。

## 目前問題

### Inventory

`AllocationDemandQueryService.findPrimaryOrder()` 會依狀態執行多個查詢：

1. demand 與 lines
2. FIFO blocker（PENDING 才需要）
3. 當下 ATP（PENDING 才需要）
4. moves
5. move lines
6. stock quants
7. pickings

`listPending()` 與 `findPrimaryOrder()` 共用 `toView()`，只優化其中一個會形成兩套組裝邏輯，因此兩個入口必須
一起調整。

### WMS

`JpaShipmentRepositoryAdapter.findByOrderId()` 先查 shipments，接著 `toDomain()` 對每筆 shipment 分別讀取 lazy
`lines` 與 `pickTasks`，查詢數為 `1 + 2 × shipmentCount`。

`EventDrivenFulfillmentCancellationCoordinator` 只需要 shipment 數量與 ID，卻呼叫完整的
`GetOrderShipmentsUsecase`，連 lines、PickTasks 都一起載入。

## Task 1：建立 WMS 專用 read query

### 目標

讓 Shipment 詳情查詢固定使用三組批次 query：

1. shipment headers
2. 所有 shipment lines
3. 所有 PickTasks

禁止用單一多集合 JOIN，避免 `lines × pickTasks` 形成 Cartesian product。

### 預計異動

- 新增：`wms-context/.../application/query/ShipmentQueryService.java`
- 新增：`wms-context/.../application/query/ShipmentQueryRepository.java`
- 新增：`wms-context/.../infrastructure/persistence/query/JpaShipmentQueryRepository.java`
- 修改：`wms-context/.../infrastructure/configuration/WmsApplicationConfiguration.java`
- 刪除或取代：`wms-context/.../application/usecase/GetOrderShipmentsUsecase.java`

建議介面：

```java
public interface ShipmentQueryRepository {

    List<ShipmentView> findByOrderId(UUID orderId);

    List<UUID> findIdsByOrderId(UUID orderId);
}
```

`ShipmentQueryService` 負責參數驗證並委派 repository。Infrastructure adapter 可以使用 `EntityManager` 或
Spring Data scalar projections，但不得還原 `Shipment` aggregate。

### 清理既有 aggregate repository

完成 read query 後，若沒有其他 production caller：

- 從 `ShipmentRepository` 移除 `findByOrderId()`。
- 從 `JpaShipmentRepositoryAdapter` 移除對應方法。
- 更新測試中的 fake repositories。

### 驗收

- 空清單、單一 shipment、多 shipment 都能正確回傳。
- `ShipmentView.lines` 與 `pickTasks` 的排序維持穩定。
- 查詢數不隨 shipment 數量增加；固定最多三次 SQL。
- WMS command-side repository 的行為不變。

## Task 2：取消流程改用輕量 Shipment ID query

### 預計異動

- 修改：`EventDrivenFulfillmentCancellationCoordinator`
- 修改：`EventDrivenFulfillmentCancellationCoordinatorTest`

將：

```java
List<ShipmentView> shipments = getOrderShipmentsUsecase.query(orderId);
```

改為透過 `ShipmentQueryService.findIdsByOrderId(orderId)` 取得 IDs。後續規則維持不變：

- 0 筆：直接嘗試取消 Order。
- 1 筆：以 shipment ID 呼叫 `CancelShipmentUsecase`。
- 大於 1 筆：仍拋出 ship-complete conflict。

### 驗收

- 取消流程不讀 shipment lines 與 PickTasks。
- `PUTBACK_REQUIRED`、`REJECTED_AFTER_HANDOVER` 與正常取消語意不變。
- Events 與 Temporal orchestration mode 的既有取消測試都通過。

## Task 3：建立 Inventory 的 `AllocationDemandView` read repository

### 目標

把 query-side 的跨表讀取從 domain repositories 移到專用 read repository，並讓 `listPending()` 與
`findPrimaryOrder()` 使用同一條 view 組裝路徑。

### 預計異動

- 新增：`inventory-context/.../application/query/AllocationDemandViewQuery.java`
- 新增：`inventory-context/.../infrastructure/query/AllocationDemandViewQueryImpl.java`
- 修改：`AllocationDemandQueryService`
- 修改或刪除：`AllocationDemandQueryRepository` 與 `AllocationDemandQueryRepositoryImpl`
- 修改：`JpaAllocationDemandRepository`
- 更新：`AllocationDemandQueryServiceTest`
- 新增：read query 的 persistence integration test

### Query 分組

避免一條巨型 SQL；以 candidate IDs 批次執行三組查詢：

1. **Demand facts**：header 與 lines。
2. **Waiting facts**：每筆 PENDING demand 的 FIFO blocker，以及每個 required SKU 的當下可配置數量。
3. **Execution facts**：moves、move lines、stock quant identity 與 pickings。

SQL 回傳 facts，`AllocationDemandQueryService` 或獨立 assembler 負責計算：

- `WAITING_FOR_EARLIER_DEMAND`
- `READY_TO_ALLOCATE`
- `NO_ALLOCATABLE_STOCK`
- `INSUFFICIENT_ATP`
- `availableQuantities`
- `missingQuantities`

不要把這些判斷複製成多套 SQL `CASE`。

### 既有 query 的處置

當兩個 application query 都切換完成後：

- `findByStatusOrderByEnqueuedAtAscIdAsc` 若已無 caller，移除。
- `findBlockingDemandId` 若已被批次 waiting-facts query 取代且無 caller，移除。
- `AllocationDemandQueryRepository` 若已無職責，整個移除，不保留空殼。
- `AllocationDemandRepository.findBySource()` 仍被 command/application flow 使用，必須保留。
- scheduler 的 queue-head／queue-key queries 完全不動。

### 驗收

- `findPrimaryOrder()` 找不到 demand 時仍回傳 `Optional.empty()`。
- PENDING demand 的 blocker、ATP 與缺口結果和現在一致。
- ALLOCATED demand 的 moves、pickings 與 reservation batch identity 和現在一致。
- `listPending(limit)` 維持 FIFO 順序與 limit 語意。
- 列表查詢數不隨 demand 數量線性增加。

## Task 4：重新接線 `OrderFulfillmentQueryService`

### 預計異動

- 修改：`OrderFulfillmentQueryService`
- 修改：`OrderFulfillmentDemoControllerTest`
- 視命名調整更新 Spring configuration／wiring tests

新的 composition 仍維持四個清楚的來源：

```text
Ordering query
Inventory AllocationDemandView query
WMS ShipmentView query
Temporal Workflow Query（僅 TEMPORAL mode）
```

Ordering 目前只讀一筆 `Order`，不是主要效能問題，本次先保留 `GetOrderUsecase` 與
`FulfillmentOrderView.from(order)`。若未來要完全分離 command/query model，再另開任務新增 Ordering read view。

### 驗收

- `OrderFulfillmentView` 欄位與 JSON 結構不變。
- 不存在的 Order 仍回傳 404。
- EVENTS 模式的 `workflow` 仍為 `null`。
- TEMPORAL 模式仍能查到進行中與完成後的 Workflow snapshot。

## Task 5：測試與回歸

### 模組測試

```bash
cd backend
./gradlew :inventory-context:test :wms-context:test :deployments:monolith:test
```

### 格式與編譯

每批 Java 異動後：

```bash
cd backend
./gradlew spotlessApply
```

提交前：

```bash
cd backend
./gradlew spotlessCheck
```

### 整體驗證

```bash
cd backend
./gradlew test
./gradlew :deployments:monolith:sit
```

如果本機 e2e 環境可用，再執行 Events 與 Temporal workflow 的完整流程，確認
`GET /demo/orders/{orderId}/fulfillment` 回傳內容未改變。

## 建議 Commit 邊界

1. `refactor(wms): add shipment read query`
2. `refactor(fulfillment): use shipment id lookup for cancellation`
3. `refactor(inventory): add allocation demand view query`
4. `refactor(demo): wire fulfillment composition to read queries`
5. `test: cover fulfillment read query performance boundaries`

## 非本次範圍

- 新增 `order_fulfillment_projection` table。
- 新增或擴充 Integration Event payload。
- 發布 Shipment／PickTask／Workflow progress projection events。
- Cache、Redis、Elasticsearch 或 JVM memory projection。
- 修改 allocation scheduler、公平輪詢或 reservation command flow。
