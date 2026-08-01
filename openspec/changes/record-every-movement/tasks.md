## 0. 遷移的形式（沿用前一個 change）

本專案未上線，只有本機開發與測試容器，因此**改寫既有 migration 檔案為最終形狀**。逐檔改動
見 design 的「遷移改寫既有檔案」與「逐檔的改動」。

- [ ] 0.1 移除既有的 Postgres volume 並確認可從空資料庫重建。**不得以 `flyway repair` 略過 checksum 不符。**

  `V4` 整檔改寫，checksum 必然不符。作法與前一個 change 相同：`docker compose -f e2e/perf/docker-compose.yml rm -sfv postgres` 後 `up -d postgres`。

## 1. 搬運的表

- [ ] 1.1 依 `stock-movement` 的 **An operation type says where its movements run between**，把 `V4__create_stock_reservations.sql` 改寫為 `V4__create_stock_movements.sql`，其中建 `stock_picking_types`：`id`、`warehouse_id`、`code`（`INBOUND`／`OUTBOUND`／`INTERNAL`，CHECK 約束）、`name`、`default_from_location_id`、`default_to_location_id`。**不加 `sequence_code`**——本系統不產生單號，那一欄沒有讀者。行為上：code 超出集合時由資料庫拒絕。以 schema 整合測試驗證。

  這張表是「新增一種流程 = 新增一筆資料」的落腳處。它要擋的是**流程種類被編碼成 enum 值**那個病——既有系統的出庫類型長到 18 種、狀態欄位長到 28 個值，就是那樣累積的。

  **但它不說「下一段是誰」。** 作業類型之間唯一的關聯是退貨對應；把多段串起來是另一回事，而本系統沒有多段。

- [ ] 1.2 依 **A dispatch document is not shared between orders**，以及 design 的決策「picking 與 move 的職責分界」、「picking 不帶 state，狀態由底下的 move 彙總」與「一張單一張 picking，不跨單合併」，建 `stock_pickings`：`id`、`picking_type_id`、`owner_id`、`from_location_id`、`to_location_id`（**皆 NOT NULL**）、`reference`、`scheduled_at`。**不加 `order_id`、不加 `state`、不加 `version`。**

  picking 是**倉庫任務的真相**，move 是**庫存數量的真相**。picking 因此沒有 SKU、沒有數量、不直接影響庫存——它只說「這一趟作業從哪到哪、屬於誰」。

  **不加 `order_id`**：「這張單據服務哪張訂單」由它底下 move 的 `order_line_id` 推導。Odoo 的 `stock_picking.sale_id` 是追溯用的捷徑，而 `sale_line_id` 才是精確來源；只取精確的那一個，捷徑會成為第二個真相。

  **不加 `state`**：Odoo 的 `stock.picking.state` 是由底下 moves 算出來的 computed 欄位，store 只為了畫面篩選。ship-complete 下一張單的所有 move 同進同出，彙總是 trivial 的，而存起來就有兩份要對齊的真相——目前也沒有任何查詢需要它。

  **不加 `version`**：沒有併發寫入 picking 的路徑。庫存的樂觀鎖在 `stock_pools` 上，那才是會被搶的東西。

  **日後若要跨單合併，分組鍵必須含貨主。** Odoo 的分組鍵是 `(來源參照, 起點, 終點, 作業類型)`，不含貨主——它的隔離維度是法人。照抄會讓兩個貨主的 move 併進同一張單據，而「一個倉服務多個貨主」正是 3PL 的定義性特徵。

- [ ] 1.3 依 **Every movement of goods is recorded with both of its ends**，建 `stock_moves`：`id`、`picking_id`、`owner_id`、`sku_code`、`from_location_id`、`to_location_id`（**皆 NOT NULL**）、`order_line_id`（**可空**）、`demand_quantity`、`state`、`version`。行為上：缺任一端時寫入被拒絕。以 schema 整合測試驗證。

  `order_line_id` 可空是必要的：第三個 change 的入庫 move 背後沒有任何訂單行。現在就可空，比屆時放寬乾淨——放寬一個 NOT NULL 要同時處理既有列。

  **不加 `previous_move_id`。** 它看起來便宜，但會把線性假設鎖進 schema：一筆補貨支撐多個下游、多來源匯入一個下游、拆分與部分完成，任何一個出現就得把所有既有的鏈重建。串接真的出現時建關聯表。

