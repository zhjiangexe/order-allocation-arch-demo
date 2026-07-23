# SR-04 Allocation Domain Service 實作紀錄

狀態：已完成

完成日期：2026-07-24

## 實作範圍

SR-04 收斂 allocation domain policy，讓完整 reservation、ATP 不足、嚴格 FIFO 與 release 都以明確的 domain behavior 表達；未提前建立 SR-05～SR-07 的 application flow 或 SR-15 的 optimistic-lock retry。

- 將原本空白的 `AllocationPolicy` 改為可替換的 backorder selection strategy。
- 提供 `StrictFifoAllocationPolicy` 與 `MaximizeFulfilledOrdersPolicy`，預設使用 Strict FIFO。
- 以 generic immutable Context 與 `AllocationContextFactory` 隔離各 Policy 的動態輸入需求。
- 由 `AllocationSelector.contextual(...)` 配對 Policy 與 ContextFactory，讓 `AllocateService` 只依賴非泛型的 selection port。
- 單筆 allocation 只允許完整 reservation，不支援部分成功。
- 將 ATP 不足建模為 `INSUFFICIENT_ATP` 業務結果。
- Batch allocation 直接回傳成功配置的 Orders；未被選取的 Orders 維持原狀。
- 將跨 `StockReservation` 與 `StockPool` 的 release 從 allocation policy 分離。
- 以純 domain unit tests 驗證 allocation、backorder decision、release 與 head-of-line blocking。

## Domain 結果模型

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocationOutcome.java`

單筆 allocation 只回傳兩種業務結果：

```text
ALLOCATED
INSUFFICIENT_ATP
```

`INSUFFICIENT_ATP` 只代表目前 ATP 無法完整滿足 Order quantity。它不是 exception，也不代表 optimistic-lock conflict。

ATP 不足時 domain service：

- 不增加 `reservedQuantity`。
- 不做部分 reservation。
- 不修改 Order 狀態。
- 由 SR-05 application flow 決定將 Order 標記為 `BACKORDERED`。

## Allocation Policy

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocationPolicy.java`

`AllocationPolicy` 是 backorder selection strategy：

```java
public interface AllocationPolicy<C extends AllocationContext> {
  List<Order> selectOrders(List<Order> candidates, C context);
}
```

Policy 只決定候選 Order，不直接修改 aggregate，也不負責 persistence。完整 reservation invariant 仍由 `AllocateService` 統一執行，因此替換 selection strategy 不會偷偷引入 partial allocation。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/StrictFifoAllocationPolicy.java`

依輸入順序累計完整 quantity；遇到第一筆 ATP 不足立即停止，不跳過它處理後單。

SR-10 repository 已提供 `backorderedSince ASC, id ASC` 的穩定 FIFO candidates。Strict FIFO policy 保留這個順序並實作 head-of-line blocking。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/MaximizeFulfilledOrdersPolicy.java`

先以 `quantity ASC` 穩定排序，再從最小完整 Order 開始選取。所有 Order 的完成價值都視為 1，因此選擇最小 quantities 能在既有 ATP 下最大化完整完成的訂單數。

例如 ATP 為 5、候選 quantities 為 `6, 2, 3`：

```text
Strict FIFO               -> 第一張 6 無法滿足，完成 0 張
Maximize Fulfilled Orders -> 選取 2 + 3，完成 2 張
```

相同 quantity 維持原 candidates 順序。這項 alternative policy 必須由 composition 明確注入，不是系統預設。

## Generic Context 與 Factory

### `AllocationRequest` 與 `AllocationContext`

`AllocationRequest` 只保存每次 allocation 一定具備的核心事實：

```java
Long stockPoolId
String sku
int availableToPromise
Instant decisionAt
```

`AllocationContext` 是 Policy-specific immutable context 的 marker interface。目前兩個內建 Policy 都使用：

```java
BasicAllocationContext(int availableToPromise)
```

未來若 Channel Quota Policy 需要額外動態資料，可定義自己的完整 context，而不在共用 context 中加入大量 nullable／optional 欄位。

### `AllocationContextFactory<C>`

