## Context

`docs/dom-stock-movement-scope.md` 記錄了一個跨四個 change 的決定：庫存從「一個可被加減的
數字」改成有來源與目的的異動流水，需求與執行分成兩層。這是四個之中的第一個。

它本身不引入任何搬運——`stock_pickings` / `stock_moves` 是下一個 change。它做的是為搬運準備
端點：**搬運需要來源與目的，而倉當不了那兩個端點。**

現況：`stock_pools` 以 `(owner, node, sku, in_date, expiry)` 為身分，`node` 指
`fulfillment_nodes`。`demand_lines` view 把 `orders.fulfillment_node_id` 輸出成 `node_id`，
配貨查詢按倉取批。倉這個維度貫穿整條路徑。

## Goals / Non-Goals

**Goals**

- 庫存的所在由倉換成位置，位置的值域一次定義完整
- 虛擬位置存在且可被指向，即使這個 change 還沒有東西指它們
- 配貨行為、FIFO 保證、樂觀鎖語意完全不變
- 對外契約（Kafka 事件、REST、前端）不變

**Non-Goals**

- 不引入 `stock_picking_types` / `stock_pickings` / `stock_moves` / `stock_move_lines`
- 不動 `orders` / `order_lines` 的任何欄位
- 不做位置樹、不做 `stock_lot`、不做 `stock.route` / `stock.rule`
- 不改任何型別、表名或端點的命名——連 `stock_pools` 也不改

## Decisions

### `orders` 不碰位置

下單只決定倉。位置屬於執行層，而執行層在下一個 change 才出現。

**這解掉了一個曾經卡住的問題。** `orders` 上有一條複合外鍵：

```sql
FOREIGN KEY (owner_id, fulfillment_node_id) REFERENCES owner_nodes(owner_id, node_id)
```

`V2` 的註解說明它為什麼是複合的——它讓「倉存在，但這個貨主沒掛這個倉」由資料庫擋下，而不是
只擋得住「倉不存在」。**位置不帶貨主，接不上這條外鍵，而外鍵不能跨兩跳。**

曾考慮讓 `orders` 同時帶倉與位置、再用第二條複合外鍵綁死一致。**不必了**——`Order` 是需求
不是搬運單據（scope 文件的決定一），它本來就不該有位置。

### 對外說倉，對內說位置

Kafka 事件與 REST 契約繼續帶 `nodeId`；系統在邊界上把它解析成該倉的 `internal` 位置。

**不是為了少改東西。** 理由是職責：貨主的 ERP 說「這批貨進北部倉」，它不知道也不該知道北部
倉裡分成收貨區、庫存區、出貨暫存區——那是倉庫內部的作業編排。一旦上游契約帶了位置，倉庫要
新增一個內部位置就變成上游的 breaking change。

**代價**：邊界上多一次解析（倉 → 該倉的 `internal` 位置）。一倉一位置時它是一次查表；日後
一倉多位置時，「解析成哪一個」會需要規則，而那個規則就是下一個 change 的 `picking_type`。

### `demand_lines` view 解析位置，不是讓配貨自己解析

view 加一個 join 到 `stock_locations`，輸出 `location_id` 取代 `node_id`。

替代方案是 view 繼續輸出 `node_id`，由 `StockPoolRepository` 在查詢前解析。**否決**：那會讓
allocation 同時認識倉與位置兩套詞彙，而它只需要一套。view 是 ordering 與 allocation 之間的
介面，解析放在介面上，兩邊各自只說自己的語言。

`WHERE` 的兩個既有謂詞（`cancelled_at IS NULL`、無 ACTIVE／CONSUMED 預留）**一字不動**，檔頭
那兩段註解一併保留。

### 虛擬位置現在就建，但欄位不預留

兩件事看起來都是「為未來準備」，判斷卻相反：

