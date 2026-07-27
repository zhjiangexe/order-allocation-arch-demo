# 執行 Roadmap：八個 change 的順序、依賴與任務

狀態：規劃，未確認

日期：2026-07-27

## 這份文件回答什麼

六份範圍文件描述「要做什麼」，本文件回答「**按什麼順序做、哪些不能先做**」。

每個 change 附任務清單與驗收條件。範圍細節不重複，一律指回來源文件。

| 來源文件 | 涵蓋 |
| --- | --- |
| [system-layer-map.md](system-layer-map.md) | 分層、跨層契約、用詞決定 |
| [dom-order-intake-scope.md](dom-order-intake-scope.md) | ① 收單編排 |
| [dom-promising-scope.md](dom-promising-scope.md) | ② Promising |
| [dom-sourcing-scope.md](dom-sourcing-scope.md) | ③ Sourcing/Routing |
| [fulfillment-minimal-scope.md](fulfillment-minimal-scope.md) | 履約層最小版 |
| [fulfillment-full-scope.md](fulfillment-full-scope.md) | 履約層增量（未決定執行） |
| [r1-order-data-model-and-usecases.md](r1-order-data-model-and-usecases.md) | **R1 的逐欄位、逐方法展開** |

本文件不含 openspec 的 spec 格式，是開 proposal 前的排程依據。

---

## 依賴圖

```text
R1 訂單資料模型 ─────┬──▶ R5 收單冪等
                     ├──▶ R8 放寬多筆 line
                     │
R2 節點與覆蓋主檔 ───┴──▶ R3 庫存四維化 + FEFO ──▶ R4 編排權歸位
                                                        │
                                                        ▼
                                                R6 Sourcing 決策
                                                        │
                                                        ▼
                                          R7 履約層最小版 + 出貨閉環
```

| Change | 依賴 | 可與誰並行 |
| --- | --- | --- |
| **R1** 訂單資料模型 | — | R2 |
| **R2** 節點與覆蓋主檔 | — | R1 |
| **R3** 庫存四維化 + FEFO | R1、R2 | R5、R8 |
| **R4** 編排權歸位 | **R3** | R5、R8（**不可與 R3 並行**） |
| **R5** 收單冪等 | R1 | 任何 |
| **R6** Sourcing 決策 | R4 | R5、R8 |
| **R7** 履約層最小版 + 出貨閉環 | R3、R6 | R5、R8 |
| **R8** 放寬多筆 line | R1 | 任何 |

---

## 執行前必讀：五條禁忌

以下順序錯了會產生無法編譯或語意錯誤的中間狀態。

| # | 禁忌 | 原因 |
| --- | --- | --- |
| 1 | **R3 不可先於 R1** | `stock_reservations` 的 FK 要指向 `order_line_id`，而 `order_lines` 在 R1 才建立。先做等於之後要搬 FK |
| 2 | **R3 不可先於 R2** | `stock_pools.node_id` 的參照對象 `fulfillment_nodes` 在 R2 才存在。先做只能存無主的 UUID |
| 3 | **R3 與 R4 不可並行** | 兩者都改 `AllocationService`：R3 加批次篩選與 FEFO，R4 斷開 `Order` 耦合。必須串行，且**順序固定為 R3 → R4**（見下一列） |
| 3b | **R4 不可先於 R3** | R4 建立的 `demand_lines` view 引用 `stock_reservations.order_line_id` 與 `CONSUMED`，兩者都在 R3 才存在。先做只能寫一個之後要改的暫時版本 |
| 4 | **R6 不可先於 R4** | 編排權仍在 allocation 時，sourcing 無處可掛，會被迫寫進 `OrderAllocationCoordinator`，把現有錯位再放大一次 |
| 5 | **R7 不可先於 R3、R6** | 無批次資訊不知道揀哪一批；無 `nodeId` 不知道在哪個節點揀貨 |

### 一條建議而非禁忌

R2 完成後除了節點頁沒有任何行為變化——它是純主檔。這是**刻意接受的**：把它從 R6
拆出來，是為了讓 R3 的 `node_id` 有參照對象，而不是為了讓 R2 本身有價值。