Factory 將統一 request 轉為指定 Policy 所需的 typed context：

```java
public interface AllocationContextFactory<C extends AllocationContext> {
  C create(AllocationRequest request);
}
```

目前的 `BasicAllocationContextFactory` 只映射 ATP。未來需要 quota、fulfillment capacity 或 replenishment information 時，可由 application／infrastructure implementation 注入對應 read port，查詢後組成 immutable context；`AllocationPolicy` 本身仍維持純算法。

### `AllocationSelector` facade

`AllocationSelector.contextual(...)` 以泛型 static factory 配對 Factory 與 Policy：

```text
AllocationRequest
  -> AllocationContextFactory<C>.create(...)
  -> AllocationPolicy<C>.selectOrders(...)
```

static factory 回傳非泛型的 `AllocationSelector` lambda，因此 `AllocateService` 只負責使用已組裝完成的 selector，不認識 `C`，也不使用 `instanceof` 判斷 Policy 類型。Compiler 仍會保證 Factory 與 Policy 使用相同 Context type，且不需要額外的 concrete selector class。

## 完整 Reservation

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocateService.java`

`AllocationSelector` 為兩個內建策略提供 named factory，隱藏 Policy 與 Basic ContextFactory 的組裝細節；`AllocateService()` 預設使用 Strict FIFO：

```java
new AllocateService();
// 預設 Strict FIFO

new AllocateService(AllocationSelector.maximizeFulfilledOrders());
// 明確選擇最大化完成訂單數
```

內建 selector 也可在 composition 中直接取得：

```java
AllocationSelector.strictFifo();
AllocationSelector.maximizeFulfilledOrders();
```

需要不同 typed context 時仍由 `contextual(...)` 封裝 generic 配對：

```java
new AllocateService(
    AllocationSelector.contextual(policy, contextFactory)
);
```

`allocateBackorders(...)` 只負責從 StockPool 與 decision time 建立統一 `AllocationRequest`，其餘 context-specific mapping／query 由 Factory 負責，因此新增 Policy 不需要修改 `AllocateService`。

`allocate(order, stockPool, allocatedAt)`：

1. 驗證 Order 與 StockPool 的 SKU 相同。
2. 呼叫 `StockPool.canReserve(order.quantity)` 判斷 ATP 是否能完整滿足需求。
3. ATP 不足時回傳 `INSUFFICIENT_ATP`，兩個 aggregate 都保持原狀。
4. ATP 充足時先由 Order Aggregate 驗證並標記為 `ALLOCATED`。
5. 呼叫 `StockPool.reserve()` 完整增加 `reservedQuantity`。
6. 回傳 `ALLOCATED`。

`allocateBackorders(...)` 先由 policy 選取候選，再逐一套用 private `applyAllocation(...)` 的完整 reservation behavior，最後回傳成功配置的 Orders。未被選取的 Orders 不需重複包進結果物件，其狀態維持不變。單筆與批次共用相同 mutation，Policy 負責 selection，StockPool 仍是 ATP invariant 的最後防線。

Order lifecycle validation 在 reservation mutation 前執行；若狀態或 allocated time 不合法，不會留下已修改的 StockPool。

## Release Consistency

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/ReservationReleaseService.java`

Release 不屬於 backorder selection strategy，因此從 `AllocationPolicy` 與 `AllocateService` 分離。`release(reservation, stockPool, releasedAt)` 在修改 aggregate 前先驗證：

- Reservation 必須屬於指定 StockPool。
- release time 必須存在，且不可早於 reserved time。
- Reservation quantity 不可超過 StockPool 的 `reservedQuantity`。

驗證通過後才同時將 Reservation 改為 `RELEASED` 並釋放 StockPool quantity。重複 release 回傳 `false`，不會再次增加 ATP。

這是 SR-06 可重用的 domain primitive；Inbox、repository、transaction 與 integration event 仍留給 SR-06。

## 預設嚴格 FIFO

移除會自動跳過不足 Order 的 `drain()`。預設 `StrictFifoAllocationPolicy` 的 head-of-line blocking 範例：

