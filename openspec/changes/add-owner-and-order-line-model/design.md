## Context

`orders` 目前是 `(id, sku, quantity, status, 四個時間戳, version)`。`sku` 是裸字串，
沒有主檔可以指向；一張單只能是一個 SKU 的一個數量；沒有貨主，因此「這批貨屬於誰」在
系統裡不存在。

這個形狀不是疏漏，是 v1 刻意的最小起點——當時要證明的是超賣防線與 FIFO 佇列，兩者都
不需要貨主與 line。但 [execution-roadmap.md](../../../docs/execution-roadmap.md) 之後的
每一個 change 都需要：R3 的跨貨主隔離要 `owner_id`，R6 的選點要溫層與重量，R7 的揀貨
要 line 粒度。本 change 是 roadmap 的 **R1**，無依賴，可與 R2 並行。

逐欄位、逐方法的展開在
[r1-order-data-model-and-usecases.md](../../../docs/r1-order-data-model-and-usecases.md)，
設計理由在
[dom-order-intake-scope.md](../../../docs/dom-order-intake-scope.md)。本文件不重複那些
內容，只記錄**做這個 change 時要下的技術決定**。

限制條件有兩個。第一，schema 尚未部署至任何環境，因此 migration 可以改寫而非疊加。
第二，`stock_pools` 在本 change **不動**——它的四維化屬 R3，這造成一個明確的中間狀態：
seed 有兩個貨主，但配貨仍可能跨貨主取用。

## Goals / Non-Goals

**Goals:**

- 讓一張訂單能表達「哪個貨主的、哪張上游單、送到哪、什麼時候要到、包含哪些行」。
- 建立貨主／款／規格三層主檔，讓 `sku` 從裸字串變成有主檔可指的識別碼，且 3PL 的
  撞號情境（兩個貨主都有 `SKU-A`）在鍵的設計上被正確處理。
- 把後續 change 會反覆 ALTER 同一組表的項目一次做完，避免重複遷移。
- **讓 N=2 的路徑從第一天就一直在跑**，即使正式入口只收一筆 line。
- 不讓壓測基準斷掉——FIFO index 在本 change 就重建於 `order_lines`。

**Non-Goals:**

- **不做收單冪等的行為**（重送時回傳既有訂單）。欄位與 unique constraint 在本 change，
  行為在 R5。兩者之間重送會得到資料庫錯誤——那不是正確行為，只是安全的錯誤行為。
- **不放寬多筆 line**。schema、`rehydrate()` 與 FIFO index 都已就緒，但 `place()` 仍
  限定恰好一筆。放寬屬 R8，且它不是「移除一個檢查」——整籃原子判斷要重寫
  `StrictFifoAllocationPolicy`。
- **不做跨 SKU 的整籃判斷**。本 change 的整籃原子性只涵蓋**同一個 SKU 的多行**，因為
  現況一次配貨只取一個 `StockPool`：配貨 usecase 以單一 SKU 撈池、`AllocationService`
  的簽章吃一個池、`BasicAllocationContext` 只是一個可承諾量的整數。跨 SKU 要同時改這
  四處加上 coordinator 的持久化，那是 R8 的核心。
- **不加 `PARTIALLY_ALLOCATED`**。採 ship-complete（見
  [dom-promising-scope.md](../../../docs/dom-promising-scope.md)），該狀態不存在，不是
  「留到 R8」。
- **不動 `stock_pools`／`stock_reservations`**，因此跨貨主隔離不生效。屬 R3。
- **不改 partition key，而且現階段改反而會壞。** `stock_pools` 的 unique key 仍是
  `(sku)`，因此兩個貨主的同名 SKU **真的共用同一列庫存、搶同一個樂觀鎖版本號**。`sku`
  策略的目的正是讓競爭同一個 `StockPool` 的事件收斂到同一個 partition，所以裸 `sku`
  在此刻是正確的 key。若現在就改成 `ownerId:skuCode`，兩個貨主的事件會被分到不同
  partition 卻平行去搶同一列，single-writer 保證直接破掉。**partition key 的正確形狀
  必須跟著 `StockPool` 的識別走**——R3 讓庫存真的分開之後才輪到它改（另見下方「sku
  partition 策略有到期日」）。
- **不建 `fulfillment_nodes`**，因此 `order_lines.assigned_node_id` **只建欄位不建 FK**，
  且恆為空。主檔屬 R2、填值屬 R6。
