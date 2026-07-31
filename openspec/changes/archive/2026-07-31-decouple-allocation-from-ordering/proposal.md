## Why

配貨現在直接讀寫 ordering 的 `Order` aggregate：

```text
OrderAllocationCoordinator   order.markAllocated() / markBackOrdered()
                             orderRepository.save(order)
AllocateOrderUsecase         orderRepository.findById(orderId)
ReleaseReservationUsecase    orderRepository.findById(orderId)
ReplenishmentUsecase         orderRepository.findBackordersBySkuInFifoOrder(...)
```

三個問題，第一個與 module 邊界無關：

**一個交易同時修改三個 aggregate**（`Order`、`StockPool`、`StockReservation`）。aggregate 的
用途就是一致性邊界，一次交易跨三個邊界，即使兩個 context 在同一個 module 裡也一樣違反。

**`orders` 被兩個 module 寫**——ordering 寫下單與取消，allocation 寫配貨結果。表沒有單一
寫入者，「誰把這一列改成這樣」就沒有唯一答案。

**`promising.allocation-events` 有事件在發，卻沒有任何 consumer。** 配貨結果既走事件、又走
同一交易內的直接寫入，同一件事有兩條路徑陳述，而只有一條被消費。

## What Changes

**讀**：allocation 取得需求的來源由 `List<Order>` 改為 `demand_lines` view，映射成它自己的
`Demand` 與 `DemandLine`。view 由 ordering 側發布，「還欠什麼」由對 `stock_reservations` 的
`NOT EXISTS` 決定，不看 `order_lines.status`——ordering 的配貨狀態落後於 allocation 的決策，
拿它當閘門會重複預留。

**寫**：`OrderAllocationCoordinator` 不再注入 `OrderRepository`、不再呼叫 `markAllocated()`
與 `markBackOrdered()`。ordering 新增 Kafka 入口消費 `promising.allocation-events`，由
`ConfirmOrderUsecase` 推進 `Order` 的狀態。那個 topic 因此第一次有了 consumer。

**佇列查詢改為以訂單分組並依倉別篩選**：撈出「哪些訂單有待配行命中這個 `(貨主, 倉, SKU)`」，
再把那些訂單的**全部**待配行取出。少了倉別篩選，補南部倉的貨會撈進北部倉的缺貨單，那些單
查自己的倉找不到批次，白佔喚醒上限；少了分組，就無從判斷 ship-complete 要的「整籃同時可
滿足」。

**FIFO 的排序鍵由 `backordered_since` 改為 `order_id`。** 後者是 UUID v7，時間戳編在主鍵裡，
值等於這張單進系統的時刻——而佇列的順序只能是到達順序：在單抵達之前，系統對它一無所知。
不用 `placed_at`：前一個 change 之後那是上游給的下單時刻，可空，且由我們控制不了的時鐘決定。

**`order_lines` 砍掉 `backordered_since`。** 它的唯一存在理由是「讓佇列能從單一表篩選與排序」，
而那個查詢從來就是 join。倉別與下單時間**不**物化到行上——那樣能建出覆蓋 index，但支持它的
理由全部是查詢效能，而現在沒有任何量測。

## Capabilities

### Modified Capabilities

- `order-intake`——line 的欄位與 FIFO 佇列的範圍改變；ordering 開始消費配貨結果事件並自己
  推進訂單狀態，含「訂單已取消」視為 no-op。
- `stock-allocation`——需求來源改為 `demand_lines`，配貨不再認識 `Order`，也不再寫它。
- `fifo-replenishment-demo`——喚醒的候選集合改為依倉別篩選並以訂單分組。

## Impact

**BREAKING（行為）**

| 改變 | 影響 |
| --- | --- |
| FIFO 排序鍵 `backordered_since` → `order_id` | 同一批缺貨單的配貨順序可能與改動前不同 |
| 喚醒候選加上倉別篩選 | 補一個倉的貨不再喚醒其他倉的缺貨單 |
| 訂單狀態改為非同步推進 | 配貨完成到訂單狀態更新之間有短暫落後 |

最後一項的代價要說清楚：畫面上會看到「已配貨但列表仍顯示缺貨」。**對操作台無害，但不可當
配貨的閘門**——這正是 view 不含 `status` 的理由。

**BREAKING（schema）**

`order_lines` 砍掉 `backordered_since`，index 由 `(owner_id, sku_code, backordered_since, id)`
改為 `(owner_id, sku_code)`；新增 `demand_lines` view。沿用 R1～R3 的判準改寫既有 migration
而非新增——schema 未部署，新開一支只會在歷史上留下「建了又砍」的假歷史。

**不改的**：`StockPool` 的超賣防線（`canReserve()`、樂觀鎖、建構子檢查）完全不動。那條線與
「誰改 `Order`」無關。對外事件的 payload、topic、partition key 也不動——R3 已經把四則生命
週期事件瘦成只帶 `orderId` 與時間戳，正好是 ordering 重讀整張單所需要的全部。

**範圍邊界**：配貨演算法**維持單行**。型別（`Demand` 持有 `List<DemandLine>`）與查詢（以訂單
分組）現在就做成多行的形狀，因為那是 R8 不會再重寫的部分；而 `StrictFifoAllocationPolicy` 的
per-SKU 餘量映射留給 R8，因為收單仍限制單行，提前做沒有測試能驗證真實路徑。
