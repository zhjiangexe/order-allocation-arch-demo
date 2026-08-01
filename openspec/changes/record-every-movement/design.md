## Context

`docs/dom-stock-movement-scope.md` 記錄的四個 change 之中的第二個。第一個把庫存的所在換成
位置並建好虛擬位置；那三個虛擬位置至今**沒有任何讀者**，因為還沒有東西會移動貨。

現況：配貨的產出是 `stock_reservations`——「這條行從這一批鎖了多少」。它記了搬運的一半，缺
的另一半是「那批貨要去哪」。待配需求則由 `demand_lines` view 從訂單與預留 join 推導。

## Goals / Non-Goals

**Goals**

- 每一段搬運有單據、有起訖、有狀態
- 待配需求由 move 的狀態回答，不再由推導得出
- 預留成為搬運的一個階段，而不是平行的另一本帳
- `orders` / `order_lines` 與所有對外契約不變

**Non-Goals**

- 不做入庫方向的 move（第三個 change）
- 不做 move 之間的串接、不做 `stock_move_dependencies`、**也不加 `previous_move_id`**
- 不做 `stock.route` / `stock.rule`（獨立階段）
- 不動 `OrderStatus`、不改任何型別的 package 位置（第四個 change）

## Decisions

### `demand_lines` 不廢除，換用途

scope 文件原本寫「view 廢除」。**那個結論是錯的**，理由是收單的資料怎麼過界：

`OrderPlacedIntegrationEvent` 只帶 `(eventId, orderId, receivedAt)`——`EventSeparationTest`
釘死了「配貨結果事件是通知，不是狀態傳輸」。所以執行層要知道一張單有哪些行，**只能讀**。
而反方向（ordering 直接寫 `stock_moves`）是邊界的反面，更糟。

於是 view 留著，但角色改變：

| | 現在 | 之後 |
| --- | --- | --- |
| 回答 | 還欠什麼 | 哪些行**還沒有 move** |
| 謂詞 | `NOT EXISTS` 有效預留 | `NOT EXISTS` move |
| 消費者 | 配貨佇列與收單 | **只有收單** |
| 配貨佇列 | 同上 | `stock_moves.state = 'CONFIRMED'` |

**兩個佇列，語意各自清楚**：view 回答「哪些需求還沒被接手」，move 的狀態回答「哪些搬運還在
等貨」。前者是跨界的投影，後者是執行層自己的資料。

順帶消掉一個長期的別扭：view 的檔頭花一整段解釋「刻意不含 `ol.status`，因為 ordering 的狀態
落後於 allocation 的決策」。改成看 move 之後，**執行層讀的是自己寫的東西**，那個時間差不存在。

### 收單即建 move，不是配到才建

`OrderPlaced` 進來時，為每一條行建一個 `CONFIRMED` 的 move——**即使當下一件貨都沒有**。

替代方案是「配到貨才建 move」，那樣 move 只記錄成功的搬運。**否決**：待配需求就沒有落腳處，
只能繼續由 view 推導，而本 change 的目的正是讓「還在等貨」變成一列真實資料而不是一個查詢的
副產物。Odoo 也是這個順序——`_run_pull` 在確認時就建 move，它停在等庫存的狀態。

代價是「一張永遠配不到的單」會留下一個永遠 `CONFIRMED` 的 move。那是**優點**：現在那種單
在系統裡沒有任何痕跡，只能靠比對訂單與預留的差集才看得出來。

### move 的狀態只取四個

| 狀態 | 意思 | 本 change 有產生者嗎 |
| --- | --- | --- |
| `CONFIRMED` | 要貨，還沒拿到 | ✅ 收單時 |
| `ASSIGNED` | 貨已鎖定 | ✅ 配到時 |
| `CANCELLED` | 這段不做了 | ✅ 取消時 |
| `DONE` | 貨真的動了 | ❌ **R7 才有** |

`DONE` 現在就進值域，理由與 `stock_reservations.CONSUMED` 相同：**下游的謂詞要現在就寫對**。
`demand_lines` 的新謂詞是「沒有 move」，而已完成的 move 也算「有」——少了 `DONE`，R7 每一張
已出貨的單都會重新變成待接手的需求，**而那一刻不會有任何測試失敗**。

**不取 `WAITING`。** 它的意思是「等上一段完成」，而沒有上一段。它隨串接一起到來——那時同時
需要依賴關係表，兩者是同一件事的兩半。

