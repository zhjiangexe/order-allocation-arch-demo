## 1. Schema 與 view

- [x] 1.1 依 `order-intake` 的 **A line's status mirrors its header, and no timestamp is stored on it**，以及 design 的決策「砍掉 `order_lines.backordered_since` 與它那支 index」，改寫 `V2__create_ordering_tables.sql`：砍掉 `order_lines.backordered_since` 與 `idx (owner_id, sku_code, backordered_since, id)`，index 改為 `(owner_id, sku_code, order_id)`。行為上：schema 少一個沒有讀取者的欄位，而 index 的排序段換成真的會被用上的。以 schema SIT 斷言 index 定義驗證。

  **實作時把第三欄補回來了**（原任務寫 `(owner_id, sku_code)`）。排序鍵是 `order_id`，它就該在 index 裡——少了它，佇列查詢每次都要為排序掃一遍。欄位數與改動前相同，只是把從未被探到的 `backordered_since` 換成用得到的主鍵。

  那個欄位的註解寫著「純粹是為了建出下方的單表 FIFO index」，而 R3 已經查明該查詢從來就是 join、排序取自 `orders`——**欄位沒有讀取者，index 的排序段從來沒被用上**。

  沿用 R1～R3 的判準改寫既有 migration 而非新增——schema 未部署，新開一支只會在歷史上留下「建了又砍」的假歷史。

  **`order_lines` 不加倉別與下單時間**（design 的決策「倉別與下單時間留在 header，view 直接 join 取值」）。物化它們能建出覆蓋 index，但那個理由的全部內容是查詢效能，而現在沒有任何量測支持它成立。R1 的 **A line inherits its order's warehouse rather than carrying its own** 因此完全不動。

- [x] 1.2 新增 migration 建 `demand_lines` view，依 `stock-allocation` 的 **Outstanding demand is decided by reservations, not by an order's status** 與 design 的決策「FIFO 的排序鍵是 `order_id`」：欄位為 `order_id`、`order_line_id`、`owner_id`、`node_id`（取 `o.fulfillment_node_id`）、`sku_code`、`quantity`、`received_at`（取 `o.received_at`，供顯示用，不是排序鍵）；條件為訂單未取消，且該行沒有 `status IN ('ACTIVE','CONSUMED')` 的預留。行為上：view 回答「現在還欠什麼」。以 SIT 涵蓋 ACTIVE／CONSUMED／RELEASED／已取消四種情形驗證。

  **`CONSUMED` 現在不會出現，但謂詞必須現在就寫對。** 它在 R7 出貨扣帳時才產生，屆時若謂詞只寫 `ACTIVE`，每一張已出貨的訂單都會重新變成待配需求而被配第二次——而那一刻沒有任何測試會失敗，因為出貨流程還不存在。

- [x] 1.3 view **刻意不含 `order_lines.status`**。行為上：任何呼叫端都拿不到那個欄位，因此不可能拿它當閘門。以 view 定義審閱驗證。

  ordering 的配貨狀態由事件推進，落後於 allocation 的決策：allocation 配到貨、發事件，ordering 還沒處理完，`status` 仍是 BACKORDERED；此時第二筆補貨進來又讀到同一筆需求，就重複預留。**讀不到比讀得到而約定不用更強**。

## 2. allocation 的需求模型

- [x] 2.1 依 `stock-allocation` 的 **Allocation takes its demand from a published view, never from the order aggregate**，新增 `allocation/domain/model/Demand` 與 `DemandLine`：`Demand(orderId, ownerId, nodeId, receivedAt, List<DemandLine>)`、`DemandLine(orderLineId, skuCode, quantity)`，皆為唯讀 record。行為上：allocation 有自己的需求型別，不再持有 `Order`。以領域測試驗證。

  依 design 的決策「allocation 自己的需求模型叫 `Demand`，不叫 `DemandOrder`」，**名字不帶 `Order`**：它與 `ordering.Order` 描述同一張現實中的單，但那是兩個 context 的兩個模型——後者 15 個欄位、一組狀態機與 domain events 且可變。名字帶 `Order` 會引來「為什麼它沒有 status」，而這個 change 的重點正是 allocation 不該認識那個東西。

  **`ownerId` 與 `nodeId` 放在 `Demand` 而不是 `DemandLine`**：一張單一個貨主一個倉，放行上是重複。view 的每一列都帶（它是行的 view），映射時提到 order 層級。

- [x] 2.2 `Demand.demandFor(String skuCode)`：從 `lines` 摺疊出該 SKU 的需求量。行為上：同一 SKU 有多行時加總，單行時等於那一行。以領域測試涵蓋兩種情形驗證——**多行的那支現在就要寫**，它是 R8 放寬收單時唯一不必改的部分。

