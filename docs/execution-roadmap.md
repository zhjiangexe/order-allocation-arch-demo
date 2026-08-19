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
R1 訂單資料模型 ─────┬──▶ R5 收單冪等（已滿足，不執行）
                     ├──▶ R8 放寬多筆 line ✓ ──────────────┐
                     │                                      │
R2 倉庫主檔（極簡）──┴──▶ R3 庫存分批 + FEFO ✓ ──▶ R4 編排權歸位 ✓ ─┤
                                                                    │
                                                                    ▼
                                                R7 履約層最小版 + 出貨閉環
                                                       ← M4，唯一還沒做的
```

| Change | 依賴 | 可與誰並行 |
| --- | --- | --- |
| **R1** 訂單資料模型 | — | R2 |
| **R2** 倉庫主檔（極簡） | — | R1 |
| **R3** 庫存分批 + FEFO | R1、R2 | R8 |
| **R4** 編排權歸位 | **R3** | R8（**不可與 R3 並行**） |
| ~~**R5** 收單冪等~~ | — | **已滿足，不執行**——見下方該節 |
| ~~**R6** Sourcing 決策~~ | — | **已移出範圍**——見下方該節 |
| **R7** 履約層最小版 + 出貨閉環 | R3、R4、**R8** | 任何 |
| **R8** 放寬多筆 line | R1 | 任何 |

**R6 Sourcing 決策已移出範圍**，見下方「為什麼沒有 R6」。

---

## 執行前必讀：五條禁忌

以下順序錯了會產生無法編譯或語意錯誤的中間狀態。

| # | 禁忌 | 原因 |
| --- | --- | --- |
| 1 | **R3 不可先於 R1** | `stock_reservations` 的 FK 要指向 `order_line_id`，而 `order_lines` 在 R1 才建立。先做等於之後要搬 FK |
| 2 | **R3 不可先於 R2** | `stock_pools.facility_id` 的參照對象 `facilities` 在 R2 才存在。先做只能存無主的 UUID |
| 3 | **R3 與 R4 不可並行** | 兩者都改 `AllocationService`：R3 加批次篩選與 FEFO，R4 斷開 `Order` 耦合。必須串行，且**順序固定為 R3 → R4**（見下一列） |
| 3b | **R4 不可先於 R3** | R4 建立的 `demand_lines` view 引用 `stock_reservations.order_line_id` 與 `CONSUMED`，兩者都在 R3 才存在。先做只能寫一個之後要改的暫時版本 |
| 4 | **R7 不可先於 R3、R4** | 無批次資訊不知道揀哪一批；編排權未歸位時出貨事件無處可掛 |
| 5 | **不可把倉庫維度從 `stock_pools` 的唯一鍵拿掉** | 見下方「為什麼沒有 R6」的護欄。少一個維度事後補回來是改鍵、改所有查詢、改對帳等式，不是加欄位 |

### 一條建議而非禁忌

R2 完成後除了倉庫頁沒有任何行為變化——它是純主檔。這是**刻意接受的**：它存在的唯一
理由，就是讓 R3 的 `facility_id` 有參照對象，而不是為了讓 R2 本身有價值。

---

## 里程碑

| 里程碑 | 組成 | 完成後可展示 | 狀態 |
| --- | --- | --- | --- |
| **M1** | R1 + R2 + R3 | **完整的 supply-demand allocation**：批次、FEFO、貨主隔離、跨倉庫存歸屬、有貨但不可售 | ✓ |
| **M2** | M1 + R4 | 編排權歸位，寫入改為非同步 | ✓ |
| **M3** | M2 + R8 | 多品項訂單，以及**整籃配或整籃不配**——有貨卻不配 | ✓ |
| **M4** | M3 + R7 | 兩本帳、短揀對帳 | |

**R8 原本掛在「隨時」而不屬於任何里程碑**，理由是它加的是輸入的維度、不推翻任何設計。做完
之後那個歸類站不住：它交出了一個展示得出來的狀態，而且是全系統最反直覺的那一個——一張單的
某個 SKU 湊不滿時，其餘 SKU 明明有貨也一件都不鎖。「多品項」三個字看不出這件事，所以連描述
一起改。R7 因此往後成為 M4。

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
| `UNIQUE (owner_id, external_order_no)` | R1（原訂 R5） | 一行 constraint。把「靜默建立重複訂單」變成「明確報錯」——而那個報錯後來成為最終行為，見 R5 那一節 |
| `orders.fulfilled_at` | R7 | 一個 nullable 欄位，讓 `orders` 只被 ALTER 一次。**這項最弱**，純粹省一次遷移 |

逐欄位與逐方法的展開見
[r1-order-data-model-and-usecases.md](r1-order-data-model-and-usecases.md)，含 header 與
line 時間戳的聚合規則。

### 任務

1. Migration：建 `owners`（含 `allow_split_shipment`）、`products`（PK `(owner_id, product_code)`，含 `temperature_zone`）、`skus`（PK `(owner_id, sku_code)`，FK 指向 `products`，含 `spec_name`、`weight_gram`）、`order_lines`（含反正規化的 `owner_id`、line 層級的 `status`／`backordered_since`／`assigned_facility_id`）。**建表順序：`products` 先於 `skus`**，FK 的被指向方在前。（R1 已完成。其中 `allow_split_shipment` 與 `assigned_facility_id` 兩欄於 R2 砍除，理由見「為什麼沒有 R6」）
2. Migration：`orders` 加 `owner_id`、`external_order_no`、`ship_to_zone`、`ship_to_address`、`promised_delivery_date`、`requested_facility_id`（R2 更名為 `facility_id`）、`fulfilled_at`；砍 `sku`、`quantity`
3. Migration：**`UNIQUE (owner_id, external_order_no)`**（從 R5 提前）與 **待配佇列的 index**（從 R8 提前，**不含 `status`**——待配佇列的查詢刻意不依 status 過濾，見 R1 詳細文件；R4 之後它是 `idx_order_lines_demand_fifo (owner_id, sku_code, order_id)`，排序鍵換成了時間有序的主鍵）。舊的 `idx_orders_backorder_fifo` 會隨 `DROP COLUMN sku` 被 PostgreSQL 自動移除
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

純主檔與 seed。無決策邏輯。**它存在的唯一理由是讓 R3 的 `facility_id` 有參照對象。**

### 任務

1. **改寫 `V3`，不新增 migration**。schema 尚未部署至任何環境，沿用 R1 的判準——新開一支
   migration 會在歷史上留下「`assigned_facility_id` 建了又砍、`requested_facility_id` 建了又改名」
   的假歷史。代價是既有 Postgres volume 必須移除重建，**不得以 `flyway repair` 略過**
2. `V3` 加 `facilities`（`id`、`code`、`name`）。**刻意不建 `status`**——砍掉 sourcing
   之後沒有「排除停用倉庫」的篩選，它會是第二個沒有讀者的欄位。倉庫停用在營運上是真的，
   但那要等有讀者時再加
3. `V3` 加 `owner_facilities`（PK `(owner_id, facility_id)`，雙 FK）。**零設定欄位**——它在 R2 的
   用途只有一個：下單表單知道這個貨主能選哪些倉。設定欄位等 R3（見該節待定事項 6）
4. `V3` 的 `orders.requested_facility_id` 更名為 `facility_id`、改 **`NOT NULL`**、補
   **複合外鍵 `(owner_id, facility_id)` → `owner_facilities`**（不是單欄指向
   `facilities`）。已實測可行，因此「倉存在但這個貨主沒掛」由資料庫擋下，應用層零
   檢查。補 FK 刻意偏離「外部來的不補」原則：收件地址千變萬化，但倉別是簽約時就固定的少數
   幾個值，上游送錯就是設定錯誤，早點擋下比較好
5. `V3` 砍掉 `order_lines.assigned_facility_id`、`owners.allow_split_shipment` 與
   **`owners.status`**。最後一個是任務 2 的同一把尺——它同樣沒有任何決策讀它，種子兩個貨主
   都是 `ACTIVE`，`SUSPENDED` 只出現在測試裡。只砍新的而留下舊的不是判準，是慣性
6. Domain：`Facility`；Infrastructure：entity／mapper／repository；
   Usecase：`ListFacilitiesForOwnerUsecase`（依貨主列出可用倉庫）
7. `OrderPlaced` 領域事件加 `facilityId`（R3 的 partition key 要用）
8. Seed：**3 個倉庫**，兩個貨主**各掛 2 個、共用其中 1 個**。三件事要同時看得出來：同一貨主
   有多個倉、不同貨主的倉不同、**一個倉服務多個貨主**。第三件是 3PL 的定義性特徵，少了它，
   一個「以倉庫而非配對關係做過濾」的錯誤實作會安靜地通過
9. 前端：**只在下單表單加倉庫下拉**（依所選貨主過濾），**不做倉庫頁**——原本的倉庫頁是為了
   展示覆蓋矩陣，現在只剩三個欄位的清單，撐不起一頁
10. 測試：persistence、seed 一致性、下單未指定倉庫被擋下、倉庫下拉依貨主過濾

任務 4、5 的理由見下方「為什麼沒有 R6」。

### 驗收

- 下單表單的倉庫下拉只列出該貨主掛的倉；換貨主時清空既有選擇
- 下單必須指定倉庫，未指定或指定了該貨主沒掛的倉，請求被擋下
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

- `facilities`——極簡主檔
- `stock_pools.facility_id`——庫存分倉，且**在唯一鍵裡**
- `orders.facility_id`——貨主指定的倉，NOT NULL
- `Shipment` 帶 `facilityId`——這批貨從哪個倉出的

一個貨主可以有多個倉（多對多），但**一張訂單只能一個倉，明細不可跨倉**。這是上游系統的
既有規則，不是我們的簡化。

### 連帶砍掉的兩個欄位

| 欄位 | 為什麼 |
| --- | --- |
| `owners.allow_split_shipment` | 它的定義是「是否允許**跨節點**拆單」。明細不可跨倉，這個開關沒有東西可以開關 |
| `order_lines.assigned_facility_id` | 它放在 line 而非 header 的唯一理由是「拆單後不同 line 可能從不同倉出」。不跨倉之後它永遠等於 header，是純重複 |

### 護欄：讓 sourcing 之後補得回來

補回 sourcing 在**資料層是純加法**——多兩張表（覆蓋、溫層能力），不動任何既有的鍵；成本函數
要讀的 `skus.weight_gram`、`orders.promised_delivery_date` R1 都已經有了。

但這段期間有三件事**不可以**因為「反正單倉」而簡化掉，否則補回來就是重寫而不是加法：

1. **`stock_pools` 的唯一鍵必須含 `facility_id`。** 這是唯一真的補不回來的——事後加維度等於改鍵、
   改所有查詢、改兩本帳的對帳等式。
2. **`Shipment` 保留 `facilityId`。** `fulfillment-minimal-scope.md` 已經寫明「最小版三者退化成
   一對一，但欄位從一開始就是最終形態」。
3. **倉庫這個概念不可以消失。** 一旦程式裡開始假設「只有一個倉」，補回來的成本就從加法變成
   重寫。

---

## R3 庫存分批 + FEFO

**依賴**：R1、R2　**不可與 R4 並行**　**規模**：約 30 檔（**實際 120+ 檔**）

**R3 已完成**（2026-07-30 archive，change `add-batch-stock-and-fefo`）。兩處與本節原定計畫不同，
都是 code review 的結果：**partition key 由三維改為二維**（第 3 項決策已就地更新），以及
**四個訂單生命週期對外事件全部瘦成只帶識別碼與時間戳**。唯一未收掉的是壓測基準與喚醒上限的
調校，兩者卡在同一個前提，見「已識別但未排程」。

**系統唯一真正在算的決策**（配哪批貨）。`stock_pools` 的四個新維度必須在**同一次 migration** 完成——它們動的是同一組 unique constraint 與同一批查詢，分次做等於改四輪。

### 任務

1. Migration：`stock_pools` 由「一個 SKU 一列」改為「一批一列」。**不新增任何表。**

   ```sql
   stock_pools(
     id,
     owner_id, facility_id, sku_code, in_date, expiry_date,   -- 身分
     on_hand_quantity, reserved_quantity, version, updated_at,
     UNIQUE (owner_id, facility_id, sku_code, in_date, expiry_date),
     FOREIGN KEY (owner_id, sku_code) REFERENCES skus(owner_id, sku_code)
   )
   ```

   **身分是屬性的組合**，不是批號也不是到貨編號——理由見下方「動工前」第 1 項。

   `expiry_date` **NOT NULL**。可空會踩到 PostgreSQL 的坑：unique 約束把 NULL 視為互不相同，
   兩批同日到貨的無效期商品會各成一列而不是合併。種子全是食品，NOT NULL 不造成虛構；真的
   出現非效期商品時再處理，那時是改約束不是改鍵。

   `in_date` 留在鍵裡而不只當屬性：同效期不同日到貨因此各成一列，補貨一律是 insert，不需要
   「合併時日期取哪一個」這種規則。代價是列數多一些，換到的是沒有合併語意要維護。

   **這不是相容的欄位擴充**：同一列的語意從「該 SKU 的可用量」變成「某貨主在某倉、某日到貨、
   某效期的那一批」。既有列要補上四個維度的值才遷得過去。

   **表名不改**，理由見 [dom-order-intake-scope.md](dom-order-intake-scope.md) 的「為何不改名為
   `stock_batches`」。但改寫 migration 時要在註解裡明說**一列是一批不是一個池**——名字與內容
   不符，不寫下來下一個人會誤讀。

2. Migration：`stock_reservations` FK 改為 `order_line_id`，並加批次欄位
3. Domain：`StockQuant` 加四維、加 `consume()`；`ReservationStatus` 加 `CONSUMED`；`StockReservation` 粒度改為 line × 批次
   - `CONSUMED` 會被 R4 的 `demand_lines` view 用在「已滿足」謂詞裡（`status IN ('ACTIVE','CONSUMED')`）。R3 先於 R4，所以此處只需確保 enum 存在；**若日後再擴充 `ReservationStatus`，必須同步檢查 view 定義**——漏掉會讓已出貨的訂單重新出現在待配佇列，而當下沒有任何測試會發現
4. Repository：`findBySku` 拆為 `findSellableBatchesInFefoOrder(...)` 與 `findBatches(...)`
5. `AllocationService`：批次篩選（未過期）→ FEFO 排序 → 依序取用；加 `requireMatchingOwner()`。**排序鍵是 `(expiry_date, in_date, id)`**——只用效期不夠：同效期不同日到貨會平手，而順序不定會讓配貨結果不可重現，也讓任務 9 的防死鎖排序失效
6. `AllocationOutcome`：區分「完全無批次」與「有批次但全不可售」。**決策層級是訂單**（採 ship-complete：整單配到／被哪條 line 卡住），per-line 資訊只作診斷用。型別要能承載「哪一條 line 的哪個 SKU 卡住了」
7. `ConfirmStockReceiptUsecase`：改為依五維鍵 upsert。`ConfirmStockReceiptCommand` 加 `facilityId`、`inDate`、`expiryDate`。同貨主同倉同 SKU 同日同效期的補貨會加到既有那一列，其餘一律新開列——合併規則因此完全由鍵決定，沒有額外邏輯
8. **補貨喚醒的批次上限**：`findBackordersBySkuInFifoOrder()` 目前無上限，`AllocationFifoAvailabilityIncreaseBatchIntegrationTest` 已是「單次補貨喚醒 500 張」的情境——一個交易改 500 張 `Order`、寫 500 筆預留、發 1,000 則事件，而 `StockQuant` 的樂觀鎖全程暴露在衝突下（交易越久越容易衝突 → 重試 → 更久）。批次化之後這從效能問題升級為**正確性問題**：一次補貨涉及的批次數量由佇列內容而非事件決定，鎖範圍不可預測，而任務 9 的死鎖防線依賴「知道自己會碰哪些列」。現行作法是**上限以張數為維度、可設定**；availability event 只做首輪，剩餘 scope 由 Scheduler 後續掃描，不再發 orchestration-only continuation event。這保留短交易，也讓 eventual convergence 有單一 reconciliation owner。
9. **防死鎖**：`OrderAllocationCoordinator` 的持久化段落**明確依 `(sku_code, expiry_date, in_date, id)` 排序後寫入**，不可依賴集合的自然順序。排序鍵**現在就寫成跨 SKU 的形式**，即使單行時只有一個 SKU——R8 之後一次配貨會碰多個 SKU 的多個批次，屆時才改排序鍵是死鎖最難重現的一類問題
10. **Partition key**：Ordering 與 stock availability 使用 `ownerId + "/" + facilityId` 的 `StockContentionKey`。收貨命令是同步 REST；提交後的 availability fact 經 Outbox/Kafka 觸發首輪，Scheduler 不經 Kafka。
11. 事件：`OrderAllocatedIntegrationEvent` 加批次清單（含每批對應的 `orderLineId`）
12. Seed：同 SKU 三批（近／中／遠效期）、一批已過期、一張跨批次需求的單。**其中兩批刻意同效期不同入庫日**，否則 tie-breaker 沒有測到
13. 前端：庫存頁改批次列表（效期、良品狀態、數量、是否可售與**落選理由**）＋ 貨主篩選；訂單詳細頁顯示配到哪些批次
14. 刪除 `DevSeedDataIntegrationTest` 中「每個庫存池的 SKU 都存在於主檔」那支測試——它驗的東西已由任務 1 的外鍵保證（見動工前第 4 件）
15. 測試：`AllocationHotSkuConcurrencyIntegrationTest`、`AllocationFifoAvailabilityIncreaseBatchIntegrationTest`、`AllocationConcurrencyEndToEndIntegrationTest` 的**前提失效，須重新設計**——熱點的定義從「一個 SKU」變成「一個批次」。`AllocationFifoAvailabilityIncreaseBatchIntegrationTest` 另受任務 8 影響：500 張的單次喚醒會變成 availability 首輪加 Scheduler 多輪，斷言要改為「scheduled reconciliation 收斂後的最終狀態」，**而 head-of-line blocking 的斷言必須保留**——那是這支測試存在的理由

### 動工前的六件事

以下在 R1 實作期間與 2026-07-29 的範圍討論中浮現，都不是 R1 能決定的。**六件全數定案**，
動工時直接照著做即可；每一項都保留了推導過程，因為被推翻的理由比結論更常被重複用到。

**1. `stock_pools` 的身分是屬性的組合**（已定，2026-07-29）

唯一鍵 `(owner_id, facility_id, sku_code, in_date, expiry_date)`，**一列一批、每批一個 aggregate、
一把樂觀鎖**。

這個結論繞了四版才到，過程記在這裡，因為**每一版被推翻的理由都是可複用的判準**：

**第一版：三維 `(owner_id, facility_id, sku_code)`，批次移到子表。** 理由是「不超賣是跨批次的
不變式，而 aggregate 邊界就是不變式的邊界」。**那個理由錯了**——每批各自滿足
`reserved ≤ on_hand`，總和就自動滿足，不變式會分解。真正的 DDD 論證是「一次交易應只改一個
aggregate」，弱得多，代價也具體（多列更新要固定順序，見任務 9）。
*判準：先確認不變式真的不可分解，再談 aggregate 邊界。*

**第二版：用批號當身分。** 卡在上游不給批號時：自產的批號就是代理鍵穿了件衣服，而「業務採用
它」這個立論在自產時不成立。為了補救而生的 `lot_source` 欄位、「不管批」模式、哨兵值，全是
在替一個錯的身分打補丁。
*判準：一個身分需要三個補丁才成立時，錯的是身分不是補丁。*

**第三版：身分是到貨（`stock_receipts` ＋ `receipt_id`）。** 這一版解掉了批號的問題，但它自己
被兩件事推翻：一是「自產的 `receipt_id` 同樣是系統產的」——我用來否定批號的那個論證對它一樣
適用，所以那個論證從一開始就不成立；二是把 receipt 表砍到真正需要的欄位之後，**除了 `id`
之外每一欄都已冗餘到 `stock_pools` 上，而我們從不單獨查詢它**。
*判準：一張所有欄位都被複製到別處、又從不被單獨查詢的表，沒有在做事。*

**第四版（採用）：屬性組合。** 效期與入庫日直接是 `stock_pools` 的欄位，兩者同時進鍵。
不需要批號、不需要到貨編號、不需要額外的表。合併規則完全由鍵決定——同貨主同倉同 SKU 同日
同效期就是同一列，其餘各成一列。

**放棄了什麼**：批號層級的追溯（召回時精確到製造批），以及逐次到貨的稽核記錄。兩者都在
[system-layer-map.md](system-layer-map.md) 劃定的範圍外（入庫是「事件過場」），要做時是加一張
表並把身分換過去——**在那之前不會有人依賴那些欄位**。

**沒有納入 `stock_status`**（`AVAILABLE`／`DAMAGED`／…）。它與效期服務同一個展示點——
「有貨但不可售」——而效期已經覆蓋（過期即不可售）。破損品在本 demo 裡是「不存在」而非「存在
但不可售」，這是誠實的簡化：我們沒有驗收、報廢、隔離解除那些流程。

**2. partition key 與庫存維度的先後順序**（已定，2026-07-29）

**同一個 change 裡做。** partition key 那一項只有兩個 producer adapters（`OrderingIntegrationEventPublisher`
與收貨入口），拆出去省不到什麼，卻要記住一條順序規則。

真要拆的話，**只有一個安全的順序：庫存先分維度、key 後改**。兩個方向的風險不對稱：

| 順序 | 中間狀態 | 後果 |
| --- | --- | --- |
| 庫存先、key 後 | key 比爭用群組**粗** | 不同貨主的事件擠同一個 partition 排隊，但各自更新自己的列——**只是慢** |
| key 先、庫存後 | key 比爭用群組**細** | 不同貨主的事件並行卻更新同一列 → 樂觀鎖衝突暴增 → 重試耗盡 → **落 DLT** |

判準就是下一項自己寫的那句：**選太粗只是過度收斂，選太細會讓 single-writer 失效**。
本項先前寫成「不能分開做，中間 single-writer 是壞的」，那對第一個方向是過度描述、對第二個
方向是低估。

**一個限縮**：`archone.allocation.partition-key-strategy` 預設是 `order-id`，該模式下每張單依
自己的 id 分區，本來就沒有 single-writer，衝突全由樂觀鎖與重試處理。上述風險只在跑 `sku`
策略時存在——也就是壓測與 v3 對比那個情境。這把風險從「production 事故」降為「demo 數字
不可信」，但不表示可以不管：v3 的吞吐對比（270 vs 203 orders/s）正是靠那個策略。

**3. partition key 該含哪些維度：二維 `(owner_id, facility_id)`**
（原定三維，2026-07-29；**於 R3 的 code review 改為二維，2026-07-30**）

判準是「**一次交易會碰到的資源集合**」，凡是交易會跨越的維度都不能進 key：

| 維度 | 進 key 嗎 | 理由 |
| --- | --- | --- |
| `owner_id` | 是 | 庫存分開後不同貨主不再競爭 |
| `facility_id` | 是 | 一張訂單只有一個倉、明細不可跨倉，交易不跨節點。（原本因「R6 拆單會跨節點」而排除，R6 移出範圍後那個理由消失） |
| `sku_code` | **否（改）** | 見下方「為什麼把 SKU 拿掉」 |
| `in_date`、`expiry_date` | 否 | FEFO 在一次交易內跨批次取用，事前不知道會碰到哪幾列 |

選太細比太粗危險：**太粗只是慢但正確**（本來可平行的被序列化），**太細直接失去 single-writer**。

**為什麼把 SKU 拿掉。** 含 SKU 的 key 更貼近「哪些列會被碰到」，但它有一個到期日：R8 把訂單
放寬成多行多 SKU 之後，一張單摺不出單一個 key，而 ship-complete 要求整籃的 ATP 在同一個交易裡
判斷——per-SKU 的 writer 管轄範圍必然被跨越。拿掉 SKU 就沒有那個到期日：不管一張單跨幾個 SKU，
它仍然只屬於一個 `(貨主, 倉)`，single writer 成立，而且**一個 writer 看得到整張單**，那正是
ship-complete 需要的。

換句話說，三維那個版本會在 R8 被迫改，而改的時候是「已經在跑的 partition 策略要換 key」——
**現在改的成本是兩個檔案，那時改的成本是一次 rebalance**。

代價是**過度序列化**：同貨主同倉、不同 SKU 的訂單本來永遠不會撞（不同的庫存列），現在也排在
同一條隊伍裡。在本專案的量體下這幾乎收不到——壓測量到「完全沒有 single writer」也只掉約 25%
吞吐，容量餘裕有一個數量級。

因此 **partition key 比庫存的身分粗三級**——身分是五維、爭用群組是二維。這不是妥協，是兩者
本來就在回答不同的問題：身分問「哪一列是哪一列」，爭用群組問「哪些訊息會搶同一批列」。
`event_outbox` 把 `aggregateid` 與 `partition_key` 分成兩欄，正是為了讓這兩件事各自獨立變動。

**字串怎麼組**：`ownerId + "/" + facilityId`。Kafka 只拿 key 做 `hash(key) % partitions`、從不解析
它，所以唯一的要求是**確定性**與**不撞鍵**。兩個 UUID 都是定長的，直接以分隔字元相接不會有
歧義——這也是拿掉 SKU 的附帶好處：**不再需要擔心自由文字的 SKU 代碼含有分隔字元**（原本靠
「定長的放前面、自由文字放最後」來迴避，那個顧慮整個消失了）。

**設定值一併由 `sku` 改名為 `stock`**：值應該以「它序列化什麼」命名，而不是以「key 由哪些欄位
組成」命名——否則像這次一樣調整組成，名字就變成謊言。

不要改用 hash：Kafka UI 上看不出訊息落在哪個爭用群組，而在 Kafbat UI 上觀察 partition 分佈
正是這個 demo 的展示內容之一。

順帶釐清一個容易混的點：outbox 的 `aggregateid` 恆為 `orderId`，**與 `StockQuant` 無關**
——發事件的是 `Order`，`StockQuant` 不出現在 outbox 裡。`partition_key` 對應的不是任何
aggregate 的識別，而是「會競爭同一批庫存的事件群組」。

**4. `stock_pools` 建 `(owner_id, sku_code)` → `skus` 的外鍵**（已定，2026-07-29）

有了 `owner_id` 之後這才變成可行選項，而且**與 R1 對 `order_lines` 的作法一致**：走自然鍵的
外鍵強制每一次參照都帶上貨主，跨貨主的錯誤組合因此建不起來。庫存池面對的是同一個問題——
SKU 代碼跨貨主撞號——用不同解法沒有道理。

現在的防線是 `DevSeedDataIntegrationTest` 的一支測試（斷言每個庫存池的 SKU 都存在於主檔），
那是最弱的一種：它只驗種子，正式路徑寫進一個不存在的 SKU 資料庫不會抱怨，症狀是「有庫存卻
永遠配不到貨」，而那要查很久才會歸因到打錯代碼。**建了 FK 之後那支測試可以刪。**

耦合的代價比看起來小：兩張表在同一個 schema、同一個 Postgres、同一次 migration 建出來。真正
的耦合成本要到分庫或拆服務時才出現，而那不在計畫裡。唯一站得住的反對理由是模組邊界
（allocation 不該知道 catalog 的表存在），但 R1 已經破了同一條線，而且真正的邊界防線是
[system-layer-map.md](system-layer-map.md) 提的 Gradle module，不是外鍵。

**5. 批號不做**（已定，2026-07-29；本項曾兩度判定相反，見第 1 項）

`stock_pools` 上**沒有 `lot_number` 欄位**，也沒有存放它的表。

業界慣例的考證仍然成立且值得留著：GS1 的批號（AI 10）由製造商／品牌商指定，識別的是**製造
批次**；倉庫的角色是記錄它、跟著它走完儲存→揀貨→出貨，不是發明它。召回通知引用的也是製造
批號。**所以批號的價值幾乎全在追溯，而追溯不在本專案範圍。**

不做的直接後果：**同效期同日到貨的兩個製造批會併成一列**，召回時無法精確到批。這是可接受的
——本專案沒有召回流程，也沒有供應商整合可以提供真實批號。

**「可否換批號」這個設定因此重新定義為「可否混用不同的庫存列」**，也就是一次出貨能不能跨
`(in_date, expiry_date)` 取貨。這是它在本模型裡唯一能一致執行的解讀，而且仍然改變演算法的
形狀：可混時是「依 FEFO 依序取用直到湊滿」，不可混時是「找一個單列能滿足全量」。展示價值
不變。

**要恢復批號時**：加一欄 `lot_no` 進 `stock_pools` 並納入唯一鍵，或抽出 `stock_receipts` 表把
身分換過去。前者較小，後者較完整；觸發點是「到貨開始有批號以外的屬性」或「真的要做召回」。

**6. 貨主 × 倉庫對應表：R2 建配對，R3 只加一欄**（已定，2026-07-29）

`stock_pools` 帶了 `owner_id` 與 `facility_id` 之後，「這個貨主的貨放在這個倉」就已經被庫存列的
存在表達了，不需要授權表。**所以這張表只有在它承載「設定」時才值得建。**

- **R2** 建 `owner_facilities(owner_id, facility_id)`，零設定欄位——用途只是讓下單表單知道能選哪些倉
- **R3** 只加 `allow_mixed_batch`（可否換批號）一欄

`allow_mixed_batch` 是必要的，因為它改變**配貨演算法的形狀**而非參數：可換時是「依 FEFO
依序取用直到湊滿」，不可換時是「找一個單列能滿足全量」。本模型沒有批號，因此「批」指的是
`(in_date, expiry_date)` 決定的那一列，見第 5 項。同一張單、同樣庫存，兩種設定下會得出
不同結果，甚至一個配得到一個配不到。它也接手 `owners.allow_split_shipment` 被砍掉後留下的
「兩個貨主對比組」角色，而且這次對比的是真的會跑到演算法的東西。

**效期管理 flag 刻意不加**，理由與直覺不同：配貨那邊不需要它——沒管效期的貨 `expire_date`
就是 null，FEFO 排序自然排不到，行為由資料決定不需要開關。它真正有用的地方是**收貨時的
驗證**（「這個組合該帶效期卻沒帶 → 擋下」），那是另一件事，等真的要做收貨驗證再加。

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
2. 新增 `RecordOrderAllocationUsecase` 與 `RecordOrderBackorderUsecase`（結果記錄器）
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
10. **`RecordOrderAllocationUsecase` 必須把「訂單已取消」視為 no-op**：配貨完成與使用者取消可能交錯，`order.markAllocated()` 對 CANCELLED 訂單會拋例外。那是合理競爭而非錯誤，若當成失敗，一次正常取消就會製造一筆 DLT 訊息。現況不會遇到，因為配貨與改 `Order` 在同一交易內

### 驗收

- `grep -r "ordering.domain.model.Order" allocation/` 結果為空
- **allocation 的程式碼不出現 `orders`／`order_lines` 表名**（含 SQL 字串與 JdbcTemplate）
  ——只檢查 Java import 擋不住繞過型別直接寫表
- **每張表只有一個 module 寫**：`orders`／`order_lines` 只由 ordering 寫，
  `stock_pools`／`stock_reservations` 只由 allocation 寫
- **取消與配貨交錯時不落 DLT**：配貨事件抵達時訂單已 CANCELLED，`RecordOrderAllocationUsecase`
  安靜跳過，且 allocation 的預留已由取消事件釋放
- **超賣防線不受影響**：壓測仍為 500 配到／500 缺貨、不超賣。這條線在 `StockQuant`
  aggregate 與樂觀鎖，與「誰改 `Order`」無關
- **配貨、缺貨、補貨重配的結果不變，但兩處行為刻意改了**：FIFO 的排序鍵由
  `backordered_since` 改為 `order_id`（UUID v7，等於到達順序），補貨喚醒的佇列範圍加上倉別。
  前者讓系統的處理抖動不再決定客戶之間的先後，後者讓別的倉的單不再佔滿以張數計的喚醒上限

---

## R5 收單冪等——**以拒絕達成，不另開 change**

**狀態**：**已滿足，不執行**（決定於 R4 完成後）

### 目標已經達成

R5 的驗收條件是「同一 `(owner_id, external_order_no)` 重送 N 次，訂單表只有一列，庫存只扣
一次」。**現況完全滿足**：`uq_orders_owner_external_no` 擋下第二次寫入，`OrderController` 把
它轉成 `409`，沒有第二列，也沒有第二次配貨。`OrderControllerTest` 有一支測試蓋著這個行為。

欄位與 constraint 在 R1 就建好了，而 R3 期間又把「靜默建立重複訂單」改成了「明確報錯」。剩下
的只有「要不要把報錯換成回傳既有訂單」——而那個換法被否決了。

### 為什麼不做「重送回傳既有訂單」

原任務寫的是「重送時回傳既有訂單，而非讓 unique constraint 拋錯」。**不做，因為它與
`POST /orders` 的語意衝突。**

收單是**新增**：一個上游單號對應一張新的訂單。同一個唯一鍵的第二次請求是與既有狀態衝突，
而 `409` 正是那件事的 HTTP 語意。改成回傳既有訂單，等於把新增偷偷變成 upsert——而 upsert
的前提是「內容相同就是同一件事」，那個前提在收單上不成立：

```text
上游送：PO-1001, SKU-A × 10
後來送：PO-1001, SKU-A × 20   ← 改量？還是 bug？
```

回 200 加既有訂單，上游會以為 20 件收下了，實際上只有 10 件。倉儲場景下那是出貨數量的差錯。
要支援它，需要的是一條明確的**修改路徑**（語意是「改這張單」而不是「再送一次」），那不在
現在的範圍。

### 這個決定的有效期

上游真的需要安全重送時，正確的做法是補修改路徑或讓上游換單號，**不是**把 `POST` 變成冪等
upsert。如果哪天要重開這一項，先問的應該是「內容不符時怎麼辦」，而不是「怎麼讓它不拋錯」。

---

## R7 履約層最小版 + 出貨閉環

**依賴**：R3、R4、**R8**、庫存異動模型（五個 change，已交付）　**規模**：約 30 檔（含新 module；少了兩張表與它們的 domain）　**里程碑**：M4

> **刻意排在 M4，不接著 M3 做。** R8 完成後系統已經有一個完整、展示得出來的狀態（M3），所以
> R7 不再是「補完最後一塊」而是「開下一塊」——那讓它可以等。
>
> 它也是唯一一個要新開 Gradle module 的 change：一動就是三十幾檔，而且中間沒有可展示
> 的中繼點（只有 `shipments` 沒有 `pick_tasks`，或反過來，都不構成一個看得懂的狀態）。要嘛整個
> 做完，要嘛還沒開始。
>
> 依賴多了 R8：`PickTask` 帶 `orderLineId`，而「一張單有幾條行」在 R8 之後才是開放的。若先做
> R7，那些結構會在單行的假設下長出來，然後在 R8 放寬時全部要重驗一次。

### 任務

1. 新增 Gradle module `fulfillment`；`bootstrap` 同時依賴兩個 module
2. Migration：`shipments`、`pick_tasks`（**只有兩張新表**，見下方「五個 change 之後改掉的三項」）
3. Domain：`Shipment`（兩態，粒度 `(order, node)`）、`PickTask`（含 `orderLineId`）
4. Usecase 五支：`CreateShipment`、`GeneratePickTasks`、`ConfirmPick`（含短揀分支）、`CancelShipment`、`ListPickTasks`。**`CreateShipment` 與 `GeneratePickTasks` 同交易＝即時釋出，那是政策不是必然**——見「已識別但未排程」的作業釋出。~~`GetLocationStock`~~ **刪除**——儲位層的庫存就是 `stock_pools`，已經有 `GetStockQuantUsecase`
5. 取位規則：單一儲位優先 → 量多優先 → `code` 字典序（**第三條為了決定性，不可省**）
6. 事件出：`ShipmentDeparted`、`ShortPickDetected`、`ShipmentCancelled`
7. **庫存側**：`ShipmentDeparted` 的 handler 呼叫未來正式的出庫扣帳 use case，**完成那段出庫搬運**（**跨 module，寫在 order-promising 的 `inventory`**）
8. **庫存側**：`ShortPickDetected` 的 handler 修正在庫量——同樣經由搬運，不是直接改數字
9. ~~`ConfirmStockReceiptUsecase` 同時寫入 `LocationStock`~~ **刪除**：可用庫存事件只更新 Promising 的 `StockQuant` 投影，不維護第二份 `LocationStock`
10. Seed：每節點 3～4 儲位、一個 SKU 分散三儲位、**一筆預先埋好的帳差資料**
11. 前端：揀貨頁——待揀清單、回報實揀數、**刻意短揀按鈕**、**對帳差異顯示**
12. 測試：邊界斷言 `fulfillment` 不得依賴 `order-promising`
13. **`OrderStatus` 加 `FULFILLED`，並連同「離倉後不得取消」的禁令一起加**。兩者必須同批——
    只加狀態不加禁令，會出現一條沒有補償手段的取消路徑（逆物流不在範圍內）。那條禁令
    在 R7 之前**不是被違反而是表達不出來**：沒有 `FULFILLED` 可以拒絕。要求見
    [system-layer-map.md](system-layer-map.md) 交會點 4，理由記在 `Order.cancel()` 的 Javadoc
14. **取消的守門條件會從「只看 `OrderStatus`」變成跨 module**。層級地圖列的四段取消窗口裡，
    前兩段（未產生 `PickTask`、`PickTask` 未開始）在 R7 之後**都是 `ALLOCATED`**——「這張
    ALLOCATED 的單還能不能取消」將取決於履約側有沒有 `PickTask`、它到了哪一步。
    `Order.cancel()` 今天只看自己就夠，唯一的理由是履約層還不存在

### 驗收

- 出貨後在庫量確實遞減（**這是目前完全不存在的行為**），且**遞減有一段 `DONE` 的搬運與一條明細對得上**
- 刻意短揀後：搬運修正 → 在庫量修正 → 訂單重新決策，全鏈可在畫面上追蹤
- **新增明確的出庫完成／扣帳 transaction boundary**，以實際 `ShipmentDeparted` 事實推進，不重用已移除的 inbound-only 元件
- **`FULFILLED` 的訂單取消時被拒絕**，且拒絕發生在領域層而不是靠呼叫端記得檢查

### 風險

任務 7、8 跨 module，容易被誤放進 `fulfillment`。它們動的是庫存，屬 `inventory`，必須寫在
`order-promising`——`fulfillment` 只發事件。這與既有的三條規則一致：**寫別人的表從不、通知用
事件、查別人的資料走對方發布的檢視**（`inventory` 讀 `demand_lines` 就是第三條）。

`fulfillment` 若需要「這條行配到哪幾批」來產生揀貨單，走的也是第三條——由 `inventory` 發布一個
檢視，而不是去 join `stock_move_lines`。

### 五個 change 之後改掉的三項

這一節原本的規劃寫在庫存異動模型之前（見 `docs/dom-stock-movement-scope.md`），有三處已經
與現況衝突：

| 原本規劃 | 為什麼不成立 | 改成 |
| --- | --- | --- |
| 新建 `locations` 表（階層 code） | `stock_locations` 已經存在。scope 文件對位置樹寫的是「日後要加時 `orders` 已指倉、`stock_pools` 已指位置，兩者都不用動」——儲位是**同一棵樹長出 `parent_id`**，不是第二個位置模型。Odoo 也只有一棵（`stock.location.parent_id`） | `stock_locations` 加 `parent_id`；`stock_pools.location_id` 指到更細的那一層 |
| 新建 `location_stock` 表 + 對帳等式 | 那個等式的存在理由是「有兩份庫存」。只有一份就不需要對帳 | 刪除。庫存仍是 `stock_pools`，只是位置更細 |
| handler 呼叫 `StockQuant.consume()`、reservation 轉 `CONSUMED` | `stock_reservations` 與 `CONSUMED` 都不存在了；扣帳必須有實際出貨憑證 | handler 呼叫正式的出庫扣帳 use case，由出貨事實與搬運明細去扣 |

R7 應新增清楚命名的出庫 transaction boundary，而不是復活已移除的 inbound-only
`InboundReceiptCompleter`。它仍不得另開一條沒有出貨憑證、直接任意改 StockQuant 的路。

**其他 scope 文件還沒跟上。** 這一節已經對齊，但下列文件裡仍有 `LocationStock`、
`stock_reservations`、`CONSUMED` 等已消失的東西——**開 R7 之前要先讀過它們**，否則會照著
過期的規劃長出結構：

| 文件 | 過期處 |
| --- | --- |
| `docs/fulfillment-minimal-scope.md` | 15 |
| `docs/system-layer-map.md` | 13 |
| `docs/dom-order-intake-scope.md` | 11 |
| `docs/dom-promising-scope.md` | 6 |
| `docs/fulfillment-full-scope.md` | 4 |

沒有現在就改，是因為那幾份各有自己的推理脈絡，逐句讀過才改得對——而那是 R7 開工的第一步，
不是這次順手的事。

任務 13 的禁令容易漏：加一個 enum 值是機械動作，而「順手把 `cancel()` 的守門條件補上」不是。
漏掉不會有任何測試失敗——因為那條路徑今天不存在，也就沒有測試在守它。

---

## R8 放寬多筆 line

**依賴**：R1、**R3 任務 8（補貨喚醒的批次上限）**　**不可與 R3 並行**　**規模**：約 20 檔（配貨演算法重寫，非原估的 15）

> **實際範圍是演算法，不是結構。** 任務 4 已由 R3 解決（partition key 改粗成 `(貨主, 倉)`），
> 任務 6、7 由 R4 解決或消失（`demand_lines` view 取代了那支會產生重複列的查詢）。剩下的是
> 整籃可滿足性、per-SKU 的餘量、跨 SKU 的取批——全部落在 `AllocationService` 與挑單政策裡，
> 一個 migration 都沒有。

對 R3 的依賴只有一項但是硬的：多行之後一次補貨喚醒涉及的 `StockQuant` 數量由佇列內容
決定，沒有批次上限就是無界，死鎖排序鍵無從先算（見任務 3）。

`order_lines` 已在 R1 完成，本 change **不搬遷任何結構、不加任何欄位、不改任何 index**。

~~line 層級的 `backordered_since` 與 FIFO index 都已在 R1 完成~~ → **R4 已經改掉這兩樣**：
那個欄位的存在理由是「單表 FIFO index」，而該查詢從來就是 join、排序取自 header，欄位因此
從未被讀到；index 隨之改為 `(owner_id, sku_code, order_id)`——排序鍵換成 UUID v7 的主鍵。

**但它不是「只移除一個檢查」。** 採 ship-complete（見
[dom-promising-scope.md](dom-promising-scope.md)）之後，多行訂單的配貨必須是**整籃原子
判斷**——可滿足性從逐 SKU 獨立變成「整籃的所有 SKU 同時可滿足」，`StrictFifoAllocationPolicy`
要重寫，補貨喚醒要跨 SKU 檢查，一次交易會碰多個 `StockQuant`。規模因此不是原估的 15 檔。

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
4. ~~`ownerId/facilityId/skuCode` partition 策略必須退場~~ → **已於 R3 解決,本 change 不必處理。**
   原本這裡寫著「必須退場且不是選項而是必然」,理由是 per-SKU 的 key 與 ship-complete 根本
   衝突——整籃原子判斷要在同一個交易裡檢查所有 SKU 的 ATP,而 per-SKU 分區的保證是「同一個
   SKU 的事件由同一個 writer 序列化」,跨 SKU 的交易必然跨越多個 writer 的管轄。

   **R3 的 review 期間把 key 改粗成 `(貨主, 倉)`,那個衝突就消掉了**:一張單不管跨幾個 SKU
   都只屬於一個 `(貨主, 倉)`,一個 writer 看得到整張單。代價是過度序列化（同貨主同倉不同
   SKU 也排隊),而壓測顯示「完全沒有 single-writer」也只掉約 25% 吞吐,台灣量體下容量餘裕
   有一個數量級。設定值同時由 `sku` 改名為 `stock`。

   **護欄:不得把 SKU 加回 partition key。** 加回去就重新引入這個衝突,而症狀要到多 SKU 訂單
   出現才浮現。`DomainEventTranslatorTest` 有一支測試斷言 key 裡不含 SKU。
5. **補貨喚醒的候選集合變成「入口」而非「集合」。** 「缺 SKU X 的 BACKORDERED 單」退化為候選
   集合的入口——每張候選單還要載入全部 SKU 的需求、取得對應的全部 `StockQuant`,才能做
   ship-complete 的整籃判斷。一次補貨交易涉及的池數量因此由**佇列內容**決定而非事件決定。
   前提是 R3 任務 5.3 的喚醒上限已在位,否則池的數量無界,死鎖排序鍵無從先算。
6. ~~修 `findBackordersBySkuInFifoOrder` 的重複列~~ → **R4 移除了那支查詢，這一項消失。**
   原本的內容是：那支查詢的 SQL 是 `orders left join order_lines` 且沒有 `DISTINCT`，一張單
   兩行同 SKU 時 join 會 match 兩次，同一張單造出兩個 domain 物件 → 兩筆預留、扣兩次庫存 →
   **超賣**。

   取代它的是 `demand_lines` view 上的兩段式查詢（先 `SELECT DISTINCT d.orderId` 取出這一輪
   的訂單，再一次載入它們的全部行），在結構上排除了重複列。**但那是 R4 的副作用而非它的
   目標**，所以本 change 留了一支 SIT 明確守著：一張單兩行同 SKU，只扣一次量。

7. ~~決定 `order_lines.status` 與 `order_lines.backordered_since` 要不要留~~ → **R4 已經處理
   掉 `backordered_since`**（它的存在理由是「單表 FIFO index」，而該查詢從來就是 join、排序
   取自 header，欄位因此從未被讀到）。

   `order_lines.status` **留著，不在本 change 決定**。它唯一的讀取者仍是 REST 回應逐行揭露，
   而 ship-complete 之下它恆等於 header。留著的理由沒有改變：ship-complete 若日後變成可設定
   的政策（部分配貨，業界的常態做法），行的狀態就會合法地與 header 不同。多行本身不改變這個
   判斷——它改變的只是「一張單有幾行」，不是「行的狀態能不能與 header 不同」。

8. `AmendOrderUsecase`、`SplitOrderUsecase`（**可拆成獨立的更小 change**）
9. 前端：下單表單加行編輯器（可新增／移除行，換貨主清空**每一條**行，任一行不完整即擋下
   送出）。訂單列表**不必改**——它早就以 `lines` 為來源渲染，每一行的商品與數量都看得到
10. 測試：整籃原子性、head-of-line blocking 在多 SKU 下的行為；**一張單兩行同 SKU 時只被配一次**（任務 6 的迴歸）

### 驗收

- 一張單含多條 line、其中一條缺貨時，**整單不配、其他 line 也不預留**，整單為 `BACKORDERED`
- 補貨只補齊其中一個 SKU 時，該單仍不配；補齊全部 SKU 後才一次配到
- backorder 的 FIFO 隊列按貨主分開

---

## 建議的執行序列

若單人依序執行，這是衝突最少的一條路：

```text
1. R1 訂單資料模型                                        ✓ 完成
2. R2 倉庫主檔（極簡）        ← 可與 1 並行               ✓ 完成
3. R3 庫存分批 + FEFO         ← M1 達成，演算法可展示     ✓ 完成
4. R4 編排權歸位              ← 依賴 R3　M2 達成          ✓ 完成
5. R8 放寬多筆 line           ← M3 達成，整籃配貨可展示   ✓ 完成
6. R7 履約層最小版 + 出貨閉環  ← M4 達成　**刻意延後，見該節**
```

**R5 不在序列裡**：它的驗收條件已由 R1 的 unique constraint 加上 `409` 達成，而「重送回傳既有
訂單」被否決——收單是新增，不是 upsert。見該節。

**R8 與 R7 的先後在 R4 之後改了**。原本 R8 排最後，理由是「先在單 line 下把決策模型與兩本帳
做對」；但 R4 已經把型別（`Demand` 持有 `List<DemandLine>`）與查詢（以訂單分組）做成多行的
形狀，剩下的只有配貨演算法本身。**R8 先做，R7 就直接面對多行的世界**，不必在履約層完成後再
回頭驗一次多行的組合狀況。

R8 排最後的理由：它會讓 **R3 與 R7** 同時面對多行的組合狀況。先在單 line 下把決策
模型與兩本帳做對，再放寬維度。這不是「先做簡化版再升級」——R8 加的是輸入的維度，不會
推翻前面任何設計。

R3 也會被壓到這件事容易被忽略。採 ship-complete 之後只剩一個位置：**防死鎖的排序鍵要跨
SKU**（一次配貨從碰一個 SKU 的多批次變成多個 SKU 的多批次），而這項已藉 R3 任務 9 提前
處理。原先擔心的「配貨結果從全有全無變成部分」在 ship-complete 下不存在。

曾考慮把 R8 移到 R7 之前，理由是「單行下短揀對帳 demo 很弱」。**採 ship-complete 之後這個
理由消失了**——多行也不會出現「一行出貨、一行短揀」，短揀只能是整批退回重新決策。
**維持最後。**

---

## 已識別但未排程

以下不屬於 R1～R8 任何一個 change，但已經知道要做，記在這裡以免被當成新發現重新推導一遍。

### head-of-line blocking 的吞吐：緩解是保留額度，不是跳過

`StrictFifoAllocationPolicy` 遇到隊首配不到就 `break`，後面的單一律不配——即使它們配得到。
多 SKU 之後這件事更容易發生：隊首可能只是缺其中一個 SKU。

**若吞吐成為問題，緩解是替隊首保留額度**——佔住它需要的量，後面的單用剩下的。先來先服務因此
仍然成立，只是不再獨佔整批庫存。

**不是把 `break` 改成 `continue`。** 那看起來只有一個字，但它讓隊首在後面不斷有小單時永遠
等下去——而「永遠等下去」在缺貨佇列裡不會有任何錯誤訊號，只會表現成一張越來越舊的單。

這一條寫在這裡是因為那個改動太便宜：任何一次「優化吞吐」的重構都可能順手做掉它。

### 取消的入口，以及「釋放後喚醒佇列」

**入口尚未接線。** `CancelOrderUsecase` 目前沒有任何呼叫端——沒有 REST endpoint、沒有 Kafka
handler，前端也沒有取消按鈕。這條流程「只有出海口沒有進水口」：`OrderCancelled` → 對外事件 →
allocation 釋放預留，整段都已實作且有 SIT，只是沒有東西觸發它。預定的入口是 **demo controller
加上操作台的取消按鈕**。

**而入口一接上，就會看見一個既有缺口：釋放不會喚醒缺貨佇列。**

```text
SKU 有 10 件，全被 A 單預留
B 單來 → 配不到 → 掛帳進 FIFO 佇列
A 單取消 → 10 件釋放回來
B 單永遠掛著   ← 直到有人補貨
```

`OrderAllocationCoordinator.releaseMoves()` 不發任何事件，`ReleaseReservationUsecase`
沒有喚醒邏輯。釋放出來的貨閒著，而佇列裡有人在等。**這與事件延遲無關**——等再久也不會發生。

> 方法名在「搬運單據與異動」那個 change 由 `releaseReservations` 改為 `releaseMoves`；
> `OrderAllocationCoordinator` 本身會在接下來的流程重組裡消失，屆時這段邏輯落在
> `AllocationReservationCanceller`。缺口與修法都不受影響。

修法可沿用現有 availability-triggered wake 的語意——「這個 `(貨主, 倉, SKU)` 的 ATP 增加，
去喚醒佇列」。若取消釋放需要低延遲首輪，應發布另一個明確的業務 fact 並映射到同一個
`AllocateWaitingDemandUsecase`；即使漏事件，Scheduler 仍負責 eventual convergence。不要復活只為控制
流程存在的 continuation event。

**但有一個設計問題要在有畫面可看的時候決定**：每次釋放都喚醒，還是只在可承諾量從 0 變正時
喚醒？前者在「釋放 1 件而隊首要 999 件」時是白跑一趟交易；後者要多一個判斷，而那個判斷需要
釋放前後的 ATP 快照。

**兩件事排在同一個 change**：取消按鈕與這個修正會一起被看見，而喚醒策略的取捨在有真實畫面時
才判斷得準。

### 取消的非同步窗口是刻意接受的

訂單已 `CANCELLED` 而庫存仍 `reserved`，直到 Kafka 往返完成（量級是幾十到幾百毫秒）。窗口內
ATP 被低估，後果是「某張單本來配得到卻掛帳了」——**保守方向的錯，不會超賣**。

**「搬運單據與異動」之後，同一個窗口多了一個反方向的後果。** 舊的待配佇列由 `demand_lines`
回答，那個 view 讀 `orders.cancelled_at`，而取消由 ordering 同步寫入——所以取消一落地，那張單
立刻離開佇列，窗口內只會少配、不會誤配。新佇列讀的是 `stock_moves.state`，而搬運要等
`OrderCancelled` 被消費才轉成 `CANCELLED`：**窗口內一次補貨可以把貨配給一張已取消的單。**

後果有界且不會壞任何東西：`RecordOrderAllocationUsecase` 早就把「已取消」當成合理競爭而靜默
略過，接著釋放把量還回去。真正的殘留是它會走到上一節那個既有缺口——**貨變回可用，卻沒有人
喚醒佇列**，所以排隊中的下一張單要等到下一次補貨。修好上一節，這條路徑的殘留也一併消失。

消除窗口本身只有一個做法：讓佇列查詢 join `orders`。**否決**——那是把剛拆掉的跨 context 讀取
放回系統最熱的那條路徑上，代價遠大於一個會自癒的窗口。Odoo 也是同一個形狀：取消訂單連帶取消
它的 move，而預留那條路徑不讀訂單。

曾考慮在 `CancelOrderUsecase` 裡同步 release 以消掉窗口，否決：`ordering` 會直接操作
`allocation` 的聚合根、一個交易跨兩個聚合根，而那正是 R4「編排權歸位」要斷開的耦合方向。

### R3 留下的兩項量測，都卡在同一個前提：一台安靜的機器

R3 archive 時任務 10.3 未勾。**它不是被放棄，是條件不具備**——這兩項都需要可信的計時，而量測那一輪
機器的 load average 是 5.73。

1. **壓測基準未更新。** 正確性的三條斷言全過（不超賣、無逾時、下單全成功），只有
   `order_decision_latency_ms p(99) < 10s` 實測 11.2s 沒過。那條門檻實際上是**吞吐量門檻的
   偽裝**，與程式正確性無關。沒有放寬門檻、也沒有拿那組數字更新任何 baseline，理由與完整
   重測步驟記在 `e2e/perf/README.md` 的「一次失敗的量測」。
2. **喚醒上限 `200` 尚未調校。** 它被選中的理由只有一個：讓 1,000 張佇列確實由 availability
   首輪與 Scheduler 多輪收斂。要調校它需要「單筆喚醒交易的實際
   耗時」，而那要在安靜的機器上量——**與第 1 項同一個前提**。調校方向：上限 × 單筆耗時 ≈ 交易
   長度，而交易長度決定併發的新單要等多久。

兩項一起做，因為它們共用同一次環境準備（關掉其他負載 → `down` 重建 → 暖機一輪 → 正式一輪）。

### 配貨服務對「一組批」的 feature envy

`AllocationService` 對傳進來的 `Map<String, List<StockQuant>>` 做了七處 map 形狀的操作：
`keySet().containsAll`、`removeAll`、`values().stream().allMatch(List::isEmpty)`、`forEach`
加總 ATP、`get(sku).isEmpty()`、`get(sku)` 取批、`forEach` 驗每個批掛對 SKU。**沒有一個是
「配貨」**，全是「這組庫存怎麼查」。

該抽的是那個 map 而不是 `(Demand, batches)` 這一對。配對物件套不上喚醒那條路（它是
`List<Demand>` 對一組批），而且配對本身沒有自己的不變式——唯一能寫的「這兩者相容」有更自然的
擁有者：庫存回答「我是不是這筆需求的合法供給」。

已抽成 `AllocatableBatches`：建構時驗「每個批掛在自己的 SKU 鍵下」、`forSku(skuCode)` 缺鍵
即拋錯、`availableToPromiseFor(skuCode)`，以及需求涵蓋檢查。

`allocate(demand, batches)` 已拿掉未使用的 `now`；只有 `allocateWaitingBatch` 保留時間，餵給
`AllocationRequest.decisionAt`。單筆與批次入口共用 private `attemptAllocation`，但挑選政策仍只
屬於等待需求批次。

沒有排程是因為它不修任何 bug——但它動的是 `AllocationService` 的介面與 repository 的回傳型別，
混進任何一個功能 change 都會讓那個 change 讀不出來，所以要自己一個。

#### 第二個物件：`AllocationAttempt`——`planned` 需要一個擁有者

上面說的是**資源**（可以動用的庫存），但這個過程還有第二段藏起來的狀態：`planPicks` 裡的
`Map<UUID, Integer> planned`——「**這張單已規劃、但還沒寫回 `StockQuant` 的取用量**」。

它是全系統最微妙的一段邏輯。少了它，同一張單的第二行會看到第一行還沒扣掉的可用量，兩行都
算得出「夠」，然後其中一行在 `reserve()` 時炸掉——或更糟，兩行都成功而超賣。**而這在單行下
完全碰不到**，所以現在沒有任何測試會因為它壞掉而變紅。

它目前是一個私有方法裡的區域變數。該有的形狀：

```java
AllocationAttempt attempt = stock.attemptFor(demand);   // planned 住在這裡，不外洩
AllocationPlan plan = attempt.finish();                 // 可行，或帶著每個 SKU 的缺口
plan.apply();                                           // 取代 applyPicks
```

`plan.apply()` 這一步不只是搬家：**「規劃與套用分開」現在只靠服務內部的執行順序保證，改成
型別之後它是結構性的**——可以拿著一個計畫而不套用，而拿不到「套用了一半」的東西。

之後 `allocate` 剩五行，`requireBatchesCoverDemand` / `planPicks` / `applyPicks` / `outcomeFor`
四個私有方法全部消失（各自搬進上面兩個物件）。

**不要再多了**：`SkuQuantities`、`BatchPick`、`AllocationResult`、`AllocationPlan` 已經存在而且
夠用。目標是這兩個，不是五個。底層也不貧血——`StockQuant.reserve()` 自己守著不變式；貧血的是
協調那一層。

#### 觸發點：R8 之前

`planned` 的危害要等**放寬多筆 line** 才碰得到。等到那時才做，等於讓 R8 的人在同一個 change
裡既改配貨演算法又補型別保護——而那個 bug 的症狀是超賣，不是編譯錯誤。

#### Odoo 19 在這一段沒有可以照抄的形狀

查過 19.0 的原始碼（`stock/models/stock_move.py` 的 `_action_assign`）：它**一次一個 move**，
迴圈前不建任何依商品分組的結構，拿不夠就把該 move 標成 `partially_available` 繼續下一個。

**我們的 map 是 ship-complete 的產物**——要在動任何庫存之前知道整籃湊不湊得滿，就必須先把所有
SKU 的批一起握在手上。Odoo 沒有這個需求，所以沒有這個型別。

但它給了兩個關於封裝的提示：

- 它的「可以拿的庫存」是 `stock.quant._gather(...)` 回傳的 **quant recordset**，不是 dict；
  而「拿」這個動作掛在庫存自己身上（`_update_reserved_quantity`），不是呼叫端算好再寫回去
- **取用順序是一個具名方法**：`_get_removal_strategy_order('fifo')` → `'in_date ASC, id'`；
  FEFO 甚至不在 `stock` 模組裡，是 `product_expiry` 覆寫同一個方法加上 `'removal_date, in_date, id'`。
  策略可替換，因為它有名字——而我們的 FEFO 排序鍵現在散在 repository 的 JPQL、
  `StockWriteOrder` 的比較器與註解三個地方

### SIT 的 Postgres container 是 Spring bean，每個 context 各起一個

`PostgreSQLTestConfiguration` 把 `PostgreSQLContainer` 宣告成 `@Bean`，所以**每一個不同的
Spring context 都會起一個 container**。SIT 有 16 個 test class 各自帶 context 設定，快取命中
不了的組合就各起一份。

症狀是跑 SIT 時 docker 反覆起停 Postgres，看起來像有東西在無限重開。實際不會壞掉，只是慢，
而且在 context 設定變動時特別明顯。

修法是把 container 改成 `static` 欄位加手動 `start()`（Testcontainers 的 singleton container
模式），生命週期綁 JVM 而不是 Spring context。要注意的是 `@ServiceConnection` 會失效，得改回
`@DynamicPropertySource` 或自己設 datasource 屬性——那是這個修法唯一有分量的取捨：換來的是
container 只起一次，付出的是連線設定不再由 Spring Boot 自動接。

### 作業釋出（波次／截單）：自動化倉真正的自動化

現在的行為是：**訂單一到，作業單立刻建立、貨立刻鎖定、工作立刻可以下到現場**。那是「單張
即時揀貨」政策——它合法，但目前是**隱含的**，不是一個決策。

真實的自動化倉，自動化的核心正是這個決策：**什麼時候把哪些單放到現場**。分組的依據是承運商
截單時間、揀貨區域、優先序。

#### 為什麼不放進 R7

- **沒有量的時候，波次與沒有波次看不出差別**——一個波次裡一張單
- R7 已經約 30 檔，而波次要加聚合、截單設定、觸發介面

#### R7 要留的縫（一句話，現在就要）

R7 的 `CreateShipment` 與 `GeneratePickTasks` **在同一個交易裡**。那個耦合要記成
「即時釋出是一個政策」，而不是「必然如此」——日後在 `CREATED` 與 `DEPARTED` 之間插入
`RELEASED`，才不必回頭拆交易。

#### 觸發點

任一成立即重新評估：

- 單倉單日的訂單量讓「一張單一趟揀貨」變成浪費（揀貨員在倉庫裡走的路遠多於揀的貨）
- 承運商截單需要把單分組交運

### route/rule：需求反向傳遞的那個階段

**門檻只有一個，而它現在不成立。** `docs/dom-stock-movement-scope.md` 的五個判準裡，只有第
四項是 route/rule 的真正門檻——「有**需求反向傳遞**嗎（客戶 → Output → Pack → Stock →
另一倉／採購）？」其餘四項流程模板就夠，而我們連模板都還不需要：`picking_type` 現在承擔
的正是那個角色。

它解的問題是**本倉沒貨時，貨該從哪來**：

```text
客戶要 100 件
  └─ 北部倉沒貨
       ├─ 從中部倉調撥？   → 產生一段跨倉搬運
       └─ 向供應商採購？   → 產生一張採購需求
            └─ 到貨後接回原本那張出貨單
