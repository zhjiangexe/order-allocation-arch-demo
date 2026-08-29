## Why

目前 outbound 在等待庫存時只持久化 `AllocationDemand`，commit 後才建立 `ASSIGNED StockMove`；但
`StockMove.CONFIRMED`、movement timestamps 與既有 movement lifecycle 本來就用來表達「已確認但尚未取得庫存」。
這造成 requirement、queue identity、quantity 與 route facts 在 demand 和 move 之間重複，並使 inbound／outbound
採用不同的 movement 語意。現在應在更多 routing、partial allocation 與 warehouse execution 行為加入前，收斂為
move-centric inventory model。

## What Changes

- `StockMove` 成為 Inventory 對 stock-consuming intent 的 canonical representation；接受 outbound source request 時即建立
  `CONFIRMED` moves，而不是等到 allocation commit 才 materialize moves。
- Order、Transfer 等 source document 與其 demand order 保留在來源 bounded context／application adapter；Inventory 不再複製
  `AllocationDemandLine` 作為第二份 SKU、quantity 與 route truth。
- `SHIP_COMPLETE`、FIFO、required-by、priority 與 retry scheduling 由既有的 `StockPicking` 分組；picking 只保存 operation、
  source trace、policy 與 scheduling facts，不重複 move 的 SKU／quantity，也不等同 WMS `Shipment`。
- Allocation planning 以 open `CONFIRMED` moves 與 allocatable `StockQuant` 為輸入；proposal 直接描述
  `moveId -> stockQuantId -> quantity`。
- Allocation commit 會在同一 transaction 更新 quant reserved counter、建立 `StockMoveLine` reservation detail，並把既有 moves
  從 `CONFIRMED` 轉成 `ASSIGNED`；`StockMoveLine` 是目前 reservation／execution detail 的唯一持久化真相，不再建立平行的
  `Allocation`／`AllocationSlice` ledger。失敗時不得留下部分 reservation 或部分 assigned move set。
- Release 移除現行 move lines、還回 quant counter 並讓仍有效的 move 回到 `CONFIRMED`；cancellation 才把 move 終止為
  `CANCELLED`。Lifecycle audit 由同 transaction 寫入的 business fact 保存，不留在 active stock model。
- Pending selection、backlog age、coverage、cancellation、query/read model 與 reconciliation 改以 picking、move state 和 move-line
  reservation facts 回答，不再以 `AllocationDemand` lifecycle 或 target materialization 推論。
- **BREAKING**：移除 `AllocationDemand`／`AllocationDemandLine` persistence 與 aggregate API，以及 contracts、WMS／Temporal
  payload 中只為這份 duplication 存在的欄位；需要跨 context 的穩定 inventory grouping identity 改為 `pickingId`，
  source trace 則保留 source type、source id 與 source line id。
- Breaking Integration Events 使用新的 move-centric contract version；consumer 先同時接受舊／新版本，producer 才切換，
  舊 reader 直到 source topic、Outbox 與 DLT replay window 全部結束後才由後續 change 移除。
- **BREAKING**：撤回尚未發布的 parallel allocation commitment schema；任何已套用的 draft migration 必須用 forward migration
  修正，不得重寫已發布 checksum。

## Capabilities

### New Capabilities

無。這次變更是收斂既有 inventory movement 與 allocation capabilities，而非加入新的產品能力。

### Modified Capabilities

- `allocation-demand`: 以 `StockPicking` operation group 與 canonical moves 取代 duplicate demand aggregate；source acceptance、
  idempotency、precedence 與 cancellation 改為以 picking 及 moves 表達。
- `stock-movement`: outbound movement 在 source acceptance 時以 `CONFIRMED` 建立，成為從 requirement、reservation 到 completion
  的核心 identity 與 lifecycle。
- `stock-allocation`: planner、assign、release、consume 與 reconciliation 直接操作 moves、move lines 和 quants，並以 picking policy
  保證 `SHIP_COMPLETE` 原子性，不建立平行 reservation ledger。

## Impact

- Inventory allocation、movement、balance 的 domain model、application services、repositories、JPA entities、queries、health checks
  與 database migrations。
- Ordering adapters、integration contracts、Temporal workflow、WMS handoff/cancellation、demo fulfillment read model 與 seed data。
- Unit、architecture、persistence、concurrency、integration 與 e2e tests；測試 vocabulary 將由
  `source -> allocationDemandLine -> allocationSlice -> move` 收斂成 `source -> picking -> move -> moveLine`。
- 保留 FEFO、strict shared-SKU FIFO、`SHIP_COMPLETE`、transactional quant counters、Outbox、operation-level audit events 與 WMS
  bounded context 分離；改變的是 canonical identity 與 ownership，不是這些業務保證。
