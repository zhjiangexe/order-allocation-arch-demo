# R1 訂單資料模型：資料表與 Usecase 的新增與變更

> **2026-07-29 更新**：③ Sourcing／R6 已移出範圍，本文件中所有「R6 會讀它」「R6 的決策輸入」
> 之類的理由**均已失效**。這是 R1 的設計記錄，因此不改寫那些脈絡——它們忠實記載了當時的判斷
> 依據。但**欄位本身有變動的**已就地標註（見 `allow_split_shipment`、`assigned_facility_id`、
> `requested_facility_id` 三條）。移出的理由見
> [system-layer-map.md](system-layer-map.md) 的「為什麼不做 ③」。


狀態：規劃，未確認

日期：2026-07-27

## 這份文件回答什麼

[execution-roadmap.md](execution-roadmap.md) 的 R1 列出了任務清單，本文件把其中的
**資料模型與 usecase 逐欄位、逐方法展開**，作為開 proposal 與寫 migration 的直接依據。

| 相關文件 | 涵蓋 |
| --- | --- |
| [execution-roadmap.md](execution-roadmap.md) | **R1 的定位、依賴、驗收與風險（本文件的上游）** |
| [dom-order-intake-scope.md](dom-order-intake-scope.md) | 為什麼是這些欄位、header／line 的判準、款／規格拆兩層的理由 |
| [dom-sourcing-scope.md](dom-sourcing-scope.md) | `temperature_zone`、`weight_gram` 的下游用途 |

本文件不重複「為什麼」，只回答「**是什麼**」。設計理由一律指回來源文件。

## 範圍界線

R1 **不動** `stock_pools`、`stock_reservations`、`facilities`。跨貨主隔離在 R3
才生效，這是 roadmap 明確標註的已知中間狀態。

---

## 一、新增資料表

### `owners` 貨主主檔

3PL 的貨主。倉庫不擁有貨，貨屬於委託方。

| 欄位 | 型別 | 約束 | 說明 |
| --- | --- | --- | --- |
| `id` | `UUID` | PK | |
| `code` | `VARCHAR(64)` | `NOT NULL`、`UNIQUE` | 貨主代號，人可讀。畫面與 log 用 |
| `name` | `VARCHAR(255)` | `NOT NULL` | 貨主名稱。畫面顯示，不能只存 id |
| `status` | `VARCHAR(32)` | `NOT NULL` | `ACTIVE` / `SUSPENDED`。停用的貨主不得收單 |
| ~~`allow_split_shipment`~~ | — | — | **2026-07-29 砍除**。它的定義是「是否允許跨節點拆單」，而一張訂單只能一個倉、明細不可跨倉，這個開關沒有東西可以開關 |

（以下段落已失效，保留為記錄）`allow_split_shipment` 在 R1 只是**存下來**，沒有任何讀取端——R6 才用。它現在就建的
理由是它屬於貨主主檔的自然欄位，補在後面要再改一次表與 seed。

### `products` 商品款主檔

一款商品有多個規格。溫層屬於款層級。

| 欄位 | 型別 | 約束 | 說明 |
| --- | --- | --- | --- |
| `owner_id` | `UUID` | PK 之一、FK → `owners` | 3PL 裡商品編碼由貨主自訂，不同貨主會撞號 |
| `product_code` | `VARCHAR(64)` | PK 之一 | 貨主自訂的款號 |
| `name` | `VARCHAR(255)` | `NOT NULL` | 品名。畫面顯示為「品名 · 規格」的前段 |
| `temperature_zone` | `VARCHAR(32)` | `NOT NULL` | `AMBIENT` / `CHILLED` / `FROZEN`。原為 R6 選點的硬約束，該用途已隨 ③ 移出範圍；欄位保留，它仍是商品的事實 |

PK 為 `(owner_id, product_code)`。

**溫層放在款層級而非規格層級**，是為了讓「同款兩種溫層」這類髒資料在結構上無法產生
——那種錯誤原本會拖到選點時才浮現。理由見
[dom-order-intake-scope.md](dom-order-intake-scope.md) 的「商品主檔為何拆成款與規格
兩層」。

### `skus` 規格主檔

款之下的具體規格。重量屬於規格層級。