---

## 里程碑

| 里程碑 | 組成 | 完成後可展示 |
| --- | --- | --- |
| **M1** | R1 + R2 + R3 | **完整的 supply-demand allocation**：批次、FEFO、貨主隔離、有貨但不可售 |
| **M2** | M1 + R4 + R6 | 多節點選點決策、候選節點與落選理由、拆單 |
| **M3** | M2 + R7 | 兩本帳、短揀對帳、re-source |
| 隨時 | R5、R8 | 冪等防護、多品項 |

**M1 是最短的可展示路徑。** 若時間有限，做完 M1 就有一個完整的演算法故事，不需要
R4、R6、R7。

---

## R1 訂單資料模型

**依賴**：無　**並行**：R2、R4　**規模**：約 32 檔

建立 `owners`、`products`、`skus`、`order_lines` 四張表，`orders` 補齊分流欄位。此
change 不動 `stock_pools`。

商品主檔分**款**（`products`）與**規格**（`skus`）兩層：`temperature_zone` 在款層級，
`weight_gram` 在規格層級。理由見
[dom-order-intake-scope.md](dom-order-intake-scope.md) 的「商品主檔為何拆成款與規格
兩層」——它讓「同款兩種溫層」這類髒資料在結構上無法產生，而那種錯誤會拖到 R6 選點時
才浮現。

**四項從後續 change 提前到 R1**，理由是它們動的都是同一組表，分次做等於重複 ALTER：

| 提前的項目 | 原本在 | 提前理由 |
| --- | --- | --- |
| `order_lines.backordered_since` | R8 | **不提前就沒有 FIFO index 可用**——篩選鍵在 line、排序鍵在 header，跨表無法用單一複合 index 覆蓋。`allocated_at` **不放 line**，ship-complete 下它恆等於 header 且無 index 需要它 |
| `idx_order_lines_backorder_fifo` | R8 任務 5 | 同上。留在 R8 等於 R1～R8 全程無 index，壓測基準會斷掉且無法歸因 |
| `UNIQUE (owner_id, external_order_no)` | R5 | 一行 constraint。把 R1～R5 之間的「靜默建立重複訂單」變成「明確報錯」 |
| `orders.fulfilled_at` | R7 | 一個 nullable 欄位，讓 `orders` 只被 ALTER 一次。**這項最弱**，純粹省一次遷移 |

逐欄位與逐方法的展開見
[r1-order-data-model-and-usecases.md](r1-order-data-model-and-usecases.md)，含 header 與
line 時間戳的聚合規則。

### 任務

1. Migration：建 `owners`（含 `allow_split_shipment`）、`products`（PK `(owner_id, product_code)`，含 `temperature_zone`）、`skus`（PK `(owner_id, sku_code)`，FK 指向 `products`，含 `spec_name`、`weight_gram`）、`order_lines`（含反正規化的 `owner_id`、line 層級的 `status`／`backordered_since`／`assigned_node_id`）。**建表順序：`products` 先於 `skus`**，FK 的被指向方在前
2. Migration：`orders` 加 `owner_id`、`external_order_no`、`ship_to_zone`、`ship_to_address`、`promised_delivery_date`、`requested_node_id`、`fulfilled_at`；砍 `sku`、`quantity`
3. Migration：**`UNIQUE (owner_id, external_order_no)`**（從 R5 提前）與 **`idx_order_lines_backorder_fifo (owner_id, sku_code, backordered_since, id)`**（從 R8 提前，**不含 `status`**——待配佇列的查詢刻意不依 status 過濾，見 R1 詳細文件）。舊的 `idx_orders_backorder_fifo` 會隨 `DROP COLUMN sku` 被 PostgreSQL 自動移除
4. Domain：`Owner`、`Product`、`Sku`、`OrderLine`；`Order` 改為持有 line 集合（**每張單先只有一筆**）
5. Infrastructure：四組 entity／mapper／repository
6. Application：`PlaceOrderUsecase` 接受 line；`GetOrderUsecase`、`ListRecentOrdersUsecase`、`OrderDetail` 回傳 line
7. **修正 `AllocateOrderUsecase:53`**：`order.getSku()` 改為讀 line 的 `sku_code`
8. 事件：`OrderPlacedIntegrationEvent` 加 `ownerId`、`shipToZone`、`promisedDeliveryDate`；連帶 allocation 端的 handler
9. Entrypoint：`PlaceOrderRequest`、`OrderStatusResponse`、`OrderController`
10. Seed：一至兩個貨主（`allow_split_shipment` 分別為 `true`／`false`）；常溫與冷凍各一款商品，**其中一款帶兩個規格**以顯示款／規格兩層
11. 前端：下單表單加貨主、目的地、承諾到貨日；訂單列表加貨主欄；SKU 顯示為「品名 · 規格」
12. 測試：既有 ordering 七支測試的簽章調整
13. **line 數量無關性的三項防護**（見下方驗收）：以 `rehydrate()` 造 N=2 fixture、聚合規則的 N=2 測試、架構測試禁止 `getLines().get(` 與 `.getFirst()`

