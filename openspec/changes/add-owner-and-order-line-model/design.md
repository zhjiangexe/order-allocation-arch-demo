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
- **不加 `PARTIALLY_ALLOCATED`**。採 ship-complete（見
  [dom-promising-scope.md](../../../docs/dom-promising-scope.md)），該狀態不存在，不是
  「留到 R8」。
- **不動 `stock_pools`／`stock_reservations`**，因此跨貨主隔離不生效。屬 R3。
- **不改 partition key**。`sku` 策略在多貨主下會讓不同貨主的同名 SKU 收斂到同一
  partition，製造假競爭。修正要等 R3——那時 `stock_pools` 才有 `owner_id`，
  `ownerId:skuCode` 這個 key 才有對應的實體。本 change 留著這個已知缺陷。
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

### 主檔以 `(owner_id, code)` 複合鍵，不用代理鍵

`products` 的 PK 是 `(owner_id, product_code)`，`skus` 的是 `(owner_id, sku_code)`。
另一個選項是給每張表一個 UUID 代理鍵，把 `(owner_id, code)` 降為 unique constraint。

選複合鍵，因為在 3PL 裡 `sku_code` 由貨主自訂，**不同貨主必然會撞號**，「SKU-A」單獨
存在時不指向任何東西。複合鍵讓這個事實出現在型別上——所有要指向 SKU 的地方都被迫同時
帶上 `owner_id`，包括 `order_lines` 的 FK。代理鍵做得到同樣的完整性，但允許程式碼只
帶 `sku_code` 到處跑，而那正是現況的問題。

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

### `PlaceOrderUsecase` 改收 command 物件

header 欄位加到六個之後位置參數的呼叫端可讀性崩潰，而且 line 清單無法用位置參數自然
表達。`placeOrder(PlaceOrderCommand)` 取代 `placeOrder(String sku, Integer quantity)`。

`GetOrderUsecase`、`ListRecentOrdersUsecase`、`CancelOrderUsecase` 的簽章不變，只是
回傳的 `Order` 帶 lines。

### 訂單回應帶 `ownerName`，是刻意的反正規化

`GET /orders` 的每一筆同時回 `ownerId` 與 `ownerName`。只回 `ownerId` 更「純」，但列表
要顯示貨主名稱，前端就得為每一列再打一次 `/owners/{id}`——那是 N+1。這與
`add-demo-console-api` 對 `limit` 的態度一致：讓呼叫端拿到它實際需要的東西，而不是逼
它自己拼。

### 三項 line 數量無關性的防護

「schema 是 line 形狀」不保證「邏輯與 line 數量無關」。下列寫法在單行下**是正確的、
不是 bug**，因此不會有任何測試失敗來提醒你：

| 寫法 | 單行下 | 多行下 |
| --- | --- | --- |
| `order.getLines().get(0)` | 正確 | 只處理第一行 |
| 逐行獨立判斷可滿足性、配得到就預留 | 正確 | **違反 ship-complete**，為出不去的單鎖住庫存 |
| 一次配貨只碰一個 `StockPool` | 正確 | 會碰多個，排序鍵不含 `sku_code` 就有死鎖風險 |

因此本 change 付出三項防護：以 `rehydrate()` 造 N=2 fixture 驗讀取／映射／序列化；
整籃原子性的 N=2 測試（兩行中一行可滿足時**兩行都不得預留**）；架構測試斷言 production
code 不出現 `getLines().get(` 與 `.getFirst()`。

**第二項最容易跳過也最危險**——單行下它退化成「配不到就缺貨」，所以「逐行獨立配貨」的
錯誤實作會通過其他所有測試。

### 地址內嵌 `orders`，不另開 `addresses` 表

`ship_to_zone` 與 `ship_to_address` 直接放 `orders`。沒有地址簿需求，地址逐單指定、
不可重用，獨立表只會憑空多一層 join。`ship_to_zone` 是 R6 的決策輸入（配送分區），
`ship_to_address` 是履約與面單用，sourcing 不看——兩者用途不同因此都存。

### 四項從後續 change 提前

| 提前的項目 | 原本在 | 理由 |
| --- | --- | --- |
| `order_lines.backordered_since` | R8 | 不提前就沒有 FIFO index 可用（見上） |
| `idx_order_lines_backorder_fifo` | R8 | 同上，壓測基準會斷掉 |
| `UNIQUE (owner_id, external_order_no)` | R5 | 一行 constraint，把「靜默建立重複訂單」變成「明確報錯」。倉儲場景下前者是資料事故 |
| `orders.fulfilled_at` | R7 | 一個 nullable 欄位，讓 `orders` 只被建立一次。**這項最弱**，純粹省一次遷移 |

共同判準是「動的是同一組表，分次做等於重複遷移」。**不是所有後續欄位都提前**——
`assigned_node_id` 的 FK 就沒有，因為它需要 R2 的 `fulfillment_nodes` 先存在，提前的
不是遷移成本而是相依性。

