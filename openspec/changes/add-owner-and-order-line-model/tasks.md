## 1. Schema

- [ ] 1.1 依「`V3` 改寫為最終形狀並更名，不新增 ALTER migration」，把 `V3__create_orders.sql` 改寫為 `V3__create_ordering_tables.sql`，依 `owners` → `products` → `skus` → `orders` → `order_lines` 的順序建表，FK 的被指向方一律在前。`orders` 直接帶齊 `owner_id`、`external_order_no`、`ship_to_zone`、`ship_to_address`、`promised_delivery_date`、`requested_node_id`、`fulfilled_at`，且不出現 `sku`、`quantity`。行為上：在空資料庫上一次套用即得到最終 schema，migration 歷史不含任何「建了又砍」的中間形狀。以 `./e2e/perf/run.sh down` 後重新啟動、Flyway 套用成功並通過 `DatabaseFoundationIntegrationTest` 驗證。
- [ ] 1.2 依「主檔以 `(owner_id, code)` 複合鍵，不用代理鍵」與「地址內嵌 `orders`，不另開 `addresses` 表」，把 `products` 的 PK 定為 `(owner_id, product_code)`、`skus` 的 PK 定為 `(owner_id, sku_code)` 且 FK `(owner_id, product_code)` 指向 `products`，收件分區與地址直接落在 `orders`。行為上：任何指向 SKU 的外鍵都被迫同時帶上 `owner_id`，單獨的 `sku_code` 無法建立參照。以 persistence 測試斷言複合 FK 存在且以單一 `sku_code` 無法插入 `order_lines` 驗證。
- [ ] 1.3 依「`order_lines` 反正規化 `owner_id` 與 `backordered_since`」建立 `order_lines`，含 `line_no`（`UNIQUE (order_id, line_no)`）、`quantity > 0`、line 層級的 `status`、`backordered_since`，以及**無 FK 且恆為空**的 `assigned_node_id`（其 FK 需要 R2 的 `fulfillment_nodes` 先存在）。**不建 line 層級的 `allocated_at`**。行為上：line 可獨立於 header 被篩選與排序，而 `assigned_node_id` 在本 change 全程為空。以 persistence 測試斷言欄位與約束、並斷言 `order_lines` 無 `allocated_at` 欄位驗證。
- [ ] 1.4 依「FIFO index 建在 `order_lines` 且刻意不含 `status`」與「四項從後續 change 提前」，建立 `idx_order_lines_backorder_fifo (owner_id, sku_code, backordered_since, id)` 與 `UNIQUE (owner_id, external_order_no)`，並在 migration 內以註解記錄不含 `status` 的理由（R4 之後待配佇列不依 status 過濾，且 status 夾在中間會讓 index 白建）。舊的 `idx_orders_backorder_fifo` 隨 `sku` 離開 `orders` 而消失。行為上：貨主與 SKU 的 backorder 查詢走 index 取得 FIFO 序，壓測基準不因本 change 斷掉。以 `OrderPersistenceIntegrationTest` 沿用既有 `createsRecentOrdersIndex` 的手法斷言新 index 的欄位順序與方向驗證。

## 2. 主檔的 domain 與持久化

- [ ] 2.1 實作 **An owner is the party whose goods the warehouse holds**：新增 `Owner` aggregate root，持有 `code`、`name`、`status`、`allowSplitShipment`，本 change 無行為方法。行為上：查詢一個貨主可取得代號、名稱、狀態與是否允許拆單；`allowSplitShipment` 目前無任何讀取端（R6 才用）。以 domain 測試與 mapper 往返測試驗證。
- [ ] 2.2 實作 **Catalog identifiers are scoped to their owner**：依「商品主檔拆成款與規格兩層」與「主檔以 `(owner_id, code)` 複合鍵，不用代理鍵」，新增 `Product`（`ownerId`、`productCode`、`name`、`temperatureZone`）與 `Sku`（`ownerId`、`skuCode`、`productCode`、`specName`、`weightGram`）兩個 aggregate root。行為上：兩個貨主各自定義的同一個 `sku_code` 是兩個相異的 SKU，各自帶自己的款、規格名與重量。以「兩貨主同碼」的 persistence 測試斷言互不覆蓋、互不可見驗證。
- [ ] 2.3 實作 **Temperature zone belongs to the product, weight belongs to the SKU**：`temperatureZone` 只存在於 `Product`、`weightGram` 只存在於 `Sku` 且必須為正。行為上：同一款的兩個規格必然回報相同溫層而各自回報自己的重量，「同款兩種溫層」在型別上無法表達。以 domain 測試斷言 `Sku` 無溫層欄位、`weightGram` 非正時建構失敗，並以一款兩規格的 fixture 斷言溫層一致驗證。
- [ ] 2.4 為 `Owner`、`Product`、`Sku` 各新增 entity／mapper／repository 與其 JPA 介面。行為上：三者可寫入與查詢，複合鍵在 mapper 往返後保持完整。以三組 mapper 往返測試與 persistence 測試驗證。

