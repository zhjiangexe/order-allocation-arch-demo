## 0. 遷移的形式（已確認）

本專案未上線，只有本機開發與測試容器，因此**改寫既有 migration 檔案為最終形狀**，不以
ALTER 疊加——與 `V2` 檔頭記錄的判斷一致。逐檔改動見 design 的「遷移改寫既有檔案，不以
ALTER 疊加」的「逐檔的改動」。

- [x] 0.1 移除既有的 Postgres volume 並確認可從空資料庫重建。**不得以 `flyway repair` 略過 checksum 不符。**

  改寫既有 migration 的代價就是這個。`flyway repair` 會讓資料庫接受一份與它實際結構不符的歷史，而那個不一致要到下一次有人從空庫重建時才會爆——屆時症狀是「同一份 migration 在兩台機器建出不同的 schema」，很難歸因。

  作法：`docker compose -f e2e/perf/docker-compose.yml rm -sfv postgres` 後 `up -d postgres`。資料在匿名 volume 上，因此只動 postgres 一個服務，kafka 與 connect 不受影響。完成後資料庫為空、連 `flyway_schema_history` 都不存在，下次啟動應用會從 `V1` 乾淨套一遍。

  **刻意不以 psql 手動套 migration 到 dev 庫**：那會建出表卻沒有 `flyway_schema_history`，下次啟動反而衝突。「可從空庫重建」由 `DatabaseFoundationIntegrationTest` 證明——它每次都對全新的 Testcontainers postgres 跑 Flyway `validate`。

- [x] 0.2 依 design 的決策「`stock_locations` 建在 `V2` 而不是新的 `V7`」，`V2__create_ordering_tables.sql` 的檔頭把「訂單層的七張表」改為八張，並把建表順序更新為 `owners → products → skus → fulfillment_nodes → stock_locations → owner_nodes → orders → order_lines`。

  依賴鏈是 `fulfillment_nodes` → `stock_locations` → `stock_pools`(V3)，`stock_locations` 必須排在 `V3` 之前。另開 `V7` 會讓被指向方排在指向方後面，而那正是該檔頭說「不可任意調換」的那個順序。

## 1. 位置模型

- [x] 1.1 依 `stock-locations` 的 **A location's usage decides whether its contents are the company's stock**，以及 design 的決策「`usage` 是 CHECK 約束，不是外鍵到分類表」，在 `V2__create_ordering_tables.sql` 中緊接 `fulfillment_nodes` 之後建 `stock_locations`：`id`、`warehouse_id`（可空，FK 指 `fulfillment_nodes`）、`code`、`name`、`usage`。`usage` 以 `CHECK (usage IN ('INTERNAL','SUPPLIER','CUSTOMER','INVENTORY'))` 約束。行為上：usage 超出集合時由資料庫拒絕。以 schema 整合測試驗證。

  **不建 usage 分類表。** 這四個值不是設定而是程式邏輯的分支——只有 `internal` 算庫存。可設定的值域會讓「新增第五種 usage」看起來是資料維護，實際上每個分支都要跟著改。Odoo 同樣以 selection 而非關聯表表達。

- [x] 1.2 依同一 capability 的 **Every warehouse has exactly one internal location, and virtual locations have none**，加約束：`usage='INTERNAL'` 時 `warehouse_id` 必須非空，其餘三種必須為空；並以 partial unique index 保證一個倉最多一個 `internal` 位置。行為上：一倉建第二個 internal 位置時被拒絕；虛擬位置帶 `warehouse_id` 時被拒絕。以 schema 整合測試驗證兩個方向。

  **兩個方向都要擋。** 只擋一邊時，另一邊的髒資料會安靜地存在——「北部倉有兩個 internal 位置」會讓倉→位置的解析從一次查表變成不定的選擇，而那個不定性在測試裡是隨機失敗。

