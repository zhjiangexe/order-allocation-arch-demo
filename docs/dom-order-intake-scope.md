# 訂單層 ① 收單編排：職責盤點與資料模型

狀態：分析，未確認

日期：2026-07-26

## 這份文件回答什麼

本文件盤點 DOM 三職責中 ① 收單編排的完整職責，判定本系統要補哪些，並定義訂單的
資料模型——包含分流資訊（貨主、收發地址、指定倉）該放在哪一層、要不要另開表。

| 相關文件 | 涵蓋 |
| --- | --- |
| [execution-roadmap.md](execution-roadmap.md) | **八個 change 的順序、依賴、任務與禁忌** |
| [system-layer-map.md](system-layer-map.md) | 全流程分層、跨層契約、帳務交會點、用詞決定 |
| [dom-promising-scope.md](dom-promising-scope.md) | ② Promising：批次庫存模型與配貨演算法 |
| [dom-sourcing-scope.md](dom-sourcing-scope.md) | ③ Sourcing/Routing |
| [fulfillment-minimal-scope.md](fulfillment-minimal-scope.md) | 履約層（最小版）：兩本帳與短揀對帳 |
| [fulfillment-full-scope.md](fulfillment-full-scope.md) | 履約層（深做版）：從最小版的增量 |

本文件不是 change proposal，不含任務拆解與驗收條件。

## 前提

本系統是 **3PL（第三方物流）**：倉庫不擁有貨，貨屬於委託的貨主。此前提決定了庫存
必須按貨主隔離，見「資料模型」一節。

訂單來源是上游系統（貨主 ERP、電商平台、OMS）送來的出貨指令，不是消費者在瀏覽器
上的點擊。此前提決定了冪等是必要而非選配。

### `Order` 的語意

本系統的 `Order` 是 **outbound order（出貨指令）**，不是商務層的 sales order。

| | Sales Order | **本系統的 `Order`** |
| --- | --- | --- |
| 視角 | 客戶要買什麼 | **倉庫要出什麼** |
| 持有者 | 貨主的 ERP／商務層 | **本系統** |
| 帶什麼 | 價格、折扣、客戶帳務 | **出什麼、送到哪、誰的貨** |

`Order → OrderLine → Shipment` 的三層結構等同業界的
`Outbound Order → OutboundLine → Delivery`，只是交貨單在履約層 module。

**不更名為 `OutboundOrder`。** 兩個理由：`Order` 相關型別遍布整個 codebase，更名是
大範圍重構且無功能價值；而 `Order → Shipment` 的語意比 `OutboundOrder → Shipment`
清楚——後者兩個名稱過於相似，反而更易混淆。

---

## 完整職責盤點

| # | 職責 | 說明 | 歸屬 | 本系統現況 | 要補 |
| --- | --- | --- | --- | --- | --- |
| 1.1 | 訂單受理 | 接收上游送來的出貨指令並建立訂單 | ① | `PlaceOrderUsecase` 已有 | — |
| 1.2 | **收單冪等** | 同一張上游單重送時不得建立第二筆訂單 | ① | **無任何防護** | **要** |
| 1.3 | 語法驗證 | 必填欄位、型別、數值範圍 | ① | `Order` aggregate 內建 | — |
| 1.4 | 語意驗證 | SKU 是否存在、地址是否可解析、數量是否合理 | ① | 無 | 選配 |
| 1.5 | **訂單正規化** | 一張單多個品項；上游單的 line 結構要保留 | ① | **單 SKU 單數量** | **要** |
| 1.6 | **狀態機所有權** | 訂單狀態轉移必須由 ordering 自己驅動 | ① | **由 allocation 代勞** | **要** |
| 1.7 | **下游編排** | 依 ②③ 回報的事實推進訂單狀態 | ① | **編排邏輯長在 allocation** | **要** |
| 1.8 | 訂單變更 | 改量、改地址、改品項 | ① | 無 | 選配 |
| 1.9 | 拆單 | 部分可出、部分缺貨時拆成多張履約單 | ① | 無 | 依 ③ 而定 |
| 1.10 | 取消與補償 | 取消時釋放已佔用的資源 | ① | 已有，鏈路完整 | — |
| 1.11 | 對上游回報 | 訂單狀態變化回送商務層 | ① | Outbox + Kafka 已有 | — |
| 1.12 | **貨主歸屬** | 訂單屬於哪個貨主，決定可動用哪批庫存 | ① | **概念不存在** | **要** |
| 1.13 | **分流資訊受理** | 收下 ③ 需要的決策輸入：收件地、指定倉、承諾到貨日 | ① | **概念不存在** | **要** |
| 1.14 | **商品主檔** | SKU 的溫層與重量，供 ③ 作硬約束與成本計算 | ① 保管、③ 使用 | **`sku` 只是裸字串** | **要** |
| 1.15 | 風控／信用審核 | 付款失敗、信用額度不足時掛起 | **商務層** | — | **不做** |
| 1.16 | 金流狀態同步 | 待付款、已付款、退款 | **商務層** | — | **不做** |