## Implementation Contract

**行為**

- 下單時呼叫方提供貨主、上游單號、收件分區與地址、承諾到貨日，以及**恰好一筆**訂單行
  （SKU 與數量）。送出零筆或兩筆以上會被拒絕，錯誤訊息指明是收單政策的限制。
- 送出不存在於主檔的 `(ownerId, skuCode)` 組合會失敗，不會建立訂單。
- 查詢訂單（單筆或列表）回傳的訂單帶貨主識別碼、貨主名稱與訂單行陣列；`sku` 與
  `quantity` 不再出現在訂單頂層。
- 同一 `(ownerId, externalOrderNo)` 送出兩次，第二次失敗且**不建立第二筆訂單**。回傳
  的是錯誤而非既有訂單——冪等行為屬 R5。
- 配貨、缺貨、取消的既有流程行為完全不變，包含超賣防線與 FIFO 佇列順序。
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
  `ship_to_address`、`promised_delivery_date`、`requested_node_id`（無 FK）、
  `fulfilled_at`（恆為空）；移除 `sku`、`quantity`；
  `UNIQUE (owner_id, external_order_no)`。
- `PlaceOrderCommand` 承載 header 欄位與 line 清單；`placeOrder` 回傳 `Order`
  （沿用 `add-demo-console-api` 建立的「POST 與 GET 共用回應型別」慣例）。
- `OrderRepository.findBackordersBySkuInFifoOrder(UUID ownerId, String skuCode)`。
- `OrderPlacedIntegrationEvent` 帶 `ownerId`、`shipToZone`、`promisedDeliveryDate`
  與 line 清單；`OrderCancelledIntegrationEvent` 帶 `ownerId`。

**失敗模式**

- line 數量不為一：`place()` 拒絕，屬收單政策，訊息要與 domain invariant 區分。
- `(ownerId, skuCode)` 不存在：資料庫 FK 違反。**不在應用層預先檢查**（見 Non-Goals）。
- 重複的 `(ownerId, externalOrderNo)`：unique constraint 違反，明確失敗而非靜默建立。
- 跨貨主配貨：**本 change 不擋**，會成功。這是已知且刻意的中間狀態，R3 的
  `requireMatchingOwner()` 才生效。

**驗收**

- 下單 → 查詢 → 取消的完整流程在含貨主與 line 的模型下通過。
- 以 `Order.rehydrate()` 造兩行訂單，`OrderMapper` 往返與訂單回應序列化皆正確。
- 兩行中一行可滿足、一行不可滿足時，**兩行都不得預留**，整單進 `BACKORDERED`。
- 架構測試通過：production code 不出現 `getLines().get(` 與 `.getFirst()`。
- `OrderPersistenceIntegrationTest` 斷言 `order_lines` 上的 FIFO index 存在，欄位順序
  與方向都驗（沿用既有 `createsRecentOrdersIndex` 的手法）。
- `./e2e/perf/run.sh down && up` 後 k6 既有 thresholds 全數通過，`e2e/perf/README.md`
  的 baseline 數字以本次結果更新。

**範圍邊界**

- 在範圍內：ordering 模組的完整改造、allocation 側因 `getSku()` 消失而必須的最小調整、
  兩支 integration event 的契約、前端下單與列表、seed。
- 不在範圍內：allocation 的決策邏輯、`stock_pools` 的任何欄位、partition key 策略、
  `demand_lines` view、節點主檔、任何寫入型主檔 usecase。

## Risks / Trade-offs

- **`order.getSku()` 的呼叫點漏改** → 這是本 change 最容易漏的一項。`sku` 從 `orders`
  搬走後所有呼叫點都編譯失敗，包含 `AllocationService.requireMatchingSku()` 與兩個
  `AllocationPolicy` 實作。動手前先跑一次全域搜尋確認範圍，不要邊改邊找。
- **ship-complete 的整籃原子性被寫成逐行獨立** → 本 change 只有一筆 line，所以「配得到
  就預留」在單行下完全正確，會在 R8 放寬時才爆，而爆的形式是「為出不去的單鎖住庫存」
  ——不會讓任何測試失敗，只會讓庫存莫名被占住。緩解是三項防護裡的第二項，那是唯一能
  在今天就讓這個錯誤現形的機制。
- **跨貨主配貨在本 change 是通的** → 已知且刻意。緩解是在驗收與 seed 註解裡明確標註，
  而不是留給下一個人自己發現。R3 的 `requireMatchingOwner()` 收尾。
- **partition key 仍為裸 `sku`** → 兩個貨主的同名 SKU 收斂到同一 partition，製造假競爭，
  壓測的併發衝突率會略高於真實情況。緩解是接受並記錄；提前修會做出一個沒有實體對應的
  key（`stock_pools` 還沒有 `owner_id`）。
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
