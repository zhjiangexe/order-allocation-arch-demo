## Context

R3 讓一列庫存成為一批貨，配貨改為跨批取用。allocation 的內部因此重寫了一輪，但它與
ordering 的關係沒有動：仍然直接 `findById` 拿整個 `Order` aggregate、直接呼叫
`markAllocated()`、直接 `save()`。

`docs/dom-order-intake-scope.md` 已經把這個 change 的核心決定寫定並附了理由：採資料庫 view
而非 Port 介面或投影表、view 刻意不含 `order_lines.status`、「已滿足」的謂詞是
`ACTIVE`／`CONSUMED`、寫入走事件而非同步呼叫、取消與配貨交錯時視為 no-op、每張表只有一個
module 寫。**本文件不重開那些決定**，只記錄那份文件寫成之後（R2 的倉別、R3 的分批）才浮現
的落差，以及為了收掉落差而做的新決定。

那份文件的 view 定義寫於 R2 之前，因此少了倉別；也寫於 R3 之前，因此排序鍵的討論還停在
「line 上有 `backordered_since`」的前提上。兩者都要修。

## Goals / Non-Goals

**Goals**

- allocation 不 import `ordering.domain.model.Order`，也不出現 `orders`／`order_lines` 表名
- `orders`／`order_lines` 只由 ordering 寫；`stock_pools`／`stock_reservations` 只由 allocation 寫
- 一個交易只修改一個 aggregate
- `promising.allocation-events` 接上 consumer
- 佇列查詢的形狀做成 R8 不必重寫的樣子

**Non-Goals**

- **不重寫配貨演算法。** 整籃原子判斷（per-SKU 餘量映射）留給 R8
- **不放寬收單的單行限制。** 那是 R8 任務 1
- 不動超賣防線
- 不改對外事件的 payload、topic 或 partition key

## Decisions

### `demand_lines` 帶倉別，佇列查詢依倉別篩選

來源文件的 view 定義只有 `owner_id` 與 `sku_code`。R3 的批次查詢是
`findAllocatableBatchesInFefoOrder(ownerId, nodeId, skuCode, today)`——**少了倉別配不到貨**，
所以 view 必須帶 `fulfillment_node_id`。這一項沒有選擇。

有選擇的是查詢要不要用它篩選。**要。** 現況
`findBackordersBySkuInFifoOrder(ownerId, skuCode, limit)` 不分倉：補南部倉 100 件，會撈出該
貨主該 SKU 的全部缺貨單，其中北部倉的那些各自查自己的倉、找不到批次、跳過。它們沒有造成
錯誤，但**佔滿了以張數計的喚醒上限**——200 張的上限裡可能有 150 張從一開始就配不到。

浪費隨倉數線性成長，而收斂它的唯一時機就是現在：R4 本來就要重寫這個查詢，之後再改要為它
單開一個 change。

### FIFO 的排序鍵是 `order_id`

view 不能用 `status` 篩選（那是落後視圖），所以**剛下單、還沒配過的 PENDING 單也會出現在
`demand_lines` 裡**，而它們的 `backordered_since` 是 NULL。現況的佇列查詢只撈 BACKORDERED，
碰不到這個情形。

排除 `backordered_since` 的理由不只是 NULL：它是**系統的處理時間**。兩張單相差 1 毫秒下單，
誰先被判缺貨取決於 Kafka 的處理順序，而 retry、rebalance、樂觀鎖衝突都會改變它。用它排序，
等於讓系統的處理抖動決定客戶之間的先後。

**也不用 `placed_at`。** 前一個 change（`separate-received-time-from-placed-time`）把那個欄位
的意義改成「上游說客戶下單的時刻」——它**可空**（上游沒有義務送），而且由一個我們控制不了
時鐘與送單排程的系統決定。一張三天前下單、今天才同步過來的單，會因此插到已經等候一天的單
前面；而**在單抵達之前，系統對它一無所知，不可能為它保留任何東西**。佇列的順序只能是到達
順序。

