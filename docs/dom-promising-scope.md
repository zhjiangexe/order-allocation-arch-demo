# 訂單層 ② Promising：批次庫存模型與配貨演算法

狀態：分析，未確認

日期：2026-07-26

## 這份文件回答什麼

本文件定義 ② Promising 的庫存模型與配貨演算法：庫存以什麼粒度存在、一張訂單如何配
到具體的庫存批次、以及既有實作缺了什麼。

| 相關文件 | 涵蓋 |
| --- | --- |
| [execution-roadmap.md](execution-roadmap.md) | **八個 change 的順序、依賴、任務與禁忌** |
| [system-layer-map.md](system-layer-map.md) | 全流程分層、跨層契約、帳務交會點、用詞決定 |
| [dom-order-intake-scope.md](dom-order-intake-scope.md) | 訂單層 ① 收單編排、訂單與商品資料模型 |
| [dom-sourcing-scope.md](dom-sourcing-scope.md) | 訂單層 ③ Sourcing/Routing |
| [fulfillment-minimal-scope.md](fulfillment-minimal-scope.md) | 履約層（最小版） |

本文件不是 change proposal，不含任務拆解與驗收條件。

## 現況與缺口

② 是三個職責中唯一已實作的：`StockPool`、`StockReservation`、`AllocationService`、
`AllocationPolicy` 抽象、樂觀鎖重試、backorder 與補貨後的 FIFO 重配都已完成。

三個缺口：

| # | 缺口 | 嚴重性 |
| --- | --- | --- |
| P1 | **庫存被當成同質的** —— 一個 SKU 一個數字，無法表達效期與良品狀態 | 結構性 |
| P2 | **庫存只增不減** —— `onHandQuantity` 沒有任何遞減路徑 | 閉環缺失 |
| P3 | 貨主與節點維度缺失 | 見 ①、③ 兩份文件 |

---

## 業界的庫存與預留流派

在決定本系統要什麼之前，先定位既有實作落在哪裡。

### 庫存模型的四種流派

| 流派 | 模型 | 用在哪 |
| --- | --- | --- |
| **A 數量欄位** | `stock(sku, onHand, reserved, damaged…)`，每種狀態一欄 | 簡單電商。**本專案現況** |
| **B 批次為維度** | `stock(sku, expireDate, group, qty)`，每批一列 | **WMS 主流**（Manhattan、Blue Yonder、SAP EWM） |
| **C 流水帳** | `movement(sku, lot, qtyDelta, type, ref, ts)`，現量為 `SUM` | 財務級可追溯，現代 WMS 常見 |
| **D 單件序號** | `item(serial, sku, location, status)`，每件一列 | 高單價強追溯（3C、醫材、汽車零件） |

**本系統採 B。** A → B 就是 P1 的改動。

不採 C 的理由：它的優勢是天然記錄調整異動（短揀只是一筆 movement），但查詢現量要
聚合，實務上得配 snapshot 表維持效能——那等於回到 B 再加一層。而本專案已有 outbox
事件流，異動軌跡部分已經具備。

不採 D 的理由：數量規模不允許，且本專案無單件追溯需求。

### Reservation 的三種流派

| 流派 | 做法 | 用在哪 |
| --- | --- | --- |
| **1 Soft** | 只預留數量不指定批次，**出貨時才選批**（pick-time allocation） | 電商主流 |
| **2 Hard** | 預留時就指定批次 | **WMS／3PL 主流**，尤其食品藥品 |
| **3 兩階段** | 收單時 soft 檢查 ATP，出貨前才 hard 決定批次 | SAP 等大型系統 |

**本系統已經是流派 2。** `StockReservation` 持有 `stockPoolId`，指向特定的庫存池——
批次化是這個模型的自然延伸，不是換流派。

證據在 `ReleaseReservationUsecase:53`：

```java
StockPool stockPool = stockPoolRepository.findById(reservation.getStockPoolId())
```