### 驗收

- 下單 → 查詢 → 取消的完整流程在含貨主與 line 的模型下通過
- **明確標註限制**：`stock_pools` 尚無 `owner_id`，**跨貨主隔離在 R3 才生效**。此階段
  seed 若有兩個貨主，配貨仍可能跨貨主取用——這是已知且刻意的中間狀態
- **N=2 的讀取路徑已驗過**：以 `Order.rehydrate()` 造兩行訂單（`place()` 仍拒絕多行），
  `OrderMapper` 往返與 `OrderStatusResponse` 序列化皆正確
- **整籃原子性在 N=2 下正確**：兩行中一行可滿足、一行不可滿足時，**兩行都不得預留**，
  整單進 `BACKORDERED`。**單行下這條退化成「配不到就缺貨」，因此「逐行獨立配貨」的
  錯誤實作會通過其他所有測試**（採 ship-complete，見 dom-promising-scope.md）
- **架構測試通過**：production code 不出現 `getLines().get(` 與 `.getFirst()`

### 風險

任務 7 是最容易漏的。`sku` 從 `orders` 搬到 `order_lines` 之後，所有讀 `order.getSku()`
的地方都會編譯失敗——包含 `AllocationService.requireMatchingSku()` 與兩個
`AllocationPolicy` 實作。先跑一次全域搜尋確認範圍。

第二個風險是 ship-complete 的整籃原子性被寫成逐行獨立。R1 只有一筆 line，所以「配得到
就預留」在單行下完全正確，然後在 R8 放寬多筆時才爆——而爆的形式是「為出不去的單鎖住
庫存」，那不會讓任何測試失敗，只會讓庫存莫名被占住。

---

## R2 節點與覆蓋範圍主檔

**依賴**：無　**並行**：R1、R4　**規模**：約 15 檔

純主檔與 seed。無決策邏輯。

### 任務

1. Migration：`fulfillment_nodes`（`code`、`name`、`type`、`zone`、`status`、**`capabilities`**、`daily_capacity`、`cutoff_time`）
2. Migration：`node_coverage`（PK `(node_id, zone)`、`serviceable`、**`base_cost`**、**`cost_per_kg`**、`lead_time_days`）
3. Domain：`FulfillmentNode`、`NodeCoverage`
4. Infrastructure：entity／mapper／repository
5. Usecase：`ListNodesUsecase`、`GetNodeCoverageUsecase`（皆為查詢）
6. Seed：3～4 個節點、3～4 個配送區、**至少一組 `serviceable = false`**、**至少一個節點不支援冷凍**
7. 前端：節點頁（唯讀）——節點清單與覆蓋範圍矩陣
8. 測試：persistence 與 seed 一致性

### 驗收

- 節點頁可見拓撲與覆蓋矩陣，含不可達與能力不符的標示
- **尚無任何決策使用這些資料**——這是預期的，R6 才會讀

