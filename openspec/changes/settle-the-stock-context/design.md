## Context

`docs/dom-stock-movement-scope.md` 記錄的第四個、也是最後一個 change。前四個把模型換掉了；
這一個讓名字追上模型，並清掉一個連投影都不是的欄位。

**這個 change 幾乎全是命名**，所以它的風險不是「改壞」而是「改過頭」——把一個仍然精確的名字
也一起換掉，換不到精確度，卻讓所有既有文件的引用失效。

## Goals / Non-Goals

**Goals**

- Java 那一側的名字與資料庫、spec 對齊（全部是 `stock`）
- 「預留」這個已經不存在的東西從型別名裡消失
- `order_lines.status` 移除，而 REST 契約一個欄位都不變

**Non-Goals**

- 不改任何行為
- 不動對外 Kafka 事件、topic、設定鍵、前端
- 不改 `orders.status`（`BACKORDERED` / `ALLOCATED` 已決定留為投影）

## 決策

### `stock` 而不是 `inventory`

第一個 change 的 design 記的是 `inventory`。**推翻它的是一個名字衝突**：
`LocationUsage.INVENTORY` 已經佔用了這個詞，指的是盤點調整的虛擬位置。同一個字在同一個
context 裡指兩件事，是比舊名字更糟的狀態。

`stock` 另有三組證據：四張表全是 `stock_*`、三個 spec 是 `stock-*`、而 `StockPool` /
`StockMove` / `StockPicking` / `StockMoveLine` 這些型別本來就以它開頭。

### 改的是 context 的名字，不是配貨這個概念

**這個 change 最容易做過頭的地方。** `allocation` 這個 package 名不準，是因為這個 context
做的事比配貨多；但配貨仍然存在，而且仍然叫配貨。

| 改 | 不改 |
| --- | --- |
| package `allocation` → `stock` | `AllocationService`——它真的在配貨 |
| `ReleaseReservation*` → `CancelMovements*` | `AllocationOutcome` / `AllocationResult` / `AllocationPlan` / `AllocationRequest` |
| | `AllocationSelector` / `AllocationPolicy` / `AllocationContext` |
| | `OrderAllocationCompleted` / `OrderAllocated`——事件說的正是配貨這件事 |
| | `AllocationRetryExecutor` / `AllocationDomainEventTranslator` |
| | `AllocationBoundaryArchitectureTest`——它守的是配貨與需求之間那條界線 |

判準只有一句：**這個名字說的是「配貨」還是「這個 context」？** 說前者就留。

### `ReleaseReservation*` → `CancelMovements*`

`ReleaseReservationUsecase` 的 javadoc 自己寫著：「類別名還叫 `ReleaseReservation`，而預留
已經不是一個獨立的東西了——它是一段搬運被鎖定的狀態。」

新名字取自它實際做的事，與 `MovementCanceller` 對得起來（usecase 委派給它）。
`ReleaseReservationCommand` 一併改，而**對外事件 `OrderCancelledIntegrationEvent` 不動**——
那是 ordering 發的，說的是訂單被取消，與這一側怎麼稱呼它的處置無關。

### `order_lines.status`：欄位刪掉，REST 欄位留著

`OrderLine` 的 javadoc 說 `status` 留著是因為「放寬多行之後畫面不必改契約就能逐行顯示」。
**那個理由不成立**：ship-complete 保證一張單的所有行同進同出，多行之後那些值仍然恆等於
header。前端的 `OrderLineView` 註解甚至寫著同一句話——「`status` 隨整張單走」。

所以它不是投影，是**同一份資料存兩次**。

但 REST 的逐行 `status` **保留**，改由 header 導出：

```java
// OrderStatusResponse
new Line(line.getLineNo(), line.getSkuCode(), line.getQuantity(), order.getStatus().name())
```

值一個字都不變，前端因此完全不動。差別在它從「存起來的第二份真相」變成「讀取時的組合」——
而那正是這個 change 想表達的東西：**恆等於別人的東西不該有自己的欄位。**

`OrderLine` 因此完全沒有可變狀態，`markAllocated` / `markBackOrdered` / `markCancelled`
三個方法一起消失。

### `-er` 明確不改

`MovementRecorder` / `Assigner` / `Completer` / `Canceller` 不是 DDD 文獻推薦的形式——
Evans 一系會把 `-er` 視為「以動作者命名」，也就是披著物件外衣的程序，並建議用活動名詞
（`MovementRecording`）或用例名（`RecordMovement`）。

**但 ubiquitous language 是每個 context 自己的。** 這個 codebase 通篇是
`AllocationRetryExecutor`、`AllocationDomainEventTranslator`、`KafkaIntegrationEventDispatcher`、
`OutboxAppender`、`DevSeedDataInitializer`——`-er` 就是這裡的說法。換掉四個會讓它們成為唯一
的例外；要換就得整批換，而那與這個 change 的目的（讓名字追上模型）無關。

而且動詞的部分本來就是領域語彙：`record` / `assign` / `cancel` 取自 Odoo 的 `_action_*`，也
出現在 spec 與 `MoveState` 的值域裡。`-er` 只是「執行它的那個東西」在 Java 裡的寫法。

## Risks / Trade-offs

**這是一次大範圍的機械改動（112 個 Java 檔），而機械改動最容易在邊角出錯。** 上一次同類的
經驗（`node_id` → `location_id`）留下三個教訓，這裡逐一避開：

| 上次踩到的 | 這次怎麼避 |
| --- | --- |
| `\bnode_id\b` 差點改壞 `fulfillment_node_id` | 只改 package 宣告與 import 行，不做全文字串替換 |
| getter 沒跟著改，欄位與方法名不一致 | package 改名不涉及成員名，範圍天然收斂 |
| 誤改到對外事件的 getter | 對外事件與 topic 明列為不改，並在驗證階段 grep 確認 |

**`order_lines.status` 是 migration 改寫。** 專案未上線，照前四個 change 的作法直接改
`V2`，並移除 Postgres volume 重建。

**風險最高的一項其實是「改過頭」。** 判準寫在上面的表裡，而驗證階段要逐一核對留下來的
`Allocation*` 型別——若哪一個被改成 `Stock*`，那是這個 change 走偏的訊號。
