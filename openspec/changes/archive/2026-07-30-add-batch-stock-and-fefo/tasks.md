## 1. Schema

- [x] 1.1 依決策「**改寫 `V2` 與 `V4`，不新增 migration**」，把 `V2__create_stock_pools.sql` 改寫為最終形狀：唯一鍵 `(owner_id, node_id, sku_code, in_date, expiry_date)`，`expiry_date` **NOT NULL**（PostgreSQL 的 unique 把 NULL 視為互不相同，可空會讓同日到貨的無效期商品各成一列而非合併），欄位 `sku` 更名為 `sku_code` 與其餘各表一致。行為上：空資料庫一次套用即得最終 schema，migration 歷史不含「一個 SKU 一列」這個從未部署過的中間形狀。以 `./e2e/perf/run.sh down` 後重新啟動、Flyway 套用成功驗證——已於 2026-07-30 執行：`flyway_schema_history` 是 `V1__baseline` → `V2__create_ordering_tables` → `V3__create_stock_pools` → `V4__create_stock_reservations` → `V5__create_event_inbox_and_outbox`，新的編號順序在空資料庫上一次套用成功，種子的六批庫存也依設計寫入（含同效期不同入庫日的那一對與已過期的那一批）。
- [x] 1.2 依 **A stock row references a SKU its owner actually holds**，為 `stock_pools` 建外鍵 `(owner_id, sku_code)` → `skus(owner_id, sku_code)`。走自然鍵而非 `skus.id`，與 `order_lines` 同一個手法——強制每一次參照都帶上貨主，跨貨主的錯誤組合因此建不起來。行為上：寫入一列指向該貨主沒有的 SKU 代碼會被資料庫拒絕。以 SIT 斷言該寫入失敗驗證。
- [x] 1.3 建 FEFO 查詢用的 index，欄位順序與 `ORDER BY` 完全一致：`(owner_id, node_id, sku_code, expiry_date, in_date, id)`。行為上：配貨查詢沿 index 取列，不需排序整表。以 `EXPLAIN` 確認未出現 Sort 節點驗證。
- [x] 1.4 依決策「**預留的粒度是訂單行 × 批次**」，把 `V4__create_stock_reservations.sql` 的外鍵由 `order_id` 改為 `order_line_id`，並移除「一張單一筆預留」的唯一限制。行為上：同一條訂單行可以有多筆預留，各自指向不同的庫存列。以 SIT 寫入兩筆同行不同列的預留並斷言成功驗證。
- [x] 1.5 `ReservationStatus` 加 `CONSUMED`。**本 change 不產生這個狀態**——它為 R7 的出貨扣帳準備，但必須現在加，因為 R4 的 `demand_lines` view 會用它做「已滿足」謂詞而 R4 緊接在後。行為上：enum 與資料庫的 CHECK 都接受這個值。以 schema 審閱與 SIT 驗證。

## 2. 庫存的 domain

- [x] 2.1 依 **Stock is held per owner, warehouse, arrival and expiry** 與決策「**`StockPool` 從「一個 SKU 的池」變成「一批貨」**」，`StockPool` 加 `ownerId`、`nodeId`、`inDate`、`expiryDate` 四個不可變欄位。**類別名與表名都不改**，但要在 Javadoc 與 migration 註解裡明說**一列是一批不是一個池**——名字與內容不符，不寫下來下一個人會誤讀。行為上：建構時缺任一維度即拋錯。以領域測試驗證。
- [x] 2.2 依 **Expired stock is present but not allocatable**，加 `isExpired(LocalDate today)`。行為上：效期已過的批回 `true`，當日到期與未到期回 `false`；判斷只看效期，不看數量。**方法名講事實不講後果**——「配不配得到」還要看有沒有量，那個判斷屬於查詢（3.1）；混在一起會讓「有 100 件但一件都出不了」與「什麼都沒有」在畫面上分不開，而前者要報廢、後者要進貨。**不叫 `isSellable`**：3PL 不賣貨、貨主才賣，倉庫回答的是出不出得了。以領域測試涵蓋過期、當日到期、未到期三種情形驗證。
- [x] 2.3 依決策「**`consume()` 與 `reserve()` 分開**」，加 `consume(int)`：`reserve()` 鎖住額度、`consume()` 於出貨時真正扣掉在手量。**本 change 不呼叫 `consume()`**，它為 R7 的兩本帳準備。行為上：`reserve` 只動 `reservedQuantity`，`consume` 同時減少 `onHandQuantity` 與 `reservedQuantity`。以領域測試驗證兩者的差異。