| 欄位 | 型別 | 約束 | 說明 |
| --- | --- | --- | --- |
| `owner_id` | `UUID` | PK 之一、FK 之一 → `products` | 同上 |
| `sku_code` | `VARCHAR(64)` | PK 之一 | 貨主自訂的規格編碼。**這就是現有程式碼裡那個裸字串 `sku`** |
| `product_code` | `VARCHAR(64)` | `NOT NULL`、FK 之一 → `products` | 所屬款 |
| `spec_name` | `VARCHAR(255)` | `NOT NULL` | 規格，如「500ml」「XL」。畫面顯示為「品名 · 規格」的後段 |
| `weight_gram` | `INTEGER` | `NOT NULL`、`> 0` | **R6 成本函數的運費基準**。500ml 與 1L 重量不同，因此在規格層級 |

PK 為 `(owner_id, sku_code)`；FK 為 `(owner_id, product_code)` → `products`。

**只放這四個欄位。** `hazmat_class`（與溫層機制相同）、`volume_cm3`（需要材積重取大者
但決策類型不變）、`unit_of_measure`、`storage_requirement`（履約層最小版無上架、無儲位
容量）都不做，理由見
[dom-order-intake-scope.md](dom-order-intake-scope.md) 的「SKU 主檔只放三個欄位的理由」。

### `order_lines` 訂單行

| 欄位 | 型別 | 約束 | 說明 |
| --- | --- | --- | --- |
| `id` | `UUID` | PK | |
| `order_id` | `UUID` | `NOT NULL`、FK → `orders` | |
| `line_no` | `INTEGER` | `NOT NULL`、`UNIQUE (order_id, line_no)` | 上游單的行號，保留原始結構 |
| `owner_id` | `UUID` | `NOT NULL`、FK 之一 → `skus` | **反正規化**，見下 |
| `sku_code` | `VARCHAR(64)` | `NOT NULL`、FK 之一 → `skus` | |
| `quantity` | `INTEGER` | `NOT NULL`、`> 0` | |
| ~~`assigned_facility_id`~~ | — | — | **2026-07-29 砍除**（R2 執行）。它放在 line 的唯一理由是跨倉拆單，而明細不可跨倉，它永遠等於 header |
| `status` | `VARCHAR(32)` | `NOT NULL` | 行狀態。R1 與整單狀態同步，R8 才會分歧 |
| `backordered_since` | `TIMESTAMPTZ` | 可空 | 這一行進入缺貨的時間。**從 R8 提前**，理由見下 |

FK 為 `(owner_id, sku_code)` → `skus`。

**`owner_id` 反正規化存在 line 上**的三個理由：值不可變（一張單的貨主不會改變，無同步
問題）、讀取路徑（配貨與揀貨直接讀 line，join header 取貨主是多餘往返）、FK 完整性
（`(owner_id, sku_code)` 可直接建外鍵，否則只能在應用層檢查）。

（以下已失效，`assigned_facility_id` 於 R2 砍除）**`assigned_facility_id` 與 `status` 在 line 而非 header**，因為拆單後不同 line 可能從不同
節點出。放 header 之後必須搬遷。R1 先建欄位是為了避免 R6 再改一次表。

**`backordered_since` 從 R8 提前到 R1，理由是 index 而非領域事實。** 這一點要寫清楚，
否則下一個人會以為 line 可以獨立缺貨：

採 ship-complete（見 [dom-promising-scope.md](dom-promising-scope.md)）之後，所有 line 在
同一個交易內一起配到或一起缺貨，所以 line 的 `backordered_since` **恆等於 header 的值**。
它在這裡純粹是為了建出單表 FIFO index——FIFO 查詢的篩選鍵（`owner_id`、`sku_code`）在
line、排序鍵在 header，橫跨兩張表的「篩選 ＋ 排序」無法用單一複合 index 覆蓋。

**`allocated_at` 不放 line。** 同樣的邏輯下它也恆等於 header，但沒有任何 index 需要它，
所以是純冗餘欄位。

`backordered_since` 的反正規化與 `owner_id` 同一個判準：值不可變，無同步成本。

---

## 二、變更資料表：`orders`

### 新增欄位