---

## R3 庫存四維化 + FEFO

**依賴**：R1、R2　**不可與 R4 並行**　**規模**：約 30 檔

本專案最大的單一 change。`stock_pools` 的四個維度必須在**同一次 migration** 完成。

### 任務

1. Migration：`stock_pools` unique key 由 `(sku)` 改為 `(owner_id, node_id, sku_code, expire_date, group)`。**既有列的 `expire_date` 與 `group` 預設值是資料決定而非技術細節**——這不是相容的欄位擴充，而是同一列的語意從「該 SKU 的可用量」變成「該 SKU 的其中一組可互換單位」，既有列退化為「效期空、`group = GOOD`」那一列。**表名不改**，理由見 [dom-order-intake-scope.md](dom-order-intake-scope.md) 的「為何不改名為 `stock_batches`」
2. Migration：`stock_reservations` FK 改為 `order_line_id`，並加批次欄位
3. Domain：`StockPool` 加四維、加 `consume()`；`ReservationStatus` 加 `CONSUMED`；`StockReservation` 粒度改為 line × 批次
   - `CONSUMED` 會被 R4 的 `demand_lines` view 用在「已滿足」謂詞裡（`status IN ('ACTIVE','CONSUMED')`）。R3 先於 R4，所以此處只需確保 enum 存在；**若日後再擴充 `ReservationStatus`，必須同步檢查 view 定義**——漏掉會讓已出貨的訂單重新出現在待配佇列，而當下沒有任何測試會發現
4. Repository：`findBySku` 拆為 `findSellableBatchesInFefoOrder(...)` 與 `findBatches(...)`
5. `AllocationService`：批次篩選（`group = GOOD` 且未過期）→ FEFO 排序 → 依序取用；加 `requireMatchingOwner()`
6. `AllocationOutcome`：區分「完全無批次」與「有批次但全不可售」。**決策層級是訂單**（採 ship-complete：整單配到／被哪條 line 卡住），per-line 資訊只作診斷用。型別要能承載「哪一條 line 的哪個 SKU 卡住了」
7. `ReplenishmentUsecase`：改為帶效期的 upsert；`ReplenishStockCommand` 加 `expire_date`、`group`
8. **防死鎖**：`OrderAllocationCoordinator` 的持久化段落**明確依 `(sku_code, expire_date)` 排序後寫入**，不可依賴集合的自然順序。排序鍵**現在就寫成跨 SKU 的形式**，即使單行時只有一個 SKU——R8 之後一次配貨會碰多個 SKU 的多個批次，屆時才改排序鍵是死鎖最難重現的一類問題
9. **Partition key**：`OrderingDomainEventTranslator` 的 sku 策略改為 `ownerId:skuCode`
10. 事件：`OrderAllocatedIntegrationEvent` 加批次清單（含每批對應的 `orderLineId`）
11. Seed：同 SKU 三批（近／中／遠效期）、一批不良品、一批已過期、一張跨批次需求的單
12. 前端：庫存頁改批次列表（效期、良品狀態、數量、是否可售與**落選理由**）＋ 貨主篩選；訂單詳細頁顯示配到哪些批次
13. 測試：`AllocationHotSkuConcurrencyIntegrationTest`、`AllocationFifoReplenishmentBatchIntegrationTest`、`AllocationConcurrencyEndToEndIntegrationTest` 的**前提失效，須重新設計**——熱點的定義從「一個 SKU」變成「一個批次」

### 驗收

- FEFO 取用順序可在畫面上驗證
- 「總量 100、ATP 60」時庫存頁能解釋差額（不良品 30、已過期 10）
- 跨貨主配貨被 `requireMatchingOwner()` 擋下
- 一張單吃多個批次時，`stock_reservations` 產生多筆且各自指向正確批次

### 風險

任務 8 與 13 最容易被跳過。前者不做會在壓測時出現偶發死鎖且難以重現；後者的三支測試
會「看起來還會過」但已經測不到原本要測的東西。

---

