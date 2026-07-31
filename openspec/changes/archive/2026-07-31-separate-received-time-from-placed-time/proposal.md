## Why

`orders.placed_at` 的名字說的是客戶下單的時刻，值卻是我們自己寫的 `Instant.now()`：

```java
// PlaceOrderUsecase
Instant placedAt = Instant.now();
```

所以那個欄位存的一直是**我們收到這張單的時刻**，而客戶真正下單的時刻**根本沒有存**——
`PlaceOrderRequest` 收貨主、倉別、SKU、數量、地址、承諾日，沒有任何時間欄位。

兩個問題：

**名字說謊。** 讀規格或讀欄位的人會以為那是客戶的下單時間，據以計算的任何東西都會錯。

**少了一個 3PL 需要的事實。** 履約 SLA 通常從客戶下單算起，不是從我們收到算起；上游系統延遲
送單（網路、批次、重跑）在 3PL 是常態而非例外，兩個時間可能差幾分鐘也可能差一天。沒有存下
單時間，就無法分辨延遲來自上游還是來自我們。

## What Changes

- **`orders.placed_at` 更名為 `received_at`**，值與語意都不變——它一直是收單時刻。
- **新增可空的 `orders.placed_at`**，存上游給的下單時間。
- **下單 API 接受一個可選的下單時間欄位**。上游不給就是 NULL。
- **REST 回應同時帶兩個時間戳**，前端的訂單頁一併顯示。
- 排序基準仍然是收單時刻（改名後的 `received_at`），與改動前完全相同。

## Capabilities

### Modified Capabilities

- `order-intake`——訂單記錄兩個不同的時刻，各有各的權威來源與可空性。
- `order-promising-http-api`——下單命令接受可選的下單時間；訂單表示帶兩個時間戳；最近訂單的
  排序欄位改名。

`demo-console-frontend` **不需要改 requirement**：它已經要求「訂單頁的列表顯示訂單表示的每一
個欄位」，新增的欄位因此自動涵蓋。下單表單也不加時間欄位——表單模擬的是上游系統送單，而上游
給不給下單時間是上游的事；兩種情形由種子資料展示。

## Impact

**BREAKING（HTTP 契約）**

| | 改動 |
| --- | --- |
| 訂單表示 | `placedAt` 更名 `receivedAt`；新增可空的 `placedAt`（意義不同） |
| `POST /orders` | 新增可選欄位，接受上游的下單時間 |

**同一個名字換了意義**是這次最需要小心的地方：舊的 `placedAt` 是收單時刻，新的 `placedAt` 是
上游下單時刻，兩者在同一個欄位名下互換。任何只改名不改語意的讀取端都會安靜地讀到不同的東西。
這個 repo 的唯一消費端是自己的前端，會在同一次改完。

**BREAKING（schema）**：`orders` 欄位更名並新增一欄；`idx (placed_at DESC, id DESC)` 隨之改名。
沿用 R1～R3 的判準改寫 `V2` 而非新增 migration。

**不改的**：FIFO 佇列與最近訂單列表的排序基準都仍是收單時刻，順序與改動前完全一致。對外事件
的 payload 不變——R3 已經把生命週期事件瘦成只帶識別碼與時間戳，那個時間戳描述的是系統事實，
仍取收單時刻。

**為什麼獨立於 R4**：R4（`decouple-allocation-from-ordering`）是編排權歸位，範圍與時間戳無關。
把一次橫跨前後端的改名夾進去，會讓那個 change 的 delta 說不出自己在做什麼。先做這個，R4 的
文件就直接用新名字，不必寫「日後會改名」。