| 欄位 | 型別 | 約束 | 說明 |
| --- | --- | --- | --- |
| `owner_id` | `UUID` | `NOT NULL`、FK → `owners` | 決定可動用哪批庫存。R3 才真正生效 |
| `external_order_no` | `VARCHAR(128)` | `NOT NULL` | 上游單號。**R5 的冪等鍵**，R1 只收下不去重 |
| `ship_to_zone` | `VARCHAR(32)` | `NOT NULL` | 配送分區（郵遞區號前三碼或縣市）。**R6 的決策輸入** |
| `ship_to_address` | `VARCHAR(512)` | `NOT NULL` | 完整地址。履約與面單用，sourcing 不看 |
| `promised_delivery_date` | `DATE` | `NOT NULL` | 承諾到貨日。**R6 時效項的基準** |
| `requested_facility_id` | `UUID` | 可空 | **R2 更名為 `facility_id` 並改 `NOT NULL` ＋ 補 FK**。沒有選點可跳過了——貨主指定的就是實際出貨倉 |
| `fulfilled_at` | `TIMESTAMPTZ` | 可空 | 整單出貨完成時間。**從 R7 提前**，只為了讓 `orders` 只被 ALTER 一次。R1～R7 之間恆為空 |

### 新增 constraint

| Constraint | 說明 |
| --- | --- |
| `UNIQUE (owner_id, external_order_no)` | **從 R5 提前。** R5 真正的工作是 usecase 行為（重送時回傳既有訂單），但 constraint 只有一行、且屬同一次 migration。提前的價值在於 R1～R5 之間**把「靜默建立重複訂單」變成「明確報錯」**——在倉儲場景前者是資料事故，後者只是錯誤訊息 |

**提前 constraint 不等於冪等完成。** R1～R5 之間重送同一張單會得到資料庫錯誤而非既有
訂單，那仍然不是正確行為，只是安全的錯誤行為。

地址**內嵌 `orders` 而不另開 `addresses` 表**：這裡沒有地址簿需求，地址逐單指定、不可
重用，獨立表會憑空多一層 join。

### 移除欄位

| 欄位 | 去哪 |
| --- | --- |
| `sku` | 搬到 `order_lines.sku_code` |
| `quantity` | 搬到 `order_lines.quantity` |

**這是 R1 最容易漏的地方。** 所有 `order.getSku()` 的呼叫點都會編譯失敗，包含
allocation 側的 `AllocationService.requireMatchingSku()` 與兩個 `AllocationPolicy`
實作。動手前先跑一次全域搜尋。

### 不變欄位

`id`、`status`、`placed_at`、`allocated_at`、`backordered_since`、`cancelled_at`、
`version` 全部保留，語意不變。

### header 與 line 的時間戳分工

採 ship-complete 之後這件事很簡單：**所有 line 在同一個交易內一起配到或一起缺貨**，
因此 line 的時間戳與 header 的時間戳恆等。

| 欄位 | 在 header | 在 line | 說明 |
| --- | --- | --- | --- |
| `placed_at` | ✓ | — | 整張單同時下單 |
| `allocated_at` | ✓ | — | ship-complete 下 line 的那份是純冗餘 |
| `backordered_since` | ✓ | **✓** | line 的那份**只為了單表 FIFO index**，值恆等於 header |
| `cancelled_at` | ✓ | — | 取消是整單行為 |
| `fulfilled_at` | ✓ | 待 R7 決定 | 拆單後不同 line 可能從不同節點出，出貨時間可能不同 |

**沒有聚合函數。** 若日後改採 ship-partial，`allocated_at` 才需要「全部 line 配到時取
最大值」這類規則，`PARTIALLY_ALLOCATED` 也才會出現。

**FIFO 查詢讀 line 的 `backordered_since`**（走 index）；header 的那份給畫面看。兩者
恆等，所以不會分歧。

### Index 的連鎖

| Index | R1 的處置 |
| --- | --- |
| `idx_orders_backorder_fifo (sku, status, backordered_since, id)` | **被 PostgreSQL 強制刪除**——`ALTER TABLE orders DROP COLUMN sku` 會自動移除所有引用該欄位的 index，這不是排程選擇 |
| **新增** `idx_order_lines_backorder_fifo (owner_id, sku_code, backordered_since, id)` | 建在 `order_lines` 上。**這是 R8 任務 5 提前到 R1**，因為不提前就沒有 index 可用。**不含 `status`**，理由見下 |
| `idx_orders_recent (placed_at DESC, id DESC)` | 不受影響 |

新 index 含 `owner_id` 是必要的：不同貨主的 backorder 隊列必須分開排序，A 貨主的單不
應該被 B 貨主的單卡住。

欄位順序與舊 index 同一個道理——等值篩選（`owner_id`、`sku_code`）在前，排序鍵
（`backordered_since`）其次，`id` 作為 tie-breaker 確保同一毫秒進 backorder 的多行順序
穩定。