### 判定說明

**1.15／1.16 不做**：本系統不從客戶端收錢，金流在商務層。因此原先設想的 `ON_HOLD`
（風控／付款／信用審核）與 `CONFIRMED`（付款確認後可出貨）兩個狀態失去存在理由。
沒有金流時，配貨完成即可出貨，`ALLOCATED` 已完整表達該事實，`CONFIRMED` 是多餘的
同義狀態。

**1.4 與 1.8 列為選配**：兩者都不影響系統正確性。1.4 缺席時錯誤在下游才浮現，
1.8 缺席時變更以「取消後重下」達成。兩者可獨立後補，不阻擋任何其他工作。

**1.13 的職責分工**：分流資訊由 ① 收下並保管，由 ③ 使用。收單時不做任何選點決策，
只確保決策所需的輸入完整。

---

## 資料模型

### 判準：header 還是 line

只有一條——**這個值在整張單層級唯一嗎？**

| 欄位 | 位置 | 理由 |
| --- | --- | --- |
| `ownerId` 貨主 | **header** | 一張單一個貨主，跨貨主不得同單 |
| `shipTo` 收件地 | **header** | 一張單送一個地方 |
| `promisedDeliveryDate` | **header** | 承諾到貨日，③ 的時效項基準 |
| `requestedNodeId` 指定倉 | **header**（選填） | 貨主指定則跳過 sourcing |
| `sku` / `quantity` | **line** | — |
| ~~`assignedNodeId` 實際出貨倉~~ | ~~line~~ | **2026-07-29 砍除**——它放在 line 的唯一理由是跨倉拆單，而一張訂單只能一個倉、明細不可跨倉。倉別在 header 的 `fulfillment_node_id` |
| `lineStatus` | **line** | **不是為了 `PARTIALLY_ALLOCATED`**——採 ship-complete，配貨階段各 line 狀態恆等。它為履約階段而存在（部分出貨、短揀） |

原本這裡寫的是「sourcing 的輸入在 header，輸出在 line」——那條規則隨 ③ 移出範圍而失效。
現在倉別**只在 header**：貨主指定一個倉，明細不可跨倉，沒有 per-line 的倉別可言。

### 判準：要不要另開表

| 資料 | 判定 | 理由 |
| --- | --- | --- |
| `order_lines` | **獨立表** | 多品項的必然 |
| `owners` 貨主主檔 | **獨立小表** | 畫面要顯示貨主名稱，不能只存 id。（原本還要承載「是否允許拆單」，該欄位已砍除） |
| `products` 商品款主檔 | **獨立表** | 一款商品有多個規格（SKU）。溫層屬於款層級——見下節「為何拆兩層」 |
| `skus` 規格主檔 | **獨立表** | 重量屬於規格層級，進 ③ 的成本函數；`sku` 不能永遠是裸字串 |
| `fulfillment_nodes` | **獨立表** | 欄位定義見 [dom-sourcing-scope.md](dom-sourcing-scope.md) 的「主檔需求」 |
| `stock_availability` 可用量 | **不做** | 可用量 100% 可從批次推導，存它只是快取。見「明確不做」的解封條件 |
| 收發地址 | **內嵌 orders** | 這裡沒有「地址簿」需求。獨立 `addresses` 表是 CRM 的做法，會憑空多一層 join，且地址是逐單指定的，不可重用 |

### 表結構

```sql
owners
  id, code, name, status,
  -- allow_split_shipment 已於 2026-07-29 砍除：它的定義是「是否允許跨節點拆單」，
  -- 而明細不可跨倉，這個開關沒有東西可以開關
  UNIQUE (code)

products                                            -- 1.14，款層級
  owner_id, product_code,
  name,                                             -- 品名，畫面顯示
  temperature_zone,                                 -- ③ 的硬約束，同款所有規格必然一致
  PRIMARY KEY (owner_id, product_code)

skus                                                -- 1.14，規格層級
  owner_id, sku_code,
  product_code,                                     -- FK 為 (owner_id, product_code)
  spec_name,                                        -- 規格，如「500ml」「XL」
  weight_gram,                                      -- ③ 的成本函數，規格不同重量不同
  PRIMARY KEY (owner_id, sku_code),
  FOREIGN KEY (owner_id, product_code) REFERENCES products

orders
  id,
  owner_id                NOT NULL REFERENCES owners,   -- 1.12
  external_order_no       NOT NULL,                     -- 1.2 冪等鍵
  ship_to_zone            NOT NULL,                     -- 1.13 ③ 的決策輸入
  ship_to_address         NOT NULL,                     -- 履約與面單用
  promised_delivery_date  NOT NULL,                     -- 1.13 ③ 的時效項基準
  fulfillment_node_id,                                  -- NOT NULL，貨主在上游指定的出貨倉
  status, placed_at, allocated_at, backordered_since,
  cancelled_at, fulfilled_at, version
  UNIQUE (owner_id, external_order_no)                  -- 冪等鍵，見下

order_lines
  id, order_id, line_no,
  owner_id,                                             -- 反正規化，見下
  sku_code,                                             -- FK 為 (owner_id, sku_code)
  quantity,
  status
  UNIQUE (order_id, line_no)
```

