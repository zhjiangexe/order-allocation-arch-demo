# Allocation demand boundary、cutover 與 repair runbook

首次閱讀程式流程請先看
[從 AllocateOrderUsecase 看懂配貨流程](allocation-demand-flow.md)，其中包含活動圖、循序圖與主要
use case／adapter／planner／committer 的責任說明。

狀態：現行設計。此文件取代 `stock-reservation-design.md` 與 `dom-order-intake-scope.md`
中以 `demand_lines`／`MovementAssigner`／`picking.orderId` 定義 generic allocation queue 的段落。

## 現行 boundary

- Allocation 的唯一需求生命週期是 `allocation_demands` + `allocation_demand_lines`。
  identity 為 `(source_type, canonical source_id, allocation_unit_key)`；order 使用
  `ORDER/{orderId}/PRIMARY`。
- 一筆 demand 只有一個 owner、facility、source location，數量是 positive integer base unit。
  V1 不支援 partial allocation、FIFO bypass、UOM/decimal、lot/serial、quality constraint，或跨
  allocation unit 的 source-level atomic completion。
- Order adapter 從 `allocation_order_source_demands` published view 擷取 immutable snapshot，
  acceptance 在一個 allocation local transaction 建 demand、canonical lines、outbound picking
  與帶 paired demand references 的 moves。相同 identity 的 retry 以
  `accepted_content_version = 1` 做 structural comparison；mutable execution state 與 generated id
  不參與比較，任何 immutable 差異回 `SourceDemandConflict`。
- Planner 只接收 `AllocationDemand` 與已載入的 `AllocatableBatches`，回傳以
  `allocationDemandId/allocationDemandLineId` 識別的 immutable plan，不查 repository、不 reserve。
  Committer 依 demand → globally ordered stock pools → moves/picking 的順序寫入，最後才將 demand
  改為 `ALLOCATED`。
- FIFO 以 owner/facility/location/SKU 分 queue。候選需在每個 required SKU 都是最早 pending；
  stock prefilter 不會移除 predecessor。每個 local transaction 最多 commit 一筆 demand，
  scheduler 以 bounded global work budget 進行後續 iterations。
- Generic `AllocationCommitted` fact 帶 source identity、move/picking、source location、quantity 與
  committed batch picks。只有 order completion adapter 轉成既有
  `OrderAllocatedIntegrationEvent v1` 與
  `AllocationCommittedForFulfillmentIntegrationEvent v1`；v1 `allocationId` 仍是 picking id。
- Inbound receipt、inventory adjustment 與 already-reserved supply operations 不推導 demand；inbound
  move 的 demand references 必須為 null。

正式 `TRANSFER`、`REPLENISHMENT`、`PRODUCTION`、`MANUAL` adapter 必須各開 OpenSpec change，
定義 stable allocation-unit key、acceptance、completion、cancellation 與 integration contract。
本 change 只用 contract fixtures 證明 core 不依賴 order aggregate。

## Migration 與 invariant gates

Migration 順序固定為：

1. `V12` 新增 demand/cancellation tables 與 nullable movement references，允許 rolling coexistence。
2. `V13` 冪等 backfill no-move、`CONFIRMED`、active `ASSIGNED` order units。已有 execution 時保留
   move source location 與最早 `created_at`；沒有 execution 才使用當下 outbound default；不發布
   completion。
3. `V14` 驗證 paired references、order trace、demand/demand-line FK，並建立 execution lookup index。

Quiesced final backfill 後必須取得零列：

```sql
SELECT source_type, source_id, allocation_unit_key, count(*)
FROM allocation_demands
GROUP BY 1, 2, 3 HAVING count(*) > 1;

SELECT id FROM stock_moves
WHERE order_line_id IS NOT NULL
  AND (allocation_demand_id IS NULL OR allocation_demand_line_id IS NULL
       OR source_line_id IS DISTINCT FROM order_line_id::text);

SELECT d.id
FROM allocation_demands d
WHERE d.status = 'PENDING'
  AND EXISTS (
    SELECT 1 FROM allocation_demand_lines l
    WHERE l.allocation_demand_id = d.id
      AND (SELECT count(*) FROM stock_moves m
           WHERE m.allocation_demand_id = d.id
             AND m.allocation_demand_line_id = l.id) <> 1
  );
```

