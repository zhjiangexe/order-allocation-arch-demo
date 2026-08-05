## Why

**這是整串遷移的目的。** 前三個 change 建好了位置、搬運單據、以及操作它們的三個動作，但庫存
數字仍然可以被直接寫：

```java
// ReplenishmentUsecase.upsertBatch
stockPool.replenish(command.quantity());   // ← 沒有任何異動紀錄
stockPoolRepository.save(stockPool);
```

於是系統仍然回答不了那三個問題：這 100 件哪來的？上週三為什麼少了 20 件？補進來的那批走了
哪張單據？

更根本的是**不變式不存在**：任何拿得到 `StockPoolRepository` 的程式都能改庫存，而改錯了不會
留下痕跡。搬運的表已經建好了，卻還有一條繞過它的路。

## What Changes

**補貨變成一段真正的搬運：供應商 → 庫存位置。**

```text
StockReplenished 事件
   └─ MovementRecorder.recordInbound(...)   建 INBOUND 單據 + CONFIRMED 搬運（order_id 為空）
   └─ MovementCompleter.complete(...)       找到或開一列庫存 → 建明細 → 轉 DONE → 加數量
   └─（同一個交易內）喚醒佇列
```

**動庫存的是明細，不是搬運。** 這一點取自 Odoo 19：`stock.move.line._action_done()` 的註解
自己寫著「It'll actually move a quant」，而 `stock.move._action_done` 只負責篩選與轉狀態。我們的
`stock_move_lines` 已經指向 `stock_pools`，形狀本來就對得上。

**庫存的數量改由型別關起來。** `StockPool.replenish(int)` 換成需要一條明細當憑證：

```java
stockPool.receive(StockMoveLine line);   // 沒有明細就加不了數量
```

「庫存只能由搬運寫」因此是**型別上的事實**，不是架構測試守著的約定。

**`MoveState.DONE` 第一次有了產生者**，`stock_pickings.order_id` 第一次真的為空——那兩件事在
前兩個 change 就預留好了，這裡兌現。

## Impact

- Affected specs: `stock-movement`（新增「入庫是供應商到庫存的一段搬運」與「完成的搬運才改變
  在庫量」）、`stock-allocation`（修改「庫存以五維識別」——那條規則不變，但**寫入的入口變了**）
- Affected code: `StockOperationRecorder`（加 `recordInbound`）、新增 `MovementCompleter`、
  `ReplenishmentUsecase`、`StockPool`、種子資料（加 INBOUND 作業類型）
- **不動**：對外事件契約、REST、前端、`AllocationService`、配貨與取消兩條路徑

## 不做的事

| 不做 | 理由 |
| --- | --- |
| 出貨完成（`DONE` 的第二個產生者） | R7。`MovementCompleter` 現在只服務入庫，但介面照兩邊都能用的形狀設計 |
| 在虛擬位置上記庫存 | Odoo 的 quant 在供應商位置留下負數，全域總量因此守恆；我們的 `stock_pools` 有 `CHECK (location_usage = 'INTERNAL')`，只記內部側。那是第一個 change 就定下的取捨，這裡不推翻 |
| 收貨與上架分兩段 | 兩段收貨要 `waiting` 狀態與搬運之間的鏈結，兩者都明確不做 |
| 退貨 | 它是反向的搬運，需要作業類型之間的對應關係 |
| 盤點調整 | 第三個虛擬位置（`INVENTORY`）至今仍無讀者，它要的是自己的入口而不是補貨的變形 |