## 3. 庫存的持久化

- [x] 3.1 依 **Allocation consumes the earliest-expiring stock first**，`StockPoolRepository.findBySku` 拆為 `findAllocatableBatchesInFefoOrder(ownerId, nodeId, skuCode, today)` 與 `findBatchesAcrossNodes(ownerId, skuCode)`。**篩選與排序都在資料庫做**，不在 domain service——批數隨時間成長，把配不到的載進記憶體只為了丟掉是錯的方向。前者兩個篩選條件缺一不可：`expiry_date >= today` **且** `on_hand_quantity > reserved_quantity`——全被預留光的批對配貨而言與不存在沒有差別，留著只會讓每個呼叫端各自記得跳過它，而方法名承諾的就是「配得到的」。排序鍵 `(expiry_date, in_date, id)`。行為上：前者只回配得到的批且順序固定；後者跨倉回全部（含過期、含預留光的），供庫存頁顯示。以 SIT 斷言順序與兩個過濾條件驗證。
- [x] 3.2 依決策「**依五維鍵 upsert**」，加 `findByIdentity(ownerId, nodeId, skuCode, inDate, expiryDate)`。行為上：補貨用它決定是加到既有列還是新開一列，合併規則因此完全由鍵決定，沒有額外邏輯。以 SIT 驗證。

## 4. 配貨演算法

- [x] 4.1 依 **Allocation consumes the earliest-expiring stock first** 與決策「**配貨從「一個池」變成「一組批」**」，`AllocationService.allocate()` 的簽章由 `(Order, StockPool, Instant)` 改為 `(Order, List<StockPool>, Instant)`，list 是已排序的可配批。跨批依序取用直到湊滿。行為上：需求 80、庫存為近效期 60 與遠效期 40 時，取近效期 60 與遠效期 20。以領域測試驗證取用的批與數量。
- [x] 4.2 依 **An order is satisfied wholly or not at all**，可配總量不足時**不預留任何一批**、整單缺貨。行為上：需求 80、可配總量 50 時，兩批的 `reservedQuantity` 都不變。以領域測試驗證——這是分批之後最容易不小心違反的一條（拿了 60 件鎖住卻出不了貨）。
- [x] 4.3 把 `requireDemandIsEntirelyInThisPool()` 改為 `requireBatchesMatchDemand()`：所有批必須屬於同一個 `(owner, node, sku)`，且該三元組等於訂單那一行的需求。行為上：呼叫端傳入混了別的 SKU 或別的貨主的批時拋錯。以領域測試驗證——這條守的是程式錯誤，不是資料錯誤。
- [x] 4.4 依 **A reservation is held per order line and per stock row** 與決策「**預留的粒度是「訂單行 × 批次」**」，`OrderAllocationCoordinator` 為每一個被取用的批建立一筆預留。行為上：一條行吃兩批就產生兩筆預留，數量加總等於該行的數量。以 SIT 驗證。
- [x] 4.5 `AllocationOutcome` 區分「沒有可配的批」與「有可配的批但量不足」。決策層級仍是訂單（ship-complete）。**「有批但全部過期」不放在這個 enum 裡**——原本要求區分它與「完全沒有批」，實作時否決：那個區別屬於**庫存狀態而非訂單狀態**。訂單掛帳後不再重跑配貨（`markBackOrdered` 只接受 PENDING），寫下來的理由只是第一次失敗那一刻的快照，之後補貨、報廢都不會更新它；而要行動的人問的是「**現在**為什麼出不了」。該問題由庫存頁回答（任務 9.1：逐批列出 `expired` 與 `availableToPromise`，每次請求重算——兩個欄位各講一件事，讀的人合起來就知道是過期還是被預留光，不需要第三個理由欄位轉述）。行為上：訂單側區分「沒得配」與「不夠配」，庫存側說明「這批是什麼狀態」。以領域測試涵蓋兩種情形驗證。
- [x] 4.6 依決策「**FEFO 的排序鍵是三層**」，`OrderAllocationCoordinator` 的持久化段落**明確依 `(sku_code, expiry_date, in_date, id)` 排序後寫入**，不可依賴集合的自然順序。排序鍵**現在就寫成跨 SKU 的形式**，即使單行時只有一個 SKU——R8 之後一次配貨會碰多個 SKU 的多個批，屆時才改排序鍵是死鎖最難重現的一類問題。以程式碼審閱與一支「打亂輸入順序、斷言寫入順序不變」的測試驗證。