**刻意不含 `status`。** 舊 index 有它，因為舊查詢是 `WHERE status = 'BACKORDERED'`。但
R4 之後待配佇列的查詢**不能依 status 過濾**——ordering 的配貨狀態落後於 allocation 的
決策，拿它當閘門會重複預留（見
[dom-order-intake-scope.md](dom-order-intake-scope.md) 的「view 的定義」）。

而 `status` 若留在 `sku_code` 與 `backordered_since` 之間，index 掃出的列會先按 status
分組、再按時間排序——查詢不篩 status 時 PostgreSQL 仍得排序一次，index 等於白建。

---

## 三、Domain 的新增與變更

### 新增 aggregate 與 entity

| 型別 | 種類 | 說明 |
| --- | --- | --- |
| `Owner` | aggregate root | `code`、`name`、`status`、`allowSplitShipment`。R1 無行為方法，純主檔 |
| `Product` | aggregate root | `ownerId`、`productCode`、`name`、`temperatureZone` |
| `Sku` | aggregate root | `ownerId`、`skuCode`、`productCode`、`specName`、`weightGram` |
| `OrderLine` | **entity（`Order` 的一部分）** | 不是 aggregate root。生命週期屬 `Order`，不可獨立存取。持有 `status`、`allocatedAt`、`backorderedSince`、`assignedFacilityId` |

`OrderLine` 是 entity 而非 aggregate root：它沒有獨立的一致性邊界，數量與狀態的變更
必須經過 `Order` 才能維持整單狀態的一致。

### `Order` 的變更

| 現況 | R1 之後 |
| --- | --- |
| `place(id, sku, quantity, placedAt)` | `place(id, ownerId, externalOrderNo, shipTo…, lines, placedAt)`，**lines 先限定恰好一筆** |
| `getSku()`、`getQuantity()` | **移除**。改由 `getLines()` 取得 |
| `rehydrate(...)` 九個參數 | 加 `ownerId` 等六個 header 欄位與 lines。**不施加「恰好一筆」限制**——它的職責是還原資料庫裡的東西，而 schema 從 R1 就允許 N 筆 |
| `markAllocated`、`markBackOrdered`、`cancel` | **簽章不變**，但內部要同時寫 header 與所有 line（ship-complete 下兩者恆等）。R1 恰好一筆 line，因此行為不變 |
| `releaseDomainEvents()` | 不變 |

**「每張單恰好一筆 line」的限制只寫在 `Order.place()` 裡**，不在 schema、也不在
`rehydrate()`。三者的分工是刻意的：

| 位置 | 是否限制一筆 | 理由 |
| --- | --- | --- |
| schema | 否 | R8 才不用改表——這是 R8「不搬遷任何結構」的前提 |
| `Order.place()` | **是** | 「恰好一筆」是**收單政策**，不是 domain invariant |
| `Order.rehydrate()` | 否 | 它必須還原資料庫裡的任何東西 |

`rehydrate()` 不設限的直接用途是：**測試可以造出 N=2 的 `Order`**，把讀取、聚合、
配貨、事件全部在多行下驗過，即使正式入口還進不來。見「測試」一節。

`OrderStatus` **不加 `PARTIALLY_ALLOCATED`**——採 ship-complete 之後這個狀態不存在，
不只是「留到 R8」。R1 的 line 狀態與整單狀態同步，R8 之後仍然同步。

### Domain event 的變更

| 事件 | 變更 |
| --- | --- |
| `OrderPlaced` | 加 `ownerId`、`shipToZone`、`promisedDeliveryDate`；`sku`／`quantity` 改為 line 清單 |
| `OrderBackordered` | 加 `ownerId`；`sku` 改為來自 line |
| `OrderAllocated` | 同上 |
| `OrderCancelled` | 加 `ownerId` |

---

## 四、Usecase 的新增與變更

### 新增

| Usecase | 簽章 | 說明 |
| --- | --- | --- |
| `ListOwnersUsecase` | `List<Owner> listAll()` | 前端下單表單的貨主下拉選單需要 |
| `ListProductsUsecase` | `List<Product> listByOwner(UUID ownerId)` | 前端選商品；也供語意驗證（1.4，選配）之後使用 |
| `ListSkusUsecase` | `List<Sku> listByProduct(UUID ownerId, String productCode)` | 選規格 |

三支都是純查詢，無狀態變更。**R1 不新增任何寫入型 usecase**——`Owner`／`Product`／
`Sku` 由 seed 建立，主檔維護介面不在範圍內。

### 變更