- **不提供主檔的寫入介面**。`Owner`／`Product`／`Sku` 由 seed 建立，CRUD 介面不在範圍。
- **不做應用層的 SKU 語意驗證**。`order_lines` 的 `(owner_id, sku_code)` FK 已在資料庫
  層擋住不存在的 SKU；再加一層應用層檢查只能換到更好的錯誤訊息，不改變正確性。
- 不做選點決策、不做出貨、不改 allocation 的決策邏輯。

## Decisions

### `V3` 改寫為最終形狀並更名，不新增 ALTER migration

四張新表加上 `orders` 的欄位增刪，有兩種落法：新增 `V6` 做 `CREATE` 加 `ALTER`，或把
`V3__create_orders.sql` 改寫成最終形狀。

選改寫，並更名為 `V3__create_ordering_tables.sql`（檔案不再只建 `orders`）。三個理由：
schema 從未部署，`ALTER TABLE orders DROP COLUMN sku` 記錄的會是一段沒有任何環境經歷過
的假歷史；`orders.owner_id` 的 FK 指向 `owners`，若 `owners` 建在 `V6` 就會出現「被指向
方比指向方晚建」的倒置；本專案前兩個 change（`add-demo-console-api`、
`fix-outbox-partition-key-semantics`）已經以相同理由改寫既有 migration，慣例一致。

代價是必須 `./e2e/perf/run.sh down` 移除 Postgres volume 後重建，否則 Flyway checksum
不符。**不得以 `flyway repair` 略過**——那會讓資料庫裡的表停在舊形狀而 Flyway 認為已
套用。

檔案內的建表順序固定為 `owners` → `products` → `skus` → `orders` → `order_lines`，
FK 的被指向方一律在前。

### 商品主檔拆成款與規格兩層

`products` 持有 `temperature_zone`，`skus` 持有 `weight_gram`。另一個選項是單一
`skus` 表同時持有兩者。

拆兩層是為了讓「同款兩種溫層」在結構上無法產生。單表下，同一款商品的兩個規格可以各自
填不同溫層，那是髒資料而非合法狀態，而它不會在收單時報錯——會拖到 R6 依 `capabilities`
篩選節點時才以「同一款商品被篩到不同節點」的形式浮現，屆時很難歸因。

分層的判準是「這個屬性屬於款還是屬於規格」：冷凍水餃的 500g 包與 1kg 包都是冷凍（款），
但重量不同（規格）。

### 主檔用代理鍵，撞號保護交給 unique constraint

`products` 與 `skus` 的 PK 是 UUID 代理鍵，`(owner_id, product_code)` 與
`(owner_id, sku_code)` 降為 unique constraint。

**這與最初的決定相反。** 一開始選的是複合 PK，理由是「讓 `sku_code` 單獨無意義這件事
出現在型別上」。那個理由不足以偏離慣例：

- 既有五張表（`orders`、`order_lines`、`stock_pools`、`stock_reservations`、outbox）
  全部是 `id UUID PRIMARY KEY`。主檔用複合 PK 是這個 repo 裡唯一的例外，而 design 若要
  留下例外就得有比「比較貼切」更強的理由。
- **撞號保護不需要複合 PK。**「同一貨主的 `sku_code` 唯一、不同貨主可以撞號」由 unique
  constraint 保證，與主鍵是誰無關。把約束價值與主鍵選擇混為一談是最初那個決定的錯誤。
- 複合 PK 的成本是真實且每天都會碰到的：JPA 要 `@IdClass` 或 `@EmbeddedId`，前者的鍵
  欄位得宣告兩次、不同步時整個 persistence context 建不起來，後者讓查詢方法變成
  `findById_OwnerId…`。複合鍵還會沿外鍵鏈往下傳播欄位。

代理鍵的代價——`sku_code` 這個跨模組流通的字串與 `id` 並存——在這裡不需付：
**`order_lines` 的外鍵仍然是 `(owner_id, sku_code)`，指向 `skus` 的 unique constraint。**
PostgreSQL 允許外鍵指向 unique constraint 而不必是 PK，因此資料庫層的保證完全不變，
訂單行也不必多存一個 `sku_id`，配貨路徑不必為了取 `sku_code` 而 join 主檔。

`skus.id` 目前沒有引用者。它存在是為了 JPA 的單純與慣例一致，不是為了被指向。

判準留給後續 change：**代理鍵是預設**，只有「純連接表」與「身分依附父實體的弱實體」
兩種情形才用複合 PK；偏離時 unique constraint 一律不可省。

### 主檔放獨立的 `catalog` package