- [x] 2.3 新增 `allocation/domain/repository/DemandRepository`，唯讀，兩個方法：依 `(ownerId, nodeId, skuCode, limit)` 取分組佇列、依 `orderId` 取單筆。行為上：allocation 取得需求只經過這一個介面。以介面審閱與 SIT 驗證。

## 3. 佇列查詢

- [x] 3.1 依 `order-intake` 的 **Backorder queues are scoped to one owner, one warehouse, and one SKU**、`stock-allocation` 的 **Allocation takes its demand from a published view, never from the order aggregate**，以及 design 的決策「佇列查詢分兩段：先選單，再取整張單的全部待配行」與「`demand_lines` 帶倉別，佇列查詢依倉別篩選」，實作分組佇列查詢：**① 選單**——哪些訂單有待配行命中 `(貨主, 倉, SKU)`，依 `order_id` 排序取前 N 張（UUID v7，等於到達順序）；**② 取行**——以 `order_id` 對 `demand_lines` **自我 join**，取出①選中訂單的全部待配行。行為上：一張單有兩個 SKU 的待配行時，兩行都在同一個 `Demand` 裡。以 SIT 驗證。

  ② **不得 join `order_lines`**——那會讓 allocation 的 SQL 出現該表名，而 6.1 的驗收禁止它。`demand_lines` 自我 join 拿得到同樣的東西。

  只撈命中該 SKU 的行會漏掉同一張單其他 SKU 的行，那就無從判斷 ship-complete 要的「整籃同時可滿足」。

- [x] 3.2 回傳型別是 `List<Demand>`，**不是 `Map<UUID, List<DemandLine>>`**。行為上：順序即 FIFO 順序。以測試斷言順序驗證。

  順序是 `List` 的性質。`LinkedHashMap` 的保序要靠註解維持——有人換成 `HashMap`，FIFO 就壞了，不拋錯、不留 log，佇列只是悄悄變成亂序。`receivedAt` 在 Map 裡也沒有地方放，而 policy 要用它。

- [x] 3.3 移除 `OrderRepository.findBackordersBySkuInFifoOrder()` 與其實作。行為上：ordering 不再為 allocation 提供查詢。以編譯與測試通過驗證。

## 4. 配貨路徑不再碰 Order

- [x] 4.1 `AllocationSelector`、`AllocationPolicy`、`MaximizeFulfilledOrdersPolicy`、`StrictFifoAllocationPolicy` 的 `List<Order>` 改為 `List<Demand>`。行為上：既有的挑選行為不變。以既有測試改寫後通過驗證。

  依 design 的決策「演算法維持單行，型別與查詢做成多行的形狀」，**演算法本身不動**：餘量仍是單一純量。整籃原子判斷（per-SKU 餘量映射、`break` 判準改為「任一 SKU 不足」）是 R8 任務 2，此處不做——收單仍限制單行，提前做的唯一驗證方式是直接建構多行 `Demand` 繞過收單，而那證明不了真實路徑。

- [x] 4.2 `OrderAllocation` 由 `(Order, List<BatchPick>)` 改為 `(Demand, List<BatchPick>)`。行為上：配貨結果不再持有 aggregate。以編譯與既有測試驗證。

- [x] 4.3 依 `stock-allocation` 的 **Allocation publishes its outcome and writes only its own tables**，`OrderAllocationCoordinator` 移除 `OrderRepository` 注入、移除 `order.markAllocated()` 與 `order.markBackOrdered()`、移除代 `Order` 發 domain event。`AllocationService` 移除 `order.markAllocated()`。行為上：一個交易只改 `stock_pools` 與 `stock_reservations`。以 SIT 斷言配貨交易中 `orders` 的 `version` 不變驗證。

- [x] 4.4 `AllocateOrderUsecase` 改用 `DemandRepository` 依 `orderId` 取單筆。行為上：不再 `findById` 一個 `Order`；查無需求是正常結果（重送或已取消），不再拋錯。以單元測試驗證。

- [x] 4.5 **（實作時新增）**`stock_reservations` 加 `order_id`，`StockReservationRepository` 加 `findActiveByOrderId`，`ReleaseReservationUsecase` 改用它；移除 `findActiveByOrderLineIds`。行為上：取消時 allocation 自己回答得了「這張單有哪些預留」。以持久化 SIT 驗證。

  **原任務漏了這條路徑，而 `demand_lines` 幫不上忙**：那個 view 只有「還欠的」行，而要釋放的恰恰是**已經配到**的那些——它們早就從 view 裡消失了。沒有 `order_id` 就只能 join `order_lines`，那正是這個 change 拆掉的方向。

  值不可變（一筆預留屬於哪張單不會改）、且有獨立理由（allocation 自己的表回答自己的問題），與 `order_lines.owner_id` 當初被留下是同一條判準。刻意不建外鍵指向 `orders`——那會讓 allocation 的 schema 依賴 ordering 的表；完整性由 `order_line_id` 的外鍵保證。

  對照過 Odoo：`stock.move` 指向 `sale_line_id` 而沒有 `sale_order_id`，但它是 monolith，`move.sale_line_id.order_id` 穿過去毫無代價。我們的約束不同。