## 5. 補貨

- [x] 5.1 依 `demo-only-probes` 的 **The replenishment probe publishes a real upstream stock event**，`ReplenishStockCommand` 與 `StockReplenishedIntegrationEvent` 加 `nodeId`、`inDate`、`expiryDate`；`/demo/replenish` 的 request body 隨之改變，缺任一欄即回 `400`。行為上：補貨能指定要加到哪一列。以 web 層測試涵蓋完整與缺欄兩種情形驗證（缺欄位是參數化的六種）。**實作時發現缺的不只是測試**：探針沒有 `IllegalArgumentException` 的 handler,缺欄位會回 **500** 而不是 400——把呼叫方的錯誤報成伺服器的錯誤。另外 `quantity` 是包裝型別（為了讓「沒帶」與「帶了 0」分得開),但程式直接拆箱給收 `int` 的建構子,缺欄位時是 **NPE** 而不是 IllegalArgumentException。兩者都修了,並以「把 null 檢查拿掉、確認該支測試變紅」驗證。
- [x] 5.2 `ReplenishmentUsecase` 改為依五維鍵 upsert：命中既有列就加數量，否則新開一列。行為上：同貨主同倉同 SKU 同日同效期的兩次補貨合併成一列；任一維度不同就是兩列。以 SIT 涵蓋兩種情形驗證。
- [x] 5.3 依 `fifo-replenishment-demo` 的 **Wake a queued backorder list on StockReplenished**（該 requirement 新增的「喚醒必須有界」段落）與決策「**補貨喚醒要有批次上限**」，補貨喚醒加上以張數為維度的可設定上限；超出時發一則續做事件（同 topic 同 partition key），**終止條件為「本輪喚醒張數 < 上限即不續做」**。行為上：1,000 張佇列在上限 200 時分多輪收斂，且不會無限續做。以 SIT 斷言收斂與續做次數驗證。~~**續做事件由 translator 而非 usecase 寫進 outbox**（review 期間改的）：usecase 發 `BackorderWakeContinuationRequired` 領域事件，`AllocationDomainEventTranslator` 譯成對外事件並決定 topic 與 key。原本 usecase 直接注入 `OutboxAppender` 自己 append——那讓它成為**唯一一個知道 outbox 存在的 usecase**，也是唯一在 translator 之外自己組對外事件的地方，而 translator 這一層的用途正是讓「領域事實」與「怎麼送出去」只有一處交會。現在 `OutboxAppender` 只有兩個 translator 碰得到。**預設值以壓測觀察決定，並把當時的觀察值寫進註解**~~（design 的 Open Question 之一）→ **機制已驗證，但預設值沒有調校。** `200` 是為了讓機制真的被走到而選的（1,000 張佇列在這個上限下會分多輪收斂，續做與終止條件都有 SIT 蓋著），不是量出來的。要調校它需要「單筆喚醒交易的實際耗時」，而那與任務 10.3 卡在同一件事上——安靜的機器。註解已改成誠實敘述並寫明調校方向，不再宣稱觀察值記在 README（那句話原本是假的：README 裡沒有那個值）。**實作時發現並修掉一個 bug**：終止條件原本以「本輪**讀到**幾張」判斷，而 design 寫的是「本輪**喚醒**（真正配到）幾張」。兩者只在 blocker 卡在隊首且庫存還有量時分歧，而那正是會出事的情形——每輪讀滿上限卻配不到任何一張，於是無限續做，且庫存沒耗盡不會有任何別的機制讓它停。`AllocationFifoReplenishmentBatchIntegrationTest` 新增一支專測那個情境的測試（原本那支碰不到，因為那裡庫存剛好用完），並以「把判準改回讀取數、確認撞上硬上限」驗證過。

