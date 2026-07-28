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
R2 倉庫主檔（極簡）──┴──▶ R3 庫存分批 + FEFO ──▶ R4 編排權歸位
                                                        │
                                                        ▼
                                          R7 履約層最小版 + 出貨閉環
```

| Change | 依賴 | 可與誰並行 |
| --- | --- | --- |
| **R1** 訂單資料模型 | — | R2 |
| **R2** 倉庫主檔（極簡） | — | R1 |
| **R3** 庫存分批 + FEFO | R1、R2 | R5、R8 |
| **R4** 編排權歸位 | **R3** | R5、R8（**不可與 R3 並行**） |
| **R5** 收單冪等 | R1 | 任何 |
| **R7** 履約層最小版 + 出貨閉環 | R3、R4 | R5、R8 |
| **R8** 放寬多筆 line | R1 | 任何 |

**R6 Sourcing 決策已移出範圍**，見下方「為什麼沒有 R6」。

---

## 執行前必讀：五條禁忌

以下順序錯了會產生無法編譯或語意錯誤的中間狀態。

| # | 禁忌 | 原因 |
| --- | --- | --- |
| 1 | **R3 不可先於 R1** | `stock_reservations` 的 FK 要指向 `order_line_id`，而 `order_lines` 在 R1 才建立。先做等於之後要搬 FK |
| 2 | **R3 不可先於 R2** | `stock_pools.node_id` 的參照對象 `fulfillment_nodes` 在 R2 才存在。先做只能存無主的 UUID |
| 3 | **R3 與 R4 不可並行** | 兩者都改 `AllocationService`：R3 加批次篩選與 FEFO，R4 斷開 `Order` 耦合。必須串行，且**順序固定為 R3 → R4**（見下一列） |
| 3b | **R4 不可先於 R3** | R4 建立的 `demand_lines` view 引用 `stock_reservations.order_line_id` 與 `CONSUMED`，兩者都在 R3 才存在。先做只能寫一個之後要改的暫時版本 |
| 4 | **R7 不可先於 R3、R4** | 無批次資訊不知道揀哪一批；編排權未歸位時出貨事件無處可掛 |
| 5 | **不可把倉庫維度從 `stock_pools` 的唯一鍵拿掉** | 見下方「為什麼沒有 R6」的護欄。少一個維度事後補回來是改鍵、改所有查詢、改對帳等式，不是加欄位 |

### 一條建議而非禁忌

R2 完成後除了倉庫頁沒有任何行為變化——它是純主檔。這是**刻意接受的**：它存在的唯一
理由，就是讓 R3 的 `node_id` 有參照對象，而不是為了讓 R2 本身有價值。

---

## 里程碑

| 里程碑 | 組成 | 完成後可展示 |
| --- | --- | --- |
| **M1** | R1 + R2 + R3 | **完整的 supply-demand allocation**：批次、FEFO、貨主隔離、跨倉庫存歸屬、有貨但不可售 |
| **M2** | M1 + R4 | 編排權歸位，寫入改為非同步 |
| **M3** | M2 + R7 | 兩本帳、短揀對帳 |
| 隨時 | R5、R8 | 冪等防護、多品項 |

**M1 是最短的可展示路徑，也是演算法密度最高的一段。** 系統實際會算的決策只有兩個
（配哪批貨、裝幾箱），M1 完成的是第一個；第二個見 [system-layer-map.md](system-layer-map.md)
的演算法定位表。其餘決策（從哪個倉出、送到哪裡、誰來送）在 3PL 裡由合約與上游決定，
不是演算法問題。

---

## R1 訂單資料模型

**依賴**：無　**並行**：R2、R4　**規模**：約 32 檔

建立 `owners`、`products`、`skus`、`order_lines` 四張表，`orders` 補齊分流欄位。此
change 不動 `stock_pools`。

商品主檔分**款**（`products`）與**規格**（`skus`）兩層：`temperature_zone` 在款層級，
`weight_gram` 在規格層級。理由見
[dom-order-intake-scope.md](dom-order-intake-scope.md) 的「商品主檔為何拆成款與規格
兩層」——它讓「同款兩種溫層」這類髒資料在結構上無法產生。

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

1. Migration：建 `owners`（含 `allow_split_shipment`）、`products`（PK `(owner_id, product_code)`，含 `temperature_zone`）、`skus`（PK `(owner_id, sku_code)`，FK 指向 `products`，含 `spec_name`、`weight_gram`）、`order_lines`（含反正規化的 `owner_id`、line 層級的 `status`／`backordered_since`／`assigned_node_id`）。**建表順序：`products` 先於 `skus`**，FK 的被指向方在前。（R1 已完成。其中 `allow_split_shipment` 與 `assigned_node_id` 兩欄於 R2 砍除，理由見「為什麼沒有 R6」）
2. Migration：`orders` 加 `owner_id`、`external_order_no`、`ship_to_zone`、`ship_to_address`、`promised_delivery_date`、`requested_node_id`（R2 更名為 `fulfillment_node_id`）、`fulfilled_at`；砍 `sku`、`quantity`
3. Migration：**`UNIQUE (owner_id, external_order_no)`**（從 R5 提前）與 **`idx_order_lines_backorder_fifo (owner_id, sku_code, backordered_since, id)`**（從 R8 提前，**不含 `status`**——待配佇列的查詢刻意不依 status 過濾，見 R1 詳細文件）。舊的 `idx_orders_backorder_fifo` 會隨 `DROP COLUMN sku` 被 PostgreSQL 自動移除
4. Domain：`Owner`、`Product`、`Sku`、`OrderLine`；`Order` 改為持有 line 集合（**每張單先只有一筆**）
5. Infrastructure：四組 entity／mapper／repository
6. Application：`PlaceOrderUsecase` 接受 line；`GetOrderUsecase`、`ListRecentOrdersUsecase`、`OrderDetail` 回傳 line
7. **修正 `AllocateOrderUsecase:53`**：`order.getSku()` 改為讀 line 的 `sku_code`
8. 事件：`OrderPlacedIntegrationEvent` 加 `ownerId`、`shipToZone`、`promisedDeliveryDate`；連帶 allocation 端的 handler
9. Entrypoint：`PlaceOrderRequest`、`OrderStatusResponse`、`OrderController`
10. Seed：一至兩個貨主（原以 `allow_split_shipment` 作對比，該欄位於 R2 砍除）；常溫與冷凍各一款商品，**其中一款帶兩個規格**以顯示款／規格兩層
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

## R2 倉庫主檔（極簡）

**依賴**：無　**並行**：R1、R4　**規模**：約 8 檔

純主檔與 seed。無決策邏輯。**它存在的唯一理由是讓 R3 的 `node_id` 有參照對象。**

### 任務

1. Migration：`fulfillment_nodes`（`id`、`code`、`name`、`status`）
2. Domain：`FulfillmentNode`；Infrastructure：entity／mapper／repository
3. Usecase：`ListNodesUsecase`（查詢）
4. Migration：`orders` 的 `requested_node_id` 更名為 `fulfillment_node_id`、改 `NOT NULL`、補 FK 指向 `fulfillment_nodes`
5. Migration：砍掉 `order_lines.assigned_node_id` 與 `owners.allow_split_shipment`
6. `OrderPlaced` 領域事件加 `fulfillmentNodeId`（partition key 要用，見 R3 任務）
7. Seed：2～3 個倉庫；下單表單加倉庫選擇
8. 測試：persistence 與 seed 一致性

任務 4、5 的理由見下方「為什麼沒有 R6」。

### 驗收

- 倉庫頁可見清單；下單必須指定倉庫，未指定的請求被擋下
- **尚無任何決策使用倉庫**——這是預期的，R3 才會讀（庫存分倉）

---

## 為什麼沒有 R6

原計畫的 R6 是 Sourcing 決策：成本函數選出貨節點、候選節點篩選與落選理由。**已移出範圍**
（2026-07-29 決定），連帶把 R2 從「節點與覆蓋範圍主檔」縮成上面的極簡倉庫表。

### 理由

本系統是 3PL，而 3PL 的五個決策裡只有兩個是系統算的：

| 決策 | 誰決定 |
| --- | --- |
| 從哪個倉出 | 上游／合約 |
| 送到哪裡 | 上游 |
| 誰來送 | 上游（且 TMS 本來就不做） |
| **配哪批貨** | **系統**——R3 |
| **裝幾箱** | **系統**——履約層，目前未排程 |

貨主在上游下單時就指定了倉別，系統照做。**沒有選擇，就沒有選點問題**：成本函數、覆蓋範圍、
節點能力比對全部沒有輸入來源。硬做出來的會是一個沒有人使用的決策，展示時也只能靠調權重
看數字跳動。

### 保留了什麼

砍掉的是**決策**，不是**倉庫**。倉庫仍然是領域裡的真實維度：

- `fulfillment_nodes`——極簡主檔
- `stock_pools.node_id`——庫存分倉，且**在唯一鍵裡**
- `orders.fulfillment_node_id`——貨主指定的倉，NOT NULL
- `Shipment` 帶 `nodeId`——這批貨從哪個倉出的

一個貨主可以有多個倉（多對多），但**一張訂單只能一個倉，明細不可跨倉**。這是上游系統的
既有規則，不是我們的簡化。

### 連帶砍掉的兩個欄位

| 欄位 | 為什麼 |
| --- | --- |
| `owners.allow_split_shipment` | 它的定義是「是否允許**跨節點**拆單」。明細不可跨倉，這個開關沒有東西可以開關 |
| `order_lines.assigned_node_id` | 它放在 line 而非 header 的唯一理由是「拆單後不同 line 可能從不同倉出」。不跨倉之後它永遠等於 header，是純重複 |

### 護欄：讓 sourcing 之後補得回來

補回 sourcing 在**資料層是純加法**——多兩張表（覆蓋、溫層能力），不動任何既有的鍵；成本函數
要讀的 `skus.weight_gram`、`orders.promised_delivery_date` R1 都已經有了。

但這段期間有三件事**不可以**因為「反正單倉」而簡化掉，否則補回來就是重寫而不是加法：

1. **`stock_pools` 的唯一鍵必須含 `node_id`。** 這是唯一真的補不回來的——事後加維度等於改鍵、
   改所有查詢、改兩本帳的對帳等式。
2. **`Shipment` 保留 `nodeId`。** `fulfillment-minimal-scope.md` 已經寫明「最小版三者退化成
   一對一，但欄位從一開始就是最終形態」。
3. **倉庫這個概念不可以消失。** 一旦程式裡開始假設「只有一個倉」，補回來的成本就從加法變成
   重寫。

---

## R3 庫存分批 + FEFO

**依賴**：R1、R2　**不可與 R4 並行**　**規模**：約 30 檔

本專案最大的單一 change，也是**系統唯一真正在算的決策**（配哪批貨）。`stock_pools` 的新維度必須在**同一次 migration** 完成。

### 任務

1. Migration：`stock_pools` unique key 由 `(sku)` 改為 `(owner_id, node_id, sku_code, expire_date, group)`。**既有列的 `expire_date` 與 `group` 預設值是資料決定而非技術細節**——這不是相容的欄位擴充，而是同一列的語意從「該 SKU 的可用量」變成「該 SKU 的其中一組可互換單位」，既有列退化為「效期空、`group = GOOD`」那一列。**表名不改**，理由見 [dom-order-intake-scope.md](dom-order-intake-scope.md) 的「為何不改名為 `stock_batches`」
2. Migration：`stock_reservations` FK 改為 `order_line_id`，並加批次欄位
3. Domain：`StockPool` 加四維、加 `consume()`；`ReservationStatus` 加 `CONSUMED`；`StockReservation` 粒度改為 line × 批次
   - `CONSUMED` 會被 R4 的 `demand_lines` view 用在「已滿足」謂詞裡（`status IN ('ACTIVE','CONSUMED')`）。R3 先於 R4，所以此處只需確保 enum 存在；**若日後再擴充 `ReservationStatus`，必須同步檢查 view 定義**——漏掉會讓已出貨的訂單重新出現在待配佇列，而當下沒有任何測試會發現
4. Repository：`findBySku` 拆為 `findSellableBatchesInFefoOrder(...)` 與 `findBatches(...)`
5. `AllocationService`：批次篩選（`group = GOOD` 且未過期）→ FEFO 排序 → 依序取用；加 `requireMatchingOwner()`
6. `AllocationOutcome`：區分「完全無批次」與「有批次但全不可售」。**決策層級是訂單**（採 ship-complete：整單配到／被哪條 line 卡住），per-line 資訊只作診斷用。型別要能承載「哪一條 line 的哪個 SKU 卡住了」
7. `ReplenishmentUsecase`：改為帶效期的 upsert；`ReplenishStockCommand` 加 `expire_date`、`group`
8. **補貨喚醒的批次上限**：`findBackordersBySkuInFifoOrder()` 目前無上限，`AllocationFifoReplenishmentBatchIntegrationTest` 已是「單次補貨喚醒 500 張」的情境——一個交易改 500 張 `Order`、寫 500 筆預留、發 1,000 則事件，而 `StockPool` 的樂觀鎖全程暴露在衝突下（交易越久越容易衝突 → 重試 → 更久）。批次化之後這從效能問題升級為**正確性問題**：一次補貨涉及的批次數量由佇列內容而非事件決定，鎖範圍不可預測，而任務 9 的死鎖防線依賴「知道自己會碰哪些列」。作法：**上限以張數為維度、可設定，超出時發一則續做事件**（同 topic 同 partition key），**終止條件為「本輪喚醒張數 < 上限即不續做」**——喚醒數不足代表佇列已清空或被 head-of-line blocker 卡住，再送一次結果相同，這同時保證進展性。**續做方案的前提是 FIFO 只保證「補貨當下的佇列快照」**（見 [dom-promising-scope.md](dom-promising-scope.md) 的「補貨的三個決定」）；若那條契約被改成嚴格全域 FIFO，本項只能退回同交易內分頁，而那沒有縮短交易
9. **防死鎖**：`OrderAllocationCoordinator` 的持久化段落**明確依 `(sku_code, expire_date)` 排序後寫入**，不可依賴集合的自然順序。排序鍵**現在就寫成跨 SKU 的形式**，即使單行時只有一個 SKU——R8 之後一次配貨會碰多個 SKU 的多個批次，屆時才改排序鍵是死鎖最難重現的一類問題
10. **Partition key**：`OrderingDomainEventTranslator` 與 `ReplenishmentProbeController` 的 key 改為 `ownerId + "/" + nodeId + "/" + skuCode`（見下方「動工前要先定」的第 3 項）。`AllocationDomainEventTranslator` 不動——它發的是往下游的結果事件，下游更新的是 `Order` 那一列，爭用群組本來就是 orderId
11. 事件：`OrderAllocatedIntegrationEvent` 加批次清單（含每批對應的 `orderLineId`）
12. Seed：同 SKU 三批（近／中／遠效期）、一批不良品、一批已過期、一張跨批次需求的單
13. 前端：庫存頁改批次列表（效期、良品狀態、數量、是否可售與**落選理由**）＋ 貨主篩選；訂單詳細頁顯示配到哪些批次
14. 測試：`AllocationHotSkuConcurrencyIntegrationTest`、`AllocationFifoReplenishmentBatchIntegrationTest`、`AllocationConcurrencyEndToEndIntegrationTest` 的**前提失效，須重新設計**——熱點的定義從「一個 SKU」變成「一個批次」。`AllocationFifoReplenishmentBatchIntegrationTest` 另受任務 8 影響：500 張的單次喚醒會變成多輪續做，斷言要從「一次補貨事件後的最終狀態」改為「續做收斂後的最終狀態」，**而 head-of-line blocking 的斷言必須保留**——那是這支測試存在的理由

### 動工前要先定的四件事

以下在 R1 實作期間浮現，都不是 R1 能決定的，但留到動工時才想會來不及。

**1. `stock_pools` 的 aggregate 邊界：批次要不要進 unique key**

任務 1 目前寫的是五維 `(owner_id, node_id, sku_code, expire_date, group)`，也就是**每個批次
一個 aggregate、一個樂觀鎖**。另一個選項是三維 `(owner_id, node_id, sku_code)`，批次移到
子表、成為 aggregate 內的集合。

判準是「這個維度劃分**不可互換的庫存**，還是只是同一組內的排序依據」。**FEFO 的存在本身
就證明批次可互換**——如果不可互換，就沒有「該先出哪一批」的問題。而「不超賣」這個 invariant
也是跨批次計算的（ATP = 總量 − 預留）。按 DDD 的判準，一起變更、一起檢查不變式的東西該在
同一個 aggregate 裡。

五維的代價很具體，就是任務 8 那條防死鎖規則——一次交易更新多個獨立 aggregate，順序不固定
就撞。三維讓「跨批次」那一層的多對一消失。

**但任務 8 不能因此刪掉**：R8 放寬多行之後一次交易會跨多個 SKU，多對一會在那一層重新出現，只是排序
鍵從 `(sku_code, expire_date)` 換成含 `node_id`。

**2. partition key 必須與加 `owner_id` 在同一個 change**

任務 9 已列出「改成 `ownerId:skuCode`」，但沒說**不能分開做**。key 的正確形狀由庫存的識別
決定：庫存分開的那一刻，裸 `sku` 就從「正確」變成「假競爭」，兩個貨主的事件擠進同一個
partition 排隊等一個它們其實不共用的鎖。分兩個 change 做的話，中間那段時間 single-writer
是壞的。

**3. partition key 該含哪些維度：三維**

判準是「**一次交易會碰到的資源集合**」，凡是交易會跨越的維度都不能進 key：

| 維度 | 進 key 嗎 | 理由 |
| --- | --- | --- |
| `owner_id` | 是 | 庫存分開後不同貨主不再競爭 |
| `node_id` | **是** | 一張訂單只有一個倉、明細不可跨倉，交易不跨節點。（原本因「R6 拆單會跨節點」而排除，R6 移出範圍後那個理由消失） |
| `sku_code` | 是 | 競爭的單位 |
| 批次維度（效期／良品狀態／批號） | **否** | FEFO 在一次交易內跨批次取用，事前不知道會碰到哪幾批 |

選太細比太粗危險：太粗只是過度收斂（本來可平行的被序列化），太細會讓 single-writer 失效。

**字串怎麼組**：`ownerId + "/" + nodeId + "/" + skuCode`。Kafka 只拿 key 做
`hash(key) % partitions`、從不解析它，所以唯一的要求是**確定性**與**不撞鍵**。撞鍵風險來自
分隔字元——`skuCode` 是貨主自訂的自由文字，可能包含任何字元，包括 `/`。解法是**把定長的
放前面、自由文字放最後**：UUID 是定長 36 字元，因此不論 `skuCode` 含什麼，都不可能有另一組
三元組產生同一個字串。

不要改用 hash：Kafka UI 上看不出訊息落在哪個爭用群組，而在 Kafbat UI 上觀察 partition 分佈
正是這個 demo 的展示內容之一。

順帶釐清一個容易混的點：outbox 的 `aggregateid` 恆為 `orderId`，**與 `StockPool` 無關**
——發事件的是 `Order`，`StockPool` 不出現在 outbox 裡。`partition_key` 對應的不是任何
aggregate 的識別，而是「會競爭同一批庫存的事件群組」。

**4. `stock_pools` 要不要建 `(owner_id, sku_code)` → `skus` 的外鍵**

有了 `owner_id` 之後這才變成可行選項。好處是資料庫直接擋住「庫存池指向不存在的 SKU」；代價
是 allocation 與 catalog 的儲存綁在一起。R1 目前靠 `DevSeedDataIntegrationTest` 的一支測試
補這一段（斷言每個庫存池的 SKU 都存在於主檔）——若 R3 決定建外鍵，那支測試可以刪。

**5. 批次的身分是「屬性組合」還是「批號」**

任務 1 目前寫的是把效期與良品狀態放進唯一鍵，也就是**批次由屬性組合定義**。另一個作法是
批次有**批號**（lot number），唯一鍵變成 `(owner_id, node_id, sku_code, lot_no)`，效期與良品
狀態降為屬性欄位。

後者的好處是**以後加屬性不用改鍵**——進倉日、供應商、原產地、QC 結果全部是加欄位，不動唯一鍵、
不動查詢、不動兩本帳的對帳等式。而前者每加一個維度就是改鍵。FEFO 兩者都不受影響：效期仍是
真的 `DATE` 欄位、仍照樣建 index 排序，只是不在鍵裡。

要處理的細節是**效期管理關閉的貨主**：那些貨沒有批號，而 `lot_no` 不能是 NULL（Postgres 的
unique 把 NULL 當互不相同，會允許重複列），通常給一個哨兵值代表「此組合不做批次管理」。

**未決**：上游系統的批號是外部給的、系統自產的流水號、還是有些貨有有些沒有——三種答案會導向
不同的選擇。

**6. 貨主 × 倉庫對應表要不要建**

`stock_pools` 帶了 `owner_id` 與 `node_id` 之後，「這個貨主的貨放在這個倉」就已經被庫存列的
存在表達了，不需要額外的授權表。**所以這張表只有在它承載「設定」時才值得建。**

上游系統的對應表上確實有設定，而且直接改變配貨演算法的形狀：

| 設定 | 對配貨的影響 |
| --- | --- |
| 效期管理 關 | 不看效期，FEFO 整個不適用 |
| 不可換批號 | 一次出貨只能出同一批。配貨從「依效期依序取用直到湊滿」變成「**找一個單批能滿足全量**」——這是不同的演算法，不是同一個演算法的參數 |

第二個尤其有展示價值：同一張單、同樣庫存，在可換／不可換兩種設定下得出不同結果，甚至一個
配得到一個配不到。它也可以接手 `owners.allow_split_shipment` 被砍掉之後留下的「兩個貨主對比組」
角色，而且這次對比的是真的會跑到演算法的東西。

**未決**：上游那張表除了這兩個設定還有哪些，以及哪些真的屬於配貨。

### 驗收

- FEFO 取用順序可在畫面上驗證
- 「總量 100、ATP 60」時庫存頁能解釋差額（不良品 30、已過期 10）
- 跨貨主配貨被 `requireMatchingOwner()` 擋下
- 一張單吃多個批次時，`stock_reservations` 產生多筆且各自指向正確批次

### 風險

任務 9 與 14 最容易被跳過。前者不做會在壓測時出現偶發死鎖且難以重現；後者的三支測試
會「看起來還會過」但已經測不到原本要測的東西。

任務 8 有另一種失敗方式：**它會讓一支現在是綠的測試變紅**，而最省事的反應是把上限調到
大於 500 讓測試回綠——那等於沒做。上限的意義在於讓交易長度與佇列長度脫鉤，用「調到夠大」
繞過它會保留原本的失敗模式，且不留下任何訊號。

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

## R7 履約層最小版 + 出貨閉環

**依賴**：R3、R4　**規模**：約 35 檔（含新 module）

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

**依賴**：R1、**R3 任務 8（補貨喚醒的批次上限）**　**不可與 R3 並行**　**規模**：約 20 檔（配貨演算法重寫，非原估的 15）

對 R3 的依賴只有一項但是硬的：多行之後一次補貨喚醒涉及的 `StockPool` 數量由佇列內容
決定，沒有批次上限就是無界，死鎖排序鍵無從先算（見任務 3）。

`order_lines`、line 層級的 `backordered_since` 與 FIFO index 都已在 R1 完成，本 change
**不搬遷任何結構、不加任何欄位、不改任何 index**。

**但它不是「只移除一個檢查」。** 採 ship-complete（見
[dom-promising-scope.md](dom-promising-scope.md)）之後，多行訂單的配貨必須是**整籃原子
判斷**——可滿足性從逐 SKU 獨立變成「整籃的所有 SKU 同時可滿足」，`StrictFifoAllocationPolicy`
要重寫，補貨喚醒要跨 SKU 檢查，一次交易會碰多個 `StockPool`。規模因此不是原估的 15 檔。

`PARTIALLY_ALLOCATED` **不會出現**——ship-complete 下所有 line 一起配到或一起缺貨。

**但「移除一個檢查」成立有前提**：R1 的三項防護（N=2 fixture、聚合規則的 N=2 測試、
禁止 `getLines().get(0)` 的架構測試）與 R3 的兩項（`AllocationOutcome` 為 per-line 集合、
死鎖排序鍵含 `sku_code`）都必須已經到位。少了它們，R3～R7 會在單行環境下累積一批
**在單行下正確、沒有任何訊號**的假設，本 change 就從「移除一個檢查」變成「獵捕散落
各處的單行假設」——那時本 change 提前做反而更省。

### 任務

1. 移除 `Order.place()` 裡「每張單只有一筆 line」的限制
2. **`StrictFifoAllocationPolicy` 改為整籃原子判斷**：一張單的所有 line 的所有 SKU 必須同時可滿足才配，否則整單不配、不預留。具體形狀：`remaining` 從單一純量變成 per-SKU 的餘量映射，`break` 的判準從「這個 SKU 不足」變成「任一 SKU 不足」——**仍是 `break` 不是 `continue`**，head-of-line blocking 是刻意保留的性質
3. **補貨喚醒改為跨 SKU 檢查**：補 SKU X 之後還要確認那些單的其他 SKU 也備齊
4. **`ownerId/nodeId/skuCode` partition 策略必須退場**，且這不是選項而是必然——它與 ship-complete 根本衝突：
   整籃原子判斷要在同一個交易裡檢查所有 SKU 的 ATP，而 per-SKU 分區的保證是「同一個 SKU 的
   事件由同一個 writer 序列化」，跨 SKU 的交易必然跨越多個 writer 的管轄。**換更複雜的
   複合 key 救不回來**——把整張單的 SKU 集合雜湊成 key 也不行，同一個 SKU 會出現在多種組合
   裡，仍然跨 writer。問題不在 key 的組成，在「一次交易碰多個資源」與「一個 key 只指向一個
   partition」之間的矛盾。

   「把事件按 SKU 拆成多則」**不是出路**：拆開之後一張單的兩則事件被兩個 writer 各自處理，
   沒有人看得到整張單。退回 `orderId` 之後，single-writer 的保證要改用別的手段取得（按 SKU
   分片的處理器、悲觀鎖，或單純接受樂觀鎖重試），而那是本 change 要一併決定的。**這一項比它看起來大**：「缺 SKU X 的 BACKORDERED 單」從候選集合退化為候選集合的**入口**——每張候選單還要載入全部 SKU 的需求、取得對應的全部 `StockPool`，才能做 ship-complete 的整籃判斷。一次補貨交易涉及的池數量因此由**佇列內容**決定而非事件決定。前提是 R3 任務 8 的批次上限已在位，否則池的數量無界，死鎖排序鍵無從先算
5. `AmendOrderUsecase`、`SplitOrderUsecase`（**可拆成獨立的更小 change**）
6. 前端：訂單列表一列改為可展開的多列
7. 測試：整籃原子性、head-of-line blocking 在多 SKU 下的行為

### 驗收

- 一張單含多條 line、其中一條缺貨時，**整單不配、其他 line 也不預留**，整單為 `BACKORDERED`
- 補貨只補齊其中一個 SKU 時，該單仍不配；補齊全部 SKU 後才一次配到
- backorder 的 FIFO 隊列按貨主分開

---

## 建議的執行序列

若單人依序執行，這是衝突最少的一條路：

```text
1. R1 訂單資料模型
2. R2 倉庫主檔（極簡）        ← 可與 1 並行
3. R3 庫存分批 + FEFO         ← M1 達成，演算法可展示
4. R5 收單冪等                ← 小，插在此處換氣
5. R4 編排權歸位              ← 依賴 R3（view 引用 order_line_id 與 CONSUMED）　M2 達成
6. R7 履約層最小版 + 出貨閉環  ← M3 達成
7. R8 放寬多筆 line
```

R8 排最後的理由：它會讓 **R3 與 R7** 同時面對多行的組合狀況。先在單 line 下把決策
模型與兩本帳做對，再放寬維度。這不是「先做簡化版再升級」——R8 加的是輸入的維度，不會
推翻前面任何設計。

R3 也會被壓到這件事容易被忽略。採 ship-complete 之後只剩一個位置：**防死鎖的排序鍵要跨
SKU**（一次配貨從碰一個 SKU 的多批次變成多個 SKU 的多批次），而這項已藉 R3 任務 9 提前
處理。原先擔心的「配貨結果從全有全無變成部分」在 ship-complete 下不存在。

曾考慮把 R8 移到 R7 之前，理由是「單行下短揀對帳 demo 很弱」。**採 ship-complete 之後這個
理由消失了**——多行也不會出現「一行出貨、一行短揀」，短揀只能是整批退回重新決策。
**維持最後。**