`products` 與 `skus` 的 key 含貨主的理由與 `external_order_no` 相同：**3PL 裡商品編碼由
貨主自訂，不同貨主的編碼會撞**——A 貨主的 `SKU-A` 與 B 貨主的 `SKU-A` 是完全不同的
商品。這也讓 `stock_pools` 的 `(owner_id, node_id, sku_code)` 自然對得上。

### 商品主檔為何拆成款與規格兩層

`products` 不改變任何配貨或選點的**決策行為**——③ 要的仍然是溫層與重量兩個值，多一層
只是多一次 join。依本文件後面「SKU 主檔只放三個欄位的理由」用的判準（「這個欄位會不會
讓決策的行為產生可見變化」），`products` 是通不過的。

它成立的理由是另一個：**它讓「同款商品溫層必然一致」從約定變成結構保證。**

| 欄位 | 層級 | 理由 |
| --- | --- | --- |
| `temperature_zone` | **款** | 同一款冷凍食品不可能有常溫規格。放 SKU 層，新增規格時填錯就製造出「同款兩種溫層」的髒資料，而 ③ 會據此排除節點——錯誤會在選點時才浮現 |
| `weight_gram` | **規格** | 500ml 與 1L 重量本來就不同，這是規格層級的事實 |
| `name` / `spec_name` | 款／規格 | 畫面上是「品名 · 規格」兩段 |

判準因此要補一句：**除了「是否改變決策行為」，還要看「是否讓一類資料錯誤在結構上不可能
發生」。** 兩者滿足其一即可。這條補充也解釋了為什麼 `hazmat_class`、`volume_cm3` 仍然
不做——它們兩個判準都不滿足。

### 貨主對庫存的連鎖

3PL 的鐵律：**A 貨主的貨不能出 B 貨主的單。** 即使 SKU 完全相同。

```text
北倉 · SKU-A
  ├── A 貨主的 100 個   ← 只能出 A 貨主的單
  └── B 貨主的  50 個   ← 只能出 B 貨主的單
                        兩者不可互相調用
```

因此庫存的 key 必須含貨主：

| 表 | 現況 | 目標 |
| --- | --- | --- |
| `stock_receipts` | 不存在 | 新表：到貨的不可變事實（批號、效期、到貨日） |
| `stock_pools` | `UNIQUE (sku)` | `UNIQUE (owner_id, receipt_id, node_id, stock_status)`——身分是**到貨**，批號與效期是屬性 |
| `location_stock` | 不存在 | `(location_id, owner_id, receipt_id, stock_status)` 複合主鍵——身分必須與 `stock_pools` 一致，否則對帳等式不成立 |

`stock_pools` 共有**四個**維度要擴張——貨主、節點、效期、良品狀態——**應在同一次
migration 完成**。分次做等於對同一組 unique constraint 與所有查詢改四輪，中間狀態
沒有任何價值。

### 為何不改名為 `stock_batches`

曾考慮在擴維度的同時把表改名為 `stock_batches`，理由是「一列已經代表一個批次」。**不改名。**

`lot_number` 不做（見 [dom-promising-scope.md](dom-promising-scope.md) 的 P1），因此 key 是
`(owner, receipt, node, stock_status)`。這代表：

> 今天收 100 件效期 2027-01-01，下週再收 50 件同效期——**兩者合併為同一列，`on_hand = 150`。**

真正的批次表（lot table）會把兩次收貨分開存，因為那是兩批不同的貨。這張表不會——它把
「效期與狀態相同」的單位視為完全可互換而合併。那正是 pool 的定義：**一群可互換的單位**。

加維度沒有把它變成批次表，只是**把可互換性切得更細**：

| key | 一列代表 | `pool` 準確嗎 |
| --- | --- | --- |
| `(sku)` 現況 | 這個 SKU 的所有可用單位 | 準 |
| `(owner, receipt, node, stock_status)` 目標 | 某一次到貨在某個倉、某個品質狀態下的貨 | **一樣準** |