以 representative no-move／`CONFIRMED`／`ASSIGNED` fixture 各跑 initial 與 final backfill；確認
`enqueued_at` 未刷新、active assignment 仍是 `ALLOCATED`、move references 成對、outbox 沒有新增
completion。`EXPLAIN (ANALYZE, BUFFERS)` pending scope/candidate queries 時應使用：

- `idx_allocation_demands_pending_scope`
- `idx_allocation_demand_lines_sku_queue`
- `idx_stock_moves_allocation_demand`
- Stock pool owner/location/SKU/FEFO index

若 planner 或 DB statistics 使查詢改走高成本 sequential scan，先保留 legacy indexes、更新
statistics 並評估資料分布；不得只為讓 plan 看起來漂亮而移除 predecessor correctness predicate。

## Read-only shadow comparison

Shadow phase 不得呼叫 legacy committer。比較時把兩側正規化成
`ORDER/{orderId}/PRIMARY + owner/facility/location + canonical lines`：

- 已有 move/picking：兩側都以 execution source location 比較。
- 沒有 move：以目前 outbound default location 比較。
- Legacy view 展開出的額外 internal-location rows 分類為 `KNOWN_LEGACY_LOCATION_EXPANSION`。
- New shared-SKU predecessor check 拒絕 legacy 可能允許的 multi-SKU 超車，分類為
  `KNOWN_CROSS_SKU_FIFO_CORRECTION`。
- identity、quantity、FIFO/FEFO outcome 的其他差異是 blocking anomaly。

Shadow query 只讀；unique source key 無法阻止 legacy/new 兩個 writer 同時 reserve，不能把它當
single-writer gate。`AllocationShadowComparator` 本身不注入 repository 或 committer；呼叫端完成
兩側 read 後才交給它分類，因此 comparison 不具任何寫入能力。

## Quiesced cutover 與 rollback

1. 暫停 legacy order-allocation consumer、availability consumer 與 reconciliation scheduler；order
   intake 可繼續，事件留在 broker。
2. 等待進行中的 allocation transactions 與 outbox publish 排空。
3. 執行 final idempotent backfill、上述 anomaly/constraint gates、shadow comparison 與 query-plan
   檢查。
4. 將所有 instances 一致設定 `ORDER_PROMISING_ALLOCATION_WRITER_MODE=demand`。此版本只有 demand
   committer；任何其他值會拒絕啟動。不得在同一 consumer group 混跑 legacy/new writer binary。
5. 先恢復 demand writer instances，再恢復 consumers/scheduler；queued order events 由 acceptance
   idempotency 安全 replay。

New consumers 恢復前，可維持 quiescence 直接切回 legacy release。任何 new-path reservation、
movement state 或 completion event commit 後，不得 hot rollback：再次 pause/drain，對帳 shared
movement/outbox，執行 forward reconciliation，證明 legacy 不會重複處理後才可恢復；無法證明時
維持停寫並 forward-fix。

## Alert 與人工處置

- `allocation_anomaly_isolated > 0` 或 `/actuator/health` 的 `allocationDemand=DOWN`：保持 demand
  隔離，不得 auto-repair。依 sample id 對帳 header/lines/moves/picking，修復缺失或重複 execution
  reference 後再喚醒 scope。
- `allocation_fifo_blocked_total{sku,...}` 持續增加，搭配
  `allocation_fifo_pending_age{blocked_sku,...}`：從 structured log 取得
  `blockingPredecessorId` 與 `blockedSku`。確認 predecessor 是真實需求；可由 source 正式取消，
  不得手動改 enqueue time、跳過或刪除 predecessor。
- Cancellation operation 卡在 `STARTED`：可安全以同 operation id 重試外部 coordination。
  `EXTERNAL_CONFIRMED`：不得再呼叫 warehouse，直接重試 local completion。
  `EXTERNAL_REJECTED`：同 operation 的 decision 固定，demand/reservation/move/picking 保持不變。