## 3. 主檔查詢

- [ ] 3.1 實作 **The catalog is queryable from owner to product to SKU**：新增 `ListOwnersUsecase`、`ListProductsUsecase`（依 `ownerId`）、`ListSkusUsecase`（依 `ownerId` 與 `productCode`）三支純查詢。行為上：逐層選擇時每一層只回傳上一層選定範圍內的項目。以三支 usecase 測試搭配跨貨主 fixture 斷言不外洩他貨主資料驗證。
- [ ] 3.2 實作 **The catalog exposes no write interface**：確認 `Owner`／`Product`／`Sku` 的 application 表面只有查詢，不新增任何建立、修改、刪除的 usecase 或端點。行為上：主檔只能由 seed 建立，呼叫端無從經由應用層改動主檔。以審閱三支 repository 介面與 controller 表面確認無寫入操作驗證。
- [ ] 3.3 實作 **The catalog is queryable over HTTP**：新增唯讀端點列出貨主、列出某貨主的款、列出某款的規格，款與規格的路徑巢狀於其貨主之下。行為上：以他貨主的 `productCode` 查詢不會回傳任何項目；任何非讀取的 HTTP method 都不會建立或修改主檔。以 web 層測試涵蓋逐層選擇與跨貨主查詢兩種情形驗證。

## 4. 訂單的 domain

- [ ] 4.1 實作 **Order demand is expressed as lines**：依「`OrderLine` 是 entity 而非 aggregate root」新增 `OrderLine`，持有 `lineNo`、`ownerId`、`skuCode`、`quantity`、`status`、`backorderedSince`、`assignedNodeId`；**不提供 `OrderLineRepository`**，line 只能經由 `Order` 存取。`Order` 改為持有 line 集合並移除 `getSku()`／`getQuantity()`。行為上：訂單的表示不再有頂層 SKU 與數量，而 line 無法脫離其訂單被取得或修改。以 domain 測試與「不存在 `OrderLineRepository`」的表面審閱驗證。
- [ ] 4.2 實作 **An order carries an owner, an upstream reference, and a delivery commitment**：`Order` 加 `ownerId`、`externalOrderNo`、`shipToZone`、`shipToAddress`、`promisedDeliveryDate`、`requestedNodeId`、`fulfilledAt`。`requestedNodeId` 與 `fulfilledAt` 只收下不使用，本 change 全程為空。行為上：下單時提供的五項 header 資訊在查詢時原樣取回。以 `OrderTest` 與 `OrderMapperTest` 驗證。
- [ ] 4.3 實作 **Order intake accepts exactly one line per order**：依 design 的「每張單恰好一筆 line」只寫在 `place()` 這項決定，把限制寫進 `Order.place()`，**不寫進 schema、也不寫進 `Order.rehydrate()`**。行為上：以零筆或兩筆 line 下單被拒絕且不留下任何資料，而以兩筆 line 呼叫 `rehydrate()` 成功還原。以 `OrderTest` 涵蓋 0／1／2 筆的 `place()` 與 2 筆的 `rehydrate()` 驗證。
- [ ] 4.4 實作 **A line's status and backordered timestamp mirror its header**：`markAllocated`、`markBackOrdered`、`cancel` 的簽章不變，但內部同時更新 header 與所有 line。行為上：以 `rehydrate()` 造的兩行訂單被標記缺貨後，header 與兩條 line 帶同一個時間戳與同一個狀態。以 N=2 fixture 的 `OrderTest` 驗證。
- [ ] 4.5 更新四支 domain event：`OrderPlaced` 加 `ownerId`、`shipToZone`、`promisedDeliveryDate` 並把 `sku`／`quantity` 改為 line 清單；`OrderBackordered`、`OrderAllocated`、`OrderCancelled` 加 `ownerId`。行為上：事件承載貨主，下游不需回頭查訂單即可知道這批貨屬於誰。以既有 domain event 測試的簽章調整與 `OrderTest` 的事件斷言驗證。

## 5. 訂單的持久化