`Owner`、`Product`、`Sku` 放在新的 `catalog` package，與 `ordering`、`allocation` 平行，
內部同樣分 domain／application／infrastructure／entrypoint。

替代方案是放進 `ordering`——主檔確實是收單時才用到，檔案數也最少。不選它的理由是依賴
方向：R3 的 `requireMatchingOwner()` 會讓 allocation 也需要讀貨主，屆時 allocation 就得
`import ordering.domain.model.Owner`，而 **R4 的驗收條件之一正是
`grep -r "ordering.domain.model" allocation/` 結果為空**。放進 `ordering` 等於現在種下一個
兩個 change 之後要拔掉的依賴。

放 `common` 也不行：那裡目前裝的是 outbox、inbox、ddd 這類技術設施，放業務模型進去會模糊
它的職責。

`catalog` 不依賴 `ordering` 或 `allocation`，兩者都可以依賴它——這是主檔應有的方向。

### `order_lines` 反正規化 `owner_id` 與 `backordered_since`

兩個欄位都能從 header join 得到，仍然存在 line 上，判準相同：**值不可變，因此沒有同步
成本**。一張單的貨主不會改變；ship-complete 下所有 line 一起進缺貨，line 的
`backordered_since` 恆等於 header 的值。

`owner_id` 的直接用途是 FK——`(owner_id, sku_code)` 才能建外鍵，否則 SKU 的完整性只能
在應用層檢查。`backordered_since` 的直接用途是下一個決定裡的 index。

**`allocated_at` 不放 line。** 它同樣恆等於 header，但沒有任何 index 需要它，所以是純
冗餘欄位。反正規化要有具體的讀取端，不是「順手都放」。

### FIFO index 建在 `order_lines` 且刻意不含 `status`

`ALTER TABLE orders DROP COLUMN sku` 會讓 PostgreSQL 自動移除 `idx_orders_backorder_fifo`
——這不是排程選擇，是強制的。替代的 index 是
`idx_order_lines_backorder_fifo (owner_id, sku_code, backordered_since, id)`。

**這個 index 從 R8 提前到本 change，理由是壓測而非領域事實。** FIFO 查詢的篩選鍵
（`owner_id`、`sku_code`）在 line、排序鍵在 header，橫跨兩張表的「篩選 ＋ 排序」無法用
單一複合 index 覆蓋。若留在 R8，本 change 到 R8 全程都沒有這個 index，壓測數字會斷掉
且無法歸因。

欄位順序沿用舊 index 的道理：等值篩選在前、排序鍵其次、`id` 作為 tie-breaker。含
`owner_id` 是必要的——A 貨主的單不應該被 B 貨主的單卡住。

**刻意不含 `status`**，這是與舊 index 唯一的實質差異。舊查詢是
`WHERE status = 'BACKORDERED'`，但 R4 之後待配佇列**不能依 status 過濾**——ordering 的
配貨狀態落後於 allocation 的決策，拿它當閘門會重複預留。而 `status` 若留在 `sku_code`
與 `backordered_since` 之間，index 掃出的列會先按 status 分組再按時間排序，查詢不篩
status 時 PostgreSQL 仍得排序一次，index 等於白建。

### 「每張單恰好一筆 line」只寫在 `place()`

三個位置對 line 數量的態度刻意不同：

| 位置 | 是否限制一筆 | 理由 |
| --- | --- | --- |
| schema | 否 | R8 才不用改表——這是 R8「不搬遷任何結構」的前提 |
| `Order.place()` | **是** | 「恰好一筆」是**收單政策**，不是 domain invariant |
| `Order.rehydrate()` | 否 | 它的職責是還原資料庫裡的任何東西 |

`rehydrate()` 不設限的直接用途是**測試能造出 N=2 的 `Order`**，讓讀取、映射、序列化、
配貨在多行下一直被驗證，即使正式入口還進不來。若把限制寫進 `rehydrate()`，這條路就
斷了，本 change 三項防護裡的兩項也就無法成立。

### `OrderLine` 是 entity 而非 aggregate root

`Owner`、`Product`、`Sku` 各自是 aggregate root；`OrderLine` 不是。它沒有獨立的一致性
邊界——數量與狀態的變更必須經過 `Order` 才能維持整單狀態一致，而 ship-complete 下
「整單一起配到或一起缺貨」正是這個一致性的內容。因此不提供 `OrderLineRepository`，
line 只能經由 `Order` 存取與持久化。

**建構入口的判準**（本 change 確立，供 R2 的 `FulfillmentNode`、R7 的 `Shipment` 沿用）：
分野是「**新建與從儲存還原的前置條件是否不同**」，不是「是不是 aggregate root」。