它靠 reservation 上的 id 反查，**批次化之後這行完全不用改**。流派 1 沒有這個性質。

不採流派 3 的理由：兩階段的複雜度翻倍，而它真正解決的是「批次被提前鎖死造成庫存
碎片化」——那是規模問題，demo 沒有。且 demo 要展示的「配到哪些批次」在流派 1 與
流派 3 的配貨階段都還看不到。

---

## P1：庫存不是同質的

### 判準

> 同一個 SKU，什麼情況下兩件貨不能互相替代？

| 答案 | 需要的維度 |
| --- | --- |
| 沒有，都一樣 | 0 個。`stock(sku, qty)` 就夠——**這是目前的模型** |
| 效期不同不能混 | 1 個：`expireDate` |
| 良品與不良品不能混 | 2 個：＋`group` |
| 還要可追溯到批號 | 3 個：＋`lotNumber` |

本系統取**前兩個**。`lotNumber` 不做——它不改變配對邏輯，只是多一個 tie-breaker，
而本專案沒有召回追溯的需求。

### 模型改動

```text
現況   StockPool(sku)                                     ← 一個數字
目標   StockPool(ownerId, nodeId, skuCode, expireDate, group)
```

一個貨主的一個 SKU 在一個節點上，會有**多筆** `StockPool`，各自代表一個批次。

| 方案 | 判定 |
| --- | --- |
| `StockPool` 的 key 直接加 `(expireDate, group)` | **採用** |
| 保留 `StockPool` 為聚合視圖，另建 `StockLot` 明細表 | 不採用 |

不採用第二案的理由：它會製造**第三本帳**（`StockPool` 總量、`StockLot` 明細、
`LocationStock` 實體），違反已定的「同一事實只記一處」原則。ATP 本來就該從批次算出
來，聚合值是查詢結果而非儲存值。

### `group` 取代 `damagedQuantity`

曾考慮在 `StockPool` 上加 `damagedQuantity` / `blockedQuantity` 欄位。`group` 作為
**維度**比它們更正確：

| | `damagedQuantity` 欄位 | `group` 維度 |
| --- | --- | --- |
| 能表達「這批破損的效期」 | ✗ | ✓ |
| 能擴充到待驗、凍結、退貨待判 | ✗ | ✓ |
| ATP 計算 | 要在公式裡逐項扣除 | 篩選 `group = GOOD` 即可 |

### ATP 的語意變化

```text
現況   ATP(sku)                  = onHand - reserved
目標   ATP(owner, node, skuCode) = Σ  (onHand - reserved)
                                   批次 ∈ 可售批次
```

「可售批次」由兩個硬約束決定，見下節。

`③ Sourcing` 取用的分節點 ATP 即為此聚合值。它是查詢結果，不儲存。

---

## 配貨演算法

### 兩層巢狀

| 層 | 問題 | 現況 |
| --- | --- | --- |
| 外層：**配貨優先序** | 有限庫存下，哪些**訂單**先配 | **已實作**——`AllocationPolicy` |
| 內層：**批次配對** | 選中的訂單吃哪些**批次**、各多少 | **缺** |

外層是 knapsack 變形（`MaximizeFulfilledOrdersPolicy`）或先到先服務
（`StrictFifoAllocationPolicy`）。內層是排序後依序取用，排序鍵為效期。

兩者職責分明：外層決定「誰有資格拿」，內層決定「拿到的是哪一批」。

### 內層：硬約束先篩，再 FEFO 排序

```text
1. 篩選可售批次
     group = GOOD          ← 不良品不可出
     expireDate ≥ 今日     ← 已過期不可出

2. 依 expireDate 由近到遠排序（FEFO）

3. 依序取用，直到滿足需求量或批次耗盡
```

**FEFO（First Expired First Out）而非 FIFO。** 排序鍵是效期而非入庫時間——後進的貨
可能效期更近，應該先出。

兩個維度各擔任一種角色：

