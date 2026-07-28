## 1. Schema

- [ ] 1.1 依「改寫 `V3` 而非新增 migration」，在 `V3__create_ordering_tables.sql` 建 `fulfillment_nodes`（`id`、`code` 唯一、`name`）與 `owner_nodes`（PK `(owner_id, node_id)`，雙 FK）。建表順序放在 `owners` 之後、`orders` 之前，FK 的被指向方一律在前。依決策「**`owner_nodes` 只建配對，不建設定**」，配對表**零設定欄位**——`allow_mixed_batch` 屬 R3。行為上：空資料庫一次套用即得最終 schema，migration 歷史不含任何「建了又砍」的中間形狀。以 `./e2e/perf/run.sh down` 後重新啟動、Flyway 套用成功驗證。
- [ ] 1.2 依 **A warehouse is a place goods ship from, not a place the system chooses** 與決策「**`fulfillment_nodes` 不建 `status`**」，確認 `fulfillment_nodes` 只有 `id`／`code`／`name` 三欄，**不建** `status`、`type`／`role`、`zone`、`capabilities`、`daily_capacity`、`cutoff_time`。行為上：讀取倉庫的任何路徑都拿不到這些屬性，因此沒有人會誤以為它們參與決策。以 schema 審閱與 `warehouse-catalog` 的「只有身分」情境驗證。
- [ ] 1.3 依決策「**欄位更名為 `fulfillment_node_id` 並改為 `NOT NULL`**」與 **An order carries an owner, an upstream reference, and a delivery commitment**，把 `orders.requested_node_id` 更名為 `fulfillment_node_id` 並改 `NOT NULL`。行為上：沒有倉別的訂單在資料庫層即無法寫入。以 SIT 斷言插入無倉別的訂單失敗驗證。
- [ ] 1.4 依決策「**`fulfillment_node_id` 補外鍵——刻意偏離「外部來的不補」**」與 **An order line references an existing catalog entry**，為 `orders` 建外鍵。**先嘗試複合外鍵 `(owner_id, fulfillment_node_id)` → `owner_nodes(owner_id, node_id)`**——若可行，「貨主沒掛這個倉」這條規則就由資料庫保證，與 R1 讓 `(owner_id, sku_code)` 走自然鍵外鍵是同一個手法；不可行才退回單欄 FK 指向 `fulfillment_nodes` 並在應用層補檢查。行為上：指向不存在的倉、或指向該貨主沒掛的倉，兩者都被拒絕且不留下任何列。以 SIT 分別插入兩種違規並斷言失敗驗證，且**必須記錄實際採用的是哪一種**（design 的 Open Question 要據此收斂）。
- [ ] 1.5 依決策「**明細不再持有出貨倉**」與 **A line inherits its order's warehouse rather than carrying its own**，從 `order_lines` 移除 `assigned_node_id`。行為上：行不再有倉別欄位，任何想讀它的程式碼編譯失敗。以編譯與 schema 審閱驗證。
- [ ] 1.6 依 `product-catalog` 的 **An owner is the party whose goods the warehouse holds**（已移除拆單許可），從 `owners` 移除 `allow_split_shipment`。行為上：貨主不再帶拆單許可。以編譯與 schema 審閱驗證。

## 2. 倉庫主檔的 domain 與持久化

- [ ] 2.1 依 **A warehouse is a place goods ship from**，新增 `FulfillmentNode` 領域模型與 `FulfillmentNodeRepository` 介面，置於 `catalog` context（與 `Owner`／`Product`／`Sku` 同處——倉庫是主檔，讀者與變更節奏都與商品主檔相近）。行為上：倉庫可依 id 與 code 取得。以 repository 的 SIT 驗證。
- [ ] 2.2 依 **An owner ships from an explicitly assigned set of warehouses**，新增依貨主查詢已指派倉庫的 repository 方法，回傳順序穩定。行為上：查甲貨主得到它掛的倉，且不含它沒掛的倉；重複查詢順序一致。以 SIT 斷言集合內容與順序穩定性驗證。
- [ ] 2.3 依 **The warehouse catalog exposes no write interface**，確認倉庫與配對關係沒有任何建立／修改／刪除的 repository 方法或端點。行為上：這兩者只能由 migration 與 seed 產生。以程式碼審閱驗證。

## 3. 倉庫查詢的 HTTP 表面

- [ ] 3.1 實作 **An owner's warehouses are queryable over HTTP**：新增巢狀在貨主之下的唯讀端點，回傳該貨主已指派倉庫的 `id`／`code`／`name`。路徑巢狀而非以貨主當可省略的篩選條件——扁平的倉庫清單會誘使呼叫端提供該貨主出不了貨的倉。未知貨主回空陣列而非錯誤，與既有主檔查詢一致。行為上：查甲貨主只得到它掛的倉；查一個不存在的貨主得到空陣列而非 404。以 web 層測試涵蓋兩種情形驗證。

## 4. 訂單的 domain 與收單