- [ ] 1.4 依 **Movement state covers only transitions this system performs** 與 design 的決策「move 的狀態只取四個」，`stock_moves.state` 以 CHECK 約束為 `CONFIRMED`／`ASSIGNED`／`DONE`／`CANCELLED`。行為上：值域外的狀態被資料庫拒絕。以 schema 整合測試驗證。

  **`DONE` 現在沒有產生者，但必須在值域裡。** 任務 2.2 的謂詞是「這條行有沒有 move」，而已完成的 move 也算有——少了它，R7 每一張已出貨的單都會重新變成待接手的需求，**而那一刻不會有任何測試失敗**。這與 `stock_reservations.CONSUMED` 當初的判斷相同。

  **不取 `WAITING`。** 它的意思是「等上一段」，而沒有上一段。它與依賴關係表是同一件事的兩半，一起到來。

- [ ] 1.5 依 **Every movement of goods is recorded with both of its ends** 的第二個 scenario，以及 design 的決策「`stock_move_lines` 直接指向庫存列」，建 `stock_move_lines`：`id`、`move_id`、`stock_pool_id`、`quantity`。**不帶自己的狀態。**

  外鍵直指庫存列，不像 Odoo 靠 `(商品, 位置, 批號, 包裝, 貨主)` 隱式配對——那個做法的前提是有批號表，而本系統的批次身分在 `(入庫日, 效期)` 裡，必須指名。

  不帶狀態，是因為它的狀態就是所屬 move 的狀態。多一個欄位等於多一組要對齊的真相。

- [ ] 1.6 **`stock_pickings` 與 `stock_moves` 的位置欄位不加 internal-only 約束**，只有 `stock_pools` 維持。這一項不寫程式，只在 migration 檔頭記下理由。

  前一個 change 才剛為 `stock_pools` 加過「只能指向 `INTERNAL` 位置」，照著複製到搬運上會**擋掉出庫本身**——出庫的目的地就是 `CUSTOMER` 這個虛擬位置。

  兩者的差別是：庫存是「公司持有的東西」，只存在於內部位置；搬運是「東西的移動」，兩端本來就可能在公司之外。

- [ ] 1.7 依 design 的決策「刻意不取的欄位」，逐項核對 Odoo 兩張表上有而本 change 不取的欄位，並把那份對照寫進 `V4` 的檔頭。

  下一個拿 Odoo schema 來比對的人會逐欄問「為什麼沒有」。寫下來的價值在於區分三種「沒有」：**還沒做**（波次、退貨、分批）、**做法不同**（貨主的隔離在庫存列的鍵上，不在 `restrict_partner_id`）、以及**定位不同**（`price_unit` / `value`——3PL 不擁有貨，永遠不對它持有的東西估值）。第三種最容易被當成遺漏而「補上」。

## 2. 收單即建 move

- [ ] 2.1 依 `stock-movement` 的 **Demand that has no stock yet is a movement, not an absence**，以及 design 的決策「收單即建 move，不是配到才建」，`AllocateOrderUsecase` 收到 `OrderPlaced` 時為每一條行建一個 `CONFIRMED` 的 move 與一張 picking，**即使當下一件貨都沒有**。行為上：無庫存時仍有 move，狀態為 `CONFIRMED`。以 usecase 測試與端到端測試驗證。

  替代方案是「配到貨才建 move」。**否決**：待配需求就沒有落腳處，只能繼續由 view 推導，而本 change 的目的正是讓「還在等貨」變成一列真實資料。Odoo 也是這個順序。

  代價是「永遠配不到的單」會留下一個永遠 `CONFIRMED` 的 move——**那是優點**：現在那種單在系統裡沒有痕跡，要靠比對訂單與預留的差集才看得出來。

- [ ] 2.2 依 `stock-allocation` 的 **Outstanding demand is decided by whether a movement exists**，以及 design 的決策「`demand_lines` 不廢除，換用途」，改寫 `V6__create_demand_lines_view.sql` 的 `NOT EXISTS` 謂詞：由「無 ACTIVE／CONSUMED 預留」改為「無 move」。`WHERE o.cancelled_at IS NULL` **一字不動**。

  檔頭那兩段註解要改寫而不是刪掉：「刻意不含 `ol.status`」那段的**理由消失了**（執行層讀的是自己寫的資料，沒有時間差），要說明它為何消失；`CONSUMED` 那段換成 `DONE`，警告的內容不變。

  **這是本 change 最容易靜默出錯的一步**。前一個 change 補的行為測試（「取消的單不得被補貨喚醒」）守著 `cancelled_at`；`DONE` 那一半目前沒有產生者，因此沒有測試守得住——只能靠謂詞現在就寫對。

- [ ] 2.3 `DemandRepository` 的兩個查詢改用途：`findByOrderId` 保留（收單時讀一張單的行），`findOutstandingDemandInFifoOrder` **移除**——佇列改由 move 回答（任務 3.2）。

## 3. 配貨改產生 move