| 情形 | 做法 | 本 change 的例子 |
| --- | --- | --- |
| 新建有政策或固定初始狀態，還原則否 | 一對具名 factory | `Order.place`／`rehydrate`、`OrderLine.create`／`rehydrate` |
| 建立本身是業務事件 | factory 用業務動詞，不用 `create` | `place` 而非 `createOrder` |
| 兩者皆無 | public constructor，不為了「看起來像 DDD」硬包一層 | `Owner`、`Product`、`Sku`（沿用既有的 `StockPool`） |

**不為測試方便另開第三個入口。** 測試要的「一張已存在的待配訂單」正是 `rehydrate()` 的
語意；用 `place()` 造會憑空產生一個永遠不會被發布的 `OrderPlaced` 事件，逼每個測試記得
清掉它。測試 fixture 因此區分 `pendingOrder()`（走 `rehydrate`）與直接呼叫
`Order.place(...)`（只有測收單本身的測試才用）。

### `PlaceOrderUsecase` 改收 command 物件

header 欄位加到六個之後位置參數的呼叫端可讀性崩潰，而且 line 清單無法用位置參數自然
表達。`placeOrder(PlaceOrderCommand)` 取代 `placeOrder(String sku, Integer quantity)`。

`GetOrderUsecase`、`ListRecentOrdersUsecase`、`CancelOrderUsecase` 的簽章不變，只是
回傳的 `Order` 帶 lines。

### 訂單回應只帶 `ownerId`，名稱由呼叫端自行解析

`GET /orders` 的每一筆只回 `ownerId`，不回貨主名稱。

最初的決定相反——理由是「只回識別碼的話，前端要為列表的每一列再打一次
`/owners/{id}`，那是 N+1」。**那個理由假設了一個不存在的替代方案。** 真正的替代是前端載入
時查一次 `/owners` 建 map，而**前端本來就要載那份資料**：下單表單的貨主下拉選單需要它。
同一個畫面上那份 map 已經在手上了。

「每一列各打一次」是 N+1，「整個畫面打一次」不是。把兩者混為一談，就會為了解一個不存在的
問題而在契約裡加欄位。

拿掉之後的實際差異：每次列表少一次主檔查詢；不需要 `OwnerRepository.findByIds`；而且
`OrderController` 回到與 repo 其他 controller 一致的純轉換——先前為了填名稱，它得跨兩個
來源組資料，那是 application 層的職責，是這個 repo 裡唯一一個在 controller 做組合的地方。

代價是 API 不自足：非前端的消費者要自己解析名稱。目前沒有這種消費者；真的出現時，加一個
專門的端點比現在預先反正規化好。

### allocation 拿聚合後的需求，不拿 line 集合

`Order` 對 allocation 暴露的是 `getDemand()`——SKU 對數量的映射，而非 `getLines()`。

替代方案是讓 allocation 拿到 line 集合、再靠測試擋住逐行獨立配貨。不選它，因為測試是
事後防線：「逐行判斷可滿足性、配得到就預留」在單行下**是正確的、不是 bug**，寫的人不會
察覺自己踩了什麼，只有那一支特意寫的測試會失敗。

拿到映射之後，配貨端看到的是「這張單總共要什麼」，**沒有「行」這個東西可以逐個處理**。
違反 ship-complete 的寫法不是「測試會抓到」，而是打不出來。R1 單行下這個映射只有一筆，
行為完全不變；R8 多行時它自然變成多筆，而配貨端的程式碼不需要改——它從來沒有假設過只有
一筆。

第二個好處是介面切在對的位置。R4 的 `demand_lines` view 要做的正是「allocation 不該直接
看 `Order`」，提前切在這裡等於少一次回頭拆解。

代價是 `AllocationService.requireMatchingSku()` 等處要改讀映射，比「只加 getter」多動幾
個檔——但那些檔在本 change 本來就要改。

### 單行假設集中到一個具名方法，不用字串黑名單

`sku` 從 `orders` 搬到 `order_lines` 之後，仍有兩處需要把 N 行**摺成一個值**：outbox 的
partition key 與配貨重試的 context 標籤。兩者都只能有一個值，for 迴圈給不出來。R1 之所以
做得到，純粹是因為 N=1。

原本的想法是用架構測試禁止 `getLines().get(` 與 `.getFirst()`。不採用，兩個理由：

第一，它與上述需求正面衝突——那兩處被禁之後就寫不出來。