## R4 編排權歸位

**依賴**：R3　**不可與 R3 並行**　**規模**：約 25 檔

無新功能、無欄位改動，但**不是純重構**——寫入改成非同步之後多了一個取消與配貨交錯的
邊界，見任務 10。

### 任務

1. 新增 `ordering/entrypoint/kafka/`：consumer、`OrderAllocatedIntegrationEventHandler`、`BackorderCreatedIntegrationEventHandler`、error handling config
2. 新增 `ConfirmOrderUsecase`（編排推進器）
3. ordering 接上 inbox 去重
4. Migration：建 `demand_lines` **view**（**view 方案，非投影表也非 Port 介面**，理由見來源文件）。allocation 以唯讀 repository 查它並映射到自己的 `DemandLine` record
   - view **刻意不含 `order_lines.status`**——ordering 的配貨狀態落後於 allocation 的決策，拿它當閘門會重複預留。「還欠什麼」由 view 裡對 `stock_reservations` 的 `NOT EXISTS` 決定
   - 「已滿足」的謂詞是 `status IN ('ACTIVE','CONSUMED')`，**不只是 `ACTIVE`**
   - ship-complete 的整籃判斷：以 `order_id` 對 `demand_lines` 自我 join，**不得 join `order_lines`**
5. 拆 `OrderAllocationCoordinator`：移除 `OrderRepository` 注入、移除 `order.markBackOrdered()`、移除代發 domain event
6. `AllocationService`：移除 `order.markAllocated()`
7. `AllocationSelector`、`AllocationPolicy`、兩個 policy：`List<Order>` 改為 `List<DemandLine>`
8. 移除 `OrderRepository.findBackordersBySkuInFifoOrder()`
9. 測試：斷言 **allocation package 不得 import `ordering.domain.model.Order`**，並斷言 allocation 的程式碼不出現 `orders`／`order_lines` 表名（它查 `demand_lines`，不需要例外）
10. **`ConfirmOrderUsecase` 必須把「訂單已取消」視為 no-op**：配貨完成與使用者取消可能交錯，`order.markAllocated()` 對 CANCELLED 訂單會拋例外。那是合理競爭而非錯誤，若當成失敗，一次正常取消就會製造一筆 DLT 訊息。現況不會遇到，因為配貨與改 `Order` 在同一交易內

### 驗收

- `grep -r "ordering.domain.model.Order" allocation/` 結果為空
- **allocation 的程式碼不出現 `orders`／`order_lines` 表名**（含 SQL 字串與 JdbcTemplate）
  ——只檢查 Java import 擋不住繞過型別直接寫表
- **每張表只有一個 module 寫**：`orders`／`order_lines` 只由 ordering 寫，
  `stock_pools`／`stock_reservations` 只由 allocation 寫
- **取消與配貨交錯時不落 DLT**：配貨事件抵達時訂單已 CANCELLED，`ConfirmOrderUsecase`
  安靜跳過，且 allocation 的預留已由取消事件釋放
- **超賣防線不受影響**：壓測仍為 500 配到／500 缺貨、不超賣。這條線在 `StockPool`
  aggregate 與樂觀鎖，與「誰改 `Order`」無關
- 既有的配貨、缺貨、補貨重配流程行為完全不變

---

## R5 收單冪等

**依賴**：R1　**並行**：任何　**規模**：約 5 檔

**欄位與 unique constraint 已在 R1 完成**，本 change 只做行為。

### 任務

1. `PlaceOrderUsecase`：重送時回傳既有訂單，而非讓 unique constraint 拋錯
2. `OrderController`、`PlaceOrderRequest`
3. 測試：同一鍵送兩次只建立一筆，且回傳同一筆

### 驗收

- 同一 `(owner_id, external_order_no)` 重送 N 次，訂單表只有一列，庫存只扣一次

---

## R6 Sourcing 決策

**依賴**：R3、R4　**規模**：約 30 檔（主檔已在 R2 完成）

在事實鏈中間插入一個目前不存在的階段。

### 任務

