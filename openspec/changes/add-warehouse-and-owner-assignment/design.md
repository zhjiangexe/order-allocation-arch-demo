## Context

三個為 R6 Sourcing 準備的欄位——`orders.requested_node_id`、`order_lines.assigned_node_id`、
`owners.allow_split_shipment`——從建立至今**沒有被寫入過任何值**（前兩者恆為 null，第三者有值
但沒有讀取端）。R6 已於 2026-07-29 移出範圍：本系統是 3PL，「從哪個倉出」由合約與上游決定，
貨主在上游下單時就指定倉別，系統照做。

倉庫維度本身保留。R3 要把 `stock_pools` 的唯一鍵改成 `(owner_id, node_id, sku_code, lot_no)`，
而 `node_id` 需要參照對象——這是本 change 存在的唯一理由，roadmap 的「一條建議而非禁忌」寫得
很直白。

**約束**：schema 尚未部署至任何環境；三個欄位零資料；R1 已在同一份 `V3` 上示範過改寫而非疊加
的手法。

## Goals / Non-Goals

**Goals**

- 讓 `node_id` 在 R3 有合法的參照對象，且該參照由資料庫保證
- 讓「一張訂單一個倉、明細不可跨倉」這條上游既有規則在 schema 上成立而非靠慣例
- 清掉三個為已取消的決策而生的欄位，不留下「以後也許會用」的殘骸

**Non-Goals**

- **不做倉庫選擇**。成本函數、覆蓋範圍、節點能力比對全部不在範圍，理由見 proposal 與
  `docs/system-layer-map.md` 的「為什麼不做 ③」
- **不做倉庫頁**。原計畫的倉庫頁是為了展示覆蓋矩陣；現在只剩三個欄位的清單，撐不起一頁
- **不做倉庫狀態管理**。`status` 不建，理由見下方決策
- **不做貨主倉庫的設定欄位**。`allow_mixed_batch` 屬 R3，此處只建配對本身
- **不動 `stock_pools`**。庫存分倉是 R3 的工作，本 change 只準備參照對象

## Decisions

### 改寫 `V3` 而非新增 migration

`V3__create_ordering_tables.sql` 在 R1 已經被改寫過一次，理由記在該檔開頭。本次沿用同一個
判準，因為前提沒變：**schema 尚未部署至任何環境**。

| 作法 | 結果 |
| --- | --- |
| 新開 `V6` | migration 歷史留下「`assigned_node_id` 建了又砍」「`requested_node_id` 建了又改名」的假歷史。讀歷史的人會以為那些欄位曾經有用途、曾經有資料 |
| **改寫 `V3`** | 歷史只記載最終形狀。代價是既有 Postgres volume 必須移除重建 |

代價是真的但已知：`./e2e/perf/run.sh down` 之後 `up`，**不得以 `flyway repair` 略過** checksum
不符——那會讓資料庫的實際結構與 migration 檔案永久分岔。

本專案在 `add-demo-console-api`、`fix-outbox-partition-key-semantics`、
`add-owner-and-order-line-model` 三次都選了改寫，這是第四次。判準一致：**未部署即可改寫**。

### `fulfillment_nodes` 不建 `status`

倉庫停用在營運上是真實的，但本 change 之後**沒有任何程式會讀它**：原本的讀者是選點的硬約束
（排除停用節點），而那個決策已移出範圍。

這與 `type`／`role` 被砍掉是同一個判準——不是「以後用不到」，而是「加一個沒有讀者的欄位，
會讓下一個人以為它有意義」。R1 的 `requested_node_id` 就是活生生的例子：它帶著「R6 才讀」的
註解存在了一整個 change，最後 R6 沒有發生。

等真的有讀者（例如收單時擋掉指向停用倉的訂單）再加，那時它會帶著一條會失敗的測試一起進來。

### 欄位更名為 `fulfillment_node_id` 並改為 `NOT NULL`

