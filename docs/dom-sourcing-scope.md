# 訂單層 ③ Sourcing/Routing：職責盤點與決策模型

> **狀態：已移出範圍（2026-07-29）。本文件保留為分析記錄，不再是待實作的計畫。**
>
> 移出的理由不是規模，是**這一層在 3PL 裡沒有決策**：貨主在上游下單時就指定倉別，
> 系統照做。沒有選擇就沒有選點問題——本文件定義的成本函數、覆蓋範圍與節點能力比對
> 全部沒有輸入來源。完整理由見 [system-layer-map.md](system-layer-map.md) 的
> 「為什麼不做 ③」與 [execution-roadmap.md](execution-roadmap.md) 的「為什麼沒有 R6」。
>
> **倉庫這個維度仍然保留**（庫存分倉、訂單指定倉、出貨屬於某倉），砍掉的只是「系統選倉」。
> 若日後要補回本文件描述的決策，資料層是純加法——護欄與成本評估見 roadmap 的同一節。

狀態：分析，未確認

日期：2026-07-26

## 這份文件回答什麼

本文件盤點 DOM 三職責中 ③ Sourcing/Routing 的完整職責，定義決策模型與所需主檔，
並列出對既有程式碼的影響範圍。

| 相關文件 | 涵蓋 |
| --- | --- |
| [execution-roadmap.md](execution-roadmap.md) | **八個 change 的順序、依賴、任務與禁忌** |
| [system-layer-map.md](system-layer-map.md) | 全流程分層、跨層契約、帳務交會點、用詞決定 |
| [dom-order-intake-scope.md](dom-order-intake-scope.md) | ① 收單編排、訂單與商品資料模型、貨主 |
| [dom-promising-scope.md](dom-promising-scope.md) | ② Promising：批次庫存模型與 FEFO 配貨演算法 |
| [fulfillment-minimal-scope.md](fulfillment-minimal-scope.md) | 履約層（最小版）：兩本帳與短揀對帳 |
| [fulfillment-full-scope.md](fulfillment-full-scope.md) | 履約層（深做版）：從最小版的增量 |

本文件不是 change proposal，不含任務拆解與驗收條件。

## 前提

**Routing 在 DOM 的語意是「選出貨節點」，不是「規劃配送路線」。** 後者屬 TMS。
兩者同名不同義，是這一層最常見的範圍誤解。

分流資訊（收件地、貨主、指定倉）由 ① 收單時受理並保管，③ 只負責使用。欄位定義見
[dom-order-intake-scope.md](dom-order-intake-scope.md)。

商品主檔（`skus`）由 ① 保管，但它的兩個欄位——溫層與重量——都是為本層服務的。定義見
[dom-order-intake-scope.md](dom-order-intake-scope.md)。

---

## 完整職責盤點

| # | 職責 | 說明 | 歸屬 | 本系統現況 | 要補 |
| --- | --- | --- | --- | --- | --- |
| 3.1 | **節點主檔** | 有哪些倉／店／DC，各自狀態與能力 | ③ | **概念不存在** | **要** |
| 3.2 | **配送目的地** | 訂單要送到哪裡 | ① 收、③ 用 | **`Order` 無此欄位** | **要** |
| 3.3 | **覆蓋範圍** | 哪個節點能送到哪一區 | ③ | **概念不存在** | **要** |
| 3.4 | **分節點分貨主庫存** | 某貨主的某 SKU 在各節點的 ATP，為批次可售量的聚合 | ②，③ 依賴 | **全域單池，無節點亦無貨主** | **要** |
| 3.5 | **候選節點篩選** | 排除停用、不可達、無庫存、**處理能力不符**的節點 | ③ | 無 | **要** |
| 3.5b | **節點處理能力** | 節點支援哪些溫層；與 SKU 溫層比對 | ③ | **概念不存在** | **要** |
| 3.6 | **成本函數** | 運費、時效、負載、拆單懲罰的加權 | ③ | 抽象已在，輸入太瘦 | **要** |
| 3.7 | **節點選擇** | 依成本函數選出出貨節點 | ③ | 無 | **要** |
| 3.8 | **決策可追溯** | 為何選 A 不選 B | ③ | 無 | **要** |
| 3.9 | **重新選點** | 選定節點失敗時的 fallback | ③ | 無 | **要** |
| 3.10 | **拆單決策** | 單一節點無法滿足時，是否拆成多筆出貨 | ③ | 無 | **要**——單品項下即成立 |
| 3.11 | **節點產能管理** | 各節點當日出貨上限與截單時間 | ③ | 無 | **要** |
| 3.12 | 貨主的節點偏好 | 貨主指定只從某些倉出貨 | ③ | 無 | 選配 |
| 3.13 | 物流商選擇 | 選 carrier、比運價、下 booking | **運輸層 TMS** | — | **不做** |
| 3.14 | 路線規劃 | 車輛排程、多點配送順序 | **運輸層 TMS** | — | **不做** |
| 3.15 | 地址正規化／geocoding | 自由文字地址轉標準地址與座標 | **商務層或外部服務** | — | **不做** |