1. `AllocationRequest` 重定義：加候選節點、成本、前置時間
2. `AllocationContext`：由空介面改為承載成本函數的輸入
3. `SourceOrderUsecase`：硬約束篩選（節點停用／不配送該區／該貨主在此節點無庫存／**溫層不符節點 capabilities**）→ 成本函數 → 排序。溫層取自 `products`、重量取自 `skus`，兩者層級不同，見 R1
4. **拆單**：讀 `owners.allow_split_shipment`，`false` 時將拆單懲罰視為硬約束（**不是第二條程式路徑**）
5. `OrderSourced` 事件：帶**排序後的候選清單**（非單一節點），供 allocation 依序 fallback
6. 事實鏈插入：`OrderPlaced → SourceOrder → OrderSourced → AllocateOrder → OrderAllocated`
7. `ReSourceOrderUsecase`：選定節點失敗時的重選
8. `OrderAllocatedIntegrationEvent` 加 `nodeId`（實際出貨節點，可能與計畫不同）
9. `AllocationOutcome`：再拆分「該貨主全網無貨」與「選定節點無貨但他節點有」
10. 前端：訂單詳細頁的候選節點表（節點／ATP／溫層／運費／時效／可達／結果）
11. 測試

### 驗收

- 候選節點表四種結果皆可重現：缺貨、能力不符、不可達、選中
- 同一組庫存下，`allow_split_shipment = true` 的貨主拆成兩個計畫，`false` 的進 backorder
- 「計畫節點 vs 實際出貨節點」的落差可觀察

### 風險

任務 5 最容易做錯。`OrderSourced` 若只帶單一節點，allocation 遇到該節點被搶走時只能
回頭重跑 sourcing——那會讓 read-then-act race 變成無限重試。

---

## R7 履約層最小版 + 出貨閉環

**依賴**：R3、R6　**規模**：約 35 檔（含新 module）

### 任務

1. 新增 Gradle module `fulfillment`；`bootstrap` 同時依賴兩個 module
2. Migration：`locations`（**code 必須用階層格式 `A-01-03-02-04`，但本版不解析**）、`location_stock`、`shipments`、`pick_tasks`
3. Domain：`Shipment`（兩態，粒度 `(order, node)`）、`PickTask`（含 `orderLineId`）、`LocationStock`（含樂觀鎖）、`Location`
4. Usecase 六支：`CreateShipment`、`GeneratePickTasks`、`ConfirmPick`（含短揀分支）、`CancelShipment`、`ListPickTasks`、`GetLocationStock`
5. 取位規則：單一儲位優先 → 量多優先 → `code` 字典序（**第三條為了決定性，不可省**）
6. 事件出：`ShipmentDeparted`、`ShortPickDetected`、`ShipmentCancelled`
7. **訂單層側**：`ShipmentDeparted` 的 handler 呼叫 `StockPool.consume()` 並將 reservation 轉為 `CONSUMED`（**跨 module，寫在 order-promising**）
8. **訂單層側**：`ShortPickDetected` 的 handler 修正 `StockPool.onHandQuantity`
9. `ReplenishmentUsecase` 同時寫入 `LocationStock`，維持對帳等式
10. Seed：每節點 3～4 儲位、一個 SKU 分散三儲位、**一筆預先埋好的帳差資料**
11. 前端：揀貨頁——待揀清單、回報實揀數、**刻意短揀按鈕**、**對帳差異顯示**
12. 測試：邊界斷言 `fulfillment` 不得依賴 `order-promising`

### 驗收

- 出貨後 `StockPool.onHandQuantity` 確實遞減（**這是目前完全不存在的行為**）
- 刻意短揀後：`LocationStock` 修正 → `StockPool` 修正 → 訂單重新決策，全鏈可在畫面上追蹤
- 對帳等式在正常路徑下恆成立，短揀時可見破裂與修復

### 風險

任務 7、8 跨 module，容易被誤放進 `fulfillment`。它們動的是 `StockPool`，屬訂單層，
必須寫在 `order-promising`——`fulfillment` 只發事件。