| 維度 | 角色 |
| --- | --- |
| `group` | **純硬約束**——不良品直接排除，不參與排序 |
| `expireDate` | **既是硬約束也是排序鍵**——已過期排除，未過期依近到遠取用 |

### 產生的可見行為

> 庫存頁顯示這個 SKU 有 100 個，但 ATP 只有 60
> → 因為 30 個是不良品、10 個已過期

「有貨但不可售」是純數字模型表達不出來的狀態。庫存頁必須逐批列出並標示落選理由，
否則觀看者只會看到 ATP 比總量少，不知道為什麼。

### 缺貨時的行為：採 ship-complete

**整張訂單全有全無。** 任一條 line 無法滿足，整張單都不配、都不預留，整單進
`BACKORDERED`；補貨後依 `AllocationPolicy` 重新評估整籃。

這是業界的 ship-complete 政策（SAP 的「完整交貨」、Oracle／NetSuite 的 Ship Complete）。
業界的**預設**通常是 ship-partial（line 獨立配貨），本專案刻意選另一邊。

| | ship-partial（業界預設） | **ship-complete（本專案）** |
| --- | --- | --- |
| 一條 line 缺貨 | 其他 line 照配，整單 `PARTIALLY_ALLOCATED` | **整單都不配** |
| 可滿足性判斷 | 逐 line、逐 SKU 獨立 | **整籃的所有 SKU 必須同時可滿足** |
| `StrictFifoAllocationPolicy` | 現有邏輯直接適用 | **要改為整籃原子判斷** |
| 一次交易碰幾個 `StockPool` | 一個 | **多個** |
| `PARTIALLY_ALLOCATED` | 存在 | **不存在** |

**這不是一個 boolean 分支，是不同的配貨演算法。** 三個連帶後果：

1. **配得到的 line 不可預留。** 為一張出不去的單鎖住庫存，會擋掉本來能出貨的其他單。
2. **補貨喚醒要跨 SKU 檢查。** 補 SKU X 之後，還必須確認那些單的**其他** SKU 也都備齊，
   否則仍不能配。「隊列」因此不再是純 per-SKU 的。
3. **死鎖面放大。** 一次交易碰多個 SKU 的多個批次，因此持久化的排序鍵必須是
   `(sku_code, expire_date)` 而非只有 `expire_date`——見「一個新的併發風險：死鎖」。

單品項下 ship-complete 與 ship-partial **行為完全相同**，所以本決定在 ① 段 C（放寬多筆
line）之前不產生任何可觀察差異。但它改變段 C 的性質：段 C 從「放寬一個 domain 檢查」
變成「重寫配貨演算法」。

原先評估的 `isStockNecessary`（line 層級的缺貨行為開關）已排除。排除理由要更正——不是
「需要多品項才有意義」，而是**它是跨 SKU 原子配貨，不是狀態分岔**：一旦允許逐單切換，
兩種配貨演算法必須並存，隊列語意也要分兩套。若日後真的需要 ship-partial，應以貨主層級
開關的形式加上（與 `allow_split_shipment` 同一類），並接受兩條配貨路徑的成本。

---

## P2：庫存只增不減

`StockPool.onHandQuantity` **只有 `replenish()` 一條遞增路徑，沒有任何遞減路徑**。
`release()` 只動 `reservedQuantity`（取消退回）。系統的貨從未真正出去過。

```java
public void reserve(int q)    { reservedQuantity += q; }   // 只動 reserved
public void release(int q)    { reservedQuantity -= q; }   // 取消退回，只動 reserved
public void replenish(int q)  { onHandQuantity   += q; }   // 唯一寫 onHand 之處
```

| 缺 | 內容 |
| --- | --- |
| `StockPool.consume(int)` | onHand 與 reserved 同步遞減 |
| `ReservationStatus.CONSUMED` | 與 `RELEASED`（取消退回）語意分離。目前只有 `ACTIVE` / `RELEASED` |

