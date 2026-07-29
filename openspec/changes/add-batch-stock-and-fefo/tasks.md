## 1. Schema

- [ ] 1.1 依決策「**改寫 `V2` 與 `V4`，不新增 migration**」，把 `V2__create_stock_pools.sql` 改寫為最終形狀：唯一鍵 `(owner_id, node_id, sku_code, in_date, expiry_date)`，`expiry_date` **NOT NULL**（PostgreSQL 的 unique 把 NULL 視為互不相同，可空會讓同日到貨的無效期商品各成一列而非合併），欄位 `sku` 更名為 `sku_code` 與其餘各表一致。行為上：空資料庫一次套用即得最終 schema，migration 歷史不含「一個 SKU 一列」這個從未部署過的中間形狀。以 `./e2e/perf/run.sh down` 後重新啟動、Flyway 套用成功驗證。
- [ ] 1.2 依 **A stock row references a SKU its owner actually holds**，為 `stock_pools` 建外鍵 `(owner_id, sku_code)` → `skus(owner_id, sku_code)`。走自然鍵而非 `skus.id`，與 `order_lines` 同一個手法——強制每一次參照都帶上貨主，跨貨主的錯誤組合因此建不起來。行為上：寫入一列指向該貨主沒有的 SKU 代碼會被資料庫拒絕。以 SIT 斷言該寫入失敗驗證。
- [ ] 1.3 建 FEFO 查詢用的 index，欄位順序與 `ORDER BY` 完全一致：`(owner_id, node_id, sku_code, expiry_date, in_date, id)`。行為上：配貨查詢沿 index 取列，不需排序整表。以 `EXPLAIN` 確認未出現 Sort 節點驗證。
- [ ] 1.4 依決策「**預留的粒度是訂單行 × 批次**」，把 `V4__create_stock_reservations.sql` 的外鍵由 `order_id` 改為 `order_line_id`，並移除「一張單一筆預留」的唯一限制。行為上：同一條訂單行可以有多筆預留，各自指向不同的庫存列。以 SIT 寫入兩筆同行不同列的預留並斷言成功驗證。
- [ ] 1.5 `ReservationStatus` 加 `CONSUMED`。**本 change 不產生這個狀態**——它為 R7 的出貨扣帳準備，但必須現在加，因為 R4 的 `demand_lines` view 會用它做「已滿足」謂詞而 R4 緊接在後。行為上：enum 與資料庫的 CHECK 都接受這個值。以 schema 審閱與 SIT 驗證。

## 2. 庫存的 domain

- [ ] 2.1 依 **Stock is held per owner, warehouse, arrival and expiry** 與決策「**`StockPool` 從「一個 SKU 的池」變成「一批貨」**」，`StockPool` 加 `ownerId`、`nodeId`、`inDate`、`expiryDate` 四個不可變欄位。**類別名與表名都不改**，但要在 Javadoc 與 migration 註解裡明說**一列是一批不是一個池**——名字與內容不符，不寫下來下一個人會誤讀。行為上：建構時缺任一維度即拋錯。以領域測試驗證。
- [ ] 2.2 依 **Expired stock is present but not sellable**，加 `isSellable(LocalDate today)`。行為上：效期已過的批回 `false`，其餘回 `true`；判斷只看效期，不看數量——「沒貨」與「不可售」是兩件事，混在一起會讓落選理由說不清楚。以領域測試涵蓋過期、當日到期、未到期三種情形驗證。
- [ ] 2.3 依決策「**`consume()` 與 `reserve()` 分開**」，加 `consume(int)`：`reserve()` 鎖住額度、`consume()` 於出貨時真正扣掉在手量。**本 change 不呼叫 `consume()`**，它為 R7 的兩本帳準備。行為上：`reserve` 只動 `reservedQuantity`，`consume` 同時減少 `onHandQuantity` 與 `reservedQuantity`。以領域測試驗證兩者的差異。

## 3. 庫存的持久化

- [ ] 3.1 依 **Allocation consumes the earliest-expiring stock first**，`StockPoolRepository.findBySku` 拆為 `findSellableBatchesInFefoOrder(ownerId, nodeId, skuCode, today)` 與 `findBatches(ownerId, nodeId, skuCode)`。**排序與過期篩選在資料庫做**，不在 domain service——批數隨時間成長，把不可售的載進記憶體只為了丟掉是錯的方向。排序鍵 `(expiry_date, in_date, id)`。行為上：前者只回可售的批且順序固定；後者回全部含過期的，供庫存頁顯示。以 SIT 斷言順序與過濾驗證。
- [ ] 3.2 依決策「**依五維鍵 upsert**」，加 `findByIdentity(ownerId, nodeId, skuCode, inDate, expiryDate)`。行為上：補貨用它決定是加到既有列還是新開一列，合併規則因此完全由鍵決定，沒有額外邏輯。以 SIT 驗證。