| | 現在做 | 理由 |
| --- | --- | --- |
| 虛擬位置三列（`supplier`／`customer`／`inventory`） | **做** | `usage` 的值域必須一次定完，否則下一個 change 要改 CHECK 約束並回頭補種子資料。而**參考資料多一列的成本是零**——沒有讀取端需要處理它 |
| 任何為下一個 change 預留的欄位 | **不做** | **欄位多一個的成本是每一個讀取端都要處理它**。`orders.requested_node_id` 是前車之鑑：帶著「R6 才讀」的註解存在了一整個 change，而 R6 沒有發生 |

分界是：**新增一列資料不強迫任何人面對它，新增一個欄位會。**

### `usage` 是 CHECK 約束，不是外鍵到分類表

四個值 `internal` / `supplier` / `customer` / `inventory`，直接寫進 CHECK。

分類表能讓值域可設定，但這個值域不是設定——它是**程式邏輯的分支**：只有 `internal` 算公司
庫存。可設定的值域會讓「新增第五種 usage」看起來是資料維護，實際上每個分支都要跟著改。

Odoo 也是 selection 而非關聯表，理由相同。

### 一個倉一個 `internal` 位置，位置不成樹

`stock_locations.warehouse_id` 指 `fulfillment_nodes`，虛擬位置的該欄為 `NULL`。

**`warehouse_id` 是實體欄位，不是查詢時沿樹算。** Odoo 19 的 `stock.location.warehouse_id`
是 computed 且 `store=True`，子樹查詢靠 `parent_path` 物化路徑加 `LIKE` 前綴而非遞迴 CTE。
我們不做樹，所以連 `parent_path` 都不需要——`warehouse_id` 直接就是答案。

不加 `parent_id`。樹的用途是儲區階層與「沿樹往上找到所屬倉」，而本系統一倉一位置，沒有查詢
會沿樹走。**日後要加 Input／Output 時，`orders` 已經指倉、`stock_pools` 已經指位置**——兩者
都不用動，只是多幾列位置加上一個 `parent_id`。

### 貨主與倉的指派留在倉層

`owner_nodes` 不動。「這個貨主在哪些倉有貨」是**商業關係**——它在任何貨進倉之前就成立，這正是
`warehouse-catalog` 那條 requirement 說的「SHALL be recorded explicitly rather than inferred
from where that owner happens to hold stock」。

改指位置會讓一倉多位置時無法回答「掛五筆還是只掛庫存區」，而兩個答案都不對。

### 表名保留 `stock_pools`，語意對齊 `stock.quant`

**不改名。** 現行的兩份文件對這個名字的評價本來就不一致，而後寫的那份是對的：

| 出處 | 說法 |
| --- | --- |
| `V3__create_stock_pools.sql` 檔頭 | 「名字與內容不符是刻意保留的」 |
| `docs/dom-order-intake-scope.md:219` | 鍵擴成五維之後，`pool` **「一樣準」**——因為 pool 的定義是「一群可互換的單位」 |

**而那正是 `stock.quant` 的定義。** Odoo 的 quant 不是批次表（批次身分另立 `stock.lot`），
它就是「某個位置上一群可互換單位的餘額」。兩個詞指同一件事，改名換到的只有詞彙來源。

因此這個 change 不製造名實不符的債，反而要**修掉 `V3` 檔頭那句過重的話**——它寫於 R3，當時
比較的對象是 `stock_batches`（那個確實不準），結論被過度推廣成「現在這個名字也不對」。

### 欄位對齊到什麼程度

逐欄比對之後，只有一欄與 Odoo 不同名，而它**不改**：

| 本系統 | Odoo `stock_quant` | 判斷 |
| --- | --- | --- |
| `owner_id` | `owner_id` | 相同 |
| `node_id` → `location_id` | `location_id` | 改（本 change 的主體） |
| `sku_code` | `product_id` | 自然鍵風格與其餘各表一致，不動 |
| `in_date` | `in_date` | **Odoo 也是這個名字**，同樣用於取貨排序 |
| `expiry_date` | （在 `stock_lot`） | 不做 lot，留在本列 |
| `reserved_quantity` | `reserved_quantity` | 相同 |
| `on_hand_quantity` | `quantity` | **保留本系統的**。Odoo 該欄的 UI 標籤是 "Quantity On Hand"——欄位名是簡稱、語意是在手量。而它旁邊就是 `reserved_quantity`，裸的 `quantity` 會變成「什麼的數量」 |