```

每一步都可能再產生下一步，直到有貨為止；規則決定走哪一條，而那條鏈是自動長出來的。

我們現在沒有第二條供應路徑可選——一張單從一個倉出，沒貨就等補貨。所以規則引擎現在會是
**一個只有一條規則的引擎**，比寫死更難讀。

#### 開工前必須先做對的三件事

| # | 先備條件 | 為什麼 |
| --- | --- | --- |
| 1 | **單一的「供應請求」契約**（owner、倉、SKU、數量、需求位置、期限、服務等級、來源參照、下游 move） | 訂單、補貨、調撥都只能透過同一個契約要求供應。三種不同的輸入等於三套規則 |
| 2 | **搬運之間的依賴能表達 DAG**，且流程版本不可變 | 一筆補貨可能支撐多個下游、多個來源可能匯入一個下游。**不可用 `previous_move_id`**——它只表達得了直線，遇到拆分／合併／部分完成就會卡住。正確的形狀是一張方向明確的關聯表 |
| 3 | **規則選擇可測試、可解釋** | 要輸出「選了哪條、候選有哪些、為何淘汰、產生了哪些 move、為何終止」，並具備環偵測、最大展開深度、owner 隔離、dry-run |

第 3 項最容易被跳過，而它的代價最重：**做一個能配置卻無法解釋為什麼選到某條規則的引擎，
比寫死更危險**——出錯時沒有人查得出來是哪條規則造成的。

#### 連帶：`MoveState` 會多一個值

需求反向傳遞一旦存在，搬運就有上游，`waiting`（等上一段）才有意義。它與第 2 項的依賴表是
**同一件事的兩半，一起到來**——現在刻意兩個都不做，理由記在 `MoveState` 的 javadoc。

#### 觸發點

任一項成立即重新評估：兩段出庫（揀貨到暫存區再出貨）、跨倉調撥、MTO（出庫綁定某一筆進貨）。
R7 三者都沒有。

### 倉別時區

見 [system-layer-map.md](system-layer-map.md) 的「倉別時區：已識別但未排程」。

### 裝箱（cartonization）

見 [system-layer-map.md](system-layer-map.md) 的「裝箱：已識別但未排程」。