觸發者是履約層的 `ShipmentDeparted` 事件。契約與扣帳時機定義於
[system-layer-map.md 交會點 2](system-layer-map.md)。

此缺口與 P1、P3 無關，可獨立執行，且是履約層的前置。

---

## Reservation 要帶批次

`StockReservation` 目前記錄 `stockPoolId` 與 `quantity`。批次化之後，**一張訂單的
一個 line 可能吃到多個批次**：

```text
需求 20 個
  ├── 批次 A（效期 2026-08-01）取 12 個
  └── 批次 B（效期 2026-09-15）取  8 個
```

因此 reservation 的粒度是「一個 line × 一個批次」，而非「一張訂單」。這連帶影響：

| 影響 | 說明 |
| --- | --- |
| `StockReservation.orderId` → `orderLineId` | **FK 變更**，見下 |
| `StockReservation` 數量 | 一張單可能對應多筆 |
| `consume()` 的觸發 | 出貨時逐筆 consume，各自扣對應批次 |
| 履約層的 `PickTask` | 必須帶批次維度，才知道去揀哪一批 |

最後一項是跨層契約：`OrderAllocated` 事件要帶批次資訊，否則履約層不知道該揀哪批貨。

### `order_lines` 必須與本段同時建立

`StockReservation` 掛 `orderLineId` 需要 `order_lines` 存在。若先掛 `orderId`、等
多品項時再改，就是一次 **FK 搬遷**——與履約層 `PickTask` 掛 `orderId` vs `shipmentId`
是完全同構的問題。

因此 `order_lines` 表應在本段一併建立，**每張單先只有一筆 line**。多品項（① 段 C）
之後只是放寬「一張單可以有多筆 line」，是放寬限制而非搬遷結構。

判準與履約層的三處預防相同：**事後改動會影響別人的，現在就做對。**

---

## Repository 介面的改動

`StockPoolRepository.findBySku(String) → Optional<StockPool>` 在批次化後不成立。
四個呼叫點各有不同的改法：

| 呼叫點 | 現在 | 改成 |
| --- | --- | --- |
| `AllocateOrderUsecase:53` | `findBySku` → `Optional` | `findSellableBatchesInFefoOrder(owner, node, sku, asOf)` → **已排序的 List** |
| `GetStockPoolUsecase:18` | 同上 | `findBatches(owner, node, sku)` → List，**含不可售批次**（畫面要標落選理由） |
| `ReplenishmentUsecase:50` | `findBySku` + `replenish()` | **模式不成立**，見下 |
| `ReleaseReservationUsecase:53` | `findById(reservation.getStockPoolId())` | **不用改** |

最後一列是流派 2（hard reservation）的紅利：reservation 已指向特定批次，反查不受
批次化影響。

### 命名沿用既有 pattern

專案裡已有同樣形態的方法：

```java
OrderRepository.findBackordersBySkuInFifoOrder(sku)
```

排序語意寫進方法名、由 DB index 支撐。`findSellableBatchesInFefoOrder` 是同一個做法，
排序責任歸屬不需另行討論。

### `ReplenishmentUsecase` 是最大的改動

補貨進來的是**新的一批貨**（有自己的效期），不是往既有批次加數量：

```text
現在   findBySku(sku).replenish(qty)          ← 找到那一列，加數量

之後   upsert(owner, node, sku, expireDate, group, qty)
         同批次已存在 → 加到既有列
         不存在       → 建立新列
```

`ReplenishStockCommand` 因此要加 `expireDate` 與 `group`，usecase 從「查詢＋呼叫
domain 方法」變成 upsert。

連帶好處：操作台的補貨探針要能輸入效期，而這讓「**補一批新效期的貨進來，看 FEFO
排序改變**」成為可展示的操作。

---

## 一個新的併發風險：死鎖

現在一張單只更新一列 `stock_pools`，樂觀鎖加重試就足夠。批次化後**一張單可能更新
多列**：