**對齊要做的因此不是改欄位，是把對應寫下來。** 少了那份對照，下一個人會在引入 `stock_moves`
時重新推導一次「我們的 pool 對應到 Odoo 的什麼」，而推導出 `stock.lot` 是很自然的錯誤。

領域類別 `StockPool` 同樣保留，只有欄位 `nodeId` → `locationId`。

### 命名收斂集中在第四個 change

這個 change 一個型別名都不改。以下留到第四個 change：`allocation` package → `inventory`、
`BACKORDERED` 改由執行層狀態承接。兩者都會動到前端與對外契約，分散在四個 change 裡改等於
前端被改四次。

**`/stock-pool` 端點與 `StockPoolResponse` 則是永久保留**，不在第四個 change 的清單裡。表名
既然站得住，端點名就沒有債要償。

### FEFO 排序與樂觀鎖都不變

`idx_stock_pools_fefo` 的欄位順序 `(owner, node, sku, expiry, in_date, id)` 只有第二欄換成
`location`，其餘不動——等值篩選在前、排序鍵其次、`id` 作為最後的 tie-breaker，這個結構的理由
（`V3` 檔尾）與位置無關。

`version` 欄位原封不動。`stock_pools` 仍是**物化餘額**而非 `SUM(moves)`——Odoo 的 quant 也是
如此。因此 `docs/dom-promising-scope.md`「決定二」（補貨與喚醒共用同一批庫存列的樂觀鎖，這是
FIFO 的實作機制而非效能取捨）完全不受影響。

**護欄**：`AllocationFifoGuaranteeScopeIntegrationTest` 與
`AllocationHotSkuConcurrencyIntegrationTest` 必須在這個 change 前後都綠，且不修改斷言——它們
守的性質與位置無關，任何一個需要改斷言的情形都代表這個 change 動到了不該動的東西。

## 遷移改寫既有檔案，不以 ALTER 疊加

`V2__create_ordering_tables.sql` 的檔頭記錄過同一個判斷：

> 此 schema 尚未部署至任何環境，因此改寫為最終形狀而非以 ALTER 疊加——否則 migration 歷史
> 會記錄一段「建了又砍」的假歷史。

**這個前提仍然成立**——本專案是 demo，未上線，只有本機開發與測試容器。

代價與當時相同：**既有的 Postgres volume 必須移除後重建**（Flyway checksum 不符），
**不得以 `flyway repair` 略過**。

### `stock_locations` 建在 `V2` 而不是新的 `V7`

依賴鏈是 `fulfillment_nodes` → `stock_locations` → `stock_pools`(V3)。`stock_locations` 必須
排在 `V3` 之前，而 `V2` 是建 `fulfillment_nodes` 的檔案——放進 `V2` 緊接其後，順序自然成立。
另開 `V7` 則會讓被指向方排在指向方後面，而那正是 `V2` 檔頭說「不可任意調換」的那個順序。

位置與倉同屬參考資料，放在一起也讀得通——`V2` 的檔頭描述從「七張表」改為八張。

### 逐檔的改動

| 檔案 | 改動 |
| --- | --- |
| `V2__create_ordering_tables.sql` | 新增 `stock_locations`（緊接 `fulfillment_nodes`）。**`orders` 與 `order_lines` 一個字都不動** |
| `V3__create_stock_pools.sql` | **表名與檔名都不動。** `node_id` → `location_id`、unique 與 FEFO index 跟著改、加 `internal` 約束；檔頭補上與 `stock.quant` 的對照，並修掉「名字與內容不符」那句 |
| `V4__create_stock_reservations.sql` | **不動**（指向 `stock_pools` 的外鍵仍然成立） |
| `V5__create_event_inbox_and_outbox.sql` | 不動 |
| `V6__create_demand_lines_view.sql` | join `stock_locations`，輸出 `location_id` 取代 `node_id`。**檔頭那兩段註解與 `WHERE` 的謂詞一字不動** |