## 6. 事件與 partition key

- [x] 6.1 依 `outbox-event-delivery` 的 **Partition key strategy selects only the delivery key** 與決策「**partition key 與唯一鍵在同一個 change**」，`OrderingDomainEventTranslator` 與 `ReplenishmentProbeController` 的 key 改為 `ownerId + "/" + nodeId`，設定值由 `sku` 改名為 `stock`。`AllocationDomainEventTranslator` **不動**。

  **key 刻意不含 SKU**（review 期間改的，原任務寫的是三維）。含 SKU 更貼近「哪些庫存列會被碰到」，但它有到期日：R8 放寬多行之後一張跨 SKU 的訂單摺不出單一個 key，而 ship-complete 要求整籃 ATP 在同一交易判斷，per-SKU 的 writer 管轄必然被跨越。拿掉 SKU 之後那個到期日消失——一張單不管跨幾個 SKU 都只屬於一個 `(貨主, 倉)`，**一個 writer 看得到整張單**。代價是過度序列化（同貨主同倉不同 SKU 也排隊），而壓測量到「完全沒有 single-writer」也只掉約 25% 吞吐，容量餘裕有一個數量級。

  設定值改名的理由：名字要說**序列化什麼**而不是**用哪幾個欄位**，否則調整組成就讓名字說謊。

  **連帶簡化**：`LineSnapshot.requireSingleSku()` 不再有任何使用者，刪除；`partitionKey` 不再需要 `Supplier` 延後求值（沒有會拋錯的摺疊了）；translator 那段「本策略有到期日、唯一出路是退場」的 Javadoc 隨之改寫。

  行為上：同一個 `(貨主, 倉)` 的事件落在同一個 partition；不同貨主落在不同 partition；**跨多個 SKU 的訂單也算得出 key 且不拋錯**。以 outbox 的 SIT 斷言 key 內容、以及一支「兩種策略下多 SKU 訂單都翻譯得出來且 key 不含 SKU」的單元測試驗證（取代原本三支圍著 per-SKU 限制轉的測試）。
- [x] 6.2 ~~`OrderAllocatedIntegrationEvent` 加批次清單~~ → **否決，改為把它瘦成通知型事件**：`(eventId, orderId, ownerId, allocatedAt)`，砍掉 `sku`、`quantity` 與批次清單。三個理由：**(a) 它描述會變的狀態**——取消會釋放那些預留，而取消事件與本事件在不同 topic、沒有順序保證，下游可能拿著一份描述已被釋放的預留的事件在做事；**(b) 那份資訊已經持久且可查**（`stock_reservations`，而 R4 正在其上建 `demand_lines` view），事件抄一份只是多一個會不一致的來源；**(c) 消費端還不存在**——履約層尚未設計，預先塞欄位是在猜它要什麼，而契約欄位加容易砍難。`ownerId` 例外保留：多租戶下游要能不查就判斷「這則跟我有關嗎」，且 `OrderPlacedIntegrationEvent` 已以同一理由帶了它。**附帶效果**：`publishAllocationCompleted` 裡以 `.get(i)` 對齊 picks 與 reservations 的那段（唯一目的就是組批次清單）隨之消失，連同一個以 record 當 HashMap key 的脆弱處。行為上：事件只說「這張單配好了、屬於誰、什麼時候」。以 outbox payload 斷言不含 `reservationId` 與 `batches` 驗證。