```text
訂單 X 更新 批次A → 批次B
訂單 Y 更新 批次B → 批次A     ← 死鎖
```

**解法是固定更新順序。** 配貨既然已依 `expireDate` 排序取用，只要保證寫入順序也照
這個排序，就不會交錯。這要在 `OrderAllocationCoordinator` 的持久化段落明確寫死，
不能依賴集合的自然順序。

既有的 `AllocationRetryExecutor` 處理的是樂觀鎖衝突，**處理不了死鎖**——死鎖在 DB
層就 abort 了，重試邏輯看到的是不同的例外類型。

---

## 對 Kafka partition key 的連鎖

`OrderingDomainEventTranslator:76` 的 sku 策略：

```java
private String partitionKey(UUID orderId, String sku) {
  return SKU_STRATEGY.equals(partitionKeyStrategy) ? sku : orderId.toString();
}
```

該策略的目的是 **single-writer**——同一個 SKU 的事件收斂到同一 partition，讓同一個
`StockPool` 只有一個 consumer 在寫。

多貨主後，single-writer 的單位是 `(owner, sku)` 而非 `sku`：

| | 用裸 `sku` | 用 `ownerId:skuCode` |
| --- | --- | --- |
| 結果正確性 | ✓ 仍是 single-writer | ✓ |
| 吞吐 | **假競爭**——A 貨主與 B 貨主的同名 SKU 是不同庫存池，卻被序列化到同一 partition | 各自獨立 |

修正為 `ownerId + ":" + skuCode`。

### 一個 sku 策略本身的限制

partition key 在 `OrderPlaced` 發出時就要決定，但實際競爭發生在 allocation 時。多節點
後，同一個 `(owner, sku)` 的訂單可能被 ③ Sourcing 分到不同節點，那些訂單其實不競爭
——**partition 會過度收斂**。

理想單位是 `(owner, node, sku)`，但事件發出時還不知道 node。這是策略本身的限制，
不是實作瑕疵，記錄於此避免日後誤判為 bug。

---

## 影響檔案

### 結構性必改

| 檔案 | 原因 |
| --- | --- |
| `allocation/domain/model/StockPool.java` | key 加 `expireDate`、`group`；加 `consume()` |
| `allocation/domain/model/ReservationStatus.java` | 加 `CONSUMED` |
| `allocation/domain/model/StockReservation.java` | 粒度變為 line × 批次 |
| `allocation/domain/service/AllocationService.java` | 加入批次篩選與 FEFO 配對；`allocate()` 回傳多筆批次分配而非單一結果 |
| `allocation/domain/service/AllocationOutcome.java` | 需區分「完全無批次」與「有批次但全不可售」——兩者的畫面訊息不同 |
| `allocation/domain/repository/StockPoolRepository.java` | `findBySku` 改為批次查詢，見「Repository 介面的改動」 |
| `allocation/application/command/ReplenishStockCommand.java` | 加 `expireDate`、`group` |

### 簽章傳染

`OrderAllocationCoordinator`、`AllocateOrderUsecase`、`ReleaseReservationUsecase`、
`ReplenishmentUsecase`、`GetStockPoolUsecase`、`AllocationRequest`、
`AllocationSelector`、`AllocationPolicy` 與兩個 policy 實作、`OrderAllocationCompleted`

### 持久層與契約

| 類別 | 檔案 |
| --- | --- |
| Entity／Mapper | `StockPoolEntity`、`StockPoolMapper`、`StockReservationEntity`、`StockReservationMapper` |
| Repository | `StockPoolRepository(+Impl)`、`JpaStockRepository`、`StockReservationRepository(+Impl)`、`JpaStockReservationRepository` |
| Migration | `stock_pools` 的 unique key 改為 `(owner_id, node_id, sku_code, expire_date, group)`；建立 `order_lines`；`stock_reservations` 的 FK 改為 `order_line_id` |
| Kafka | `OrderAllocatedIntegrationEvent` 加批次資訊；`BackorderCreatedIntegrationEvent`；**`OrderingDomainEventTranslator` 的 partition key 改為 `ownerId:skuCode`** |
| REST | `StockPoolController`、`StockPoolResponse`（改為批次列表） |
| 其他 | `bootstrap/DevSeedDataInitializer`、`e2e/perf/k6/*`、`frontend/` |