- [ ] 5.1 新增 `OrderLineEntity` 並擴充 `OrderEntity`、`OrderMapper`、`OrderRepositoryImpl`，使 `Order` 連同其 lines 一併寫入與載入。行為上：一張含 N 筆 line 的訂單經 mapper 往返後 line 的順序、`lineNo` 與各自欄位皆不變。以 `OrderMapperTest` 的 N=1 與 N=2 往返測試驗證。
- [ ] 5.2 實作 **An order line references an existing catalog entry**：line 的 `(owner_id, sku_code)` 由資料庫 FK 擋住，**不在應用層預先檢查**。行為上：以該貨主不存在的 `sku_code` 下單失敗，且訂單與 line 都不留下。以 persistence 測試斷言 FK 違反且交易回滾驗證。
- [ ] 5.3 實作 **An upstream order number is unique within its owner**：`UNIQUE (owner_id, external_order_no)` 生效。行為上：同一貨主的同一上游單號送第二次會明確失敗，而不是靜默建立第二筆訂單；此時回傳的是錯誤而非既有訂單（冪等行為屬 R5）。以 persistence 測試斷言第二次寫入失敗且該對組合只存在一列驗證。
- [ ] 5.4 實作 **Backorder queues are scoped to one owner and one SKU**：`OrderRepository.findBackordersBySkuInFifoOrder` 加上 `ownerId` 參數。此方法屬 allocation 卻長在 ordering 的 repository 上，那是 R4 的耦合，本 change 只加參數、不搬家。行為上：兩個貨主使用同一 `sku_code` 時，讀某貨主的佇列不會回傳另一貨主的訂單，也不受其排序影響。以 `OrderPersistenceIntegrationTest` 的跨貨主 fixture 驗證。

## 6. Application 與 HTTP 表面

- [ ] 6.1 依「`PlaceOrderUsecase` 改收 command 物件」，把 `placeOrder(String, Integer)` 改為 `placeOrder(PlaceOrderCommand)`，command 承載 header 六個欄位與 line 清單，回傳型別維持 `Order`（沿用 `add-demo-console-api` 建立的「POST 與 GET 共用回應型別」慣例）。行為上：呼叫端以具名欄位組出訂單，line 清單不需以位置參數表達。以 `PlaceOrderUsecaseTest` 的簽章調整驗證。
- [ ] 6.2 實作 **Placing an order accepts a JSON command and returns the created order**：`POST /orders` 的 request body 改為帶貨主、上游單號、收件分區與地址、承諾到貨日與 lines，回應維持 `200` 與訂單表示型別。行為上：零筆或兩筆 line、他貨主的 `sku_code`、已用過的上游單號四種輸入各自被拒絕且不留下資料。以 web 層測試涵蓋成功一種與被拒四種驗證。
- [ ] 6.3 實作 **Recent orders are listed in stable descending order**：依「訂單回應帶 `ownerName`，是刻意的反正規化」，`GET /orders` 與 `GET /orders/{orderId}` 的回應把 `sku`／`quantity` 移入 lines，並同時帶 `ownerId` 與 `ownerName`。排序、`limit` 預設 20／上限 100、超界回 `400` 的既有行為不變。行為上：列表顯示貨主名稱不需要為每一列再打一次查詢。以 web 層測試斷言回應含 `ownerName`、且既有的排序穩定性與 `limit` 邊界測試仍通過驗證。

## 7. Allocation 側的連帶調整

- [ ] 7.1 先跑一次全域搜尋列出所有 `order.getSku()` 與 `order.getQuantity()` 的呼叫點（已知含 `AllocateOrderUsecase`、`AllocationService.requireMatchingSku()` 與 `StrictFifoAllocationPolicy`、`MaximizeFulfilledOrdersPolicy`），再逐一改為讀 line 的 `skuCode` 與 `quantity`。行為上：配貨、缺貨、補貨重配的既有流程結果完全不變，包含超賣防線與 FIFO 順序。以既有 allocation 單元測試與 SIT 全數通過驗證。
- [ ] 7.2 更新 `OrderPlacedIntegrationEvent`（加 `ownerId`、`shipToZone`、`promisedDeliveryDate`，`sku`／`quantity` 改為 line 清單）與 `OrderCancelledIntegrationEvent`（加 `ownerId`），並調整 allocation 側兩支 handler。**partition key 策略不動**，仍為裸 `sku`（多貨主下的假競爭要等 R3 才有對應實體可修）。行為上：跨模組事件承載貨主，而 Kafka topic 名稱、outbox／inbox 冪等機制與訊息 key 皆不變。以 handler 測試與 `AllocationWorkflowEndToEndIntegrationTest` 驗證。
- [ ] 7.3 調整 `AllocationFifoReplenishmentBatchIntegrationTest`、`AllocationHotSkuConcurrencyIntegrationTest`、`AllocationConcurrencyEndToEndIntegrationTest` 等 SIT 的 fixture 建構方式，使其在含貨主與 line 的模型下成立。requirement 本身不變——同一貨主下 FIFO 與熱點語意皆成立。行為上：三支 SIT 測到的仍是原本要測的東西，而非因 fixture 改寫而失去前提。以三支 SIT 通過且斷言內容未被削弱驗證。