## 4. 配貨演算法

- [ ] 4.1 依 **Allocation consumes the earliest-expiring stock first** 與決策「**配貨從「一個池」變成「一組批」**」，`AllocationService.allocate()` 的簽章由 `(Order, StockPool, Instant)` 改為 `(Order, List<StockPool>, Instant)`，list 是已排序的可售批。跨批依序取用直到湊滿。行為上：需求 80、庫存為近效期 60 與遠效期 40 時，取近效期 60 與遠效期 20。以領域測試驗證取用的批與數量。
- [ ] 4.2 依 **An order is satisfied wholly or not at all**，可售總量不足時**不預留任何一批**、整單缺貨。行為上：需求 80、可售總量 50 時，兩批的 `reservedQuantity` 都不變。以領域測試驗證——這是分批之後最容易不小心違反的一條（拿了 60 件鎖住卻出不了貨）。
- [ ] 4.3 把 `requireDemandIsEntirelyInThisPool()` 改為 `requireBatchesMatchDemand()`：所有批必須屬於同一個 `(owner, node, sku)`，且該三元組等於訂單那一行的需求。行為上：呼叫端傳入混了別的 SKU 或別的貨主的批時拋錯。以領域測試驗證——這條守的是程式錯誤，不是資料錯誤。
- [ ] 4.4 依 **A reservation is held per order line and per stock row** 與決策「**預留的粒度是「訂單行 × 批次」**」，`OrderAllocationCoordinator` 為每一個被取用的批建立一筆預留。行為上：一條行吃兩批就產生兩筆預留，數量加總等於該行的數量。以 SIT 驗證。
- [ ] 4.5 `AllocationOutcome` 區分「完全沒有批」與「有批但全部不可售」。決策層級仍是訂單（ship-complete），per-line 資訊只作診斷。行為上：庫存頁與訂單頁能說出「為什麼配不到」，而不只是「配不到」。以領域測試涵蓋兩種情形驗證。
- [ ] 4.6 依決策「**FEFO 的排序鍵是三層**」，`OrderAllocationCoordinator` 的持久化段落**明確依 `(sku_code, expiry_date, in_date, id)` 排序後寫入**，不可依賴集合的自然順序。排序鍵**現在就寫成跨 SKU 的形式**，即使單行時只有一個 SKU——R8 之後一次配貨會碰多個 SKU 的多個批，屆時才改排序鍵是死鎖最難重現的一類問題。以程式碼審閱與一支「打亂輸入順序、斷言寫入順序不變」的測試驗證。

## 5. 補貨

- [ ] 5.1 依 `demo-only-probes` 的 **The replenishment probe publishes a real upstream stock event**，`ReplenishStockCommand` 與 `StockReplenishedIntegrationEvent` 加 `nodeId`、`inDate`、`expiryDate`；`/demo/replenish` 的 request body 隨之改變，缺任一欄即回 `400`。行為上：補貨能指定要加到哪一列。以 web 層測試涵蓋完整與缺欄兩種情形驗證。
- [ ] 5.2 `ReplenishmentUsecase` 改為依五維鍵 upsert：命中既有列就加數量，否則新開一列。行為上：同貨主同倉同 SKU 同日同效期的兩次補貨合併成一列；任一維度不同就是兩列。以 SIT 涵蓋兩種情形驗證。
- [ ] 5.3 依 `fifo-replenishment-demo` 的 **Wake a queued backorder list on StockReplenished**（該 requirement 新增的「喚醒必須有界」段落）與決策「**補貨喚醒要有批次上限**」，補貨喚醒加上以張數為維度的可設定上限；超出時發一則續做事件（同 topic 同 partition key），**終止條件為「本輪喚醒張數 < 上限即不續做」**。行為上：1,000 張佇列在上限 200 時分多輪收斂，且不會無限續做。以 SIT 斷言收斂與續做次數驗證。**預設值以壓測觀察決定，並把當時的觀察值寫進註解**（design 的 Open Question 之一）。

## 6. 事件與 partition key

- [ ] 6.1 依 `outbox-event-delivery` 的 **Partition key strategy selects only the delivery key** 與決策「**partition key 與唯一鍵在同一個 change**」，`OrderingDomainEventTranslator` 與 `ReplenishmentProbeController` 的 key 改為 `ownerId + "/" + nodeId + "/" + skuCode`。定長的 UUID 在前、自由文字的 `skuCode` 在後——SKU 代碼可能含任何字元包括分隔字元，定長在前才保證不同三元組不會產生同一個字串。`AllocationDomainEventTranslator` **不動**。行為上：同一個 `(貨主, 倉, SKU)` 的事件落在同一個 partition；不同貨主的同碼 SKU 落在不同 partition。以 outbox 的 SIT 斷言 key 內容驗證。
- [ ] 6.2 `OrderAllocatedIntegrationEvent` 加批次清單，每批帶對應的 `orderLineId`。行為上：下游（R7 的履約層）知道要揀哪幾批，不必回頭查。以事件建構測試驗證。

