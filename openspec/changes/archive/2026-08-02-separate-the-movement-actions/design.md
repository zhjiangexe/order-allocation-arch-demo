## Context

`docs/dom-stock-movement-scope.md` 的「決定七」記錄了這個切法的理由與命名，本文只補實作層面
的取捨。

上一個 change 交付後的形狀：

```text
OrderPlaced ──▶ AllocateOrderUsecase（10 個依賴）
                  ├ inbox 冪等
                  ├ demandRepository.findByOrderId
                  ├ createMovements ← 位置→倉→作業類型→建單→建搬運（4 個依賴）
                  ├ stockPoolRepository.findAllocatableBatchesBySku
                  └ coordinator.allocateOrder / backorderOrder

StockReplenished ▶ ReplenishmentUsecase
                  ├ inbox 冪等 → upsert → 守門查詢
                  ├ waitingDemandFinder.findWaiting  → List<Demand>
                  ├ stockPoolRepository.findAllocatableBatchesBySku ← 與上面同一支查詢
                  └ coordinator.allocateBackorders → 續做判斷

OrderCancelled ─▶ ReleaseReservationUsecase
                  ├ 找單據 → 取搬運 → 濾掉已完成 → 取明細 → 逐條取批
                  └ coordinator.releaseMoves

                  OrderAllocationCoordinator（4 個依賴）
                  ├ allocateOrder      ─┐
                  ├ allocateBackorders ─┴─▶ AllocationService（純決策）
                  ├ backorderOrder ──────▶ 只發事件
                  └ releaseMoves ────────▶ 完全不碰 allocationService
```

## Goals / Non-Goals

**Goals**

- 建立搬運成為一個可以獨立呼叫的動作，好讓下一個 change 的入庫直接接上
- 取批查詢只有一份
- 寫入庫存列的全序有一個具名的、共用的定義
- 三支 usecase 的依賴數降到能一眼看完

**Non-Goals**

- 不改任何行為。這個 change 的驗收條件是**既有測試全綠且斷言不改**
- 不動 schema、不動對外契約、不動前端
- 不做 R7 的完成動作

## 決策

### `MovementAssigner` 接收搬運，不接收需求

現況有一次多餘的往返：收單建完搬運就把它們丟掉，`OrderAllocationCoordinator.assign` 再用
`order_line_id` 把同一批搬運讀回來；補貨路徑一樣——`WaitingDemandFinder` 讀了搬運、投影成
`Demand`、丟掉，coordinator 再讀一次。

那不是效能取捨，是分層的副作用：`Demand` 帶不動搬運，而它當時是兩者之間唯一的介面。

改成 `MovementAssigner` 直接收 `List<StockMove>`：

```text
收單：recorder.recordOutbound(demand, now) ──▶ List<StockMove> ──▶ assigner.assign(moves, now)
補貨：stockMoveRepository.findWaitingInFifoOrder(...) ─▶ List<StockMove> ─▶ assigner.assignAll(moves, now)
```

兩個呼叫端手上本來就有那些搬運。投影成 `Demand` 的那段程式碼（原
`WaitingDemandFinder.toDemands`）搬進 `MovementAssigner` 成為私有方法——**`AllocationService`
的輸入型別完全不變**，投影只是換了住處。

**連帶消掉一個失敗模式。** 現在 `assign` 找不到對應搬運時會拋
`No movement exists for order line`；搬運由呼叫端傳入之後，那個情形在型別上就不存在了。

`WaitingDemandFinder` 因此消失。它存在的理由（「讓演算法不必改」）由 `MovementAssigner` 內部
的投影承接，理由不變。

### 取批查詢歸 `MovementAssigner`

「這些搬運要用到哪些批」是鎖定這個動作的一部分，不是入口的一部分。兩個入口各寫一次的形狀，
在補貨那邊還多一段「算出候選單涉及的 SKU 聯集」——那段也一起搬進去。

**補貨的守門查詢留在 `ReplenishmentUsecase`。** 它問的是另一個問題：「補的這個 SKU 有沒有量
可配，這一輪需不需要開始」。它在佇列查詢**之前**執行，正是為了在沒有量時連佇列都不必查。

### `backorderOrder` 留在 usecase