## 8. line 數量無關性的三項防護

- [ ] 8.1 依「三項 line 數量無關性的防護」第一項，以 `Order.rehydrate()` 建立 N=2 的 fixture，驗讀取路徑、`OrderMapper` 往返與訂單回應序列化。行為上：兩行訂單在讀取與序列化的每一段都完整呈現兩條 line，不會只出現第一條。以 `OrderMapperTest` 與 `OrderControllerTest` 的 N=2 案例驗證。
- [ ] 8.2 實作 **An allocation outcome applies to a whole order, never to part of it**：這是三項防護的第二項，也是最容易跳過的一項——單行下它退化成「配不到就缺貨」，因此「逐行獨立配貨」的錯誤實作會通過其他所有測試。行為上：兩行中一行可滿足、一行不可滿足時，**兩行都不得預留**，整單進 `BACKORDERED`。以 N=2 fixture 直接測 `StrictFifoAllocationPolicy` 與 `AllocationService`，斷言 `stock_reservations` 完全沒有該訂單的列驗證。
- [ ] 8.3 實作 **Order handling does not depend on the number of lines**：新增架構測試，斷言 production code 不出現 `getLines().get(` 與 `.getFirst()`，手法與 roadmap R4 任務 9 相同。行為上：以位置取用第一筆 line 的寫法會讓建置失敗並指出來源。以刻意加入一處位置存取確認測試會失敗、移除後通過驗證。

## 9. Seed 資料

- [ ] 9.1 實作 **Seed data reproduces the collisions and contrasts later work depends on**：seed 兩個貨主（`allow_split_shipment` 分別為 `true` 與 `false`）**且兩者定義相同的 `sku_code`**、常溫與冷凍各一款、其中一款帶兩個重量不同的規格，每個貨主各一張單一筆 line。行為上：跨貨主撞號、兩種溫層、款／規格兩層在 seed 後即可在畫面上看到。以 `DevSeedDataIntegrationTest` 斷言上述四項組合驗證。
- [ ] 9.2 在 seed 的來源處以註解標註**跨貨主隔離在本 change 尚未生效**：`stock_pools` 無 `owner_id`，配貨仍可能跨貨主取用，R3 的 `requireMatchingOwner()` 才收尾。行為上：下一個讀到這段 seed 的人不會把這個中間狀態誤認為 bug 或誤認為已解決。以文件與註解審閱驗證。

## 10. 前端

- [ ] 10.1 實作 **The console presents orders and stock as two navigable pages** 的訂單列表變更：每一列加貨主欄（取自訂單回應而非另外解析），SKU 欄改為「品名 · 規格」，一列仍對應一張單的單一 line。型別模組的 `OrderView` 加 header 欄位與 `lines`，並新增 `OwnerView`、`ProductView`、`SkuView`。行為上：兩個貨主的同碼訂單在列表上可被區分。以前端測試斷言列渲染貨主與「品名 · 規格」驗證。
- [ ] 10.2 實作 **Placing an order shows the result in the list on the same page**：下單表單加貨主下拉、上游單號、收件分區、地址、承諾到貨日，商品改為「款 → 規格」兩段選擇而非輸入 SKU 代碼；切換貨主時清除既有的款與規格選擇。行為上：無法送出不屬於所選貨主的 SKU，非正數量與未完成的選擇在表單層即被擋下、不送出請求。以前端測試涵蓋切換貨主清空選擇與五種表單驗證情形驗證。

## 11. 端到端驗收與文件

- [ ] 11.1 更新 `e2e/perf/k6/hot-sku-burst.js` 的下單 request body（加貨主、上游單號、收件資訊、承諾到貨日，SKU 移入 lines）。行為上：壓測腳本能建立訂單並完成後續輪詢，`checks_total` 不因合約變更而失敗。以壓測執行時 thresholds 通過驗證。
- [ ] 11.2 依 design.md 的 Migration Plan 重建並重跑壓測：先 `./e2e/perf/run.sh down` 移除既有 Postgres volume（改寫既有 migration 必然造成 Flyway checksum 不符，**不得以 `flyway repair` 略過**），再 `./e2e/perf/run.sh up`。行為上：既有 k6 thresholds 全數通過，代表資料模型改造未使壓測退化。以本次結果更新 `e2e/perf/README.md` 的 baseline 數字，使文件數字與腳本版本一致。
- [ ] 11.3 於 `docs/stock-reservation-design.md` 補上資料模型變更後的說明：貨主／款／規格三層主檔、訂單行的粒度，以及**跨貨主隔離尚未生效**與 **partition key 仍為裸 `sku`** 兩項已知中間狀態及其收尾的 change。以文件審閱確認與實作一致驗證。