- [x] 1.3 依 design 的決策「一個倉一個 `internal` 位置，位置不成樹」，**不加 `parent_id`、不加 `parent_path`**。這一項不寫程式，只在 `stock_locations` 的 migration 檔頭寫下理由。

  Odoo 19 用 `parent_path` 物化路徑加 `LIKE` 前綴做子樹查詢，並把 `warehouse_id` 存成欄位（`store=True` 的 computed）。我們一倉一位置，`warehouse_id` 直接就是答案，沒有查詢會沿樹走。寫下來是因為下一個看到「Odoo 有 `location_id` 自我參照」的人會想補上它。

  **順帶記下一個不要照抄的坑**：Odoo 的 `_compute_warehouse_id` 沒有標 `recursive=True`（同檔的 `complete_name` 有標），把子樹搬到別的倉底下時子孫的 `warehouse_id` 不會重算。我們日後若加樹，這個一致性責任要自己補。

- [x] 1.4 依 **Seed data provides one internal location per seeded warehouse and all three virtual locations**，以及 design 的決策「虛擬位置現在就建，但欄位不預留」，`DevSeedDataInitializer` 為每個既有種子倉建一個 `internal` 位置，並建 `Vendors`（`supplier`）、`Customers`（`customer`）、`Inventory adjustment`（`inventory`）各一。以 `DevSeedDataIntegrationTest` 斷言：每個種子倉恰有一個 internal 位置，三種虛擬 usage 各恰有一列。

  三個虛擬位置在這個 change **沒有任何讀者**——還沒有東西移動貨。現在就建，是因為 `usage` 的值域必須一次定完：晚一個 change 引入等於同時改 CHECK 約束與回頭補種子資料，把兩個獨立的失效模式放進同一個 change。**參考資料多一列的成本是零，欄位多一個的成本是每個讀取端都要處理它**——這也是本 change 不為下一個 change 預留任何欄位的分界。

- [x] 1.5 新增 `catalog` 側的位置查詢：以倉解析出其 `internal` 位置。行為上：倉不存在或無 internal 位置時回空而非拋錯，由呼叫端決定怎麼處理。以整合測試驗證。

  回空而不拋錯，是因為呼叫端要的處置不同：配貨路徑需要一個明確的失敗，而診斷查詢只要看得到「這個倉沒有位置」。在這一層決定會讓其中一個呼叫端拿到錯的行為。

- [x] 1.6 依 **Locations expose no write interface**：**由既有護欄涵蓋，不新增測試。**

  位置住在 `catalog`，而 `CatalogModuleBoundaryTest.exposesNoWriteEndpoint()` 已經掃描該模組底下所有 `/entrypoint/rest/` 的寫入型 mapping。再寫一支只針對位置的測試是同一條規則的第二份表述——兩份會各自漂移，而漂移時沒有人知道該信哪一份。

  **但要記下它的邊界**：那條規則守的是 catalog，不是「全 codebase 不得寫入位置」。下一個 change 若在 `allocation` 加了寫入位置的端點，這條護欄看不到。屆時要擴充的是規則的範圍，不是再開一支測試。

- [x] 1.7 依 design 的決策「貨主與倉的指派留在倉層」，**`owner_nodes` 與 `OwnerNodeEntity` 一律不動**，且不新增任何 owner-to-location 的關聯。以既有的 `warehouse-catalog` 測試全綠驗證。

  「這個貨主在哪些倉有貨」是商業關係——它在任何貨進倉之前就成立，`warehouse-catalog` 那條 requirement 明文說了「SHALL be recorded explicitly rather than inferred from where that owner happens to hold stock」。改指位置會讓一倉多位置時無法回答「掛五筆還是只掛庫存區」，而兩個答案都不對。

  **這條線在 Odoo 19 查無對應物**——`stock.picking.type` 的 49 個欄位沒有任何 partner/owner 欄位，stock 模組的 15 條 `ir.rule` 全部 company-based。不要去 Odoo 找答案。

## 2. 庫存改指位置

- [x] 2.1 依 `stock-allocation` 的 **Stock is held per owner, location, arrival and expiry**，以及 design 的決策「表名保留 `stock_pools`，語意對齊 `stock.quant`」，`V3__create_stock_pools.sql` 的 `node_id` 改為 `location_id`（FK 指 `stock_locations`），unique constraint 改為 `(owner_id, location_id, sku_code, in_date, expiry_date)`。**表名、檔名、`V4` 都不動。** 行為上：五維全等才合併為同一列。以持久化整合測試驗證。

  不改名的理由不是改動量，是 `pool` 這個名字**本來就準**：`docs/dom-order-intake-scope.md:219` 的表格已經論證過五維鍵下「`pool` 一樣準」，因為 pool 的定義是「一群可互換的單位」——而那正是 `stock.quant` 的定義。當初被否決的 `stock_batches` 才是不準的那個（它承諾了可追溯性，而這張表不提供）。

