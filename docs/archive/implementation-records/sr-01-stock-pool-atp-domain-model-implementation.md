# SR-01 StockPool ATP Domain Model 實作紀錄

狀態：已完成

完成日期：2026-07-23

## 實作範圍

SR-01 只重構 StockPool domain model 與直接依賴該模型的 domain service／unit tests，不提前實作 StockReservation、JPA schema 或 persistence adapter migration。

- 將單一 `available` 狀態改為 `onHandQuantity` 與 `reservedQuantity`。
- 將 ATP 建模為衍生值 `availableToPromise()`。
- 以 `canReserve()`／`reserve()` 取代 domain allocation 時直接扣減庫存。
- 新增 `release()` 與 quantity invariants。
- 保留 SR-09 前必要且明確標記為 deprecated 的 legacy persistence bridge。

## Domain 行為

### ATP 三量模型

```text
availableToPromise = onHandQuantity - reservedQuantity
```

- `onHandQuantity` 代表實際在庫數量。
- `reservedQuantity` 代表已承諾但尚未由下游實際出庫的數量。
- 建立 reservation 只增加 `reservedQuantity`，不扣除 `onHandQuantity`。
- 補貨只增加 `onHandQuantity`，不改變既有 `reservedQuantity`。
- 釋放 reservation 只減少 `reservedQuantity`。

### Invariants

建立 StockPool 時必須符合：

```text
onHandQuantity >= 0
reservedQuantity >= 0
reservedQuantity <= onHandQuantity
```

Mutation quantity 必須大於零：

- `canReserve(quantity)`／`reserve(quantity)`
- `release(quantity)`
- `replenish(quantity)`

此外：

- `release(quantity)` 不允許超過目前的 `reservedQuantity`。
- `canReserve(quantity)` 在 ATP 不足時回傳 `false` 且不改變數量；`reserve(quantity)` 只接受完整預留。
- `replenish(quantity)` 使用 overflow-safe addition，避免 `int` overflow 破壞 invariant。

## 新增檔案

### `../../order-promising/src/test/java/com/flowzati/archone/allocation/domain/model/StockPoolTest.java`

新增的純 domain unit tests 目前會展開為 16 個 test cases，涵蓋：

- ATP 衍生計算。
- reservation 成功與剛好用完 ATP。
- ATP 不足且狀態不變。
- reservation release。
- replenishment 不影響 reserved quantity。
- 建構時的三項 quantity invariants。
- reserve／release／replenish 的非正數拒絕。
- release 超過 reserved quantity 時拒絕且狀態不變。

相同規則的邊界輸入使用 JUnit parameterized tests：

- 三種不合法的初始 quantity 組合使用 `@MethodSource`。
- reserve／release／replenish 的 `0` 與負數輸入使用 `@ValueSource`。
- 不同性質的業務情境維持獨立 test method，並以中文 `@DisplayName` 與必要註解說明行為。

## 變更檔案

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/domain/model/StockPool.java`

- 將 `available` 欄位替換為 `onHandQuantity` 與 `reservedQuantity`。
- 新增 `availableToPromise()`、`canReserve()`、`reserve()` 與 `release()`。
- 調整 `replenish()`，只增加 on-hand 並拒絕非正數與 overflow。
- 新增 `getOnHandQuantity()` 與 `getReservedQuantity()`。
- 集中驗證 quantity invariants。

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/domain/service/AllocateService.java`

- 將 `StockPool.tryAllocate()` 呼叫改為 `StockPool.canReserve()`／`reserve()`。
- 成功分配現在增加 reserved quantity，實際 on-hand quantity 維持不變。

### `../../order-promising/src/test/java/com/flowzati/archone/allocation/domain/service/AllocateServiceTest.java`

- 改用新的五參數 StockPool constructor。
- 驗證 allocation 後 on-hand 不變、reserved 增加且 ATP 正確下降。

### Application unit tests

以下既有測試只調整 StockPool fixture 與 assertion 使用的新 domain vocabulary，未修改 application flow：

- `../../order-promising/src/test/java/com/flowzati/archone/allocation/application/usecase/AllocateOrderUsecaseTest.java`
- `../../order-promising/src/test/java/com/flowzati/archone/allocation/application/usecase/ReplenishmentUsecaseTest.java`

### `../stock-reservation-design.md`

- SR-01 checkbox 更新為完成。
- 整體進度更新為 `2 / 17`。

## SR-09 前的相容橋接（已移除）

SR-01 完成當時，`StockPoolEntity` 與 `StockPoolMapper` 仍只有 legacy `available` 欄位，因此暫時保留：

- 四參數 `StockPool(id, sku, available, version)` constructor。
- `getAvailable()`，回傳目前的 ATP。

兩者當時都標記為 `@Deprecated(forRemoval = true)`，只供既有 mapper 維持編譯。SR-09 已完成 entity／mapper migration，並移除這兩個 bridge 與相關 compile warnings。

SR-01 沒有修改：

- `StockPoolEntity`
- `StockPoolMapper`
- JPA repository
- Flyway migration
- StockReservation domain model

## 驗證結果

執行 unit tests：

```bash
./gradlew :order-promising:test
```

結果：

```text
BUILD SUCCESSFUL
27 unit tests completed
```

其中包含 16 個 `StockPoolTest` cases。

執行完整 verification lifecycle：

```bash
./gradlew :order-promising:check
```

結果：

```text
BUILD SUCCESSFUL
27 unit tests completed
2 SIT tests completed
```

驗證內容：

- Java production、unit test 與 SIT source 均成功編譯。
- StockPool domain invariants 與狀態轉換測試全部通過。
- 既有 allocation／replenishment application tests 全部通過。
- SR-08 PostgreSQL／Flyway SIT 全部通過。

## 後續任務注意事項

- SR-02 建立 StockReservation aggregate，但不應重複管理 StockPool 的 quantity invariant。
- SR-04 應以 `canReserve()` 的結果建模 allocation／backorder policy，再以 `reserve()` 執行完整預留。
- SR-09 必須持久化 `onHandQuantity` 與 `reservedQuantity`，並移除本次保留的 deprecated bridge。
- 實際出庫扣除 `onHandQuantity` 仍不在目前 promising service 範圍。
