## Why

庫存現在掛在**倉**上：`stock_pools.node_id` 指 `fulfillment_nodes`。這在只有一種庫存動作
（補貨直接加數字）時夠用，但它擋住了下一步。

`docs/dom-stock-movement-scope.md` 決定把庫存改成有來源與目的的異動流水——每一筆搬運都是
一次位置之間的移動：

```text
入庫      Vendors ──────────────> 北部倉/庫存
出庫      北部倉/庫存 ──────────> Customers
盤盈虧    Inventory adjustment ─> 北部倉/庫存
```

三者都是位置之間的移動，全域總量因此守恆。而**倉當不了移動的端點**：`Vendors` 與
`Customers` 不是倉，它們是虛擬位置——供應商與客戶不在倉庫清單裡，卻必須是搬運的合法另一端，
否則「入庫」與「出庫」表達不出來。

這個 change 只做一件事：**把庫存的所在從倉換成位置，並把位置的值域一次定義完整。** 搬運
本身（`stock_pickings` / `stock_moves` / `stock_move_lines`）是下一個 change。

## What Changes

**新增 `stock_locations`**。每個位置帶 `usage` ∈ `internal` / `supplier` / `customer` /
`inventory`——`internal` 才算公司庫存，其餘三種是虛擬位置，用來當搬運的另一端。每個既有的倉
生成一個 `internal` 位置，虛擬位置各一個且不屬於任何倉。

**`stock_pools.node_id` → `location_id`，表名不動。** 這張表的語意（一群可互換的單位的餘額）
與 `stock.quant` 完全吻合，而 `pool` 這個詞說的正是同一件事，改名換不到精確度。取而代之的是
把逐欄對照寫進檔頭，並修掉 `V3` 那句過重的「名字與內容不符是刻意保留的」。

**`demand_lines` view 改輸出 `location_id`**，由 `orders.fulfillment_node_id` join
`stock_locations` 解析而得。配貨因此只說位置，不再需要同時認識倉與位置兩套詞彙。

## Capabilities

### Added Capabilities

- `stock-locations`——位置的值域、虛擬位置、每倉一個內部位置、以及「只有 `internal` 算庫存」。

### Modified Capabilities

- `stock-allocation`——庫存按位置持有，配貨按位置取批。

## Impact

**`orders` 與 `order_lines` 完全不動。** 訂單只帶倉——下單決定的是倉，位置屬於執行層，而執行
層在下一個 change 才出現。`orders` 上那條複合外鍵
`(owner_id, fulfillment_node_id) → owner_nodes`（它讓「倉存在但這個貨主沒掛這個倉」由資料庫
擋下）因此**一個字都不用改**。

**對外契約完全不變。** Kafka 事件（`StockReplenishedIntegrationEvent`、
`OrderPlacedIntegrationEvent` 等）與 REST 契約（`GET /stock-pool?ownerId&nodeId`、
`POST /demo/replenish`、下單）都**繼續說倉**。上游系統與操作台不知道倉裡怎麼編排位置，也不該
知道——理由見 design 的「對外說倉，對內說位置」。

**前端不動**，因為 HTTP 契約不動。這同時是驗證條件：要是前端需要改，代表某處洩漏了內部模型。

**Schema**：新增 `stock_locations`；`stock_pools` 換鍵；`demand_lines` view 重建。
**遷移以改寫既有檔案的方式進行**，不以 ALTER 疊加——本專案未上線，只有本機開發與測試容器，
與 `V2` 檔頭記錄的判斷一致。代價是既有的 Postgres volume 必須移除重建，且不得以
`flyway repair` 略過。

**不含的東西，各有理由**：

| 不做 | 理由 |
| --- | --- |
| 位置樹（`parent_id`） | 一倉一位置，沒有查詢會沿樹走。日後加 Input／Output 時，`orders` 已指倉、`stock_pools` 已指位置，兩者都不用動 |
| `stock_picking_types` / `stock_pickings` / `stock_moves` | 下一個 change。這個 change 只準備端點 |
| 型別與端點改名（`allocation` → `inventory`、`BACKORDERED`） | 命名收斂集中在第四個 change，否則前端與契約會被改四次 |

**樂觀鎖與 FIFO 保證不受影響。** `stock_pools` 保留 `version`，且它仍是物化餘額而非即時彙總
——`docs/dom-promising-scope.md`「決定二」的機制原封不動。