- [x] 2.1a 依 design 的決策「欄位對齊到什麼程度」，在 `V3__create_stock_pools.sql` 檔頭補上與 Odoo 19 `stock.quant` 的逐欄對照，並**修掉「名字與內容不符是刻意保留的」那句**——改寫為「這張表就是 `stock.quant`；批次身分（lot）不在其中，見 `docs/dom-stock-movement-scope.md`」。`StockPool` 的 javadoc 同步。

  那句話寫於 R3、比較對象是 `stock_batches`，結論被過度推廣。留著它，下一個人會為了消除一個不存在的債而改名。

  **對照必須寫下來的理由**：少了它，引入 `stock_moves` 時會有人重新推導「我們的 pool 對應 Odoo 的什麼」，而推導出 `stock.lot` 是很自然的錯誤——兩者都帶效期，差別在 quant 是餘額、lot 是可追溯的批次身分。

  **同一段對照要記下一處我們比 Odoo 強的地方**：`stock.quant` 在 Odoo 19 **沒有 unique index**，唯一性靠事後 `_merge_quants` 的 `GROUP BY` 合併。我們的 `uq_stock_pools_batch` 是補貨「命中既有列就加量、否則新開一列」的前提，不能退成事後合併——那等於容忍一段期間的重複列，而那段期間 ATP 會被低估。

- [x] 2.1b **`on_hand_quantity` 不改名為 `quantity`。** 這一項不寫程式，只在上述對照裡記下理由。

  Odoo 該欄的 UI 標籤是 "Quantity On Hand"——欄位名是簡稱、語意是在手量，而本系統的名字已經把語意寫在名字裡。它旁邊就是 `reserved_quantity`，裸的 `quantity` 會變成「什麼的數量」。**對齊業界詞彙的目的是讓人少推導一次，不是讓名字變短。**

- [x] 2.2 依同一 requirement 的 **Stock cannot be held in a virtual location**，加約束：`stock_pools.location_id` 必須指向 `usage='INTERNAL'` 的位置。行為上：指向虛擬位置的寫入被拒絕。以 schema 整合測試驗證。

  外鍵擋不到這件事——它只保證位置存在。少了這條約束，「系統宣稱在一個它不經營的地方持有貨」寫得進去，而症狀會在總量對帳時才出現。

- [x] 2.3 依 design 的決策「FEFO 排序與樂觀鎖都不變」，`idx_stock_pools_fefo` 的欄位改為 `(owner_id, location_id, sku_code, expiry_date, in_date, id)`，**index 名稱不動**。行為上：配貨查詢沿 index 取列而不排序。以既有的持久化測試確認排序不變。

  欄位順序的理由（等值篩選在前、排序鍵其次、`id` 作為最後的 tie-breaker）寫在 `V3` 檔尾，與位置無關，因此**只有第二欄換名字，結構不動**。三層排序鍵不是裝飾：同效期不同日到貨很常見，少了 `in_date` 與 `id`，配貨結果不可重現，防死鎖的寫入排序也失去依據。

- [x] 2.4 `StockPool`、`StockPoolEntity`、`StockPoolMapper`、`StockPoolRepository(+Impl)`、`JpaStockRepository` **類別名一律不動**，只把欄位 `nodeId` 改為 `locationId`。建構子的 `"Fulfillment node ID is required"` 改為位置。行為上不變。既有領域測試全綠，**斷言不得修改**。

  斷言要是需要改，代表這一步動到了數量語意——而這個 change 不碰數量。

- [x] 2.5 **`version` 欄位與樂觀鎖語意完全不動**，並在 `StockPool` 的 javadoc 寫明「餘額是物化的，不是 `SUM(moves)`」。

  這句話必須現在就寫，因為下一個 change 引入 `stock_moves` 之後，「餘額由異動推導」是最自然的誤讀。Odoo 的 quant 也是物化餘額。少了它，`docs/dom-promising-scope.md`「決定二」——補貨與喚醒共用同一批庫存列的樂觀鎖，這是 FIFO 的實作機制而非效能取捨——會在下一個 change 被當成可以優化掉的東西。