- [x] 6.3 **（review 期間新增）**把 ordering 側的三則對外事件一併瘦成只帶識別:四則訂單生命週期事件（`OrderPlaced` / `OrderCancelled` / `OrderAllocated` / `BackorderCreated`）統一收成 `(eventId, orderId, 時間戳)`。理由與 6.2 同一條規則,不套用會變成只做一半;而且消費端本來就靠 `orderId` 重讀整張單（`AllocateOrderCommand` 只收 orderId,是這個決定最強的證據）。**`ownerId` 與 `nodeId` 也不帶**——中途曾決定補上 `ownerId`,review 時推翻:這個 repo 裡沒有任何按貨主或按倉過濾的消費端,兩者都是為想像中的下游設計;而加欄位是非破壞性的、砍欄位是破壞性的,所以起點取最小。**附帶修掉一個 bug**:translator 的 Javadoc 宣稱 SKU 以 `Supplier` 延後求值,好處是「退回 order-id 策略後多 SKU 訂單真的跑得動」——但 payload 原本帶 `sku`,逼著在呼叫端 eager 呼叫 `requireSingleSku`,那個保證一直是假的。`LineSnapshot.totalQuantity` 隨之無人使用,刪除。行為上:多 SKU 的下單事件在 order-id 策略下能翻譯。以「把 eager 求值加回去、確認測試變紅」驗證。

## 7. Seed 資料

- [x] 7.1 依 **Seed data makes every allocation outcome reproducible**，為一個 SKU 種三個未過期的批（近／中／遠效期）**其中兩批同效期不同入庫日**，另加一批已過期。行為上：FEFO 的排序、tie-breaker 與「有貨但已過期」三件事都有資料可驗。以 SIT 斷言三者皆成立驗證——**少了同效期那兩批，tie-breaker 完全沒有被測到**。
- [x] 7.2 種一張需求跨兩批的訂單。行為上：多批取用與多筆預留在畫面與測試上都看得到。以 seed SIT 驗證。
- [x] 7.3 刪除 `DevSeedDataIntegrationTest` 中「每個庫存池的 SKU 都存在於主檔」那支測試——它驗的東西已由 1.2 的外鍵保證。行為上：測試數減一，而該保證更強（從只驗種子變成驗所有寫入路徑）。以測試通過驗證。

## 8. 既有測試的重新設計

- [x] 8.1 依 `hot-sku-concurrency-demo` 的 **Demonstrate a real optimistic-lock conflict**（該 requirement 新增的「競爭必須集中在單一庫存列」段落），重新設計 `AllocationHotSkuConcurrencyIntegrationTest`：庫存集中在單一批次，並**斷言它確實只有一列**。行為上：分批之後這支測試若讓庫存散在多列，競爭強度會大幅下降而測試仍然通過——那是最糟的失敗方式。以「把種子改成兩列、確認該測試失敗」驗證——已驗，失敗訊息為「熱點庫存必須只有一列，實際有 2 列——競爭已被分散」。
- [x] 8.2 重新設計 `AllocationFifoReplenishmentBatchIntegrationTest`：500 張的單次喚醒變成多輪續做，斷言由「一次補貨事件後的最終狀態」改為「續做收斂後的最終狀態」。**head-of-line blocking 的斷言必須保留**——那是這支測試存在的理由。SIT 沒有 Debezium，續做事件會停在 outbox，因此測試自己把它們餵回 consumer（`drainContinuations()`）——好處是**輪數變成可數的**，「會收斂」與「不會無限續做」兩件事都能斷言，後者靠一個硬上限，超過就失敗。喚醒上限寫在測試的 properties 而不是吃 production 預設值：預設值會隨壓測結果調整，那不該讓這支測試變色。以測試通過、blocker 仍卡住、以及「把上限拉大到不會續做→斷言失敗」三者驗證。
- [x] 8.3 重新設計 `AllocationConcurrencyEndToEndIntegrationTest`：它建立在「一個 SKU 一列」上，須改為批次前提。行為上：重試與 DLT 的既有斷言仍成立。以測試通過驗證。