```text
ATP = 5

Order A quantity 3 -> ALLOCATED，剩餘 ATP 2
Order B quantity 4 -> INSUFFICIENT_ATP，停止
Order C quantity 1 -> 不處理，即使 ATP 可以滿足
```

預設行為能避免較小後單繞過較早欠單。只有明確選擇 `MaximizeFulfilledOrdersPolicy` 時才允許為了提高完成張數而跳過大單。

## Application 相容調整

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/application/coordinator/OrderAllocationCoordinator.java`

- 單筆 allocation 改為判斷明確的 `AllocationOutcome`。
- replenishment allocation 直接使用 `allocateBackorders(...)` 回傳的 allocated Orders。
- Coordinator 的統一保存責任沒有改變。
- ATP 不足不保存未變更的 StockPool；補貨情境即使沒有成功配置任何 Order，仍保存已增加 on-hand 的 StockPool。

這只是讓現有 application wiring 能使用新的 SR-04 domain API；建立 StockReservation 與完整 application transaction 仍屬 SR-05。

## Optimistic Lock 邊界

Domain service 不捕捉 persistence exception，也不把 conflict 轉成 `INSUFFICIENT_ATP`：

```text
ATP 不足
  -> AllocationOutcome.INSUFFICIENT_ATP
  -> application 可決定建立 backorder

Optimistic lock conflict
  -> repository save／flush exception
  -> transaction rollback
  -> SR-15 重新讀取 aggregates 後 bounded retry
```

因此只有 domain 直接檢查 ATP 不足時才會產生 `INSUFFICIENT_ATP`；並行寫入失敗會繼續向外拋出。

## 測試

### `../../order-promising/src/test/java/com/flowzati/archone/allocation/domain/service/AllocateServiceTest.java`

以 12 個純 domain unit tests 驗證：

- ATP 充足時完整 reservation 並標記 Order `ALLOCATED`。
- ATP 不足時回傳明確結果，Order 與 StockPool 都保持原狀。
- quantity 剛好等於 ATP 時完整成功。
- FIFO 首單不足時立即停止，不配置較小後單。
- 成功配置前綴後遇到阻塞單時，不跳過它配置更小後單。
- ATP 足夠時依輸入順序配置全部 Orders。
- 明確注入 Maximize policy 時跳過大單，完整配置最多 Orders。
- ACTIVE Reservation 與 StockPool quantity 一致釋放。
- 重複 release 為 no-op。
- 不一致的 release 在修改任一 aggregate 前被拒絕。
- Order 與 StockPool SKU 不一致時在 mutation 前被拒絕。
- Order lifecycle 不允許 allocation 時不會先修改 StockPool。

既有 `StockPoolTest` 驗證 `canReserve()` 是純查詢、`reserve()` 不做部分 reservation，以及 release 超量時不會用歸零掩蓋問題。

### `../../order-promising/src/test/java/com/flowzati/archone/allocation/domain/service/AllocationSelectorTest.java`

新增 1 個 unit test，驗證 Context Factory 先建立 typed context，再由 selector 傳給相同 generic type 的純 Policy。

## 變更檔案

### `../stock-reservation-design.md`

- SR-04 checkbox 更新為完成。
- 整體進度更新為 `7 / 17`。
- SR-04 完成後，SR-05 的相依條件已滿足。

## 驗證結果

執行 unit tests：

```bash
./gradlew :order-promising:test
```

結果：

```text
BUILD SUCCESSFUL
78 unit tests completed
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

結果：`BUILD SUCCESSFUL`。`check` 已確認同時涵蓋 unit tests 與 PostgreSQL SIT。

## 後續任務注意事項

- SR-05 使用 `INSUFFICIENT_ATP` 決定建立 backorder；成功時建立 ACTIVE StockReservation。
- SR-06 使用 `ReservationReleaseService.release(...)` domain primitive，但仍負責 Inbox、persistence、Outbox 與 transaction。
- SR-07 將 SR-10 的 stable FIFO repository result 傳入預設為 Strict FIFO 的 `allocateBackorders(...)`。
- SR-15 才實作 optimistic-lock bounded retry；不得把 conflict 當成 backorder。