**`order_id` 就是到達順序。** `IdGenerator` 產生 UUID v7，時間戳編在主鍵裡，值等於這張單進
系統的時刻。它已經在 `order_lines` 上（外鍵），不必複製任何欄位，也不必依賴一個名字剛換過
意義的欄位。

代價誠實記下：**這是行為改變**，同一批缺貨單的配貨順序可能與改動前不同（改動前依
`backordered_since`）。roadmap R4 的驗收條件「既有的配貨、缺貨、補貨重配流程行為完全不變」
要隨之改寫。

**風險**：這條保證依賴 `IdGenerator` 繼續產生 v7。有人改回 v4，FIFO 會靜默變成亂序——不拋
錯、不留 log。需要一支測試釘住：建立時間不同的訂單，斷言 id 的大小順序與建立順序一致。

`orders.backordered_since` 保留——它仍是領域事實（何時進入缺貨），操作台的訂單頁在顯示它。
不再被用於排序而已。

### 倉別留在 header，view 直接 join 取值

佇列查詢要篩 `owner_id`、`node_id`、`sku_code`，再依 `order_id` 排序。倉別在 `orders`，其餘
三個都在 `order_lines`——所以 index 蓋得住篩選與排序（`(owner_id, sku_code, order_id)`），
只有倉別落在 join 之後過濾。

**考慮過把倉別物化到 `order_lines`**，那樣能建出完全覆蓋的 index。R1 的判準也支持得住：它
拒絕行上的倉別時寫的是「一張單只從一個倉出，行上的倉別恆等於 header，是**純**重複」——關鍵
字是「純」，即沒有獨立理由；而同一段留下了 `owner_id`，理由是外鍵需要它。倉別不可變，FIFO
查詢可以充當那個獨立理由。

**不做。** 那個理由的全部內容是查詢效能，而**沒有任何量測支持它現在就成立**。訂單量、待配比
例、每個貨主每個倉的訂單分布都會決定 planner 選什麼計畫，而這些數字現在都不存在。為了一個
推測中的計畫先把重複資料寫進 schema，是拿確定的成本換不確定的收益。

R1 那條 requirement 因此完全不動——行仍然不帶倉別，view 需要時 join `orders` 取得。

**日後要加的話，判準已經寫在這裡**：先量出實際查詢計畫與掃描量，再決定。屆時加欄位是純粹的
schema 擴充，不必回頭改 `Demand`、repository 或任何呼叫端——它們拿到的欄位一樣。

### 砍掉 `order_lines.backordered_since` 與它那支 index

那個欄位的存在理由，欄位註解寫得很清楚：「純粹是為了建出下方的單表 FIFO index」。

這個理由已經被推翻兩次。R3 查明那個查詢實際產生的 SQL 是 `orders` 與 `order_lines` 的 join，
篩選只用到行的 `owner_id` 與 `sku_code`，狀態與排序都取自 `orders`——**它從來就不是單表**。
R4 再加上倉別篩選之後更不可能是。

留著它等於留一個永遠沒有讀取者的欄位、一支探不到的 index，以及一句說謊的註解。ship-complete
下它恆等於 header，R8 也不會改變這一點（`PARTIALLY_ALLOCATED` 不會出現），所以不存在「日後
會用到」的情形。

index 隨之改為 `(owner_id, sku_code, order_id)`：前兩欄是篩選、第三欄是排序鍵。**欄位數與
改動前相同**，只是把一個從未被用到的排序段（`backordered_since`）換成一個真的會被用到的
（`order_id`）——原本那支 index 的排序段之所以探不到，正是因為查詢排序取自 `orders`。

連帶：roadmap R8 寫的「`order_lines`、line 層級的 `backordered_since` 與 FIFO index 都已在 R1
完成，本 change 不搬遷任何結構、不加任何欄位、不改任何 index」要更新。

### 佇列查詢分兩段：先選單，再取整張單的全部待配行