第二，字串黑名單擋不住真正該擋的東西。`stream().findFirst()` 繞得過，`for` 迴圈第一圈就
`break` 也繞得過，做的事與 `get(0)` 完全一樣而測試抓不到。**列舉違規寫法是列不完的。**

改為在 `Order` 上開一個具名方法（`requireSingleLine()`），語意是「此處踩在單行假設上」，
那兩處都經由它取值；架構測試改為斷言**除該方法本身外**，production code 不得以位置存取
line。配貨查庫存那處不走這個方法——它已經改讀 `getDemand()`。

差別在 R8：搜尋 `requireSingleLine()` 的呼叫點，要拆的清單就是完整的，而且方法名字本身
就在提醒它是暫時的。黑名單只能告訴你「現在沒人違規」，不能告訴你「假設藏在哪幾處」。

### 三項 line 數量無關性的防護

「schema 是 line 形狀」不保證「邏輯與 line 數量無關」。下列寫法在單行下**是正確的、
不是 bug**，因此不會有任何測試失敗來提醒你：

| 寫法 | 單行下 | 多行下 |
| --- | --- | --- |
| `order.getLines().get(0)` | 正確 | 只處理第一行 |
| 逐行獨立判斷可滿足性、配得到就預留 | 正確 | **違反 ship-complete**，為出不去的單鎖住庫存 |
| 一次配貨只碰一個 `StockPool` | 正確 | 會碰多個，排序鍵不含 `sku_code` 就有死鎖風險 |

三項防護分別對應：以 `rehydrate()` 造 N=2 fixture 驗讀取／映射／序列化；**同一個 SKU 兩
行**的整籃原子性測試；以及上一節的具名方法加架構測試。

整籃原子性測試限定同 SKU，是因為現況一次配貨只取一個 `StockPool`（見 Non-Goals）。具體
形狀是：ATP 為 5，兩行各要 5，整單配不到、`stock_reservations` 零筆。實作上要把配貨時
用的數量從「訂單的 quantity」改為 `getDemand()` 的加總——**這是實作動作而非只是測試**。

它擋住的正是要擋的錯誤：若有人寫成逐行獨立配貨，第一行會配到 5、第二行缺貨，測試就會看到
一筆不該存在的預留。**這一項最容易跳過**——單行下它退化成「配不到就缺貨」，所以錯誤實作
會通過其他所有測試。

### 補貨事件帶貨主，佇列先於庫存分開

`findBackordersBySkuInFifoOrder` 加上 `ownerId` 之後，R1 的唯一呼叫端是
`ReplenishmentUsecase`，而它的輸入來自外部 Inventory context 的補貨事件——那個事件只帶
SKU，`stock_pools` 也還沒有 `owner_id`。**參數加了卻沒有任何真實路徑填得出值。**

三個選項：讓補貨事件帶貨主；傳 null 或查全部貨主；整條留到 R3。選第一個。

第二個等於讓「backorder 佇列按貨主分開」成為一條沒有生效路徑的規格——spec 說了謊比缺一條
規格糟。第三個則讓 R1 的 FIFO index 含 `owner_id` 卻沒有查詢用得到它。

代價是一個**歪斜的中間狀態，要明確記錄**：`stock_pools` 沒有貨主維度，所以補進去的仍是
共用池，兩個貨主的訂單都吃得到，但喚醒 backorder 佇列時只喚醒該貨主的。也就是**佇列分開
了，庫存還沒分開**。這比現況好——A 貨主的單不會再被 B 貨主的單卡住——方向也對，R3 給
`stock_pools` 加 `owner_id` 就補齊。

連帶動到 `StockReplenishedIntegrationEvent`、`ReplenishStockCommand`、dev 補貨探針、前端
庫存頁的補貨動作與 k6 腳本。

### sku partition 策略有到期日

這一節不改任何程式碼，只記錄一個 proposal 原本沒有寫下的限制。

先釐清一個容易搞反的地方：**partition key 的正確形狀由 `StockPool` 的識別決定，不是由訂單
決定。** key 存在的目的是讓「會搶同一列庫存」的事件排進同一個 partition，因此兩者必須一起
演進：

| 階段 | `StockPool` 的識別 | 正確的 key |
| --- | --- | --- |
| 本 change | `(sku)` | `sku`——同名 SKU 真的共用一列，現況正確 |
| R3 之後 | `(owner_id, node_id, sku_code, expire_date, group)` | `ownerId:skuCode`，**必須與加 `owner_id` 在同一個 change** |
| R8 之後 | 同上 | 無解，見下 |

