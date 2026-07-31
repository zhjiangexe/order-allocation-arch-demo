## 1. Schema 與 view

- [ ] 1.1 依 `order-intake` 的 **A line's status mirrors its header, and no timestamp is stored on it**，以及 design 的決策「砍掉 `order_lines.backordered_since` 與它那支 index」，改寫 `V2__create_ordering_tables.sql`：砍掉 `order_lines.backordered_since` 與 `idx (owner_id, sku_code, backordered_since, id)`，index 改為 `(owner_id, sku_code)`。行為上：schema 少一個沒有讀取者的欄位。以測試通過與 schema 審閱驗證。

  那個欄位的註解寫著「純粹是為了建出下方的單表 FIFO index」，而 R3 已經查明該查詢從來就是 join、排序取自 `orders`——**欄位沒有讀取者，index 的排序段從來沒被用上**。

  沿用 R1～R3 的判準改寫既有 migration 而非新增——schema 未部署，新開一支只會在歷史上留下「建了又砍」的假歷史。

  **`order_lines` 不加倉別與下單時間**（design 的決策「倉別與下單時間留在 header，view 直接 join 取值」）。物化它們能建出覆蓋 index，但那個理由的全部內容是查詢效能，而現在沒有任何量測支持它成立。R1 的 **A line inherits its order's warehouse rather than carrying its own** 因此完全不動。

- [ ] 1.2 新增 migration 建 `demand_lines` view，依 `stock-allocation` 的 **Outstanding demand is decided by reservations, not by an order's status** 與 design 的決策「FIFO 的排序鍵是 `order_id`」：欄位為 `order_id`、`order_line_id`、`owner_id`、`node_id`（取 `o.fulfillment_node_id`）、`sku_code`、`quantity`、`received_at`（取 `o.received_at`，供顯示用，不是排序鍵）；條件為訂單未取消，且該行沒有 `status IN ('ACTIVE','CONSUMED')` 的預留。行為上：view 回答「現在還欠什麼」。以 SIT 涵蓋 ACTIVE／CONSUMED／RELEASED／已取消四種情形驗證。

  **`CONSUMED` 現在不會出現，但謂詞必須現在就寫對。** 它在 R7 出貨扣帳時才產生，屆時若謂詞只寫 `ACTIVE`，每一張已出貨的訂單都會重新變成待配需求而被配第二次——而那一刻沒有任何測試會失敗，因為出貨流程還不存在。

- [ ] 1.3 view **刻意不含 `order_lines.status`**。行為上：任何呼叫端都拿不到那個欄位，因此不可能拿它當閘門。以 view 定義審閱驗證。

  ordering 的配貨狀態由事件推進，落後於 allocation 的決策：allocation 配到貨、發事件，ordering 還沒處理完，`status` 仍是 BACKORDERED；此時第二筆補貨進來又讀到同一筆需求，就重複預留。**讀不到比讀得到而約定不用更強**。

## 2. allocation 的需求模型

- [ ] 2.1 依 `stock-allocation` 的 **Allocation takes its demand from a published view, never from the order aggregate**，新增 `allocation/domain/model/Demand` 與 `DemandLine`：`Demand(orderId, ownerId, nodeId, receivedAt, List<DemandLine>)`、`DemandLine(orderLineId, skuCode, quantity)`，皆為唯讀 record。行為上：allocation 有自己的需求型別，不再持有 `Order`。以領域測試驗證。

  依 design 的決策「allocation 自己的需求模型叫 `Demand`，不叫 `DemandOrder`」，**名字不帶 `Order`**：它與 `ordering.Order` 描述同一張現實中的單，但那是兩個 context 的兩個模型——後者 15 個欄位、一組狀態機與 domain events 且可變。名字帶 `Order` 會引來「為什麼它沒有 status」，而這個 change 的重點正是 allocation 不該認識那個東西。

  **`ownerId` 與 `nodeId` 放在 `Demand` 而不是 `DemandLine`**：一張單一個貨主一個倉，放行上是重複。view 的每一列都帶（它是行的 view），映射時提到 order 層級。

- [ ] 2.2 `Demand.demandFor(String skuCode)`：從 `lines` 摺疊出該 SKU 的需求量。行為上：同一 SKU 有多行時加總，單行時等於那一行。以領域測試涵蓋兩種情形驗證——**多行的那支現在就要寫**，它是 R8 放寬收單時唯一不必改的部分。