| Usecase | 現況 | R1 之後 |
| --- | --- | --- |
| `PlaceOrderUsecase` | `placeOrder(String sku, Integer quantity) → Order` | `placeOrder(PlaceOrderCommand) → Order`。command 帶貨主、上游單號、收件地、承諾到貨日與 line 清單 |
| `GetOrderUsecase` | `getOrder(UUID) → Order` | 簽章不變，回傳的 `Order` 帶 lines |
| `ListRecentOrdersUsecase` | `listRecent(int) → List<Order>` | 簽章不變，回傳的 `Order` 帶 lines |
| `CancelOrderUsecase` | `cancel(UUID, Instant)` | 簽章不變 |

`PlaceOrderUsecase` 從六個位置參數改為單一 command 物件的理由：header 欄位加到六個
之後，位置參數的呼叫端可讀性崩潰，而且 line 清單無法用位置參數自然表達。

### Repository 介面的變更

| 介面 | 變更 |
| --- | --- |
| `OrderRepository.save/findById/findRecent` | 簽章不變，`Order` 內含 lines |
| `OrderRepository.findBackordersBySkuInFifoOrder(String sku)` | **加 `ownerId` 參數**：`findBackordersBySkuInFifoOrder(UUID ownerId, String skuCode)`。不同貨主的 backorder 隊列必須分開 |
| `OwnerRepository`、`ProductRepository`、`SkuRepository` | **新增**，各含 `save` 與查詢 |

`findBackordersBySkuInFifoOrder` 這個方法本身屬於 allocation 卻長在 ordering 的
repository 上——那是 R4 要處理的耦合，R1 只加參數，不搬家。

---

## 五、對外契約的變更

### Integration event

| 事件 | 變更 | 影響的消費端 |
| --- | --- | --- |
| `OrderPlacedIntegrationEvent` | 加 `ownerId`、`shipToZone`、`promisedDeliveryDate`；`sku`／`quantity` 改為 line 清單 | allocation 的 `OrderPlacedIntegrationEventHandler` |
| `OrderCancelledIntegrationEvent` | 加 `ownerId` | allocation 的 `OrderCancelledIntegrationEventHandler` |

**Kafka partition key 在 R1 不改。** `sku` 策略目前用裸 `sku`，多貨主下會製造假競爭
（不同貨主的同名 SKU 收斂到同一 partition），但修正它要等 R3——那時 `stock_pools` 才
有 `owner_id`，`ownerId:skuCode` 這個 key 才有對應的實體。R1 留著這個已知缺陷。

### REST

| 端點 | 變更 |
| --- | --- |
| `POST /orders` | request body 從 `{sku, quantity}` 改為帶貨主、上游單號、收件地、承諾到貨日與 lines |
| `GET /orders` | 回應每筆加 `ownerId`、`ownerName`；`sku`／`quantity` 移入 lines |
| `GET /orders/{orderId}` | 同上 |
| `GET /stock-pool/{sku}` | **不變**。R3 才加貨主維度 |

`GET /orders` 的回應加 `ownerName` 是刻意的反正規化：列表要顯示貨主名稱，讓前端為每
一列再打一次 `/owners/{id}` 是 N+1。

### 前端

| 畫面 | 變更 |
| --- | --- |
| 下單表單 | 加貨主下拉、上游單號、收件分區、完整地址、承諾到貨日；商品改為「款 → 規格」兩段選擇 |
| 訂單列表 | 加貨主欄；SKU 欄顯示「品名 · 規格」；**一列仍是一筆 line**（多列展開屬 R8） |
| 型別模組 | `OrderView` 加 header 欄位與 `lines`；新增 `OwnerView`、`ProductView`、`SkuView` |

---

## 六、Seed 資料

| 資料 | 內容 | 為什麼是這個組合 |
| --- | --- | --- |
| 貨主 | 兩個 | ~~`allow_split_shipment` 的對比~~已失效（欄位砍除）。對比組的新軸線見 roadmap R3 的待定事項 6（貨主×倉庫的效期管理與換批號設定） |
| 商品 | 常溫一款、冷凍一款 | R6 溫層硬約束需要至少兩種溫層才看得出篩選 |
| 規格 | 其中一款帶兩個規格（重量不同） | 讓款／規格兩層在畫面上看得出來；重量不同才驗得到 R6 的成本函數 |
| 訂單 | 每個貨主一張，各一筆 line | 驗證貨主欄位與 line 結構 |