**不取 `PARTIALLY_AVAILABLE`。** ship-complete 下一張單整批配到或整批不配，部分可用不是一個
會停留的狀態。

### picking 與 move 的職責分界

兩者不是「單頭與明細」那麼簡單——它們是**兩種真相**：

| | `stock_pickings` | `stock_moves` |
| --- | --- | --- |
| 一句話 | 一張倉庫作業單 | 作業單中一個 SKU 的一段移動 |
| 是誰的真相 | **倉庫任務** | **庫存數量** |
| 有 SKU 與數量嗎 | 沒有 | 有 |
| 直接影響庫存嗎 | 否，透過底下的 move | **是** |
| 適合查什麼 | 今天要出的作業、某波次、某客戶的交貨 | SKU 缺貨、預留量、某訂單行、庫存流向 |

**本 change 取用的欄位**（其餘見下方「刻意不取」）：

| picking | move |
| --- | --- |
| `picking_type_id` | `picking_id`（可空） |
| `owner_id` | `owner_id` |
| `from_location_id` / `to_location_id`（整單共同範圍） | `from_location_id` / `to_location_id`（該 SKU 實際起訖） |
| `reference`（上游單號，文字） | `order_line_id`（**精確的需求來源**） |
| `scheduled_at` | `sku_code`、`demand_quantity` |
| — | `state` |

**訂單的連結兩層都有**，與 Odoo 19 的實際 schema 一致：`stock_picking.sale_id` 是單頭的
連結、`stock_move.sale_line_id` 是行的連結，而 `stock_move` **沒有** `sale_id`。

它在 picking 上不是捷徑：捷徑之所以危險是因為 Odoo 會跨單合併，單頭與底下的 move 可能分屬
不同的單；本系統不合併，一張出庫單就是一張 picking。而它是必要的——配貨要發帶 `orderId` 的
結果事件，而 move 只有 `order_line_id`，從行推到單得 join `order_lines`，那是邊界禁止的。

**但 ship-complete 的分組不用它，用 `picking_id`。** 單表 group by，熱路徑（補貨喚醒佇列）
因此一個 join 都沒有；`order_id` 只在配到之後發事件時才查。`picking_id` 也是更自然的分組
鍵——picking 的意思就是「這些 move 是同一份工作」。

### picking 不帶 state，狀態由底下的 move 彙總

Odoo 的 `stock.picking.state` 是 computed——由底下 moves 算出來，只是為了畫面篩選而 store。

本系統**不存**。ship-complete 下一張單的所有 move 同進同出，彙總是 trivial 的；而存起來就有
兩份要對齊的真相，且沒有任何查詢需要它（操作台目前不顯示 picking）。

要存的那天是「picking 列表要能依狀態篩選」，而那是畫面的需求，屆時它是一個 computed 欄位而
不是一份獨立寫入的狀態。

### 一張單一張 picking，不跨單合併

Odoo 把 move 併成 picking 的鍵是 `(reference, 起點, 終點, 作業類型)`——**不同訂單的 move
只要起訖與作業類型相同就會併進同一張 picking**。本 change 不合併：一張出庫單產生一張 picking。

**若日後要合併，分組鍵必須含貨主。** Odoo 的不含（它的隔離維度是法人不是貨主），照抄會讓兩個
貨主的 move 併進同一張 picking。這一條寫下來而不是現在實作——在需要合併之前，它是一個沒有
讀者的規則。

### 刻意不取的欄位

Odoo 兩張表上有的、本 change **不取**的，各有理由——寫下來是為了讓下一個拿 Odoo schema 來
比對的人不必重推：