`batch` 在 WMS 語境幾乎等同 lot，讀的人會期待「一次收貨 = 一列」與可追溯性，而這張表兩者
都不提供。那與 `order-promising` 承諾了 promise date、`availableToPromise()` 承諾了時間
分期是同一類錯誤——**名字承諾了系統沒做的事**。

要修的是散文而非表名：[dom-promising-scope.md](dom-promising-scope.md) 目前寫「各自代表一個
批次」。中文的「批次」可泛指「一群」，但英文表名叫 `batches` 會被讀成 lot。該處建議改為明確
定義——一列代表**同效期、同良品狀態的可互換單位群**，不是一次收貨的批。

### 擴維度是語意重新詮釋，不只是加欄位

`stock_pools` 現在一個 SKU 一列，語意上是**該 SKU 的可用量**。到貨、效期與品質狀態
進 key 之後，同一列的意思變成「該 SKU 的其中一組可互換單位」。既有資料會退化成
「某一次到貨、效期為空、`stock_status = AVAILABLE`」的那唯一一列——既有列因此需要補一筆 receipt 才遷得過去。

這不是相容的欄位擴充，而是**同一列的語意改變**，兩個連帶後果：

| 後果 | 說明 |
| --- | --- |
| migration 要明確處理既有列 | 既有列沒有對應的到貨記錄，補一筆 receipt 是資料決定不是技術細節 |
| 「一個 SKU 只有一列」的假設全部失效 | `StockPoolRepository.findBySku()` 目前回傳 `Optional<StockPool>`，擴維度後必須回傳 `List`。所有依賴單筆回傳的呼叫端都要改 |

### Index 的連鎖

現有 `idx_orders_backorder_fifo ON orders (sku, status, backordered_since, id)`
在 `sku` 搬到 `order_lines` 之後**會失效**，必須重建在 `order_lines` 上，且要含
`owner_id`（backorder 的 FIFO 隊列須按貨主分開）。

`idx_orders_recent ON orders (placed_at DESC, id DESC)` 不受影響。

---

## 商品主檔的下游用途

`skus` 由 ① 保管，但兩個欄位都是為 ③ 服務的：

| 欄位 | ③ 怎麼用 | 連鎖 |
| --- | --- | --- |
| `temperature_zone` | 原為候選節點篩選的硬約束 | **該用途已隨 ③ 移出範圍**（2026-07-29）。欄位保留——它仍是商品的事實，且款／規格兩層的結構價值不依賴它有沒有讀者 |
| `weight_gram` | 原為成本函數的運費基準 | **該用途已隨 ③ 移出範圍**（2026-07-29）。欄位保留——它是規格的事實，且款／規格兩層的結構價值不依賴它有沒有讀者 |

兩項連鎖的細節定義於 [dom-sourcing-scope.md](dom-sourcing-scope.md)。

① 這一側只負責兩件事：主檔要存在、收單時 `sku_code` 要能在主檔中找到（語意驗證
1.4 的一部分）。收單不做任何溫層或成本的判斷。

---

## 要補的具體工作

分為四段。段 A 獨立；段 C、D 依賴段 E。

### 段 A：編排權歸位（對應 1.6、1.7）

無新功能、無欄位改動的純重構。目前 ordering 的 `entrypoint/` 底下只有 `rest/`，
沒有 kafka——它聽不到任何事件，因此所有狀態轉移由 allocation 代勞。

要斷開的耦合點：

| 檔案 : 行 | 現在做的事 |
| --- | --- |
| `OrderAllocationCoordinator:29` | 注入 `OrderRepository`，持有他層的 repository |
| `OrderAllocationCoordinator:64` | `order.markBackOrdered(now)` |
| `OrderAllocationCoordinator:169` | `publishDomainEvents(orders)`，代發他層的 domain event |
| `AllocationService:58` | `order.markAllocated(now)`，domain service 跨層改他層 aggregate |
| `AllocateOrderUsecase:47-54` | 載入 `Order` 後由 `order.getSku()` 反查 `StockPool` |
| `ReplenishmentUsecase:54` | `orderRepository.findBackordersBySkuInFifoOrder(sku)` |
| `AllocationSelector:14`、`AllocationPolicy:8`、兩個 Policy | 排序邏輯建立在 `List<Order>` 上 |

`OrderRepository` 上的 `findBackordersBySkuInFifoOrder()` 是耦合最直接的證據——一個
純為 allocation 服務的查詢方法長在 ordering 的 repository 上。

要新增的檔案：

