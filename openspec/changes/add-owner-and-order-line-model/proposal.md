## Why

現況 `orders` 直接持有 `sku` 與 `quantity` 兩個裸欄位，一張單就是「一個 SKU 的一個
數量」。沒有貨主、沒有商品主檔、沒有收件地與承諾到貨日。這個形狀擋住
[execution-roadmap.md](../../../docs/execution-roadmap.md) 上除 R2 之外的每一個後續
change：R3 的跨貨主隔離要 `owner_id`、R6 的選點要溫層與重量、R7 的揀貨要 line 粒度。

拖延的成本不是線性的。每晚一個 change，就多一批在「單貨主、單行、SKU 是裸字串」的
環境下寫成、且**在該環境下完全正確**的程式碼——`order.getSku()`、逐行獨立判斷可滿足
性、一次配貨只碰一個 `StockPool`。它們沒有任何測試會失敗，要等到多行放寬時才以「為
出不去的單鎖住庫存」的形式浮現。因此本 change 是 roadmap 的 R1，排在最前面且無依賴。

## What Changes

- 新增 `owners`、`products`、`skus` 三層主檔。商品主檔刻意分**款**與**規格**兩層：
  `temperature_zone` 在款層級、`weight_gram` 在規格層級，讓「同款兩種溫層」這類髒資料
  在結構上無法產生。
- 新增 `order_lines`，持有 `sku_code`、`quantity`、line 層級的 `status` 與
  `assigned_node_id`，以及反正規化的 `owner_id`。
- **BREAKING**：`orders` 移除 `sku` 與 `quantity`，新增 `owner_id`、
  `external_order_no`、`ship_to_zone`、`ship_to_address`、`promised_delivery_date`、
  `requested_node_id`、`fulfilled_at`。所有 `order.getSku()` 的呼叫點都會編譯失敗，
  包含 allocation 側的 `AllocationService.requireMatchingSku()` 與兩個
  `AllocationPolicy` 實作。
- **BREAKING**：`V3__create_orders.sql` 改寫為最終形狀並更名為
  `V3__create_ordering_tables.sql`——四張表依 FK 依賴順序建立，`orders` 直接帶齊分流
  欄位。此 schema 尚未部署至任何環境，理由與 `add-demo-console-api`、
  `fix-outbox-partition-key-semantics` 一致；代價是必須 `./e2e/perf/run.sh down` 移除
  既有 Postgres volume 後重建。
- **BREAKING**：`Order.place()` 改為接受 command 物件與 line 清單，**並限定恰好一筆
  line**。`Order.rehydrate()` **不施加此限制**——它的職責是還原資料庫裡的東西，而
  schema 從本 change 起就允許 N 筆。這個不對稱是刻意的，讓測試能造出 N=2 的 `Order`。
- **BREAKING**：`POST /orders` 的 request body 改為帶貨主、上游單號、收件資訊、承諾
  到貨日與 lines；`GET /orders` 與 `GET /orders/{orderId}` 的回應把 `sku`／`quantity`
  移入 lines，並加 `ownerId` 與 `ownerName`。回應帶 `ownerName` 是刻意的反正規化——
  否則列表要為每一列再打一次貨主查詢。
- **BREAKING**：`OrderPlacedIntegrationEvent` 加 `ownerId`、`shipToZone`、
  `promisedDeliveryDate` 並把 `sku`／`quantity` 改為 line 清單；
  `OrderCancelledIntegrationEvent` 加 `ownerId`。allocation 側兩支 handler 連帶調整。
- 新增 `ListOwnersUsecase`、`ListProductsUsecase`、`ListSkusUsecase` 三支純查詢。
  **不新增任何主檔寫入介面**——`Owner`／`Product`／`Sku` 由 seed 建立。
- **四項從後續 change 提前**，理由是它們動的都是同一組表，分次做等於重複 ALTER：
  `order_lines.backordered_since` 與 `idx_order_lines_backorder_fifo`（R8）、
  `UNIQUE (owner_id, external_order_no)`（R5）、`orders.fulfilled_at`（R7）。前兩項
  不提前就沒有 FIFO index 可用，壓測基準會斷掉且無法歸因。
- **三項 line 數量無關性的防護**：以 `rehydrate()` 造 N=2 fixture 驗讀取與序列化路徑、
  整籃原子性的 N=2 測試、架構測試禁止 production code 出現 `getLines().get(` 與
  `.getFirst()`。少了它們，R3～R6 會在單行環境下累積一批沒有任何訊號的單行假設。