**`stock_pools` 的四個維度擴張——貨主、節點、效期、良品狀態——應在同一次 migration
完成。** 分次做等於對同一組 unique constraint 與所有查詢改四輪，中間狀態沒有價值。
該次 migration 的完整清單（含 `order_lines` 與 `stock_reservations` 的 FK）見
[system-layer-map.md 的「第一步」](system-layer-map.md)。

### 測試前提失效

`AllocationHotSkuConcurrencyIntegrationTest`、
`AllocationFifoReplenishmentBatchIntegrationTest`、
`AllocationConcurrencyEndToEndIntegrationTest` 的前提（單池熱點競爭）在批次化後失效。
熱點的定義從「一個 SKU」變成「一個批次」，需重新設計。

`AllocationFifoReplenishmentBatchIntegrationTest` 另有一個語意問題：補貨後的重配順序
是訂單的 FIFO（外層），與批次的 FEFO（內層）是兩件事，測試須明確區分。

---

## Seed 資料

| 項目 | 內容 | 展示什麼 |
| --- | --- | --- |
| 同 SKU 多批次 | 3 批，效期分別為近、中、遠 | FEFO 取用順序 |
| 不良品批次 | 1 批 `group = DAMAGED`，數量充足 | 硬約束：有貨但不可售 |
| 已過期批次 | 1 批 `expireDate` < 今日 | 硬約束：效期也會排除批次 |
| 跨批次需求 | 1 張單的需求量超過單一批次 | 一個 line 吃多個批次 |

第二、三列合起來讓「總量 100、ATP 60」這個差距在畫面上有明確解釋。

---

## 對 Demo 操作台的影響

| 頁 | 增量 |
| --- | --- |
| 庫存頁 | 由「一個 SKU 三個數字」改為**批次列表**：效期、良品狀態、數量、是否可售 |
| 訂單詳細頁 | 顯示**配到哪些批次、各多少**——這是內層演算法的唯一可見輸出 |

庫存頁的「是否可售」欄要標出落選理由（不良品／已過期），否則觀看者只會看到 ATP
比總量少，不知道為什麼。

---

## 明確不做

| 項目 | 理由 |
| --- | --- |
| `lotNumber` 批號 | 不改變配對邏輯，只是 tie-breaker；本專案無召回追溯需求 |
| `goodQty` / `badQty` 欄位 | 被 `group` 維度取代，維度可擴充而欄位不行 |
| 各維度的可替換開關（11 個） | 只做兩個維度時退化：`group` 永遠不可換，`expireDate` 由 FEFO 規則決定 |
| `productType` 贈品／組合／虛料號 | 需要商品結構（BOM）才有意義；只是過濾輸入，不改演算法。列為選配 |
| `sellValidity` 允售天數 | 只是把效期門檻從「今日」推到「今日 + N」，不產生新的約束類型 |
| `isStockNecessary` 缺貨行為分岔（ship-partial） | 採 ship-complete。它不是狀態分岔而是**第二套配貨演算法**（跨 SKU 原子 vs 逐 SKU 獨立），並存要付兩條路徑與兩套隊列語意的成本 |
| 客戶指定批次 | 需要 demand 側帶批次條件，會讓配對從「排序取用」變成「條件比對」 |
| 安全庫存水位、補貨點 | 屬規劃層，見 [system-layer-map.md](system-layer-map.md) |
| LIFO、LEFO 等其他取用規則 | FEFO 已足夠展示排序規則可替換；多做是同類型重複 |