| 欄位 | 在哪 | 不取的理由 |
| --- | --- | --- |
| `user_id` 作業員 | picking | 沒有現場作業的概念，也沒有人要被指派 |
| `batch_id` 波次 | picking | 波次管理的是作業單，而本系統還沒有作業 |
| `backorder_id` | picking | 分批出貨屬 R7 |
| `return_id` / `origin_returned_move_id` | 兩者 | 沒有退貨流程 |
| `printed` / `signature` / `is_locked` | picking | 沒有作業文件 |
| `move_type`（`direct` / `one`） | picking | ship-complete 恆等於 `one`。一個永遠同值的欄位沒有讀者 |
| `priority` | 兩者 | 沒有優先序政策，FIFO 是唯一的排序 |
| `sequence_id` 單號序列 | picking type | 不產生單號。`reference` 存上游給的參照就夠 |
| `procure_method` | move | 沒有 MTO——沒有「缺貨就往上游要」這條路 |
| `rule_id` / `route_ids` | move | 不做 route/rule |
| `move_orig_ids` / `move_dest_ids` | move | 沒有串接。它與依賴關係表一起到來 |
| `restrict_partner_id` | move | 在 Odoo 19 是死欄位——預留路徑從未讀它。貨主的隔離在庫存列的鍵上 |
| `price_unit` / `value` / `account_move_id` | move | **3PL 不擁有貨，永遠不對它持有的東西估值**。那是貨主帳上的事 |
| `scrap_id` / `is_inventory` | move | 沒有報廢與盤點 |
| `picking_type_id` 重複存於 move | move | Odoo 重複存是為了規則查找與查詢；我們不做規則，重複只會多一份要對齊的真相 |

### `stock_move_lines` 直接指向庫存列

Odoo 的 move line 不指 quant，它靠 `(product, location, lot, package, owner)` 隱式配對。
那個做法的前提是有 `stock.lot`；本系統沒有，批次身分在 `(in_date, expiry_date)` 裡，**必須
指名是哪一批**。

因此 `stock_move_lines.stock_pool_id` 是外鍵——與現在的 `stock_reservations` 相同，那一欄
原樣搬過來。

### 預留是 move line 的存在，不是它的狀態

`stock_reservations` 有自己的 `status`（`ACTIVE` / `RELEASED` / `CONSUMED`）。move line **不
帶狀態**——它的狀態就是它所屬 move 的狀態：

| 舊 | 新 |
| --- | --- |
| reservation `ACTIVE` | move `ASSIGNED`，move line 存在 |
| reservation `RELEASED` | move `CANCELLED`（或退回 `CONFIRMED`），move line 刪除 |
| reservation `CONSUMED` | move `DONE`，move line 存在 |

**釋放是刪除 move line，不是把它標成已釋放。** 一條被釋放的預留不再表達任何事實——貨沒有動、
沒有被鎖住。留著它等於讓每個讀取端都要記得過濾，而 `demand_lines` 的舊謂詞
（`status IN ('ACTIVE','CONSUMED')`）正是那個負擔的具體形式。

代價要寫明：**釋放的歷史因此不留在 move line 上**。它留在 move 的狀態轉換上，而搬運的歷史
本來就該記在搬運上。

### 那條邊界護欄要換，不是開例外

`AllocationBoundaryArchitectureTest` 禁止 allocation 的原始碼出現 `order_lines` 字面字串，
連 SQL 字串都掃。而 `stock_moves` 必然要 `FOREIGN KEY (order_line_id) REFERENCES order_lines(id)`。

那支測試的註解寫著「這條規則不需要為讀取開任何例外」。**開了第一個例外，它就從硬性約束退化
成裝飾**——下一個人只要再加一個就好。

新的規則要表達的是**性質的改變**：邊界從「不知道對方存在」變成「持有對方的 id，但不讀它的
欄位」。具體形式：allocation 可以出現 `order_line_id`，但不得出現 `order_lines` 的**任何其他
欄位名**，也不得 join 它。

### 遷移改寫既有檔案

與 `V2`／`V3` 相同的判斷——本專案未上線，只有本機開發與測試容器。`V4__create_stock_reservations.sql`
**整檔改寫**為 `stock_move_lines`，而不是留一張空表再新增一張。

代價：既有的 Postgres volume 必須移除重建，不得以 `flyway repair` 略過。

### 逐檔的改動

| 檔案 | 改動 |
| --- | --- |
| `V2__create_ordering_tables.sql` | 不動 |
| `V3__create_stock_pools.sql` | 不動 |
| `V4__create_stock_reservations.sql` | **改寫為 `V4__create_stock_movements.sql`**：`stock_picking_types`、`stock_pickings`、`stock_moves`、`stock_move_lines` |
| `V5__create_event_inbox_and_outbox.sql` | 不動 |
| `V6__create_demand_lines_view.sql` | 謂詞改為「沒有 move」；檔頭的兩段註解改寫——`ol.status` 那段的理由消失了（執行層讀自己的資料），`CONSUMED` 那段換成 `DONE` |