- [ ] 2.3 新增 `allocation/domain/repository/DemandRepository`，唯讀，兩個方法：依 `(ownerId, nodeId, skuCode, limit)` 取分組佇列、依 `orderId` 取單筆。行為上：allocation 取得需求只經過這一個介面。以介面審閱與 SIT 驗證。

## 3. 佇列查詢

- [ ] 3.1 依 `order-intake` 的 **Backorder queues are scoped to one owner, one warehouse, and one SKU**、`stock-allocation` 的 **Allocation takes its demand from a published view, never from the order aggregate**，以及 design 的決策「佇列查詢分兩段：先選單，再取整張單的全部待配行」與「`demand_lines` 帶倉別，佇列查詢依倉別篩選」，實作分組佇列查詢：**① 選單**——哪些訂單有待配行命中 `(貨主, 倉, SKU)`，依 `order_id` 排序取前 N 張（UUID v7，等於到達順序）；**② 取行**——以 `order_id` 對 `demand_lines` **自我 join**，取出①選中訂單的全部待配行。行為上：一張單有兩個 SKU 的待配行時，兩行都在同一個 `Demand` 裡。以 SIT 驗證。

  ② **不得 join `order_lines`**——那會讓 allocation 的 SQL 出現該表名，而 6.1 的驗收禁止它。`demand_lines` 自我 join 拿得到同樣的東西。

  只撈命中該 SKU 的行會漏掉同一張單其他 SKU 的行，那就無從判斷 ship-complete 要的「整籃同時可滿足」。

- [ ] 3.2 回傳型別是 `List<Demand>`，**不是 `Map<UUID, List<DemandLine>>`**。行為上：順序即 FIFO 順序。以測試斷言順序驗證。

  順序是 `List` 的性質。`LinkedHashMap` 的保序要靠註解維持——有人換成 `HashMap`，FIFO 就壞了，不拋錯、不留 log，佇列只是悄悄變成亂序。`receivedAt` 在 Map 裡也沒有地方放，而 policy 要用它。

- [ ] 3.3 移除 `OrderRepository.findBackordersBySkuInFifoOrder()` 與其實作。行為上：ordering 不再為 allocation 提供查詢。以編譯與測試通過驗證。

## 4. 配貨路徑不再碰 Order

- [ ] 4.1 `AllocationSelector`、`AllocationPolicy`、`MaximizeFulfilledOrdersPolicy`、`StrictFifoAllocationPolicy` 的 `List<Order>` 改為 `List<Demand>`。行為上：既有的挑選行為不變。以既有測試改寫後通過驗證。

  依 design 的決策「演算法維持單行，型別與查詢做成多行的形狀」，**演算法本身不動**：餘量仍是單一純量。整籃原子判斷（per-SKU 餘量映射、`break` 判準改為「任一 SKU 不足」）是 R8 任務 2，此處不做——收單仍限制單行，提前做的唯一驗證方式是直接建構多行 `Demand` 繞過收單，而那證明不了真實路徑。

- [ ] 4.2 `OrderAllocation` 由 `(Order, List<BatchPick>)` 改為 `(Demand, List<BatchPick>)`。行為上：配貨結果不再持有 aggregate。以編譯與既有測試驗證。

- [ ] 4.3 依 `stock-allocation` 的 **Allocation publishes its outcome and writes only its own tables**，`OrderAllocationCoordinator` 移除 `OrderRepository` 注入、移除 `order.markAllocated()` 與 `order.markBackOrdered()`、移除代 `Order` 發 domain event。`AllocationService` 移除 `order.markAllocated()`。行為上：一個交易只改 `stock_pools` 與 `stock_reservations`。以 SIT 斷言配貨交易中 `orders` 的 `version` 不變驗證。

- [ ] 4.4 `AllocateOrderUsecase` 與 `ReleaseReservationUsecase` 改用 `DemandRepository` 依 `orderId` 取單筆。行為上：兩條路徑都不再 `findById` 一個 `Order`。以既有 SIT 通過驗證。

## 5. ordering 消費配貨結果

- [ ] 5.1 依 `order-intake` 的 **Ordering advances an order's status from allocation's events**，新增 `ordering/entrypoint/kafka/`：consumer、`OrderAllocatedIntegrationEventHandler`、`BackorderCreatedIntegrationEventHandler`、error handling config（對應 allocation 既有設定）。行為上：`promising.allocation-events` 第一次有 consumer。以 SIT 驗證。