- [ ] 3.1 依 **Allocation satisfies movements, not orders directly**，`OrderAllocationCoordinator` 配到貨時把 move 轉 `ASSIGNED` 並寫出 `stock_move_lines`，取代原本建立 `StockReservation`。**取用計畫、FEFO 取批、整籃判斷、寫入排序一律不變。**

  寫入排序（`WRITE_ORDER`）是防死鎖的依據，而它依賴「事先知道會碰哪些列」。換掉的是寫出什麼，不是寫入的順序——**任何需要改動排序的實作都代表走偏了**。

- [ ] 3.2 依同一 requirement，新增「取 `CONFIRMED` 的 move」查詢取代 `findOutstandingDemandInFifoOrder`：以 `(貨主, 位置, SKU)` 篩選、以原本的 FIFO 排序鍵排序、帶張數上限。`ReplenishmentUsecase` 改用它。行為上不變。既有的 `AllocationFifoReplenishmentBatchIntegrationTest` 全綠且**斷言不改**。

  排序鍵與上限都不動：上限以張數計、終止條件是「本輪實際配到的張數 < 上限」，那兩件事的理由與資料從哪來無關。

- [ ] 3.3 `AllocateOrderUsecase` 的下單即配路徑改為「建 move → 立刻嘗試配」。行為上：有貨則 move 為 `ASSIGNED`、無貨則停在 `CONFIRMED`。以 usecase 測試驗證兩條路徑。

## 4. 釋放與取消

- [ ] 4.1 依 **Reservation is a stage of a movement, not a parallel ledger** 與 design 的決策「預留是 move line 的存在，不是它的狀態」，`ReleaseReservationUsecase` 改為：把 move 轉 `CANCELLED`、**刪除它的 move line**、並把量還給庫存列。行為上：釋放後該批的可承諾量回到釋放前。以 usecase 測試與端到端測試驗證。

  **釋放是刪除明細，不是標記為已釋放。** 一條被釋放的預留不表達任何事實——貨沒動、也沒被鎖住。留著它等於讓每個讀取端都要記得過濾，而舊 view 的 `status IN ('ACTIVE','CONSUMED')` 正是那個負擔的具體形式。

  代價要寫進程式碼的註解：**釋放的歷史不再留在明細上**，它留在 move 的狀態轉換上。

- [ ] 4.1a 移除的兩條 requirement 各自的遷移責任要走過一遍：**A reservation is held per order line and per stock row** 的粒度（行 × 批）必須在 `stock_move_lines` 上原樣成立；**Outstanding demand is decided by reservations, not by an order's status** 的四個 scenario 逐條對照新謂詞，確認每一條都有對應的新行為。

  被移除的 requirement 最容易連同它守的性質一起消失。粒度那一條尤其——「一筆預留內含批次清單」當初被否決的理由（釋放與消耗都逐批發生）在 move line 上完全相同。

- [ ] 4.2 `StockReservation`、`ReservationStatus`、`StockReservationRepository(+Impl)`、`StockReservationEntity`、`StockReservationMapper`、`JpaStockReservationRepository` **全部移除**。

  留著一個沒有寫入者的型別，下一個人會以為它還有用途。

## 5. 護欄與驗證

- [ ] 5.1 依 design 的決策「那條邊界護欄要換，不是開例外」，改寫 `AllocationBoundaryArchitectureTest` 的 `ORDERING_TABLE_NAME` 規則：allocation 可以出現 `order_line_id`，但不得出現 `order_lines` 的**其他欄位名**，也不得 join 它。

  **不要在舊規則上加例外。** 那支測試的註解寫著「這條規則不需要為讀取開任何例外」——開了第一個，它就從硬性約束退化成裝飾。邊界的性質變了（從「不知道對方存在」變成「持有對方的 id」），要換的是規則本身。

- [ ] 5.2 `AllocationFifoGuaranteeScopeIntegrationTest`、`AllocationHotSkuConcurrencyIntegrationTest`、`AllocationFifoReplenishmentBatchIntegrationTest` 全綠且**不得修改任何斷言**。

  它們守的是 FIFO 保證的範圍、熱點 SKU 的序列化與批次上限，三者都與「配貨的產出是預留還是 move」無關。**任何一個需要改斷言的情形都代表這個 change 動到了不該動的東西。**

- [ ] 5.3 前一個 change 補的「取消的單不得被補貨喚醒」必須全程綠且斷言不改。

  它守的是行為而不是機制，正是為了跨過這次的機制更換——**這是它存在的理由被兌現的一刻**。

- [ ] 5.4 前端不做任何修改，`frontend` 既有測試全綠；Kafka 事件與 REST 契約一個欄位都不改。

- [ ] 5.5 更新 `docs/dom-stock-movement-scope.md`：把「四個 change 的順序」表中第二列標記為已交付。