| 檔案 | 用途 |
| --- | --- |
| `ordering/entrypoint/kafka/OrderingKafkaIntegrationEventConsumer.java` | ordering 的耳朵 |
| `ordering/entrypoint/kafka/OrderAllocatedIntegrationEventHandler.java` | 消化配貨完成事實 |
| `ordering/entrypoint/kafka/BackorderCreatedIntegrationEventHandler.java` | 消化缺貨事實 |
| `ordering/infrastructure/configuration/OrderingKafkaErrorHandlingConfiguration.java` | 對應 allocation 既有設定 |
| `ordering/application/usecase/ConfirmOrderUsecase.java` | 編排推進器 |

並接上 inbox 去重（`V5__create_event_inbox_and_outbox.sql` 的機制已在，ordering 開始
使用）。

### 斷開耦合後 allocation 怎麼取得待配需求：資料庫 view

斷開耦合後 allocation 取不到 `List<Order>`，但 FIFO 排序需要待配需求。

| 方案 | 做法 | 判定 |
| --- | --- | --- |
| **資料庫 view** | ordering 側發布 `demand_lines` view，allocation 以唯讀 repository 查它並映射到自己的 `DemandLine` record | **採用** |
| Port 介面 | allocation 定介面，ordering 實作 adapter | 不採用——多一層介面，換到的東西 view 也給 |
| 投影表 | allocation 自建表，由 inbound handler 寫入 | 不採用——見下方比較 |

**採 view 的四個理由：**

| | 說明 |
| --- | --- |
| allocation 程式碼不出現 `order_lines` | 它查 `demand_lines`，架構測試「不得出現 `order_lines`」仍通得過 |
| `order_lines` 改結構時 allocation 不動 | 改 view 即可吸收（例如 R8 放寬多筆 line） |
| 「已滿足」的謂詞集中在一處 | 見下節。散在 Java 裡會被人忘記更新 |
| 無重複、無延遲、無對帳負擔 | 這是投影表要付的代價 |

投影表唯一的優勢是「已滿足」變成自己維護的欄位、不依賴他人的 enum。把謂詞放進 view
定義之後，那個優勢的大部分被抵銷——謂詞仍然要隨 `ReservationStatus` 演進更新，但只有
一個地方要改。

### view 的定義：兩個權威來源，各管一半

```sql
CREATE VIEW demand_lines AS
SELECT ol.order_id,
       ol.id                AS order_line_id,
       ol.owner_id,
       ol.sku_code,
       ol.quantity,
       ol.backordered_since AS queued_at
  FROM order_lines ol
  JOIN orders o ON o.id = ol.order_id
 WHERE o.cancelled_at IS NULL                    -- ordering 是權威，即時正確
   AND NOT EXISTS (                              -- allocation 自己的資料才是「已滿足」的權威
     SELECT 1 FROM stock_reservations sr
      WHERE sr.order_line_id = ol.id
        AND sr.status IN ('ACTIVE', 'CONSUMED'));
```

**刻意不出現 `ol.status`。** 寫入是非同步的（allocation 發事實 → ordering 收到後才改
`Order`），所以 ordering 的配貨狀態**落後於** allocation 的決策：

```
1. allocation 讀到需求
2. allocation 配貨成功、發出 OrderAllocatedIntegrationEvent
3. ordering 還沒處理該事件 → order_lines.status 仍是 BACKORDERED
4. 另一筆補貨進來、又讀到同一筆需求 → 重複預留
```

因此分界是：

| 事實 | 權威在 | 可否據此過濾 |
| --- | --- | --- |
| 訂了什麼、進入排隊時間 | ordering | **可以**，即時正確 |
| 是否已取消 | ordering（取消由它發起） | **可以**，即時正確 |
| **是否已配到／缺貨** | **allocation**（決策是它做的） | **不可以**，那份是落後視圖 |

一句話：**ordering 知道客人訂了什麼，allocation 知道自己滿足了什麼。**

### 「已滿足」的謂詞：`ACTIVE` 或 `CONSUMED`

| Reservation 狀態 | 何時 | 算已滿足嗎 |
| --- | --- | --- |
| `ACTIVE` | 配到、尚未出貨 | **是** |
| `CONSUMED` | 出貨後扣帳（R3 新增型別、R7 開始使用） | **是**——貨已經出去了 |
| `RELEASED` | 取消時釋放 | 否 |

**只寫 `ACTIVE` 會在 R7 出事**：出貨後 reservation 轉為 `CONSUMED`，該需求會重新出現在
view 裡而被再次配貨。而 R3 引入 `CONSUMED` 型別時**不會有任何測試提醒你**——出貨流程要到
R7 才存在。因此 R3 的檢查清單要包含「同步更新 view 定義」。

### migration 放在哪：R4，且 R4 因此依賴 R3