- 前端下單表單加貨主、上游單號、收件分區、地址、承諾到貨日，商品改為「款 → 規格」
  兩段選擇；訂單列表加貨主欄、SKU 顯示為「品名 · 規格」。**一列仍是一筆 line**。

## Capabilities

### New Capabilities

- `order-intake`: 收單的領域模型與行為——一張訂單由貨主、上游單號、收件資訊、承諾
  到貨日與訂單行組成；「每張單恰好一筆 line」是收單政策而非結構限制；ship-complete
  下 header 與 line 的時間戳恆等。
- `product-catalog`: 貨主、商品款、規格三層主檔——貨主自訂編碼在 3PL 下會撞號故以
  `(owner_id, code)` 為鍵；溫層在款層級、重量在規格層級；本 change 只提供查詢。

### Modified Capabilities

- `order-promising-http-api`: 下單命令與訂單查詢的 payload 形狀改變——命令帶貨主與
  收件資訊、訂單表示型別帶 lines 與貨主名稱。`GET /stock-pool/{sku}` 不變。
- `demo-console-frontend`: 下單表單與訂單列表的欄位改變；商品選擇從單一 SKU 輸入改為
  款與規格兩段選擇。

## Impact

- Schema：`V3__create_orders.sql` 改寫並更名為 `V3__create_ordering_tables.sql`。
  `idx_orders_backorder_fifo` 消失（其篩選鍵 `sku` 已不在 `orders`），改由
  `order_lines` 上的 `idx_order_lines_backorder_fifo (owner_id, sku_code,
  backordered_since, id)` 取代——**刻意不含 `status`**，理由見 design.md。
  `idx_orders_recent` 不受影響。
- Ordering domain：新增 `Owner`、`Product`、`Sku` 三個 aggregate root 與 `OrderLine`
  entity（**不是 aggregate root**）；`Order` 改為持有 line 集合，移除 `getSku()`／
  `getQuantity()`；四支 domain event 加貨主資訊。`OrderStatus` **不加**
  `PARTIALLY_ALLOCATED`——採 ship-complete，該狀態不存在而非留到 R8。
- Ordering infrastructure／application／entrypoint：四組 entity／mapper／repository；
  `PlaceOrderUsecase` 改收 command；`OrderRepository.findBackordersBySkuInFifoOrder`
  加 `ownerId` 參數（不同貨主的 backorder 隊列必須分開）。此方法屬 allocation 卻長在
  ordering 的 repository 上，那是 R4 要處理的耦合，本 change 只加參數、不搬家。
- Allocation：`AllocateOrderUsecase`、`AllocationService.requireMatchingSku()` 與兩個
  `AllocationPolicy` 實作改讀 line 的 `sku_code`；兩支 integration event handler 連帶
  調整。**allocation 的決策邏輯與超賣防線不變。**
- 測試：ordering 既有七支測試的簽章調整；`OrderPersistenceIntegrationTest` 的 FIFO
  index 斷言改為斷言 `order_lines` 上的新 index；`e2e/perf/k6/hot-sku-burst.js` 的
  request body；`AllocationFifoReplenishmentBatchIntegrationTest` 等 SIT 的 fixture
  建構方式（requirement 本身不變——同一貨主下 FIFO 語意成立）。
- Seed：兩個貨主（`allow_split_shipment` 各為 `true`／`false`）**且使用相同的
  `sku_code`**，這是 3PL 撞號情境的最小再現，R3 的 `requireMatchingOwner()` 要靠它
  驗證；常溫與冷凍各一款，其中一款帶兩個規格。
- **不影響**：`stock_pools`、`stock_reservations` 的任何欄位；Kafka topic 名稱；
  outbox／inbox 冪等機制；partition key 策略（仍為裸 `sku`，多貨主下會製造假競爭，
  修正要等 R3——那時 `stock_pools` 才有 `owner_id`，`ownerId:skuCode` 才有對應實體）。
- **已知且刻意的中間狀態**：`stock_pools` 尚無 `owner_id`，**跨貨主隔離在 R3 才生效**。
  seed 有兩個貨主，配貨仍可能跨貨主取用。同樣地，`UNIQUE (owner_id,
  external_order_no)` 提前並不等於冪等完成——本 change 到 R5 之間，重送同一張單會得到
  資料庫錯誤而非既有訂單，那仍不是正確行為，只是安全的錯誤行為。