## 9. 前端

- [x] 9.1 實作 `order-promising-http-api` 的 **Stock pool state is queryable by SKU**（改為帶貨主、回傳批次列表、含過期標示）與 `demo-console-frontend` 的 **Stock state and replenishment share one page keyed by SKU**：後端端點與前端畫面一起改，庫存查詢結果為批次列表：每列顯示倉別、入庫日、效期、三個數量與**是否已過期**，**依配貨會取用的順序排列**，過期的列標記而非隱藏。**不設「為什麼不能配」的欄位**——`expired` 與 `availableToPromise` 兩個欄位各講一件事，合起來就分得出「過期了」與「被預留光了」，第三個欄位只是轉述。行為上：畫面回答「哪一批會先出」與「這批是什麼狀態」。以前端測試涵蓋排序與過期標示驗證。**面板的敘事整段改寫**：原本有一段說明「補貨要選貨主、查詢不用」,理由是庫存尚未按貨主分開——那個不對稱消失了,現在兩邊都必填。`Catalog.skuCodes()`（全貨主去重）改為 `skuCodesOf(ownerId)`,理由同樣是原本那句「同碼 SKU 共用同一列」不再成立。批次列表另外把「已過期」與「已預留完」用兩個不同的標記分開——配貨眼中兩者都是配不到,但操作上一個要報廢、一個只是等出貨。
- [x] 9.2 補貨表單加倉別、入庫日、效期三個必填欄位，缺任一即在表單層擋下、不發請求。行為上：無效的補貨不會換來一次沒有必要的往返。以前端測試逐步補齊五個維度、每一步都斷言仍然擋著驗證。
- [x] 9.3 更新 `api/types.ts` 的庫存與補貨型別，並在檔頭註解補上本 change。行為上：下一個人判斷「後端合約變了要改哪」時看到的是最新的來源清單。以型別檢查與註解審閱驗證。

## 10. 端到端驗收與文件

- [x] 10.1 更新 `e2e/perf/run.sh` 的 `seed`：庫存要帶倉別、入庫日、效期。**熱點壓測的庫存必須在單一批次**，否則競爭分散、v1／v3 的對比失去意義。行為上：壓測訂單配得到貨且維持原本的競爭強度。以壓測 `checks_total` 全過驗證。**另加一道斷言**：seed 完成後查 `(貨主, 倉, SKU)` 的列數，不是 1 就直接失敗並要求先 `down` 重建。單靠固定 id 的 upsert 保證不了「沒有別人種的第二列」，而庫存一散開 checks 與 thresholds 仍然全過——那是最糟的失敗方式。已以「植入第二列、確認 seed 失敗」驗證。批次的 id 與兩個日期都固定（效期放到 2099）：日期若取「今天」，隔天重跑就會多出第二列。
- [x] 10.2 更新 `e2e/perf/k6/hot-sku-burst.js`：若腳本有補貨請求則加新欄位；下單不受影響。**腳本沒有補貨請求**（只有 `POST /orders` 與輪詢 `GET /orders/{id}`），所以這一項是 no-op——已查證，不是略過。唯一的改動是把 `BASE_URL` 預設值改成新的 host port。
- [ ] 10.3 依 design 的 Migration Plan 重建並重跑壓測：`./e2e/perf/run.sh down` 後 `perf`。行為上：既有 thresholds 全數通過。以本次結果更新 `e2e/perf/README.md` 的 baseline。

  **尚未通過，且刻意不勾。** 已跑過（`down` 重建 → 暖機一輪 → 正式一輪）：正確性的三條斷言全過——`checks` 100%、`order_allocated_total` 恰好 500（不超賣）、`order_decision_timeout_total` 為 0（1,000 張全部有決策）。掛掉的只有 `order_decision_latency_ms p(99) < 10s`，實測 11.2s，差 12%。

  **不採用那組數字、也不放寬門檻。** 量測時同一台機器的 load average 是 5.73，另外還跑著一個無關的 app、兩個編輯器、前端 dev server 與數個 agent——這個環境量不出可信的基準。而為了讓一次量測通過而動門檻，等於把迴歸偵測器關掉；那條門檻實際上是**吞吐量門檻的偽裝**（k6 輪詢間隔 100ms、1,000 個 VU 同時起跑，p99 延遲約等於整批排乾的時間）。

  失敗的那一輪連同理由記在 `e2e/perf/README.md` 的「一次失敗的量測」，以免下一個人重犯或誤以為門檻壞了。**要收掉這一項需要一台安靜的機器**：關掉其他負載、`down` 重建、暖機一輪、再量一輪。