它只發一個領域事實，不碰任何表。而兩條路徑對「配不到」的處置不同：收單配不到要掛帳、補貨配
不到不重發（那些單本來就已經掛帳了）。放進共用的 `MovementAssigner` 會逼出一個布林參數，而
布林參數是「這個方法其實是兩個方法」的訊號。

`AllocateOrderUsecase` 因此持有 `ApplicationEventPublisher`。

### `WRITE_ORDER` 抽成共用常數

現在它是 `OrderAllocationCoordinator` 的 private static field。這個 change 之後
`MovementAssigner` 與 `MovementCanceller` 都要寫 `stock_pools`，R7 的完成動作會是第三個。

三份複本會漂移，而漂移的症狀是**併發下偶爾死鎖**——最難重現的一類問題，壓測跑不出來，正式
環境才偶爾出現。收成一處：

```java
public final class StockWriteOrder {
  public static final Comparator<StockPool> BY_GLOBAL_ORDER = ...;
  public static void saveInOrder(Collection<StockPool>, StockPoolRepository);
}
```

放在 `allocation/domain/`——它是庫存這個聚合的寫入規則，不是應用層的協調細節。

### `StockOperationRecorder` 回傳它建立的搬運

不回傳 `void`，也不只回傳單據 id：呼叫端接著要把它們交給 `MovementAssigner`，而那正是上一個決策
消掉往返的方式。

下一個 change 的入庫會用同一個元件、不同的入口方法（`recordInbound`），**兩個方法而不是一個
帶方向參數的**——兩邊的輸入本來就不同型（一邊是 `Demand`，一邊是收貨的內容）。

### `MovementCanceller` 吸收查詢組裝

`ReleaseReservationUsecase` 現在自己做四段查詢（找單據 → 取搬運 → 濾掉已完成 → 取明細 → 逐條
取批）才呼叫 coordinator。那整段是「取消一張單的搬運」這個動作的內容，不是入口的責任。

usecase 因此只剩：冪等 → `canceller.cancelFor(orderId, now)`。

## 移動對照

| 現在住在哪 | 之後住在哪 |
| --- | --- |
| `AllocateOrderUsecase.createMovements` | `MovementRecorder.recordOutbound` |
| `AllocateOrderUsecase` 的取批查詢 | `MovementAssigner` |
| `ReplenishmentUsecase` 的取批查詢與 SKU 聯集 | `MovementAssigner` |
| `WaitingDemandFinder.toDemands` | `MovementAssigner`（私有） |
| `coordinator.allocateOrder` / `allocateBackorders` / `assign` / `persistAllocation` | `MovementAssigner` |
| `coordinator.backorderOrder` | `AllocateOrderUsecase`（直接發事件） |
| `coordinator.releaseMoves` + `ReleaseReservationUsecase` 的查詢組裝 | `MovementCanceller` |
| `coordinator.WRITE_ORDER` | `StockWriteOrder`（domain） |
| `AllocationService` | **原地不動** |

## Risks / Trade-offs

**這個 change 沒有任何行為要驗，因此驗收全靠既有測試。** 那是它的風險：一個「行為不變」的
change，若不小心改到了行為，唯一會發現的地方就是既有測試。因此——

- 三支護欄測試（FIFO 保證範圍、熱點 SKU 併發、批次上限）**斷言一行都不准改**
- 端到端測試的斷言同樣不准改；改 fixture 可以，改斷言不行
- 單元測試會大幅改寫（被測的類別換了），但**每一條原本在驗的性質都要在新的測試裡找得到**，
  對照表寫進 tasks

**`MovementAssigner` 會是這幾個元件裡最大的一個**：取批、投影、決策、轉狀態、寫入、發事件。
那是刻意的——它們是同一個動作的六個步驟，拆開只會讓「鎖定」這件事沒有一個完整的落點。若它日
後長到讀不完，該拆的是「規劃」與「套用」，而不是把步驟散回各個 usecase。

**投影搬家會讓 `MovementAssigner` 依賴 `StockPickingRepository`**（要從單據取 `order_id` 才發
得出事件）。那是一個新的依賴，但它取代的是 `WaitingDemandFinder` 原本就有的同一個依賴。