### 判定說明

**3.11 要做的理由**：沒有產能與負載項，成本函數會退化成「永遠選最近的節點」，所有
訂單堆到同一節點，決策在畫面上看起來像壞掉。負載項是讓決策「有內容」的關鍵。

**3.8 是 ③ 的價值所在**：若前端只顯示「從 A 倉出貨」，畫面上與寫死一個倉庫完全相同。
Sourcing 的產出必須包含候選集與落選理由，否則整個決策過程不可見。

**3.12 列為選配**：3PL 中貨主的貨只存放在特定節點，因此「該節點有此貨主的庫存」已
隱含了服務關係，不需要額外的授權表。只有當貨主在某節點有庫存卻不願從該節點出貨時
才需要，那是業務偏好而非結構需求。

---

## 決策模型

### 成本函數

倒推法：先定成本函數，欄位由它決定，不先建表。

```text
cost(node, order) = baseCost + Σ(lineWeight) × costPerKg      ← 運費，隨訂單內容變動
                  + w₁ × max(0, leadTimeDays - 距承諾日天數)   ← 時效違約風險
                  + w₂ × 拆單懲罰
                  + w₃ × 節點負載
```

前三項各自產生一種**可見的決策變化**：

| 項 | 變化 |
| --- | --- |
| 運費隨重量 | 重貨傾向選近倉，輕貨可選較遠但便宜的倉——同一組節點因訂單內容不同而選出不同結果 |
| 時效違約風險 | 急單選近倉，不急的單選成本低的倉 |
| **拆單懲罰** | 貨主允許拆單時傾向不拆但可拆；不允許時直接排除拆單方案 |

`promisedDeliveryDate` 是 w₁ 項的基準。**沒有它這一項算不出來**——`leadTimeDays`
有值，但沒有東西可以拿來比。

### 硬約束與軟成本

兩者性質不同，不可混為一談：

| 類型 | 判定 | 例 |
| --- | --- | --- |
| **硬約束** | 不滿足即排除候選 | 節點停用、不配送該區、該貨主在此節點無庫存、ATP 不足、**節點不支援該 SKU 的溫層** |
| **軟成本** | 進入成本函數比較 | 運費、時效、負載 |

把硬約束折算成「很高的成本」是錯的模型——不可達不是貴，是不能送。

### 主檔需求

| 主檔 | 欄位 | 對應職責 |
| --- | --- | --- |
| `Facility` | `id`、`code`、`name`、`type`（倉／門市／DC）、`zone`、`status`、**`capabilities`**（支援的溫層）、`dailyCapacity`、`cutoffTime` | 3.1、3.5、3.5b、3.11 |
| `NodeCoverage` | `(facilityId, zone)` 複合主鍵、`serviceable`、**`baseCost`**、**`costPerKg`**、`leadTimeDays` | 3.3、3.6 |
| `Order.shipToZone` | 決策用的分區；完整地址另存，sourcing 不看 | 3.2 |
| `Order.promisedDeliveryDate` | w₁ 時效項的基準 | 3.6 |
| `Sku.temperatureZone` / `weightGram` | 硬約束與運費基準；主檔由 ① 保管 | 3.5b、3.6 |
| `StockPool` | key 加 `facilityId` 與 `ownerId` | 3.4 |

`NodeCoverage` **不含貨主維度**：配送能力是節點的物理屬性，與貨是誰的無關。北倉能不
能送到高雄，跟那批貨屬於 A 貨主還是 B 貨主沒有關係。

### 距離：查表 vs 計算

| 方案 | 做法 | 問題 |
| --- | --- | --- |
| 計算 | 節點與地址存經緯度，用 haversine 算直線距離 | 距離 ≠ 運費 ≠ 時效；**無法表達「此節點不配送此區」** |
| **查表** | `(facilityId, zone) → cost, leadTime, serviceable` 對照表 | 資料量 N×M |

