## Why

`orders.requested_node_id` 是一個可空、無外鍵、從未被寫入的欄位；`order_lines.assigned_node_id`
與 `owners.allow_split_shipment` 同樣從未被讀過。三者都是為原計畫的 R6 Sourcing 決策準備的，
而 **R6 已於 2026-07-29 移出範圍**——本系統是 3PL，「從哪個倉出」由合約與上游決定，貨主在上游
下單時就指定了倉別，系統照做。沒有選擇就沒有選點問題。

倉庫本身**不隨之消失**。它仍是領域裡的真實維度：庫存分倉、訂單指定倉、出貨屬於某個倉。而
[execution-roadmap.md](../../../docs/execution-roadmap.md) 的 R3 要把 `stock_pools` 的唯一鍵
改成 `(owner_id, node_id, sku_code, lot_no)`——**本 change 存在的唯一理由，是讓那個 `node_id`
有參照對象**。roadmap 的禁忌第 5 條也明訂倉庫維度不可從該唯一鍵拿掉，因為事後補回來是改鍵、
改所有查詢、改兩本帳的對帳等式，不是加欄位。

先做的另一個理由是**現在改最便宜**：那三個欄位都還沒有任何資料，schema 也尚未部署至任何環境。

## What Changes

- 新增 `fulfillment_nodes` 極簡主檔（`id`、`code`、`name`）。**刻意不建 `status`**——砍掉
  sourcing 之後沒有「排除停用倉庫」的篩選，它會是一個沒有讀者的欄位。
- 新增 `owner_nodes`（PK `(owner_id, node_id)`）表達貨主與倉庫的多對多關係，**零設定欄位**。
  它在本 change 的用途只有一個：下單時知道這個貨主能選哪些倉。設定欄位（`allow_mixed_batch`）
  屬 R3。
- **BREAKING**：`orders.requested_node_id` 更名為 `fulfillment_node_id`、改 `NOT NULL`、補外鍵
  指向 `fulfillment_nodes`。「requested」暗示這是個可能被選點推翻的請求，而沒有選點之後它就是
  這張單的倉別。所有建立訂單的路徑都必須提供倉別。
- **BREAKING**：移除 `order_lines.assigned_node_id`。它放在 line 而非 header 的唯一理由是
  「拆單後不同 line 可能從不同倉出」，而一張訂單只能一個倉、明細不可跨倉，它永遠等於 header。
- **BREAKING**：移除 `owners.allow_split_shipment`。它的定義是「是否允許**跨節點**拆單」，
  明細不可跨倉之後這個開關沒有東西可以開關。種子原本以它作兩個貨主的對比組，該角色由 R3 的
  `allow_mixed_batch` 接手。
- **BREAKING**：改寫 `V3__create_ordering_tables.sql` 而非新增 migration。schema 尚未部署至
  任何環境，沿用 R1 的判準——新開一支會在歷史上留下「`assigned_node_id` 建了又砍、
  `requested_node_id` 建了又改名」的假歷史。代價是既有 Postgres volume 必須移除重建，
  **不得以 `flyway repair` 略過**。
- `OrderPlaced` 領域事件加 `fulfillmentNodeId`。R3 的 partition key 是
  `ownerId/nodeId/skuCode`，那個維度必須在事件裡拿得到。
- 新增依貨主列出可用倉庫的唯讀查詢；下單表單加倉庫下拉，依所選貨主過濾。

## Capabilities

### New Capabilities

- `warehouse-catalog`——有哪些倉庫，以及哪個貨主可以從哪些倉出貨。與 `product-catalog` 是
  兄弟關係：後者回答「這個貨主能賣什麼」，前者回答「這個貨主能從哪出」。兩者讀者不同
  （收單讀商品、配貨讀倉庫）、變更節奏不同，因此分開而非併入。

### Modified Capabilities

- `product-catalog`——貨主不再持有拆單許可；種子的兩貨主對比軸線隨之改變。
- `order-intake`——訂單必須帶倉別；行不再持有出貨倉。
- `order-promising-http-api`——`POST /orders` 的 request body 加必填倉別；新增倉庫查詢端點。
- `demo-console-frontend`——下單表單加倉庫下拉，依貨主過濾，換貨主時清空。

## Impact

**Migration**：改寫 `V3__create_ordering_tables.sql`（加兩張表、改一欄、砍兩欄）。既有
Postgres volume 必須 `./e2e/perf/run.sh down` 移除後重建。

**Domain**：`Order` 的 `requestedNodeId` 更名並改為必填；`OrderLine` 移除 `assignedNodeId`；
`Owner` 移除 `allowSplitShipment`；新增 `FulfillmentNode` 與其 repository。

**編譯失敗點**：`Order.place()`／`rehydrate()` 的簽章、`OrderPlaced` 事件、`DevSeedDataInitializer`、
`OrderFixtures`、`PlaceOrderRequest`／`OrderController`，以及所有讀 `allowSplitShipment` 的地方。

**壓測**：`e2e/perf/k6/hot-sku-burst.js` 的下單 payload 要加倉別；`run.sh seed` 要一併種倉庫
與貨主倉庫配對，否則壓測訂單的外鍵無處可指——這與 R1 時 `HOT-SKU` 需要主檔是同一個問題。

**前端**：`api/types.ts` 的 `PlaceOrderCommand` 與 `OrderView`、`PlaceOrderForm`、`useCatalog`。

**文件**：`docs/` 底下的敘述已於 2026-07-29 先行更新，本 change 只需在完成後核對一致。