- [x] 10.4 **把 `e2e/perf/README.md` 的 v1／v3 對比表標註為「partition key 改為三維之前量的」**。行為上：下一個讀那張表的人不會拿它與改動後的數字並列。要不要重測是獨立的決定（重測要跑完整的暖機方法論，見該檔）。以文件審閱驗證。
- [x] 10.5 核對 `docs/stock-reservation-design.md` 的 `stock_pools` 與 `stock_reservations` 欄位表、狀態轉換與核心流程三節與實作一致。行為上：文件與程式不分岔。以文件審閱驗證。
- [x] 10.6 **archive 前把 review 期間的六個決定回填 spec delta 與 proposal。** review 改了設計，delta 卻還寫著改之前的行為——archive 會把它們併進 `openspec/specs/`，那時規格就會以權威的姿態說錯話（R1 的 `owners.status` 正是這樣進去的）。逐份核對後修正五處與實作不符：

  1. `demo-only-probes` 說 record key 是「貨主、倉與 SKU 相接」且策略叫 `sku`——key 已不含 SKU、策略已改名 `stock`。補上「必須與 ordering 端逐位元相同」與為什麼不能含 SKU。
  2. `outbox-event-delivery` **同一份檔案自我矛盾**：requirement 正文說「命名為 `sku` 會讓名字變成謊言所以改名」，下面的 scenario 卻寫 `GIVEN ... is \`sku\``；範例表的 `partition_key` 也還是 `<owner>/<node>/HOT-SKU`。
  3. `fifo-replenishment-demo` 說「佇列還有更多時就續做」——那正是被修掉的 bug。實際條件是「本輪**配到**的張數達上限才續做」，兩者只在 blocker 卡住時分歧，而那是唯一會無限循環的情形。改為明寫「數配到的、不數讀到的」，並補一個 blocker 場景。續做事件經 translator 發出這件事也一併寫進去。
  4. `proposal.md` 的 BREAKING 清單有兩處過時：策略值與 key 組成、以及「`OrderAllocatedIntegrationEvent` 只帶 `orderId`、`ownerId`、`allocatedAt`」——最終決定是四個生命週期事件全部只帶識別碼與時間戳，`ownerId` 也拿掉了。
  5. `order-promising-http-api` **根本沒寫排序保證**，而 `demo-console-frontend` 卻說「依配貨會取用的順序排列」並刻意不重排——前端契約性地依賴一個後端沒承諾的性質。實際順序是 `(倉別, 效期, 入庫日, id)`：配貨一次只在一個倉裡進行，所以跨倉依效期混排會顯示一個永遠不會發生的取用順序。兩份都補上，並說明為什麼這是端點的保證而非呼叫端的責任（tie-break 一路到 id，呼叫端排不出來）。

  **第 5 項的排序當時沒有任何測試蓋著**（`returnsEveryBatchAcrossNodesForTheStockPage` 只斷言 `hasSize(3)`，且三批同倉），所以補了 `groupsStockPageBatchesByNodeThenFefoWithinEachNode`：兩個倉各兩批、以與期望完全相反的順序寫入。已以植入違反驗證——把 `nodeId` 從排序鍵拿掉後結果變成兩倉依效期交錯（`[3,5,2,4]` 而非 `[3,2,5,4]`），測試確實失敗。