**採查表**。理由是正確性而非資料量：

1. 真實成本是離散的——離島加價、偏遠加價、跨區不配送——連續函數表達不出來。
2. 覆蓋範圍（`serviceable = false`）是 sourcing 最重要的硬約束。計算方案只能用「距離
   很大」逼近不可達，那是錯的模型。
3. 計算方案是查表的退化實作。seed 資料可用 haversine 生成查表的初始值——那是資料
   產生方式，不是架構妥協。

分區粒度用郵遞區號前三碼或縣市即可。完整地址是履約層才需要的資訊。

### 拆單：不是另一套邏輯，是同一個參數

是否允許跨節點拆單由 `owners.allow_split_shipment` 決定，但**它不產生第二條程式
路徑**：

```text
w₂ = ∞        → 絕不拆單。等同「找一個能滿足全單需求的節點，找不到就 backorder」
w₂ = 有限值   → 允許拆，但傾向不拆
```

「不跳倉」是「允許跨節點」把拆單懲罰設為硬約束的**特例**。因此支援兩者的成本
≈ 只支援後者的成本。

單品項下即成立：一條 line 需求 20、北倉有 12、中倉有 8——

| `allow_split_shipment` | 結果 |
| --- | --- |
| `true` | 拆成兩個 `Shipment`，北倉 12、中倉 8 |
| `false` | 兩個節點都不足，訂單進 `BACKORDERED` |

因此本項**不依賴多品項**（① 段 C），③ 完成即可展示。欄位定義與歸屬理由見
[dom-order-intake-scope.md](dom-order-intake-scope.md)。

### 拆單對 `Shipment` 粒度的影響

`Shipment` 的粒度是 `(order, node)`——同一張訂單中被配到同一節點的所有 line 合成
一張出貨單。拆單時一條 line 可能橫跨兩個 `Shipment`：

```text
Order 1 · Line A（需求 20）
   ├── Shipment(北倉) ── PickTask → orderLineId = A, qty 12
   └── Shipment(中倉) ── PickTask → orderLineId = A, qty  8
```

因此 `PickTask → orderLineId` 是**多對一**。履約層的模型見
[fulfillment-minimal-scope.md](fulfillment-minimal-scope.md)。

### 演算法選型

節點選擇是 assignment／transportation problem。**第一個 policy 用 greedy min-cost
即可**，不引入 LP／OR-Tools solver。

只要決策輸入模型正確，日後換 solver 只是新增一個 `AllocationPolicy` 實作，domain
零改動。反之若輸入模型省略成本或前置時間，事後補齊等於回頭重做一次
`AllocationService` 的簽章改造。

演算法在各層的完整定位見 [system-layer-map.md](system-layer-map.md)。

---

## 事實鏈的變化

現況：

```text
OrderPlaced ──▶ AllocateOrder ──▶ OrderAllocated
(sku, qty)      (全域單池)         (orderId, reservationId, sku, qty, allocatedAt)
```

`OrderAllocatedIntegrationEvent` 的五個欄位全是結果，沒有一個是決策資訊。補上
`facilityId` 只是記錄「結果是哪個節點」，不會讓 sourcing 有地方發生。

目標：

```text
OrderPlaced ──▶ SourceOrder ──▶ OrderSourced ──▶ AllocateOrder ──▶ OrderAllocated
(+ownerId)      (選節點)        (SourcingPlan)   (對選定節點扣)     (+facilityId)
(+shipToZone)                     ↑ 目前不存在的事實
(+promisedDate)
```

| 事件 | 現況 | 目標 |
| --- | --- | --- |
| `OrderPlacedIntegrationEvent` | `orderId, sku, quantity, placedAt` | 加 `ownerId`、`shipToZone`、`promisedDeliveryDate` |
| `OrderSourced` | **不存在** | `orderId`、排序後候選清單、選中節點、各節點分數與落選理由 |
| `OrderAllocatedIntegrationEvent` | 五個結果欄位 | 加 `facilityId`（實際出貨節點） |
| `BackorderCreatedIntegrationEvent` | 單池缺貨 | **語意分裂**，見下 |

**Sourcing 不是 allocation 的下游消費者，是上游決策者。** 這是本文件最關鍵的一點：
③ 不是在既有鏈末端補欄位，是在鏈中間插入一個目前不存在的階段。

### 兩個順帶浮出的問題