- [ ] 5.2 新增 `ConfirmOrderUsecase`：收事件、依 `orderId` 重讀訂單、推進狀態。行為上：事件只帶識別碼與時間戳，狀態由重讀決定，兩者不可能不一致。以單元測試驗證。

  R3 已經把四則生命週期事件瘦成只帶 `orderId` 與時間戳，正好是重讀所需要的全部——這個 change 不必改事件契約。

- [ ] 5.3 **對已取消的訂單為 no-op，不是失敗。** 行為上：配貨事件抵達時訂單已 CANCELLED，handler 安靜跳過、不拋錯、不落 DLT。以 SIT 模擬交錯驗證。

  配貨完成與使用者取消是併發的，而這個 change 之後兩者不再由同一個交易序列化。當成失敗的話，**每一次剛好撞上的正常取消都會製造一筆 DLT 訊息**。allocation 側的預留已由取消事件釋放，兩邊都正確。

- [ ] 5.4 ordering 接上既有的 inbox 去重機制。行為上：同一則事件重送不會推進第二次。以 SIT 重送同一則事件並斷言時間戳不變驗證。

## 6. 架構測試與驗收

- [ ] 6.1 新增架構測試：allocation package **不得 import `ordering.domain.model.Order`**，且 allocation 的原始碼**不得出現 `orders`／`order_lines` 字串**（含 SQL 與 JdbcTemplate）。行為上：繞過型別直接寫表也會被擋。以植入違反確認測試變紅驗證。

  只檢查 import 擋不住「不 import 但在 SQL 字串裡寫表名」。allocation 查的是 `demand_lines`，所以這條規則不需要為讀取開例外。

- [ ] 6.2 斷言**每張表只有一個 module 寫**：`orders`／`order_lines` 只由 ordering 寫，`stock_pools`／`stock_reservations` 只由 allocation 寫。行為上：寫入權責可驗證而不只是約定。以測試驗證。

- [ ] 6.3 依 `fifo-replenishment-demo` 的 **Wake a queued backorder list on StockReplenished**，`AllocationFifoReplenishmentBatchIntegrationTest` 的種子加上倉別，並新增一支「另一個倉的更早訂單不進入本輪、不佔用上限」的斷言。行為上：跨倉的浪費被擋在查詢層。以「把倉別篩選拿掉、確認該支失敗」驗證。

- [ ] 6.4 依 `order-intake` 的 **Backorder queues are scoped to one owner, one warehouse, and one SKU**（該 requirement 的「識別碼時間有序」段落），新增一支測試釘住 `IdGenerator` 產生的識別碼時間有序：連續產生的 id，後者排序必在前者之後。行為上：改回 UUID v4 會讓這支變紅。以「把 `IdGenerator` 換成 `UUID.randomUUID()`、確認該支失敗」驗證。

  **FIFO 的整個順序保證都架在這件事上**，而它現在沒有任何測試蓋著。UUID v7 把時間戳編在主鍵裡，所以 `ORDER BY order_id` 等於到達順序；換成 v4 之後佇列會靜默變成亂序——不拋錯、不留 log，既有測試一支都不會紅，因為它們斷言的是「哪些單被配到」而不是「順序」。

- [ ] 6.5 超賣防線不受影響：壓測仍為 500 配到／500 缺貨、不超賣。行為上：`StockPool` 的樂觀鎖與 `canReserve()` 完全沒動。以壓測驗證。

## 7. 收尾

- [ ] 7.1 更新 `docs/dom-order-intake-scope.md` 的 view 定義：補上 `node_id`，排序鍵改為 `order_id`（UUID v7）。行為上：來源文件與實作一致。以文件審閱驗證。

  那份文件寫於 R2 與 R3 之前，view 定義因此少了倉別、排序鍵的討論還停在「line 上有 `backordered_since`」的前提上。

- [ ] 7.2 更新 `docs/execution-roadmap.md`：R4 的驗收條件「既有的配貨、缺貨、補貨重配流程行為完全不變」改寫——FIFO 基準與喚醒範圍都改了；R8 的「不搬遷任何結構、不加任何欄位、不改任何 index」改寫——本 change 已經改了 `order_lines` 與它的 index。行為上：roadmap 不再描述一個已經不成立的前提。

- [ ] 7.3 後端 `test` 與 `sit`、前端 `vitest` 與 `tsc` 全綠，`spectra validate --strict` 通過。