---

## R8 放寬多筆 line

**依賴**：R1　**並行**：任何　**規模**：約 20 檔（配貨演算法重寫，非原估的 15）

`order_lines`、line 層級的 `backordered_since` 與 FIFO index 都已在 R1 完成，本 change
**不搬遷任何結構、不加任何欄位、不改任何 index**。

**但它不是「只移除一個檢查」。** 採 ship-complete（見
[dom-promising-scope.md](dom-promising-scope.md)）之後，多行訂單的配貨必須是**整籃原子
判斷**——可滿足性從逐 SKU 獨立變成「整籃的所有 SKU 同時可滿足」，`StrictFifoAllocationPolicy`
要重寫，補貨喚醒要跨 SKU 檢查，一次交易會碰多個 `StockPool`。規模因此不是原估的 15 檔。

`PARTIALLY_ALLOCATED` **不會出現**——ship-complete 下所有 line 一起配到或一起缺貨。

**但「移除一個檢查」成立有前提**：R1 的三項防護（N=2 fixture、聚合規則的 N=2 測試、
禁止 `getLines().get(0)` 的架構測試）與 R3 的兩項（`AllocationOutcome` 為 per-line 集合、
死鎖排序鍵含 `sku_code`）都必須已經到位。少了它們，R3～R6 會在單行環境下累積一批
**在單行下正確、沒有任何訊號**的假設，本 change 就從「移除一個檢查」變成「獵捕散落
各處的單行假設」——那時本 change 提前做反而更省。

### 任務

1. 移除 `Order.place()` 裡「每張單只有一筆 line」的限制
2. **`StrictFifoAllocationPolicy` 改為整籃原子判斷**：一張單的所有 line 的所有 SKU 必須同時可滿足才配，否則整單不配、不預留
3. **補貨喚醒改為跨 SKU 檢查**：補 SKU X 之後還要確認那些單的其他 SKU 也備齊
4. `AmendOrderUsecase`、`SplitOrderUsecase`（**可拆成獨立的更小 change**）
5. 前端：訂單列表一列改為可展開的多列
6. 測試：整籃原子性、head-of-line blocking 在多 SKU 下的行為

### 驗收

- 一張單含多條 line、其中一條缺貨時，**整單不配、其他 line 也不預留**，整單為 `BACKORDERED`
- 補貨只補齊其中一個 SKU 時，該單仍不配；補齊全部 SKU 後才一次配到
- backorder 的 FIFO 隊列按貨主分開

---

## 建議的執行序列

若單人依序執行，這是衝突最少的一條路：

```text
1. R1 訂單資料模型
2. R2 節點與覆蓋主檔          ← 可與 1 並行
3. R3 庫存四維化 + FEFO       ← M1 達成，演算法可展示
4. R5 收單冪等                ← 小，插在此處換氣
5. R4 編排權歸位              ← 依賴 R3（view 引用 order_line_id 與 CONSUMED）
6. R6 Sourcing 決策           ← M2 達成
7. R7 履約層最小版 + 出貨閉環  ← M3 達成
8. R8 放寬多筆 line
```

R8 排最後的理由：它會讓 **R3、R6 與 R7** 同時面對多行的組合狀況。先在單 line 下把決策
模型與兩本帳做對，再放寬維度。這不是「先做簡化版再升級」——R8 加的是輸入的維度，不會
推翻前面任何設計。

R3 也會被壓到這件事容易被忽略。採 ship-complete 之後只剩一個位置：**防死鎖的排序鍵要跨
SKU**（一次配貨從碰一個 SKU 的多批次變成多個 SKU 的多批次），而這項已藉 R3 任務 8 提前
處理。原先擔心的「配貨結果從全有全無變成部分」在 ship-complete 下不存在。

曾考慮把 R8 移到 R7 之前，理由是「單行下短揀對帳 demo 很弱」。**採 ship-complete 之後這個
理由消失了**——多行也不會出現「一行出貨、一行短揀」，短揀只能是整批退回重新決策。
**維持最後。**