策略還有第二個前提：「一張單 = 一個 SKU」，一則事件才摺得出單一個 key。**這個前提在 line
模型下有壽命**——R8 之後一張單碰多個 SKU，一則 `OrderPlaced` 事件無法同時進兩個 partition。
上表的 R3 那一列只是換 key，這個前提沒變，一樣撐不到 R8。

收尾**只有一條路：策略退場、退回 `orderId`**。原本以為還有「事件按 SKU 拆成多則」這個選項
——那不成立，拆開之後一張單的兩則事件被兩個 writer 各自處理，沒有人看得到整張單，而那正是
ship-complete 要求的東西。

更根本地說，這個策略與 ship-complete 是衝突的：整籃原子判斷要在同一個交易裡檢查所有 SKU 的
ATP，而 per-SKU 分區的保證是「同一個 SKU 的事件由同一個 writer 序列化」，跨 SKU 的交易必然
跨越多個 writer 的管轄。**換更複雜的複合 key 救不回來**——把 SKU 集合雜湊成 key 也不行，同一
個 SKU 會出現在多種組合裡。問題不在 key 的組成，在「一次交易碰多個資源」與「一個 key 只指向
一個 partition」之間的矛盾。

因此複合鍵（`ownerId:skuCode`）是 R3～R8 之間的**中間形態，不是終點**。本 change 不做選擇，
只確保下一個人知道這裡有到期日，而不是以為 R3 改完就沒事了。

### 地址內嵌 `orders`，不另開 `addresses` 表

`ship_to_zone` 與 `ship_to_address` 直接放 `orders`。沒有地址簿需求，地址逐單指定、
不可重用，獨立表只會憑空多一層 join。`ship_to_zone` 是 R6 的決策輸入（配送分區），
`ship_to_address` 是履約與面單用，sourcing 不看——兩者用途不同因此都存。

### 三項從後續 change 提前

| 提前的項目 | 原本在 | 理由 |
| --- | --- | --- |
| `order_lines.backordered_since` | R8 | 不提前就沒有 FIFO index 可用（見上） |
| `idx_order_lines_backorder_fifo` | R8 | 同上，壓測基準會斷掉 |
| `UNIQUE (owner_id, external_order_no)` | R5 | 一行 constraint，把「靜默建立重複訂單」變成「明確報錯」。倉儲場景下前者是資料事故 |

共同判準是「動的是同一組表，分次做等於重複遷移」。**不是所有後續欄位都提前**——
`assigned_node_id` 的 FK 就沒有，因為它需要 R2 的 `fulfillment_nodes` 先存在，提前的
不是遷移成本而是相依性。

**`orders.fulfilled_at` 原本也在這張表上，現在移除。** 它的理由是「讓 `orders` 只被
ALTER 一次」，而那個理由在選了「重寫 `V3` 為最終形狀」之後就不成立了：R7 要加這個欄位時
本來就是一支新的 migration，與 R1 有沒有先建它無關，省下的那次 ALTER 並不存在。留著的代
價雖小，卻會讓它出現在 entity、mapper 與訂單回應上，讓讀的人以為有東西會寫進去。

`requested_node_id` 則保留。它與 `fulfilled_at` 的差別是**方向**：前者是收單時上游就指定
的輸入（貨主指定出貨倉），屬於這張單的資料；後者是 R7 才產生的輸出，R1 沒有任何路徑會寫
它。

## Implementation Contract

**行為**

- 下單時呼叫方提供貨主、上游單號、收件分區與地址、承諾到貨日，以及**恰好一筆**訂單行
  （SKU 與數量）。送出零筆或兩筆以上會被拒絕，錯誤訊息指明是收單政策的限制。
- 送出不存在於主檔的 `(ownerId, skuCode)` 組合會失敗，不會建立訂單。
- 查詢訂單（單筆或列表）回傳的訂單帶貨主識別碼與訂單行陣列；`sku` 與 `quantity` 不再
  出現在訂單頂層，貨主名稱也不在其中——呼叫端用它為下單表單載入的主檔自行解析。
- 同一 `(ownerId, externalOrderNo)` 送出兩次，第二次失敗且**不建立第二筆訂單**。回傳
  的是錯誤而非既有訂單——冪等行為屬 R5。
- 配貨、缺貨、取消的既有流程行為完全不變，包含超賣防線與 FIFO 佇列順序。
- 補貨要指定貨主。喚醒 backorder 佇列時只喚醒該貨主的訂單，另一個貨主的同碼 SKU 訂單
  不受影響——但補進去的庫存兩個貨主都吃得到（已知中間狀態）。