ship-complete 要求整籃原子判斷。只撈命中該 SKU 的行，會漏掉同一張單其他 SKU 的行，那就無從
判斷「整籃是否同時可滿足」。

```sql
-- ① 選單：哪些訂單有待配行命中這個 (貨主, 倉, SKU)，依 order_id 排序取前 N 張
-- ② 取行：以 order_id 對 demand_lines 自我 join，取出①選中訂單的全部待配行
```

② **不得 join `order_lines`**——那會讓 allocation 的 SQL 出現該表名，而驗收條件禁止它。
`demand_lines` 自我 join 拿得到同樣的東西。

**這也讓喚醒上限的維度對齊。** R3 的上限是「以張數為維度」，但查詢回的是行；現在單行所以
兩者相等，看不出差別。分組之後上限說的和查的是同一個東西，R8 放寬多行時不會突然分歧。

判準與 R3 的「死鎖排序鍵現在就寫成跨 SKU 的形式，即使單行時只有一個 SKU」相同：**會被 R8
重寫的東西才值得提前做對**。查詢、view、`Demand` 都是 R8 不會再動的。

### allocation 自己的需求模型叫 `Demand`，不叫 `DemandOrder`

```java
public record Demand(UUID orderId, UUID ownerId, UUID nodeId, Instant receivedAt,
                     List<DemandLine> lines) {
  int demandFor(String skuCode) { ... }   // 從 lines 摺疊，R8 多行時自然涵蓋同 SKU 多行
}

public record DemandLine(UUID orderLineId, String skuCode, int quantity) { }
```

名字裡不帶 `Order`。它與 `ordering.Order` 描述同一張現實中的單，但那是兩個 context 的兩個
模型——`Order` 有 15 個欄位、一組狀態機與 domain events 且可變，`Demand` 有 5 個唯讀欄位、
沒有行為。名字帶 `Order` 會讓人以為它就是那個 `Order`，然後開始問「為什麼它沒有 status」，
而這個 change 的整個重點就是 allocation 不該認識那個東西。持有 `orderId` 是為了建預留與發
事件，不代表它是訂單。

`Demand` / `DemandLine` / `demand_lines` 三者同一個字根，`demandFor()` 也已經是既有語彙。

**`ownerId` 與 `nodeId` 放在 `Demand` 而不是 `DemandLine`**：一張單一個貨主一個倉，放行上是
重複。view 的每一列都會帶（它是行的 view），映射時提到 order 層級。

**回傳型別是 `List<Demand>` 而不是 `Map<UUID, List<DemandLine>>`**：順序是 `List` 的性質。
`LinkedHashMap` 的保序要靠註解維持——有人換成 `HashMap`，FIFO 就壞了，而且不會有任何錯誤
浮現，佇列只是悄悄變成亂序。`receivedAt` 在 Map 裡也沒有地方放。

**`receivedAt` 帶在 `Demand` 上但不是排序鍵**——排序用 `orderId`（UUID v7）。帶著它是為了讓
policy 與畫面能說出「這張單等了多久」，那是一個值得顯示的事實，而排序另有其人。

### 演算法維持單行，型別與查詢做成多行的形狀

`StrictFifoAllocationPolicy` 的餘量仍是單一純量。整籃判斷要的是 per-SKU 餘量映射、`break`
的判準從「這個 SKU 不足」改為「任一 SKU 不足」——那是 roadmap R8 任務 2，約 20 檔。

不提前做的理由不是規模，是**沒有測試能驗證**：收單仍限制單行，唯一的驗證方式是直接建構多行
的 `Demand` 繞過收單，而那種測試證明不了真實路徑會發生什麼。

## Implementation Contract