| 問題 | 說明 | 影響 |
| --- | --- | --- |
| Read-then-act race | sourcing 讀 ATP 決策、allocation 才實扣，中間有窗口 | `OrderSourced` 須帶**排序後的候選清單**而非單一節點，讓 allocation 可依序 fallback 而不必回頭重跑 sourcing。直接改變 `AllocationService.allocate()` 簽章 |
| Backorder 語意分裂 | 全網無貨＝真 backorder；選定節點無貨但他節點有＝ re-source | `AllocationOutcome` 目前只有 `INSUFFICIENT_ATP` 一種，須拆為至少兩種 |

「全網」在 3PL 語境下是**該貨主的全網**——A 貨主在所有節點都沒貨才算 backorder，
B 貨主同 SKU 有貨與此無關。

---

## 影響檔案

### 結構性必改（決策模型本身需重新定義）

| 檔案 | 原因 |
| --- | --- |
| `allocation/domain/service/AllocationRequest.java` | 現為 `stockPoolId, sku, availableToPromise, decisionAt`。候選節點、成本、前置時間、貨主一個都不在，需整個重定義 |
| `allocation/domain/service/AllocationService.java` | `requireMatchingSku()` 把「一單一 SKU 對一池」寫死在 domain service；`allocate()`、`allocateWaitingBatch()` 兩支簽章須改為對節點集合；另須加 `requireMatchingOwner()` |
| `stock/inventory/domain/aggregate/StockPool.java` | 加 `facilityId` 與 `ownerId`；`availableToPromise()` 語意由「全網」變為「該貨主在該節點」 |
| `ordering/domain/aggregate/Order.java` | 加 `shipToZone`；`markAllocated(Instant)` → `markAllocated(facilityId, Instant)` |
| `allocation/domain/service/selector/AllocationContext.java` | 目前是空介面，成本函數要靠它注入 |

### 簽章傳染

`AllocationSelector`、`AllocationPolicy`、`StrictFifoAllocationPolicy`、
`MaximizeFulfilledOrdersPolicy`、`BasicAllocationContext(+Factory)`、
`OrderAllocationCoordinator`、`AllocateOrderUsecase`、`ReleaseReservationUsecase`、
`ConfirmStockReceiptUsecase`、`GetStockPoolUsecase`、`StockReservation`、
`OrderAllocationCompleted`

### 持久層與契約

| 類別 | 檔案 |
| --- | --- |
| Entity／Mapper | `StockPoolEntity`、`StockPoolMapper`、`StockReservationEntity`、`StockReservationMapper`、`OrderEntity`、`OrderMapper` |
| Repository | `StockPoolRepository(+Impl)`、`JpaStockRepository`、`StockReservationRepository(+Impl)`、`JpaStockReservationRepository`、`OrderRepository(+Impl)`、`JpaOrderRepository` |
| Migration | `stock_pools` 的 unique key 由 `sku` 改為 `(owner_id, facility_id, sku)`；`orders` 加 `ship_to_zone`；新增 `facilities`、`facility_coverage`。**V2／V3 已進版本，須開新 migration 而非改原檔** |
| Kafka | `AllocationKafkaIntegrationEventConsumer` 及各 handler |
| REST | `StockPoolController`、`StockPoolResponse`、`OrderController`、`PlaceOrderRequest`、`OrderStatusResponse` |
| 其他 | `bootstrap/DevSeedDataInitializer`、`e2e/perf/k6/*`、`frontend/` |

規模：main 約 40 檔、測試約 20 檔、migration 新增 2 至 4 支。

`stock_pools` 的四個維度——貨主、節點、效期、良品狀態——應在同一次 migration 完成。
因此本層的庫存改動與 ① 段 E、② 的批次化屬同一次改動，完整清單見
[system-layer-map.md 的「第一步」](system-layer-map.md)。

### 測試前提失效

`AllocationHotSkuConcurrencyIntegrationTest`、
`AllocationFifoAvailabilityIncreaseBatchIntegrationTest`、
`AllocationConcurrencyEndToEndIntegrationTest` 三支的**測試前提**（單池熱點競爭）在
多節點後失效。熱點的定義改變，非調整 assertion 可解決，須重新設計。

---

## Seed 資料

`DevSeedDataInitializer` 目前是 3 個 SKU、3 個池、1 張單，多節點後展示不出任何東西。
最少需要：