view 引用了兩樣 **R3 才存在**的東西：`sr.order_line_id`（R3 才把 FK 從 `order_id` 改過來）
與 `CONSUMED`（R3 新增的 enum 值）。所以它最早只能在 R3 之後，而 R4 才有消費者。

**連帶：R4 的依賴從「無」改為「R3」。** 建議序列本來就是 R3 → R4，禁忌 #3 也已禁止兩者
並行，這只是把事實寫進依賴表。

### 超賣防線不受本段影響

要明確記錄，避免日後誤解：**超賣的防線在 `StockPool` aggregate 本身**——`canReserve()`
檢查 ATP、`reserve()` 不滿足就拋、建構子拒絕 `reserved > onHand`、樂觀鎖處理併發。
這條線與「誰改 `Order`」無關，本段不動它。

本段真正相關的風險是**同一筆需求被重複預留**——那不是超賣（`reserved ≤ onHand` 仍成立），
而是庫存被白鎖：同一條 line 有兩筆預留，第二筆佔住的貨誰也用不到。兩道既有防線就足夠：

| 防線 | 作用 |
| --- | --- |
| view 的 `NOT EXISTS` | 已有 ACTIVE／CONSUMED 預留的 line 直接從 `demand_lines` 消失 |
| `StockPool` 樂觀鎖 ＋ `AllocationRetryExecutor` | 兩交易同時通過 view 時，一方 version 衝突 → 重讀 view → 該 line 已被排除 → 跳過 |

### 寫入為什麼走事件而不是同步呼叫

allocation 不呼叫 ordering,只發事實;`Order` 的狀態由 ordering 收到事實後自己推進。
三個理由,第一個與 module 邊界無關:

**（一）一個交易只修改一個 aggregate。** 現況一個交易同時改 `Order`、`StockPool`、
`StockReservation` 三個 aggregate,這違反 aggregate 作為一致性邊界的規則——即使兩個
context 在同一個 module 裡也一樣違反。

**（二）事件本來就要發。** `OrderAllocatedIntegrationEvent` 與
`BackorderCreatedIntegrationEvent` 已經在發布到 `promising.allocation-events`,那是
Promising 的對外契約,與 R4 無關。該 topic 目前**沒有任何 consumer**——R4 只是給它接上。
若改成同步呼叫,同一件事會有兩條路徑陳述,還要保證兩者一致。

**（三）鎖競爭不外溢。** 這個專案的核心量測是 `StockPool` 的樂觀鎖競爭。把 `Order` 綁進
同一個交易,會讓那個競爭的分析多一個變數。

代價要說清楚:配貨完成到訂單狀態更新之間有短暫落後,畫面上會看到「已配貨但列表仍顯示
缺貨」。**對操作台無害,但不可當配貨的閘門**——見上一節。

同步呼叫（allocation 定 `OrderConfirmationPort`,ordering 實作）也是站得住的選擇,收益是
原子性。但選它就不該再讓 ordering 消費 `OrderAllocatedIntegrationEvent`,以免雙軌。

### 一個新的邊界：取消與配貨的事件交錯

寫入改成非同步之後多了一個競爭，現況沒有：

```
allocation 配貨成功 → 發 OrderAllocatedIntegrationEvent
使用者同時取消      → ordering 改 Order 為 CANCELLED、發 OrderCancelledIntegrationEvent
                      → allocation 收到，釋放預留（正確）
ordering 收到 OrderAllocated → order.markAllocated() 拋
                              「Only pending or backordered orders can be allocated」→ 落 DLT
```

**這是合理的競爭，不是錯誤。** 因此 `ConfirmOrderUsecase` 必須把「訂單已取消」視為
**no-op**，而不是失敗——否則一次正常的取消操作就會製造一筆 DLT 訊息。

現況不會遇到，因為配貨與改 `Order` 在同一個交易裡，取消擠不進去。

### 寫入權責：每張表只有一個 module 寫

| 表 | 誰寫 | 觸發 |
| --- | --- | --- |
| `orders`、`order_lines` | **ordering** | 收單、取消、**收到配貨結果事件** |
| `stock_pools`、`stock_reservations` | **allocation** | 配貨、釋放、補貨、出庫消耗 |
| `event_outbox`、`event_inbox` | 各自 | 各自的 translator／handler |

現況的違反處就在第一列:`orders` 同時被 ordering（下單、取消）與 allocation
（`markAllocated`、`markBackOrdered`）寫入。

`stock_reservations` 的 FK 指向 `order_line_id`（R3）是**持有參照**,不是寫入,兩者不同。

這也給了段 A 一個比 grep import 更有意義的驗收條件：**allocation 的程式碼不得出現
`orders`／`order_lines` 這兩個表名（含 SQL 字串與 JdbcTemplate）**。只檢查 Java import
擋不住繞過型別直接寫表。