- 前端下單表單提供貨主下拉、款與規格兩段選擇、收件分區、地址、承諾到貨日；訂單列表
  顯示貨主欄，SKU 欄呈現為「品名 · 規格」，一列仍對應一筆訂單行。

**資料形狀**

- `owners(id, code, name, status, allow_split_shipment)`，`code` unique。
- `products(owner_id, product_code, name, temperature_zone)`，PK 為兩欄複合。
- `skus(owner_id, sku_code, product_code, spec_name, weight_gram)`，PK 為
  `(owner_id, sku_code)`，FK `(owner_id, product_code)` → `products`，
  `weight_gram > 0`。
- `order_lines(id, order_id, line_no, owner_id, sku_code, quantity, assigned_node_id,
  status, backordered_since)`，`UNIQUE (order_id, line_no)`，FK `(owner_id, sku_code)`
  → `skus`，`quantity > 0`。`assigned_node_id` **無 FK**且恆為空。
- `orders` 新增 `owner_id`（FK → `owners`）、`external_order_no`、`ship_to_zone`、
  `ship_to_address`、`promised_delivery_date`、`requested_node_id`（無 FK）；移除
  `sku`、`quantity`；`UNIQUE (owner_id, external_order_no)`。**不含 `fulfilled_at`。**
- `PlaceOrderCommand` 承載 header 欄位與 line 清單；`placeOrder` 回傳 `Order`
  （沿用 `add-demo-console-api` 建立的「POST 與 GET 共用回應型別」慣例）。
- `Order.getDemand()` 回傳 SKU 對數量的映射，是 allocation 讀取需求的唯一入口。
  `Order.requireSingleLine()` 是唯一允許以位置取 line 的地方，語意為「此處踩在單行假設
  上」，供 partition key 與重試 context 標籤使用。
- `OrderRepository.findBackordersBySkuInFifoOrder(UUID ownerId, String skuCode)`。
- `OrderPlacedIntegrationEvent` 帶 `ownerId`、`shipToZone`、`promisedDeliveryDate`
  與 line 清單；`OrderCancelledIntegrationEvent` 帶 `ownerId`；
  `StockReplenishedIntegrationEvent` 與 `ReplenishStockCommand` 帶 `ownerId`。

**失敗模式**

- line 數量不為一：`place()` 拒絕，屬收單政策，訊息要與 domain invariant 區分。
- `(ownerId, skuCode)` 不存在：資料庫 FK 違反。**不在應用層預先檢查**（見 Non-Goals）。
- 重複的 `(ownerId, externalOrderNo)`：unique constraint 違反，明確失敗而非靜默建立。
- 跨貨主配貨：**本 change 不擋**，會成功。這是已知且刻意的中間狀態，R3 的
  `requireMatchingOwner()` 才生效。

**驗收**

- 下單 → 查詢 → 取消的完整流程在含貨主與 line 的模型下通過。
- 以 `Order.rehydrate()` 造兩行訂單，`OrderMapper` 往返與訂單回應序列化皆正確。
- **同一個 SKU 兩行**、ATP 不足以涵蓋加總時，**兩行都不得預留**，整單進 `BACKORDERED`，
  `stock_reservations` 對該訂單零筆。
- 架構測試通過：除 `Order.requireSingleLine()` 外，production code 不以位置存取 line。
- 對某貨主補貨後，只有該貨主的 backorder 佇列被喚醒。
- `OrderPersistenceIntegrationTest` 斷言 `order_lines` 上的 FIFO index 存在，欄位順序
  與方向都驗（沿用既有 `createsRecentOrdersIndex` 的手法）。
- `./e2e/perf/run.sh down && up` 後 k6 既有 thresholds 全數通過，`e2e/perf/README.md`
  的 baseline 數字以本次結果更新。

**範圍邊界**

- 在範圍內：ordering 模組的完整改造、allocation 側因 `getSku()` 消失而必須的最小調整、
  三支 integration event 的契約（下單、取消、補貨）、補貨探針、前端下單／列表／庫存頁的
  貨主、seed。
- 不在範圍內：allocation 的決策邏輯、跨 SKU 的整籃判斷、`stock_pools` 與
  `stock_reservations` 的任何欄位、partition key 策略、`demand_lines` view、節點主檔、
  任何寫入型主檔 usecase。

## Risks / Trade-offs