| 項目 | 數量 | 理由 |
| --- | --- | --- |
| 節點 | 3～4 | 少於 3 個沒有選擇餘地，多於 5 個操作台畫面塞不下 |
| 配送區 | 3～4 | 其中至少一組 `serviceable = false`，用以展示硬約束 |
| 貨主 | 1～2 | 兩個貨主才能展示「同 SKU 不同貨主不可互調」 |
| SKU | 常溫與冷凍各 1 | 冷凍 SKU 才能展示溫層硬約束 |
| 節點能力 | 至少 1 個不支援冷凍 | 否則硬約束永遠不觸發 |
| 貨主拆單設定 | 兩個貨主分別為 `true` / `false` | 同一組庫存、同樣需求，兩種決策結果 |
| 關鍵情境 | 1 | 「近但缺貨」vs「遠但有貨」——唯一能一眼看出 sourcing 在決策的畫面 |

這組 seed 等同 sourcing 的驗收案例，應在 proposal 階段先行定案。它會反向檢驗欄位
是否必要：若某欄位在所有 demo 情境中都不影響結果，該欄位應刪除。

---

## 對 Demo 操作台的影響

| 後端變化 | 前端要做的 | 量 |
| --- | --- | --- |
| 目的地 | 下單表單加配送目的地欄位 | 小 |
| 分節點分貨主庫存 | 庫存頁由「一 SKU 一池三個數字」改為 SKU × 節點二維表，並帶貨主篩選 | 中 |
| 節點主檔 | **新增節點頁**（節點清單 + 覆蓋範圍矩陣） | 中 |
| 決策可追溯 | **訂單詳細頁**承載候選節點表 | 中 |

候選節點表的形態：

| 節點 | ATP | 溫層 | 運費 | 時效 | 可達 | 結果 |
| --- | --- | --- | --- | --- | --- | --- |
| 北倉 | 0 | ✓ 冷凍 | 40 | 1 天 | ✓ | ✗ 缺貨 |
| 中倉 | 50 | ✓ 冷凍 | 62 | 2 天 | ✓ | ✓ 選中 |
| 南倉 | 200 | ✗ 常溫 | 90 | 3 天 | ✓ | ✗ 無冷鏈 |
| 東倉 | 80 | ✓ 冷凍 | 55 | 4 天 | ✗ | ✗ 不配送此區 |

四列剛好各展示一種落選理由：缺貨（軟）、能力不符（硬）、不可達（硬），以及選中。
運費欄隨訂單重量變動，因此同一組節點在不同訂單下可能排出不同順序。

這是多列資料，攤不進訂單列表的單一列，因此 `add-demo-console-frontend` 的兩條
Non-Goal 在 ③ 之後必須撤銷：

| Non-Goal | 判定 |
| --- | --- |
| 不做訂單詳細頁或 modal | **撤銷**，候選節點表需要容身處 |
| 不呈現事件因果鏈 | **撤銷**，至少須呈現「計畫節點 vs 實際出貨節點」的落差 |
| 不輪詢、不做部署、不引入元件庫 | 維持 |

**立即可做的一件事**：`frontend/src/pages/` 的路由結構直接留三頁位置
（orders／stock／nodes），`AppHeader` 導覽一次排好。此舉近乎零成本，而事後由兩頁改
三頁需動 header、路由與版面。但頁面內容不應提前實作——`NodesPage` 目前連後端端點都
沒有，先放會變成死碼。

**③ 的成本估算須含前端**：節點頁 + 訂單詳細頁 + 庫存頁二維化 + 下單表單改版，約為
現有前端規模的再一倍。

---

## 明確不做

| 項目 | 歸屬 | 說明 |
| --- | --- | --- |
| 物流商選擇、比價、booking | 運輸層 TMS | Routing 在 DOM 指「選出貨節點」 |
| 車輛排程、送達追蹤 | 運輸層 TMS | 同上 |
| Geocoding、地址正規化 | 外部服務 | 分區粒度已足夠決策 |
| LP／OR-Tools solver | 未來 | 輸入模型正確的前提下，換 solver 只是新增一個 `AllocationPolicy` 實作 |
| 貨主的節點偏好（3.12） | 選配 | 有庫存已隱含服務關係 |
| 節點行事曆、假日排程 | 未來 | 不影響決策模型的正確性 |
| 危險品分級 | 精簡 | 與溫層機制相同（capability 比對），同一件事做兩遍 |
| 材積重（volume） | 精簡 | 需要「重量 vs 材積重取大者」，多一層邏輯但決策類型不變 |
| 運費到付（freightTerm） | 精簡 | 與重量費率是同一成本項的兩種操作 |