## 5. ordering 消費配貨結果

- [x] 5.1 依 `order-intake` 的 **Ordering advances an order's status from allocation's events**，新增 `ordering/entrypoint/kafka/`：consumer、`OrderAllocatedIntegrationEventHandler`、`BackorderCreatedIntegrationEventHandler`。行為上：`promising.allocation-events` 第一次有 consumer。以 SIT 與壓測驗證。

  **error handling config 不新增**（原任務寫「對應 allocation 既有設定」）。`CommonErrorHandler` 是單一 bean，Spring Boot 自動套到所有 `@KafkaListener`——新的 ordering consumer 已經被涵蓋，再建一個反而讓自動選擇失敗。而且 ordering 那條路徑不需要不同的策略：它沒有樂觀鎖衝突要退避，任何例外直接進 DLT 正是想要的，而「訂單已取消」這種合理競爭在 `ConfirmOrderUsecase` 就當成 no-op 了。只改了那份 config 的註解。

- [x] 5.5 **（實作時新增）**新增 `allocation/domain/event/OrderBackorderRecorded`，translator 改監聽它而不是 ordering 的 `OrderBackordered`。行為上：缺貨的事實由 allocation 自己陳述。以事件測試與架構檢查驗證。

  **不做會形成無限循環。** translator 原本監聽 ordering 的 `OrderBackordered`，而 ordering 收到缺貨的對外事件後會呼叫 `markBackOrdered()`——那會**再產生**一個 `OrderBackordered`，於是「發事件 → 改狀態 → 產生領域事件 → 又發事件」永遠跑下去。兩個 context 各發各的事實、不互相監聽，循環才形成不了。

- [x] 5.6 **（實作時新增）**`OrderAllocatedIntegrationEvent` 與 `BackorderCreatedIntegrationEvent` 補上 `@JsonCreator` 與 `@JsonProperty`。行為上：ordering 反序列化得了它們。以端到端 SIT 驗證。

  **這是一個真缺陷，不是測試問題。** 那兩則事件在 R4 之前只被序列化寫進 outbox，因為 `promising.allocation-events` 沒有任何 consumer；ordering 一開始消費就在 `Cannot construct instance ... no Creators` 炸掉。**只驗「outbox 有事件」永遠發現不了**——那正是端到端 SIT 選擇把 outbox 餵回 ordering、而不是改斷言的理由。

- [x] 5.2 新增 `ConfirmOrderUsecase`：收事件、依 `orderId` 重讀訂單、推進狀態。行為上：事件只帶識別碼與時間戳，狀態由重讀決定，兩者不可能不一致。以單元測試驗證。

  R3 已經把四則生命週期事件瘦成只帶 `orderId` 與時間戳，正好是重讀所需要的全部——這個 change 不必改事件契約。

- [x] 5.3 **對已取消的訂單為 no-op，不是失敗。** 行為上：配貨事件抵達時訂單已 CANCELLED，handler 安靜跳過、不拋錯、不落 DLT。以 SIT 模擬交錯驗證。

  配貨完成與使用者取消是併發的，而這個 change 之後兩者不再由同一個交易序列化。當成失敗的話，**每一次剛好撞上的正常取消都會製造一筆 DLT 訊息**。allocation 側的預留已由取消事件釋放，兩邊都正確。

- [x] 5.4 ordering 接上既有的 inbox 去重機制。行為上：同一則事件重送不會推進第二次。以 SIT 重送同一則事件並斷言時間戳不變驗證。

## 6. 架構測試與驗收

- [x] 6.1 新增架構測試：allocation package **不得 import `ordering.domain.model.Order`**，且 allocation 的原始碼**不得出現 `orders`／`order_lines` 字串**（含 SQL 與 JdbcTemplate）。行為上：繞過型別直接寫表也會被擋。以植入違反確認測試變紅驗證。

  只檢查 import 擋不住「不 import 但在 SQL 字串裡寫表名」。allocation 查的是 `demand_lines`，所以這條規則不需要為讀取開例外。

