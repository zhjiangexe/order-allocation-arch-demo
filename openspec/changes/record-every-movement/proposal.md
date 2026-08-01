## Why

第一個 change 把庫存的所在從倉換成位置，並建好了三個虛擬位置——**但沒有任何東西讀它們**，
因為還沒有東西會移動貨。

現在補上那個東西。目前的模型只有兩種狀態：庫存的數量，以及訂單配到了沒。中間發生的事沒有
資料表達：

| 問題 | 現在的答案 |
| --- | --- |
| 這 100 件哪來的？ | 查不到 |
| 上週三為什麼少了 20 件？ | 查不到 |
| 這張單的貨揀到哪一步了？ | **問不出來**——只有「配到／沒配到」 |

而 `stock_reservations` 表達的其實是一次搬運的一半：它記了「這條行從這一批鎖了多少」，卻沒有
記那批貨**要去哪裡**。出貨那一刻要補上的正是缺的那一半，而那時再改，等於把所有既有的預留
重新詮釋一次。

## What Changes

**需求與執行分成兩層。** `orders` / `order_lines` 留在需求層一個字不動；新增的四張表是執行層：

```text
stock_picking_types   作業類型：這種搬運從哪到哪
stock_pickings        一趟搬運的單據
stock_moves           單據裡的一段：某 SKU 從 A 到 B，帶 order_line_id 指回需求
stock_move_lines      實際從哪一批動——**取代 stock_reservations**
```

**待配需求變成 move 的一個狀態。** 收單後為每一條行建一個 `CONFIRMED` 的 move（要貨、還沒
拿到）；配到貨時轉 `ASSIGNED` 並寫出 move line。配貨佇列因此是「`CONFIRMED` 的 move」，
不再是一個由訂單與預留 join 推導出來的檢視。

**`demand_lines` 不廢除，改用途。** 它仍是 ordering 與 inventory 之間唯一的讀取介面——
`OrderPlacedIntegrationEvent` 只帶 `orderId`，執行層必須讀才知道一張單有哪些行。但它的謂詞
從「無有效預留」變成「**還沒有 move**」，語意從「還欠什麼」變成「哪些行還沒被接手」。

**預留成為尚未完成的 move line。** `stock_reservations` 的三個狀態對應過去：`ACTIVE` 是
move 已 `ASSIGNED` 而未 `DONE`，`RELEASED` 是 move 取消，`CONSUMED` 是 move `DONE`。

## Capabilities

### Added Capabilities

- `stock-movement`——搬運的單據、段落與落地；作業類型；狀態的值域與轉換。

### Modified Capabilities

- `stock-allocation`——配貨產生搬運而不是預留；佇列改由 move 的狀態回答；發布的需求換成
  「哪些行還沒被接手」。

## Impact

**`orders`、`order_lines` 與對外契約仍然不動。** Kafka 事件與 REST 一個欄位都不改，前端不改。
`OrderStatus` 也不動——它的重新歸屬排在第四個 change，與 `allocation` → `inventory` 的更名
一起做。

**`stock_reservations` 消失。** 資料與行為遷入 `stock_move_lines`，`ReleaseReservationUsecase`
與 `OrderAllocationCoordinator` 跟著改寫。這是本 change 影響最大的一項。

**一條邊界護欄必須換掉，而不是開例外。** `AllocationBoundaryArchitectureTest` 禁止 allocation
的原始碼出現 `order_lines` 字面字串，而 `stock_moves.order_line_id` 的外鍵必然要提到它。
**危險不在測試變紅，在它被「加一個例外」修掉**——那支測試自己寫著「這條規則不需要為讀取開
任何例外」，開了第一個例外它就從硬性約束退化成裝飾。要換的是一條表達「只准持有 id，不准讀
欄位」的新規則。

**遷移沿用改寫既有檔案的方式**（本專案未上線），與 `V2`／`V3` 的判斷一致。

**不含的東西，各有理由**：

| 不做 | 理由 |
| --- | --- |
| `stock_move_dependencies` | 單段出貨，沒有任何 move 需要串接。**但也不加 `previous_move_id`**——它會把線性假設鎖進 schema，而拆分／合併／部分完成一出現就得把所有既有的鏈重建 |
| move 的 `WAITING` 狀態 | 它的意思是「等上一段」，而沒有上一段。它隨串接一起到來 |
| 入庫產生 move | 第三個 change。本 change 只做出庫方向，補貨仍直接寫庫存 |
| 跨訂單合併 picking | 一張單一張 picking。合併的分組鍵**必須含貨主**（Odoo 的不含，不能照抄），而在需要合併之前那是一個沒有讀者的規則 |