- [x] 2.6 依 design 的決策「對外說倉，對內說位置」與「命名收斂集中在第四個 change」，`StockPoolController`、`StockPoolResponse`、`GetStockPoolUsecase`、`/stock-pool` 端點路徑與 `nodeId` 查詢參數**一律不動**；`GetStockPoolUsecase` 內部把倉解析成位置再查。以既有的 `StockPoolControllerTest` 驗證回應形狀未變。

  端點名 `/stock-pool` 是**永久保留**、不列入第四個 change 的命名收斂：表名既然站得住（任務 2.1），端點名就沒有債要償。

## 3. 待配需求與配貨查詢

- [x] 3.1 依 `stock-allocation` 的 **Allocation draws stock from a location, and demand is published with one**，以及 design 的決策「`demand_lines` view 解析位置，不是讓配貨自己解析」，改寫 `V6__create_demand_lines_view.sql`：join `stock_locations`（`warehouse_id = o.fulfillment_node_id AND usage = 'INTERNAL'`），輸出 `location_id` 取代 `node_id`。**`WHERE` 的兩個謂詞（`cancelled_at IS NULL`、無 ACTIVE／CONSUMED 預留）一字不動**，檔頭那兩段註解一併保留。`DemandLineEntity`、`DemandMapper`、`Demand`、`JpaDemandLineRepository`、`DemandRepository(+Impl)` 跟著改欄位名。

  解析放在 view 而不是 repository：view 是 ordering 與 allocation 之間的介面，**訂單說倉、配貨說位置，轉換放在兩者交會處，兩邊各自只需要一套詞彙**。放進 repository 則會讓 allocation 同時認識倉與位置。

  V6 檔頭警告過：「刻意不含 `ol.status`」與「`CONSUMED` 現在不會出現，但謂詞必須現在就寫對」。重建 view 是最容易把那兩段註解連同謂詞一起弄丟的時機，而弄丟的症狀分別是重複預留與已出貨訂單重回佇列——**兩者當下都不會有測試失敗**。

- [x] 3.2 補上「取消的單不得被補貨喚醒」的**行為測試**（`AllocationWorkflowEndToEndIntegrationTest`），而不是 `DemandRepository` 的 SIT。

  **原案是錯的。** 原本要在 view／repository 層釘住三個謂詞，但下一個 change 會把待配需求從 `demand_lines` 換成搬運的狀態——那些測試會被**整組重寫**，而被重寫掉的測試不會給任何訊號。

  改成測行為：「一張單取消之後，補貨不該把它配走，貨要落到它後面那張活著的單上」。這個斷言與機制無關，換完 move 之後原封不動仍然成立。

  **並且驗證過它抓得到**：暫時把 view 的 `cancelled_at IS NULL` 改成恆真，該測試變紅；還原後變綠。行為早就存在，所以它一寫就是綠的——不證明它會失敗，就等於沒有測試。

  `OrderPersistenceIntegrationTest.java:110-112` 的註解承諾過這支測試，**但它從未被建立**。目前 view 的三個謂詞裡，`cancelled_at IS NULL` **完全沒有測試**，`NOT EXISTS` 只被 `InboundCommandTransactionIntegrationTest` 間接釘住。這個 change 要重建 view，而下一個 change 要把「已滿足」的判準換成 move 的狀態——**沒有這支測試，兩次改動都是盲改**。

- [x] 3.3 依 **Allocation draws stock from a location, and demand is published with one**，`StockPoolRepository.findAllocatableBatchesInFefoOrder` 與 `findAllocatableBatchesBySku` 的 `nodeId` 參數改為 `locationId`；`AllocationService`、`AllocateOrderUsecase`、`OrderAllocationCoordinator` 跟著改。行為上：只取該位置的批。以領域測試驗證「同貨主同 SKU 在兩個 internal 位置」時只取其中一個。

  這個情境**現在造不出來**（一倉一位置），測試要用兩個位置直接建資料造出它。不造的話，這條 requirement 在第二個 internal 位置出現之前都是空的，而它出現時沒有任何測試會失敗。