| 元件 | 動作 |
| --- | --- |
| `V2__create_ordering_tables.sql` | `order_lines` 砍 `backordered_since`；index 改為 `(owner_id, sku_code, order_id)` |
| 新 migration | 建 `demand_lines` view |
| `allocation/domain/model/Demand`、`DemandLine` | 新增 |
| `allocation/domain/repository/DemandRepository` | 新增，唯讀：依 `(owner, node, sku)` 取分組佇列、依 `orderId` 取一筆 |
| `AllocationSelector`、`AllocationPolicy`、兩個 policy | `List<Order>` → `List<Demand>` |
| `OrderAllocation` | `(Order, List<BatchPick>)` → `(Demand, List<BatchPick>)` |
| `AllocateOrderUsecase`、`ReleaseReservationUsecase`、`ReplenishmentUsecase` | 改注入 `DemandRepository` |
| `OrderAllocationCoordinator` | 移除 `OrderRepository`、`markAllocated()`、`markBackOrdered()`、代發 domain event |
| `AllocationService` | 移除 `order.markAllocated()` |
| `OrderRepository.findBackordersBySkuInFifoOrder` | 移除 |
| `ordering/entrypoint/kafka/` | 新增 consumer、兩個 handler、error handling config |
| `ConfirmOrderUsecase` | 新增；對已取消的訂單為 no-op |
| ordering 的 inbox 去重 | 接上既有機制 |

## Risks / Trade-offs

**取消與配貨交錯**。寫入改成非同步之後多了一個現況沒有的競爭：allocation 配貨成功發出事件，
使用者同時取消，ordering 先處理取消把 `Order` 改為 CANCELLED，再收到配貨事件時
`markAllocated()` 會拋「Only pending or backordered orders can be allocated」而落 DLT。

這是**合理的競爭，不是錯誤**。`ConfirmOrderUsecase` 必須把它當 no-op，否則一次正常的取消
就會製造一筆 DLT 訊息。allocation 側的預留已由取消事件釋放，兩邊都正確。

**重複預留**。同一條 line 有兩筆預留不是超賣（`reserved ≤ onHand` 仍成立），而是庫存被白鎖。
兩道既有防線足夠：view 的 `NOT EXISTS` 讓已有 ACTIVE／CONSUMED 預留的 line 直接消失；兩個
交易同時通過 view 時，`StockPool` 的樂觀鎖讓一方 version 衝突、重讀 view、該 line 已被排除。

**view 的查詢計畫未經量測。** 這是本 change 已知且刻意接受的未決項：篩選與排序落在
`order_lines` 的 index 上，但倉別在 `orders`，只能 join 之後過濾。掃多少列才湊滿一輪的上限，
取決於目前不存在的數字（訂單量、待配比例、每個貨主每個倉的分布）。

接受它的理由是**代價不對稱**：真的太慢時，補救是把 `fulfillment_node_id` 物化到
`order_lines` 並擴充 index——那是純粹的 schema 擴充，`Demand`、repository 與所有呼叫端拿到的
欄位一模一樣，不需要回頭改。反過來，先寫進 schema 再發現不需要，就得刪欄位與改 migration。

**FIFO 依賴 `IdGenerator` 產生 UUID v7。** 排序鍵是 `order_id`，它之所以等於到達順序，全靠
v7 把時間戳編進主鍵。有人改回 v4，佇列會靜默變成亂序——不拋錯、不留 log、既有測試不會紅。
需要一支測試釘住 id 的時間有序性。

**`ReservationStatus` 演進**。「已滿足」的謂詞寫在 view 定義裡。日後再擴充該 enum 必須同步
檢查 view——漏掉會讓已出貨的訂單重新出現在待配佇列，而當下沒有任何測試會發現。

## Migration Plan

schema 未部署，沿用 R1～R3 的判準改寫 `V2` 而非新增 migration。view 需要
`stock_reservations.order_line_id` 與 `CONSUMED`（兩者都是 R3 才有），所以它的 migration 排在
現有的最後一支之後。

驗證方式與 R3 相同：`./e2e/perf/run.sh down` 後重新啟動，確認 Flyway 在空資料庫上一次套用
成功，且種子資料如設計寫入。