- [x] 6.2 斷言**每張表只有一個 module 寫**：`orders`／`order_lines` 只由 ordering 寫，`stock_pools`／`stock_reservations` 只由 allocation 寫。行為上：寫入權責可驗證而不只是約定。以測試驗證。

- [x] 6.3 依 `fifo-replenishment-demo` 的 **Wake a queued backorder list on StockReplenished**，`AllocationFifoReplenishmentBatchIntegrationTest` 的種子加上倉別，並新增一支「另一個倉的更早訂單不進入本輪、不佔用上限」的斷言。行為上：跨倉的浪費被擋在查詢層。以「把倉別篩選拿掉、確認該支失敗」驗證。

- [x] 6.4 依 `order-intake` 的 **Backorder queues are scoped to one owner, one warehouse, and one SKU**（該 requirement 的「識別碼時間有序」段落），新增一支測試釘住 `IdGenerator` 產生的識別碼時間有序：連續產生的 id，後者排序必在前者之後。行為上：改回 UUID v4 會讓這支變紅。以「把 `IdGenerator` 換成 `UUID.randomUUID()`、確認該支失敗」驗證。

  **FIFO 的整個順序保證都架在這件事上**，而它現在沒有任何測試蓋著。UUID v7 把時間戳編在主鍵裡，所以 `ORDER BY order_id` 等於到達順序；換成 v4 之後佇列會靜默變成亂序——不拋錯、不留 log，既有測試一支都不會紅，因為它們斷言的是「哪些單被配到」而不是「順序」。

- [x] 6.5 超賣防線不受影響：壓測仍為 500 配到／500 缺貨、不超賣。行為上：`StockPool` 的樂觀鎖與 `canReserve()` 完全沒動。以壓測驗證。

## 7. 收尾

- [x] 7.1 更新 `docs/dom-order-intake-scope.md` 的 view 定義：補上 `node_id`，排序鍵改為 `order_id`（UUID v7）。行為上：來源文件與實作一致。以文件審閱驗證。

  那份文件寫於 R2 與 R3 之前，view 定義因此少了倉別、排序鍵的討論還停在「line 上有 `backordered_since`」的前提上。

- [x] 7.2 更新 `docs/execution-roadmap.md`：R4 的驗收條件「既有的配貨、缺貨、補貨重配流程行為完全不變」改寫——FIFO 基準與喚醒範圍都改了；R8 的「不搬遷任何結構、不加任何欄位、不改任何 index」改寫——本 change 已經改了 `order_lines` 與它的 index。行為上：roadmap 不再描述一個已經不成立的前提。

- [x] 7.3 後端 `test` 與 `sit`、前端 `vitest` 與 `tsc` 全綠，`spectra validate --strict` 通過。

  後端 unit **261 支**、SIT **107 支**、前端 38 支，全綠；壓測四條門檻全過。

- [x] 7.4 **（實作時新增）**SIT 的測試資料改用 `IdGenerator.nextId()` 取代 `UUID.randomUUID()` 當訂單識別碼（八個檔案）。行為上：測試資料與 production 用同一個生成方式。

  **不改的話有 13 支 SIT 莫名其妙地紅。** 佇列改以 `order_id` 排序之後，用 v4（隨機）建的訂單佇列順序就是隨機的——「blocker 卡在隊首」那支因此讀到的根本不是它以為的隊列。系統裡沒有任何路徑會產出 v4 的訂單 id，那些測試本來就在測一個不存在的情境。

- [x] 7.5 **（實作時新增）**新增 `testsupport/AllocationOutcomeDrain`：把 outbox 的配貨結果事件餵回 ordering 的 dispatcher，供端到端 SIT 使用。行為上：SIT 走得完「配貨發事件 → ordering 推進訂單」這一段。

  production 裡 Debezium 做這件事，SIT 沒有它，事件會停在 outbox、訂單狀態永遠不動。**選擇補完鏈路而不是把斷言改成只看 outbox**——後者證明不了 ordering 消費得了那些事件，而 5.6 的 `@JsonCreator` 缺陷正是這樣被抓到的。

- [x] 7.6 **（實作時新增）**`e2e/perf/k6/hot-sku-burst.js` 的延遲起點由 `placedAt` 改為 `receivedAt`。行為上：`order_decision_latency_ms` 重新量得到東西。

  **前一個 change 的殘留掃描漏掉了 JavaScript。** `placedAt` 現在是「上游說客戶下單的時刻」，壓測不送它，所以是 `null`；`Date.parse(null)` 得到 NaN，k6 丟警告後把樣本丟掉，門檻於是在**零個樣本**上通過並印出 `p(99)=0s`。四條門檻全綠而其中一條什麼都沒量——那是最糟的一種綠燈。修正後量到 p99 3.68s。