**兩個貨主要用相同的 `sku_code`**，例如兩者都有 `SKU-A`。這是 3PL 撞號情境的最小
再現，R3 的 `requireMatchingOwner()` 要靠它驗證。

---

## 七、測試的變更

| 測試 | 變更性質 |
| --- | --- |
| `PlaceOrderUsecaseTest` | 簽章調整 |
| `GetOrderUsecaseTest`、`OrderControllerTest` | 簽章調整 |
| `CancelOrderUsecaseTest` | 簽章調整 |
| `OrderTest` | 加 line 相關斷言；**加一支斷言「lines 為空或多於一筆時 `place()` 拒絕」** |
| `OrderMapperTest` | 加 lines 的映射 |
| `OrderPersistenceIntegrationTest` | `findBackordersBySkuInFifoOrder` 加 `ownerId`；**FIFO index 的斷言改為斷言 `order_lines` 上的新 index**（欄位與方向都要驗，沿用既有 `createsRecentOrdersIndex` 的手法） |
| allocation 側的 handler 測試 | 事件契約變更的連帶 |
| `e2e/perf/k6/hot-sku-burst.js` | request body 變更（加貨主、上游單號、收件地、承諾到貨日，SKU 移入 lines） |

### line 數量無關性的防護

「schema 是 line 形狀」不保證「邏輯與 line 數量無關」。R3～R6 的程式碼會在每張單恰好
一行的環境下寫成並通過測試，而下列寫法在單行下**是正確的、不是 bug**：

| 寫法 | 單行下 | 多行下 |
| --- | --- | --- |
| `order.getLines().get(0)` | 正確 | 只處理第一行 |
| 逐行獨立判斷可滿足性、配得到就預留 | 正確 | **違反 ship-complete**，會為出不去的單鎖住庫存 |
| 一次配貨只碰一個 `StockQuant` | 正確 | 會碰多個，排序鍵不含 `sku_code` 就有死鎖風險 |

它們沒有任何訊號會在 R8 時提醒你。因此 R1 要付出三項防護，讓 N=2 的路徑從一開始就
一直在跑：

| # | 防護 | 內容 |
| --- | --- | --- |
| 1 | **N=2 fixture** | 以 `Order.rehydrate()` 造兩行訂單，驗讀取路徑、`OrderMapper` 往返、`OrderStatusResponse` 序列化 |
| 2 | **整籃原子性的 N=2 測試** | 兩行中一行可滿足、一行不可滿足時，**兩行都不得預留**，整單進 `BACKORDERED`。單行下這條退化成「配不到就缺貨」，所以「逐行獨立配貨」的錯誤實作會通過所有其他測試 |
| 3 | **架構測試** | 斷言 production code 不出現 `getLines().get(` 與 `.getFirst()`。手法與 roadmap 的 R4 任務 9（斷言 allocation 不得 import `Order`）相同 |

第 2 項是最容易跳過也最危險的一項。

R3 另有兩項對應的防護，見 roadmap 的 R3 任務。

### 壓測基準

**壓測基準維持可比**，因為 FIFO index 在 R1 就重建於 `order_lines`。若當初把 index
重建留在 R8，R1～R8 全程都沒有這個 index，壓測數字會斷掉且無法歸因——那是提前它的
主要理由。

---

## 八、明確不做

| 項目 | 歸屬 |
| --- | --- |
| 收單冪等的**行為**（重送時回傳既有訂單） | R5。欄位與 unique constraint 已在 R1 |
| 多筆 line、拆單 | R8。`backordered_since` 與 FIFO index 已在 R1 |
| `PARTIALLY_ALLOCATED` | **不做**。採 ship-complete，見 [dom-promising-scope.md](dom-promising-scope.md) |
| `stock_pools` 的任何變更、跨貨主隔離 | R3 |
| `facilities` 極簡主檔（`facility_coverage` 已隨 ③ 移出範圍） | R2 |
| ~~選點決策、`assigned_facility_id` 的填值~~ | **已移出範圍**（2026-07-29） |
| Partition key 改為 `ownerId:skuCode` | R3 |
| 主檔的寫入介面（Owner／Product／Sku 的 CRUD） | 不做，seed 建立即可 |
| 語意驗證（`sku_code` 是否存在於主檔） | 選配，1.4 |
| `fulfilled_at` 的**寫入**（出貨完成時填值） | R7。欄位已在 R1 |
| ~~`order_lines.assigned_facility_id` 的 FK~~ | **欄位於 R2 砍除**，改為 `orders.facility_id` 補 FK |