allocation 查的是 `demand_lines` view，因此這條規則不需要為讀取開例外。

### 段 D：收單冪等（對應 1.2）

訂單來源是上游系統而非人類點擊，因此重送是常態而非偶發：網路逾時、上游 retry、
批次重跑，每一種都會送出同一張單兩次。目前 `PlaceOrderUsecase` 無任何防護，同一張
出貨單送兩次即建立兩筆訂單、扣兩次庫存——在倉儲場景是直接的資料事故。

| 動作 | 檔案 |
| --- | --- |
| 加 `external_order_no` | `Order`、`OrderEntity`、新 migration |
| 建 `UNIQUE (owner_id, external_order_no)` | 新 migration |
| 重送時回傳既有訂單 | `PlaceOrderUsecase`、`OrderController` |
| 接受上游單號 | `PlaceOrderRequest` |

冪等鍵含 `owner_id` 的理由見「資料模型」。因此**段 D 依賴段 E**——沒有貨主就無法建
正確的 unique constraint。

### 段 E：貨主與 3PL 隔離（對應 1.12）

表列依建立順序，FK 的被指向方在前。

| 動作 | 檔案 |
| --- | --- |
| 建 `owners` 主檔 | 新 migration、`Owner`、`OwnerEntity`、`OwnerRepository(+Impl)` |
| `fulfillment_nodes` 主檔 | **不在本段**。③ 移出範圍後它縮成極簡表（`id`／`code`／`name`／`status`），但仍**必須先於** `stock_pools` 加 `node_id`，否則 FK 無處可指。見 [execution-roadmap.md](execution-roadmap.md) 的 R2 |
| 建 `products` 款主檔 | 新 migration、`Product`、`ProductEntity`、`ProductRepository(+Impl)`。key 為 `(owner_id, product_code)`，持有 `temperature_zone` |
| 建 `skus` 規格主檔 | 新 migration、`Sku`、`SkuEntity`、`SkuRepository(+Impl)`。key 為 `(owner_id, sku_code)`，FK 指向 `products`，持有 `weight_gram` |
| `orders` 加 `owner_id` | `Order`、`OrderEntity`、`OrderMapper`、新 migration |
| `stock_pools` key 加 `owner_id` 與 `node_id`（**不改名**，理由見「資料模型」） | `StockPool`、`StockPoolEntity`、`StockPoolMapper`、`StockPoolRepository(+Impl)`（`findBySku` 改回傳 `List`）、`JpaStockRepository`、新 migration |
| 加跨貨主校驗 | `AllocationService.requireMatchingOwner()` |
| 查詢帶貨主 | `AllocateOrderUsecase`、`ReplenishmentUsecase`、`GetStockPoolUsecase` |
| Seed | `DevSeedDataInitializer` 加一至兩個貨主；商品含常溫與冷凍各一款，其中一款帶兩個規格以顯示款／規格兩層 |
| 畫面 | 訂單列表與庫存頁加貨主欄；庫存頁顯示「品名 · 規格」 |

`stock_pools` 的四個維度應在同一次 migration 完成，理由見「資料模型」。此段因此與
③ 的庫存部分、② 的批次化屬同一次改動，見
[system-layer-map.md 的「第一步」](system-layer-map.md)。

### 段 C：多品項（對應 1.5、1.9）

牽動面最廣，因為它改變 Kafka 契約。

| 層 | 檔案 |
| --- | --- |
| domain | `Order`、**`OrderLine`（新）**、`OrderPlaced`、`OrderBackordered`、`OrderCancelled` |
| infrastructure | `OrderEntity`、**`OrderLineEntity`（新）**、`OrderMapper`、`OrderRepository(+Impl)`、`JpaOrderRepository`、**新 migration** |
| application | `PlaceOrderUsecase`、`GetOrderUsecase`、`ListRecentOrdersUsecase`、`OrderDetail`、`OrderingDomainEventTranslator` |
| 契約 | `OrderPlacedIntegrationEvent`、`OrderCancelledIntegrationEvent` → 連帶 allocation 的 `OrderPlacedIntegrationEventHandler`、`OrderCancelledIntegrationEventHandler`，以及 `e2e/perf/k6/*` |
| entrypoint | `OrderController`、`PlaceOrderRequest`、`OrderStatusResponse` |
| index | `idx_orders_backorder_fifo` 重建於 `order_lines`，含 `owner_id` |

整單狀態變成各 line 狀態的聚合函數。**但採 ship-complete 之後不會有
`PARTIALLY_ALLOCATED`**——所有 line 在同一個交易內一起配到或一起缺貨，聚合函數退化為
「全部 ALLOCATED → ALLOCATED；任一未滿足 → BACKORDERED」。