- [ ] 4.1 依 **An order carries an owner, an upstream reference, and a delivery commitment**，把 `Order` 的 `requestedNodeId` 更名為 `fulfillmentNodeId` 並改為必填——`place()` 與 `rehydrate()` 都要求它，缺少時拋錯而非填預設值。行為上：建立訂單時不給倉別會在領域層就失敗，不會拖到資料庫。以領域測試斷言缺倉別時拋出驗證。
- [ ] 4.2 依 **A line inherits its order's warehouse rather than carrying its own**，從 `OrderLine` 移除 `assignedNodeId`，並確認沒有任何地方以「行的倉別」為前提。行為上：行只有 `lineNo`／`ownerId`／`skuCode`／`quantity`／`status`／`backorderedSince`。以編譯與領域測試驗證。
- [ ] 4.3 依 **A line inherits its order's warehouse rather than carrying its own** 的「放寬多行後仍然如此」，在 `OrderingArchitectureTest` 加一條規則，禁止 `OrderLine` 出現任何名稱含 `node` 的欄位或方法。行為上：有人在多行情境下手滑把倉別加回行上時，建置失敗而不是安靜通過。以植入違規確認該測試會失敗驗證。
- [ ] 4.4 依決策「**`OrderPlaced` 加 `fulfillmentNodeId`**」，為該領域事件加上該欄位。行為上：R3 要組 `ownerId/nodeId/skuCode` 的 partition key 時，節點維度在事件裡拿得到，不需回頭改事件契約。以事件的建構測試驗證欄位存在且等於訂單的倉別。
- [ ] 4.5 依 `product-catalog` 的貨主定義，從 `Owner` 移除 `allowSplitShipment`，並清掉所有讀取端。行為上：貨主只有 `code`／`name`／`status`。以編譯驗證。

## 5. 下單的 HTTP 契約

- [ ] 5.1 實作 **Placing an order accepts a JSON command and returns the created order** 的變更：request body 加必填倉別；回應的訂單表示帶倉別識別碼。狀態碼維持 `200`、既有的「無行／多行／未知 SKU／單號重複」拒絕條件不變。行為上：不帶倉別、或帶了該貨主沒掛的倉，請求被拒且不建立訂單。以 web 層測試涵蓋範例表的六種情形驗證。

## 6. Seed 資料

- [ ] 6.1 依 **Seed data makes both warehouse relationships visible**，種三個倉庫，兩個貨主**各掛兩個、共用其中一個**。三件事要同時成立：同一貨主多倉、不同貨主的倉不同、**一個倉服務多個貨主**。第三件是 3PL 的定義性特徵——少了它，一個「以倉庫而非配對關係做過濾」的錯誤實作會安靜通過。以 SIT 斷言三者皆成立驗證。
- [ ] 6.2 依 1.3 的倉別必填，更新種子訂單使其帶倉別（甲貨主的已配貨單與乙貨主的缺貨單各指定一個該貨主掛的倉）。行為上：種子在倉別必填之後仍可載入，且兩張單的倉別不同以便 R3 的分倉庫存有資料可分。以既有的 seed SIT 通過驗證。
- [ ] 6.3 依 `product-catalog` 的 **Seed data reproduces the collisions and contrasts later work depends on**（已移除拆單許可對比），更新 seed 測試中關於兩貨主差異的斷言，改為只斷言 SKU 代碼撞號；溫層與雙規格的種子維持不變，它們的價值不依賴讀者。行為上：測試不再引用已不存在的欄位。以 seed SIT 通過驗證。

## 7. 前端

- [ ] 7.1 實作 **Placing an order shows the result in the list on the same page** 的變更：下單表單加倉庫下拉，選項為所選貨主已指派的倉；換貨主時**一併清空**倉庫、款、規格三者。倉庫用選的不用打——打字可以打出該貨主沒掛的倉，那會換來一次沒有必要的往返。行為上：兩個貨主的倉庫選項不同；未選倉庫時送出被表單擋下、不發請求。以前端測試涵蓋「選項依貨主過濾」「換貨主清空三者」「未選倉庫被擋」三種情形驗證。
- [ ] 7.2 更新前端的型別與主檔載入：`PlaceOrderCommand` 與 `OrderView` 加倉別，`useCatalog` 一併載入各貨主的倉庫。行為上：倉庫清單與款／規格走同一次進場載入，選擇時零請求——與現有的「靜置不發請求」保證一致。以型別檢查與既有的 `OrdersPage` 靜置測試通過驗證。

## 8. 端到端驗收與文件

- [ ] 8.1 更新 `e2e/perf/run.sh` 的 `seed` 子命令：除了現有的主檔三層與庫存池，一併種壓測用的倉庫與貨主倉庫配對。行為上：壓測訂單帶得出合法倉別，不會撞外鍵。以壓測執行時 `checks_total` 全過驗證。
- [ ] 8.2 更新 `e2e/perf/k6/hot-sku-burst.js` 的下單 payload 加倉別，倉庫識別碼由 `setup` 以代碼反查（與現行貨主的作法一致，UUID 只寫在 `run.sh` 一處）。行為上：壓測腳本能建立訂單並完成後續輪詢。以 thresholds 通過驗證。
- [ ] 8.3 依 design 的 Migration Plan 重建並重跑壓測：先 `./e2e/perf/run.sh down` 移除既有 Postgres volume（改寫既有 migration 必然造成 Flyway checksum 不符，**不得以 `flyway repair` 略過**），再 `./e2e/perf/run.sh perf`。行為上：既有 k6 thresholds 全數通過，代表倉別必填未使壓測退化。以本次結果更新 `e2e/perf/README.md` 的 baseline 數字。
- [ ] 8.4 核對 `docs/` 的敘述與實作一致——這些文件已於 2026-07-29 先行更新，本項只是驗證而非再寫一輪。特別確認 `docs/stock-reservation-design.md` 的 `orders` 欄位表、`docs/execution-roadmap.md` 的 R2 任務清單、以及 1.4 實際採用的外鍵形式三者相符。以文件審閱驗證。