## 7. Seed 資料

- [ ] 7.1 依 **Seed data makes every allocation outcome reproducible**，為一個 SKU 種三個可售批（近／中／遠效期）**其中兩批同效期不同入庫日**，另加一批已過期。行為上：FEFO 的排序、tie-breaker 與「有貨但不可售」三件事都有資料可驗。以 SIT 斷言三者皆成立驗證——**少了同效期那兩批，tie-breaker 完全沒有被測到**。
- [ ] 7.2 種一張需求跨兩批的訂單。行為上：多批取用與多筆預留在畫面與測試上都看得到。以 seed SIT 驗證。
- [ ] 7.3 刪除 `DevSeedDataIntegrationTest` 中「每個庫存池的 SKU 都存在於主檔」那支測試——它驗的東西已由 1.2 的外鍵保證。行為上：測試數減一，而該保證更強（從只驗種子變成驗所有寫入路徑）。以測試通過驗證。

## 8. 既有測試的重新設計

- [ ] 8.1 依 `hot-sku-concurrency-demo` 的 **Demonstrate a real optimistic-lock conflict**（該 requirement 新增的「競爭必須集中在單一庫存列」段落），重新設計 `AllocationHotSkuConcurrencyIntegrationTest`：庫存集中在單一批次，並**斷言它確實只有一列**。行為上：分批之後這支測試若讓庫存散在多列，競爭強度會大幅下降而測試仍然通過——那是最糟的失敗方式。以「把種子改成兩列、確認該測試失敗」驗證。
- [ ] 8.2 重新設計 `AllocationFifoReplenishmentBatchIntegrationTest`：500 張的單次喚醒變成多輪續做，斷言由「一次補貨事件後的最終狀態」改為「續做收斂後的最終狀態」。**head-of-line blocking 的斷言必須保留**——那是這支測試存在的理由。以測試通過並確認 blocker 仍卡住後續訂單驗證。
- [ ] 8.3 重新設計 `AllocationConcurrencyEndToEndIntegrationTest`：它建立在「一個 SKU 一列」上，須改為批次前提。行為上：重試與 DLT 的既有斷言仍成立。以測試通過驗證。

## 9. 前端

- [ ] 9.1 實作 `order-promising-http-api` 的 **Stock pool state is queryable by SKU**（改為帶貨主、回傳批次列表、含不可售標示）與 `demo-console-frontend` 的 **Stock state and replenishment share one page keyed by SKU**：後端端點與前端畫面一起改，庫存查詢結果為批次列表：每列顯示倉別、入庫日、效期、三個數量與可售與否，**依配貨會取用的順序排列**，不可售的列顯示理由而非隱藏。行為上：畫面回答「哪一批會先出」與「為什麼這批不能出」。以前端測試涵蓋排序與過期標示驗證。
- [ ] 9.2 補貨表單加倉別、入庫日、效期三個必填欄位，缺任一即在表單層擋下、不發請求。行為上：無效的補貨不會換來一次沒有必要的往返。以前端測試涵蓋範例表的五種情形驗證。
- [ ] 9.3 更新 `api/types.ts` 的庫存與補貨型別，並在檔頭註解補上本 change。行為上：下一個人判斷「後端合約變了要改哪」時看到的是最新的來源清單。以型別檢查與註解審閱驗證。

## 10. 端到端驗收與文件

- [ ] 10.1 更新 `e2e/perf/run.sh` 的 `seed`：庫存要帶倉別、入庫日、效期。**熱點壓測的庫存必須在單一批次**，否則競爭分散、v1／v3 的對比失去意義。行為上：壓測訂單配得到貨且維持原本的競爭強度。以壓測 `checks_total` 全過驗證。
- [ ] 10.2 更新 `e2e/perf/k6/hot-sku-burst.js`：若腳本有補貨請求則加新欄位；下單不受影響。行為上：thresholds 通過。
- [ ] 10.3 依 design 的 Migration Plan 重建並重跑壓測：`./e2e/perf/run.sh down` 後 `perf`。行為上：既有 thresholds 全數通過。以本次結果更新 `e2e/perf/README.md` 的 baseline。
- [ ] 10.4 **把 `e2e/perf/README.md` 的 v1／v3 對比表標註為「partition key 改為三維之前量的」**。行為上：下一個讀那張表的人不會拿它與改動後的數字並列。要不要重測是獨立的決定（重測要跑完整的暖機方法論，見該檔）。以文件審閱驗證。
- [ ] 10.5 核對 `docs/stock-reservation-design.md` 的 `stock_pools` 與 `stock_reservations` 欄位表、狀態轉換與核心流程三節與實作一致。行為上：文件與程式不分岔。以文件審閱驗證。