- [x] 3.4 `ReplenishmentUsecase` 的佇列範圍由倉改為位置：`findOutstandingDemandInFifoOrder` 的參數與 `wake()` 的三個查詢一致改用位置。行為上不變。既有的 `AllocationFifoReplenishmentBatchIntegrationTest` 全綠且**斷言不改**。

  理由與當初把範圍縮到倉相同：跨位置的佇列會把以張數計的上限花在從來就不是候選的單上。

- [x] 3.5 `ReplenishStockCommand`、`WakeBackordersCommand` 與其對應的對外事件 `nodeId` **不動**；兩個 Kafka handler（`StockReplenishedIntegrationEventHandler`、`BackorderWakeRequestedIntegrationEventHandler`）在進 usecase 前把倉解析成位置。`ReplenishmentProbeController` 同樣不動。

  解析放在 entrypoint 而不是 usecase：那一層的職責就是把外部詞彙翻成內部詞彙。放進 usecase 會讓每一個呼叫路徑各自解析一次，而它們遲早會不一致。

- [x] 3.6 **`StockContentionKey` 不動**，維持 `(owner, node)`，並在該類別補一段註解說明為什麼。

  它決定 partition key，而 partition key 要保證的是「碰同一批庫存列的事件落在同一個 writer」。一倉一位置時 `(owner, node)` 與 `(owner, location)` 分區完全相同；一倉多位置時 `(owner, node)` 會**過度序列化**——那是安全的方向（併發變少，正確性不變），而換成位置卻可能把共用同一把鎖的寫入拆到不同 writer。**粗是安全的，細才危險。** `DomainEventTranslatorTest` 既有的「SKU 不得進 key」斷言不變。

## 4. 護欄與驗證

- [x] 4.1 依 `stock-locations` 的 **An order names a warehouse and never a location**，以及 design 的決策「`orders` 不碰位置」，確認 `orders`、`order_lines`、`Order`、`DeliveryTerms`、`OrderEntity`、`OrderMapper`、`PlaceOrderUsecase`、`OrderRest`、`OrderStatusResponse` **一個字都沒改**，且 `orders` 上的複合外鍵 `fk_orders_owner_node` 原封不動。以既有的 `order-intake` 測試全綠驗證。

  這是這個 change 邊界的核心。訂單是需求不是搬運單據，位置屬於下一個 change 才出現的執行層。而那條複合外鍵是「倉存在但這個貨主沒掛這個倉」的唯一防線——`PlaceOrderUsecase` 的 javadoc 明說本系統刻意不做應用層預檢查。

- [x] 4.2 `AllocationFifoGuaranteeScopeIntegrationTest` 與 `AllocationHotSkuConcurrencyIntegrationTest` 在本 change 前後都必須綠，且**不得修改任何斷言**。

  它們守的是 FIFO 保證的範圍與熱點 SKU 的序列化，兩者都與貨在哪個位置無關。**任何一個需要改斷言的情形都代表這個 change 動到了不該動的東西**，應該回頭找原因而不是改測試。

- [x] 4.3 `AllocationWorkflowEndToEndIntegrationTest`、`AllocationConcurrencyEndToEndIntegrationTest`、`ReplenishmentProbeEndToEndIntegrationTest` 全綠且斷言不改，證明對外契約確實沒動。

- [x] 4.4 前端**不做任何修改**，`frontend` 既有測試全綠。

  這是「對外契約不變」最直接的證據：前端一個字都不用改。要是需要改，代表某處的契約洩漏了內部模型。

- [x] 4.5 擴充 `AllocationBoundaryArchitectureTest`：`ordering` 側不得出現 `stock_locations` 這個表名。

  收單完全不碰位置（任務 4.1），而 `demand_lines` view 的 join 屬於 allocation 的介面不屬於 ordering。少了這條，`ordering` 會慢慢長出對庫存 schema 的直接依賴——而第四個 change 要把界線重畫時，那些依賴每一條都要拆。

- [x] 4.6 更新 `docs/dom-stock-movement-scope.md`：把「四個 change 的順序」表中第一列標記為已交付。