`requested` 這個詞承載的是 sourcing 時代的語意——「貨主提出的請求，可能被選點推翻」。沒有
選點之後它就是這張單的倉別，不是請求。

`NOT NULL` 反映的是上游的事實：貨主下單時一定會給倉別。維持可空等於在型別上保留一個永遠
不會發生的狀態，而每個讀取端都要處理它。

命名的另一個候選是 `ship_from_node_id`，與現有的 `ship_to_zone`／`ship_to_address` 對稱。
選 `fulfillment_node_id` 是因為它明說指向哪張表；`ship_from` 讀起來像地址而非倉庫識別。

### `fulfillment_node_id` 補外鍵——刻意偏離「外部來的不補」

R1 定下的原則是：我方產生的值補 FK，外部來的不補。`orders.ship_to_zone` 因此沒有約束——
客人的地址千變萬化，補了會讓「地址在我們沒定義的分區」從可記錄變成收單失敗。

倉別不同。它是**簽約時就固定的少數幾個值**，上游送來一個我們不認得的倉，那不是資料多樣性，
是設定錯誤。讓它在收單時就失敗，比讓它變成一張永遠配不出貨的訂單好。

這是刻意的偏離，寫在這裡是為了讓下一個人知道原則沒有被遺忘，而是被權衡過。

### `owner_nodes` 只建配對，不建設定

`stock_pools` 帶了 `owner_id` 與 `node_id` 之後，「這個貨主的貨放在這個倉」就已經被庫存列的
存在表達了——不需要授權表。**所以這張表只有在它承載設定時才值得建。**

它在本 change 確實有一個用途：下單表單要知道這個貨主能選哪些倉。沒有它就只能列出全部倉庫，
而那是錯的。

設定欄位（`allow_mixed_batch`，可否換批號）留給 R3，因為那是配貨演算法的輸入，此處無從驗證。
先建空的配對表不是「先做簡化版」——配對關係本身就是完整的領域事實，設定是另一件事。

### 明細不再持有出貨倉

`order_lines.assigned_node_id` 放在 line 而非 header，唯一理由是「拆單後不同 line 可能從不同
倉出」（`dom-order-intake-scope.md` 原文）。一張訂單只能一個倉、明細不可跨倉之後，它永遠等於
header——是純重複，不是反正規化。

對照 `order_lines.owner_id`：那個也複製自 header，但它**有獨立理由**——`(owner_id, sku_code)`
才是 `skus` 的自然鍵，沒有它就建不出外鍵。`assigned_node_id` 沒有這種理由。

R8 放寬多行之後仍然不需要它：多行是同一張單的多個 SKU，倉別仍在 header。真正需要 per-line
倉別的是跨倉拆單，而那屬於已移出範圍的 sourcing。

### `OrderPlaced` 加 `fulfillmentNodeId`

R3 的 partition key 是 `ownerId/nodeId/skuCode`（判準見 roadmap 的 R3 動工前第 3 件）。
translator 從領域事件取值，因此節點必須在事件裡。

現在加而不是等 R3，是因為本 change 已經在改 `Order` 的建構路徑，一起改比分兩次改省事，且
R3 屆時可以只動 translator 不動事件契約。

## Implementation Contract

**新增的資料表**

`fulfillment_nodes`：`id`（UUID 主鍵）、`code`（唯一）、`name`。無其他欄位。

`owner_nodes`：`(owner_id, node_id)` 複合主鍵，雙外鍵分別指向 `owners` 與 `fulfillment_nodes`。
無其他欄位。複合主鍵而非代理鍵——這是純粹的關係，沒有自己的身分，與 R1 對 `products`／`skus`
用代理鍵的判準一致（那兩者是有身分的實體）。

**變更的資料表**

`orders`：`requested_node_id` → `fulfillment_node_id`，`NOT NULL`，外鍵指向 `fulfillment_nodes`。

`order_lines`：移除 `assigned_node_id`。

`owners`：移除 `allow_split_shipment`。

**可觀察行為**

