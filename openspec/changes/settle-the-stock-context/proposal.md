## Why

四個 change 之後，這個 context 已經不只做配貨了——它建搬運、收貨、鎖定、取消，而
`AllocationService` 只是其中一步裡的一個協作者。**package 卻還叫 `allocation`。**

```text
allocation/
├── application/movement/   ← 建立、鎖定、完成、取消
├── domain/model/           ← StockPool、StockMove、StockPicking、StockMoveLine
└── domain/service/         ← AllocationService（真的在配貨的那一個）
```

它擁有的四張表全是 `stock_*`，三個 spec 也是 `stock-*`。只有 Java 那一側停在舊名字。

同時，`order_lines.status` 仍然存在，而它的存在理由已經被自己的 javadoc 推翻：留著是為了
「放寬多行之後畫面不必改契約就能逐行顯示」——但 **ship-complete 保證那些值永遠等於 header**，
多行之後也一樣。前端型別的註解甚至寫著同一句話：「`status` 隨整張單走」。

## What Changes

**package `allocation` → `stock`。** 112 個 Java 檔的路徑與 import。

**`Allocation*` 的型別不一律改。** 這是這個 change 最容易做過頭的地方——改的是 **context 的
名字**（它做的事比配貨多），不是**配貨這個概念**（它仍然存在且仍然叫配貨）。
`AllocationService`、`AllocationOutcome`、`AllocationPlan` 名副其實，留著。

**`ReleaseReservation*` → `CancelMovements*`。** 預留已經不是一個獨立的東西了，它是搬運被
鎖定的狀態；而那支 usecase 做的事是取消一張單的搬運。

**`order_lines.status` 移除**，但 REST 的逐行 `status` **保留並改為由 header 導出**。值一個字
都不變（本來就恆等），前端因此完全不動——差別在它從「存起來的第二份真相」變成「讀取時的
組合」。

## Impact

- Affected specs: `order-intake`（逐行狀態那條 requirement 改寫）、
  `order-promising-http-api`（逐行 status 改為導出）
- Affected code: 整個 `allocation` package 的路徑、`ReleaseReservation*` 兩個型別、
  `V2` 的 `order_lines.status` 欄位、`OrderLine` 與 `OrderStatusResponse`
- **不動**：對外 Kafka 事件與 topic、設定鍵、前端、資料庫其餘部分、任何行為

## 不做的事

| 不做 | 理由 |
| --- | --- |
| Kafka topic `promising.allocation-events` | 對外契約，而名字站得住——與 `/stock-pool` 端點同一個判準：沒有債就沒有要償的 |
| 設定鍵 `archone.allocation.partition-key-strategy` | 前端逐字讀它（`AppHeader.tsx`）。改它要同時動兩側，換不到任何精確度 |
| `AllocateOrderUsecase` 改名 | 它現在做「接手 + 配貨」，但配這張單仍然是它的目的，建立搬運是其中一步 |
| `-er` 那組（`MovementRecorder`⋯⋯）改成活動名詞 | 見 design。`-er` 是這個 codebase 的 ubiquitous language，換掉會讓它們成為唯一的例外 |
| `orders.status` 的 `BACKORDERED` / `ALLOCATED` | **已決定都留為投影**（見 scope 的「決定：都留為投影」）。它們與 `order_lines.status` 不是同一件事——前者是跨 context 的投影，後者連投影都不是 |
| capability 改名（`stock-allocation` 等） | 已經是 `stock-*` 開頭，沒有債 |