- **`order.getSku()` 的呼叫點漏改** → 這是本 change 最容易漏的一項。`sku` 從 `orders`
  搬走後所有呼叫點都編譯失敗，包含 `AllocationService.requireMatchingSku()` 與兩個
  `AllocationPolicy` 實作。動手前先跑一次全域搜尋確認範圍，不要邊改邊找。
- **ship-complete 的整籃原子性被寫成逐行獨立** → 本 change 只有一筆 line，所以「配得到
  就預留」在單行下完全正確，會在 R8 放寬時才爆，而爆的形式是「為出不去的單鎖住庫存」
  ——不會讓任何測試失敗，只會讓庫存莫名被占住。主要緩解是 `getDemand()`（讓這種寫法
  打不出來），次要緩解是同 SKU 兩行的整籃原子性測試。
- **seed 的庫存池 SKU 與新主檔對不上** → `stock_pools` 沒有指向主檔的外鍵，兩邊不一致
  時不會報錯，只會讓 seed 的訂單配不到貨，而前端庫存頁與既有 seed 測試的預期值也一併
  錯位。緩解是把既有的三個庫存池與那張已預留的 seed 訂單一併改用主檔的 `sku_code`，
  並在驗收加上「seed 的訂單能走完配貨」。
- **`stock_reservations` 有 `UNIQUE (order_id)`** → 一張單只寫得進一筆預留。本 change
  不動這張表（R3 才改為指向 `order_line_id`），所以即使日後配貨改成多 SKU，也要先解掉
  這個約束。它不影響本 change 的「零筆預留」斷言，但實作時會撞上。
- **跨貨主配貨在本 change 是通的** → 已知且刻意。緩解是在驗收與 seed 註解裡明確標註，
  而不是留給下一個人自己發現。R3 的 `requireMatchingOwner()` 收尾。
- **partition key 與 `StockPool` 的識別必須同步演進** → 兩者脫節時 single-writer 就失效。
  現階段兩者一致（都是裸 `sku`），沒有問題；風險在 R3——`stock_pools` 加上 `owner_id`
  的那一刻，partition key 必須在**同一個 change** 內跟著改成 `ownerId:skuCode`，否則
  不同貨主的事件會擠進同一個 partition 排隊等一個它們其實不共用的鎖。這條要寫進 R3 的
  任務，不能留給之後補。
- **改寫 migration 需要重建 volume** → 忘記 `down` 會得到 Flyway checksum 錯誤。這是
  明確的失敗而非靜默錯誤，且前兩個 change 已經走過同一條路。

## Migration Plan

1. `./e2e/perf/run.sh down`，移除既有 Postgres volume。改寫既有 migration 必然造成
   checksum 不符，**不得以 `flyway repair` 略過**。
2. 套用改寫後的 `V3__create_ordering_tables.sql` 與新的 seed。
3. `./e2e/perf/run.sh up`，確認 k6 既有 thresholds 全數通過，並以本次結果更新
   `e2e/perf/README.md` 的 baseline 數字，避免文件數字與腳本版本脫節。

Rollback 就是 git revert 加一次 `down`／`up`——沒有需要保留的資料，也沒有任何環境
持有舊 schema。

## Open Questions

- `orders.requested_node_id`（貨主指定出貨倉）在本 change 只收下不使用，R6 才讀。它與
  `assigned_node_id` 一樣缺 FK，且 R2 之後兩者是否要一起補 FK，留給 R2 決定。

- **主檔屬性對訂單行是 join 還是快照？** R6 的選點要讀溫層（硬約束）與重量（成本函數），
  兩者都不在 `order_lines` 上，而是經 `sku_code` 從 `skus`／`products` 衍生。本 change
  的模型因此隱含了「不快照、要用就 join」——但那是**欄位設計的副產物，不是被決定過的事**。

  分歧只在一種情況出現：訂單缺貨排隊數日，期間主檔的溫層或重量被改了。R6 重新選點時該用
  下單當時的值，還是當下的值？前者要在收單時把屬性快照到行上，後者維持現況即可。

  兩邊都有道理——快照讓決策可重現、可稽核（「當初為什麼選了那個節點」）；join 讓主檔更正
  能立刻反映到還沒出貨的訂單上（溫層填錯了，改主檔就好，不必回頭修訂單）。**這是 R6 的
  決策模型要回答的問題，本 change 不預先選邊**，只確保下一個人知道現況是隱含而非既定。

  留意這與 `owner_id`／`backordered_since` 的反正規化判準不同：那兩個的值不可變，因此沒有
  同步成本；主檔屬性會變，快照與否是真的取捨。