代價是本段從「放寬一個 domain 檢查」變成**重寫配貨為整籃原子判斷**：可滿足性從逐 SKU
獨立變成「整籃的所有 SKU 必須同時可滿足」，`StrictFifoAllocationPolicy` 要跟著改，且一次
交易會碰多個 `StockPool`。詳見 [dom-promising-scope.md](dom-promising-scope.md) 的
「缺貨時的行為」。

新增 usecase：`AmendOrderUsecase`、`SplitOrderUsecase`。

---

## 執行順序

本層與履約層的整體順序見 [system-layer-map.md](system-layer-map.md)，此處僅列本層
內部依賴。

```text
段 E 貨主與商品主檔 ─┬──▶ 段 D 收單冪等（冪等鍵需要 owner_id）
                     │
                     └──▶ 段 C 多品項

段 A 編排權歸位 ──── 獨立，為 ③ 的前置
② consume 閉環 ──── 獨立，為履約層的前置
```

| 段 | 依賴 | 理由 |
| --- | --- | --- |
| 段 E 貨主與商品主檔 | 無 | 其餘兩段的前置，且 ③ 的硬約束與成本函數都要它，應最先做 |
| 段 A 編排權歸位 | 無 | 純重構，有既有測試護著；為 ③ 的前置 |
| ② consume | 無 | 可與段 A 並行；為履約層的前置 |
| 段 D 冪等 | 段 E | 冪等鍵是 `(owner_id, external_order_no)` |
| 段 C 多品項 | 段 E | 量最大且改 Kafka 契約，排最後 |

~~段 B 狀態機補完~~ 已刪除。原設想的 `ON_HOLD` 與 `CONFIRMED` 源於金流與風控，本
系統不涉及。

~~段 F 直送流程~~ 已刪除。直送（`ship_from` 由貨主指定非倉庫來源）會讓 ①②③ 與
履約層各多一個分支，而該分支上沒有配貨決策、選點或揀貨——投入產出比不成立。
`fulfillment_node_id`（貨主指定從哪個倉出）**保留**，那仍是倉出流程，與直送是兩回事。
（原文寫「只是跳過 sourcing 決策」——③ 移出範圍後已經沒有可跳過的決策，指定的就是實際出貨倉。）`Order` 的終態為 `FULFILLED`，用詞理由見
[system-layer-map.md](system-layer-map.md)。

---

## 明確不做

| 項目 | 歸屬 | 說明 |
| --- | --- | --- |
| 金流、發票、稅務 | 商務層 | 本系統不從客戶端收錢 |
| 風控、信用審核 | 商務層 | 隨金流一併排除 |
| 目錄、購物車、結帳 | 商務層 | 訂單抵達本系統時已成形 |
| 貨主結算、計費 | 商務層 | 3PL 的計費是獨立領域，與出貨正確性無關 |
| 地址簿 | — | 地址逐單指定，不可重用，內嵌於 `orders` |
| Geocoding、地址正規化 | 外部服務 | 分區粒度已足夠決策 |
| 語意驗證（1.4） | 選配 | 缺席時錯誤在下游浮現，不影響正確性 |
| 訂單變更（1.8） | 選配 | 以「取消後重下」達成 |
| 直送（drop-ship） | — | 見「執行順序」末段 |
| line 內數量部分出（訂 10 出 6） | 不做 | `canReserve(quantity)` 已是全有全無 |
| line 間部分配貨（`PARTIALLY_ALLOCATED`） | 不做 | 採 ship-complete。理由與代價見 [dom-promising-scope.md](dom-promising-scope.md) |
| `damagedQuantity` / `blockedQuantity` 欄位 | 取代 | 改用 `stock_pools` 的 `stock_status` 維度（`AVAILABLE`／`DAMAGED`／`QUARANTINE`／`BLOCKED`），見 [dom-promising-scope.md](dom-promising-scope.md) |
| `lot_number` 批號 | 延後 | 只是 tie-breaker，不改變配對邏輯，見 [dom-promising-scope.md](dom-promising-scope.md) 的 P1。**它的缺席也是 `stock_pools` 不改名為 `stock_batches` 的理由** |
| `stock_availability` 聚合表 | 不做 | FEFO 下 `reserved` 只能長在批次列上，可用量因此 100% 可推導，存它只是快取。**兩個解封條件**：③ 選點的 ATP 計算量測出延遲（每張單 K 節點 × M line × B 批次）；或出現「對貨主／通路發布可售量」的職責——那個數字要扣安全庫存與通路配額，是獨立事實而非推導值 |
| 表名改為 `stock_batches` | 不做 | 見「資料模型」的「為何不改名」 |
| `serviceLevel`、`freightTerm` | 精簡 | 與 `promisedDeliveryDate`、重量費率是同一成本項的重複操作 |