- 下單未提供倉別 → 請求被拒，不建立訂單
- 下單提供的倉別不存在 → 請求被拒（外鍵）
- 下單提供的倉別存在、但該貨主沒有掛這個倉 → **請求被拒**。這需要應用層檢查，資料庫的外鍵
  只保證倉庫存在，保證不了配對關係
- 依貨主查詢可用倉庫 → 只回該貨主掛的倉，順序穩定
- 下單成功後，`OrderPlaced` 事件帶得出倉別

**種子資料**

三個倉庫。甲貨主掛兩個、乙貨主掛一個——「同一貨主有多個倉」與「不同貨主的倉不同」兩件事都
必須在畫面上看得出來，且為 R3 的分倉庫存準備資料。

**前端**

下單表單加倉庫下拉，依所選貨主過濾；換貨主時清空既有倉庫選擇（與現有的款／規格清空同一個
理由——別的貨主的倉在這個貨主底下不成立）。倉庫清單走既有的 `useCatalog` 一次載入，不在選擇
時發請求。

**不在範圍**：倉庫頁、倉庫的建立／修改介面、倉庫狀態、任何讀取倉庫做決策的邏輯。

## Risks / Trade-offs

**[既有 Postgres volume 必須重建]** → 已知且可接受，本專案第四次。`run.sh down` 會一併停掉
背景 app；重建後要重新註冊 Debezium connector（`run.sh up` 的第 3 步會做）。**風險在於忘記
重建**——症狀是 app 啟動時 Flyway checksum 不符而失敗，訊息明確，不會靜默。

**[「貨主沒掛這個倉」只能靠應用層擋]** → 資料庫擋得住「倉不存在」，擋不住「倉存在但不是這個
貨主的」。複合外鍵在此不可行：`orders` 沒有指向 `owner_nodes` 的自然路徑（那需要 `orders` 的
`(owner_id, fulfillment_node_id)` 一起指過去，而那反而是可行的）。**緩解**：實作時優先嘗試
`FOREIGN KEY (owner_id, fulfillment_node_id) REFERENCES owner_nodes(owner_id, node_id)`——
若可行，這條規則就由資料庫保證，與 R1 讓 `(owner_id, sku_code)` 走自然鍵外鍵是同一個手法。

**[壓測腳本會撞外鍵]** → `run.sh seed` 目前只種主檔三層與庫存池。加了倉別必填之後，壓測訂單
需要倉庫與貨主倉庫配對才插得進去。這與 R1 時 `HOT-SKU` 需要主檔是同一個問題，**必須在同一個
change 內處理**，否則壓測會在 R2 之後靜默地全部失敗。

**[砍欄位會讓一批測試編譯失敗]** → 這是預期的，也是價值所在——編譯失敗即是完整的呼叫點清單。
`OrderFixtures`、`DevSeedDataInitializer`、`PlaceOrderRequest` 是已知的三處。

## Migration Plan

1. `./e2e/perf/run.sh down`，移除既有 Postgres volume。改寫既有 migration 必然造成 checksum
   不符，**不得以 `flyway repair` 略過**。
2. 套用改寫後的 `V3__create_ordering_tables.sql` 與新的 seed。
3. `./e2e/perf/run.sh perf`，確認 k6 既有 thresholds 全數通過。

Rollback 就是 git revert 加一次 `down`／`up`——沒有需要保留的資料，也沒有任何環境持有舊 schema。

## Open Questions

- **`(owner_id, fulfillment_node_id)` 的複合外鍵是否可行**，見 Risks。若可行，「貨主沒掛這個
  倉」這條規則就從應用層檢查降為資料庫約束，應用層的檢查可以只保留為更友善的錯誤訊息。
  實作時驗證，不預先決定。

- **倉庫代碼的格式與種子內容**（例如 `WH-NORTH`／`WH-CENTRAL`／`WH-SOUTH`）留給實作，它不
  影響任何結構決定。唯一的約束是三個倉必須讓「一貨主多倉」與「貨主間倉不同」都成立。
