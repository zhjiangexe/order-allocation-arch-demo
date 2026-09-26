## Context

`generalize-allocation-demand` 已完成 demand-first cutover，但保留了更早的 movement-first premise：沒有庫存的需求必須先建立 `CONFIRMED StockMove`，才能留下 waiting fact。現在 `AllocationDemand` 已經持久化 source identity、scope、scheduling、lines 與 allocation status，pending movement 不再是保存 waiting truth 的必要條件。

目前一筆 pending order allocation 同時寫入 demand、demand lines、outbound picking 與 confirmed moves。後續 candidate query 必須 join execution rows，acceptance replay 必須從 mutable move/picking 還原 immutable accepted content，commit 必須再驗證三個模型一致，pending cancellation 也必須取消尚未交給 WMS 的 execution。除此之外，fulfillment v1 對外稱為 `allocationId` 的 identity 實際是 inventory `StockPicking.id`；WMS 又在 allocation committed 後建立自己的 `Shipment`、wave 與 `PickTask`，形成兩套 outbound work grouping。

不可變的業務語意仍是：

- 一個 allocation unit 只在單一 owner、facility 與 stock location 內競爭庫存；
- shared-SKU precedence 是 strict FIFO，完全不共享 SKU 的 demands 可獨立前進；
- 每個 demand 必須 ship-complete，不能留下 partial reservation；
- batch selection 使用 FEFO；
- stock reservation 與 demand transition 以 optimistic locking 在同一個 local transaction 提交；
- WMS 只在 allocation committed 後取得 outbound work。

本 change 是 pre-release contract rewrite。若某個外部環境已保存以 picking id 為 allocation id 的訊息或 WMS shipment，該環境不允許直接套用本 change；必須另行設計 versioned integration event 與 reconciliation migration。

## Goals / Non-Goals

**Goals:**

- 讓 `PENDING AllocationDemand` 成為唯一 waiting source fact，不建立 outbound movement 或 picking。
- 把 accepted source snapshot 補完整，使 replay 不必讀取 mutable execution。
- 以 predecessor SKU-overlap relation 表達 strict FIFO，移除 per-SKU queue-position write model。
- 保留純 FEFO／ship-complete planner，讓一個清楚的 application process 原子地 materialize allocation target。
- 讓 inventory 只擁有 reservation 與 movement facts，WMS 只擁有 shipment/work facts。
- 讓 `AllocationDemand.id` 成為 event、WMS idempotency 與 outbound completion 共用的 canonical allocation identity。
- 減少 state combinations、cross-model validators、candidate SQL predicates 與 cancellation branches。

**Non-Goals:**

- 不改變 strict FIFO、FEFO、ship-complete、integer base-unit、single-location 或 optimistic-locking policy。
- 不導入 partial allocation、FIFO bypass、lot/serial constraints、cross-location solving 或 optimization solver。
- 不改變 inbound receipt 的 picking、movement、move-line 與 physical stock completion 流程。
- 不讓 `AllocationDemand` 擁有 picked、packed、shipped、wave 或 warehouse task lifecycle。
- 不新增 durable `Allocation`／reservation ledger；成功 target 仍由 `StockMove`、`StockMoveLine` 與 `StockQuant.reservedQuantity` 表達。
- 不為不存在的 pre-allocation WMS planning consumer 保留 placeholder abstraction。
- 不在同一 event field 中同時支援 picking-id 與 demand-id 兩種 `allocationId` 語意。

## Decisions

### 1. 以三組語意名稱取代 overloaded source

`AllocationDemand` 保留為 aggregate 名稱，但 accepted content 分成：

```text
DemandOrigin
  sourceType
  canonicalSourceId
  allocationUnitKey

InventoryScope
  ownerId
  facilityId
  stockLocationId

MovementIntent
  destinationLocationId
```

既有 header 欄位可以先維持 flat persistence；domain API 使用 value objects 或語意明確的 accessor。`sourceLocationId` 不再同時表示 upstream source 與 physical source：upstream 使用 `DemandOrigin`，庫存端點使用 `InventoryScope.stockLocationId`，搬運目標使用 `MovementIntent.destinationLocationId`。

`requiredBy`、`releasePriority` 與 `enqueuedAt` 是 scheduling snapshot。canonical demand lines 保存 allocation-owned line id、source trace、SKU、quantity 與 line sequence。

Order adapter 仍由 outbound operation type 解析 source/destination location，但 acceptance 只保存 resolved route。`pickingTypeId`、`direction = OUTBOUND`、`createPicking` 與 `legacyOrderId` 不再作為 allocation accepted content：direction 已由 stock-consuming allocation 固定，order identity 已在 `DemandOrigin`，inventory outbound picking 不再建立。

**Alternative considered:** 保留 `AllocationExecutionIntent` 並新增另一張 1:1 intent table。這會保留 command/persistence shape 的重複，而且所有目前 production demand 都只需要一段 resolved outbound route，因此不採用。

### 2. Pending demand 不 materialize execution

Acceptance transaction 只保存 demand header 與 lines。合法狀態空間固定為：

| Demand state | outbound moves | move lines / reservations | inventory outbound picking |
| --- | --- | --- | --- |
| `PENDING` | none | none | none |
| `ALLOCATED` | exactly one per demand line, `ASSIGNED` or later | exactly covers every demand line while active | none |
| cancelled before allocation | none | none | none |
| cancelled after allocation | cancelled moves | removed reservation lines | none |

等待時間由 `AllocationDemand.enqueuedAt` 回答；move 的 `createdAt` 從此代表 execution materialization time。`StockMove` 仍記錄完整 from/to endpoints，且 move lines 仍是 reservation 與實際批次消耗的憑證。

**Alternative considered:** 繼續預建 moves，只把 query/validator 寫得更簡單。這仍保留兩份 pending lifecycle，無法消除 replay、cancellation 與 anomaly state space，因此不採用。

**Alternative considered:** 刪除 `AllocationDemand`，讓 moves 成為唯一 demand。Multi-line ship-complete、source allocation-unit idempotency、shared-SKU precedence、generic no-picking source 與 demand-level cancellation 仍需要 group root，最後只會重新發明另一個 demand header，因此不採用。

### 3. Inventory outbound allocation 不建立 StockPicking

成功配置直接建立帶有 `allocationDemandId/allocationDemandLineId` 的 assigned moves。這些 moves 以 demand id 天然形成完整 allocation group；不再需要 picking 作第二個 group identity。

`OrderAllocationCommittedIntegrationEvent.allocationId` 使用 `AllocationDemand.id`。WMS 依該 id 冪等建立 `Shipment`，並由 Shipment 管理 wave、WarehouseWork、PickTask 與 cancellation。Outbound completion 依 allocation demand id 讀取完整 movement set，要求 command movement ids 與 persisted set 完全相同後才 consume stock。

Inbound receipt 不具 allocation demand，因此仍可使用 inventory `StockPicking` 作為 local operation grouping。`stock_moves.picking_id` 保持 nullable；本 change 不拆分 inbound aggregate。

**Alternative considered:** allocation 成功時才建立 outbound picking。這會解決 pending duplication，但 outbound grouping、state 與 identity 仍和 WMS Shipment 重複，而 completion 已能用 demand id 取得完整 movements，因此不採用。

### 4. Strict FIFO 是 predecessor overlap relation，不是 N 個 queue snapshots

對 candidate `C`：

```text
eligible(C)
= C.status = PENDING
  AND NOT EXISTS D:
      D.status = PENDING
      AND scope(D) = scope(C)
      AND (D.enqueuedAt, D.id) < (C.enqueuedAt, C.id)
      AND SKUs(D) INTERSECT SKUs(C) IS NOT EMPTY
```

這與「C 是它每個 required-SKU queue 的 head」完全等價。Backlog trigger 先取指定 queue key 的 raw FIFO head，再查它是否有 predecessor；若該 head 被阻擋，同 queue 的後繼也必然被它以 trigger SKU 阻擋，不得 bypass。

Write process 只需要 candidate 與最多一個 `AllocationBlocker` projection；不需要 `PendingDemandQueuePosition` 或每 SKU 的 `AllocationQueueHead`。Read/query side 可以另外計算 blocker 與 shared SKU set供 UI、metrics 與 log 使用。

**Alternative considered:** 改成 bounded ordered scan 並配置第一個 satisfiable demand。它會改變 strict FIFO、允許 bypass 並引入 starvation policy，不屬於本 refactor。

### 5. 保留 functional core，合併 imperative ceremony

`AllocationDemandPlanner`、`SkuQuantities`、`AllocatableBatches` 與 `AllocationBatchPick` 保留。Planner 仍是 repository-free pure function，輸出 insufficient quantities 或完整 picks。

Application process 依序完成：

1. 載入/確認 pending candidate。
2. 查 predecessor overlap；blocked 時觀測並結束。
3. 載入所有 required SKU 的 FEFO batches。
4. 呼叫 planner。
5. 若 insufficient，保持 demand 不變。
6. 依 global stock write order reserve quants。
7. 從 demand lines、destination snapshot 與 batch picks 建立 assigned moves/move lines。
8. 將 demand 標為 allocated。
9. 由 committed snapshot 寫 Outbox publication。

Initial order acceptance 與首次 attempt 仍共用外層 transaction；backlog use case 每次 transaction 最多 commit 一筆 demand。Shared process 不另外開新 transaction。Optimistic-lock failure rollback demand、stock、moves、move lines 與 outbox，由既有 retry/scheduler 重試。

`AllocationCommitter` 的 repository load/validator/application split 不再保留：target 在 step 7 才建立，沒有 pre-existing execution 可漂移。Quant existence、scope 與 quantity invariants 仍在 mutation 前驗證，並由 `StockQuant.reserve` 與 database constraints 再防守。

### 6. Replay 只比較 immutable demand snapshot

同一 `(sourceType, sourceId, allocationUnitKey)` 重送時，只比較 demand header、destination snapshot 與 canonical lines。Mutable move state、move lines、stock batches、WMS shipment 或 completion time不參與 comparison。

Accepted-content schema version 遞增，避免舊版本 comparator 把缺少 destination 的 snapshot 誤判為相同。Order adapter 配置在 acceptance 後改變時，相同 identity 解析出不同 source/destination location，必須回報 source conflict，不能改寫既有 demand。

### 7. Cancellation 依 demand state 分流

- `PENDING`: 在 local transaction 將 demand 標為 `CANCELLED`；沒有 reservation、movement、picking 或 external execution 需要處理。
- `ALLOCATED`: 保留既有 external WMS cancellation checkpoint。取得 confirmation 後依 allocation demand id 載入 movements，釋放 move-line reservations、刪除 move lines、取消 reversible moves 並標記 demand cancelled。
- `DONE` movement 或 WMS 無法確認停止：維持不可取消／physical compensation 語意。

`AllocationReservationCanceller.cancelForOrder` 與 picking lookup 不再是 generic path；source adapter 先以 source identity 找 demand，再使用 demand id。

### 8. Query model 對 source 與 targets 分層

Pending list 直接由 demand 與 current ATP 說明 waiting reason；moves/pickings 為空不是 anomaly。Allocated detail 可載入 materialized moves/reservations。Outbound inventory picking view 從 allocation response 移除，或在一次 pre-release contract rewrite 中直接刪除；WMS shipment 由 composition query 的 WMS view 顯示。

Health indicator 不再檢查 pending execution completeness。若保留 consistency probe，只檢查 allocated demand 的 assigned-or-later moves 是否完整覆蓋 lines，且不得把 anomaly predicate放進正常 candidate SQL。

### 9. Pre-release schema 與 contract 一次切換

因 repository 明確標示尚未部署，本 change 直接重寫 V12～V15 的 allocation-demand baseline，而不是新增 rolling dual semantics：

- `allocation_demands` 新增 non-null `destination_location_id` 與 location FK。
- backfill 從既有 execution 或 outbound operation default 取得 destination snapshot。
- backfill 後移除 pending confirmed outbound moves；allocated/done demand moves 保留 demand references並解除 outbound picking grouping。
- `uq_stock_moves_allocation_demand_line` 表達 allocated target 的 at-most-one movement；pending lines允許沒有 movement。
- fixtures/schema tests 反映新的 state space。

Integration contract 同 repo 原子改用 demand id。若實際部署狀態與 pre-release premise 不符，implementation 必須停止並改寫本節為 versioned event/migration，不得猜測兼容策略。

## Risks / Trade-offs

- **[Risk] WMS 或外部 consumer 實際在 allocation 前依賴 move/picking identity。** → 全 repo 搜尋與 contract tests 必須證明唯一 handoff 是 allocation-committed event；若發現外部依賴，停止 implementation 並引入明確的 `FulfillmentRelease` capability，而不是恢復 pending movement。
- **[Risk] Destination snapshot 移入 demand 被誤解為 demand 擁有 fulfillment lifecycle。** → 只保存 allocation 後 materialize movement 所需的 immutable route input；wave、pick、pack、ship state 仍完全留在 WMS。
- **[Risk] Demand allocated 但 moves 未建立。** → reserve、move creation、demand transition與 Outbox 共用一個 transaction；integration tests 注入 failure 驗證全部 rollback。
- **[Risk] FIFO overlap query 效能退化。** → 保留 pending scope 與 line SKU indexes，加入 representative `EXPLAIN`/integration coverage；只有有 query-plan 證據時才 materialize footprint。
- **[Risk] 移除 outbound picking 失去完整 group validation。** → demand id 本身是 group key，completion 同時比較 allocation id、movement id set、line coverage、owner/location/SKU 與 reservations。
- **[Risk] Baseline rewrite 套到已有舊語意資料。** → 本 change 只允許 pre-release clean migration；已部署環境必須另開 versioned migration change。
- **[Trade-off] Move createdAt 不再表示等待開始。** → waiting age 唯一由 demand.enqueuedAt 表達；move.createdAt 回到 execution fact 的建立時間。
- **[Trade-off] 少了 inventory outbound picking read view。** → WMS Shipment 是 outbound work grouping 的權威 view；inventory allocation view只顯示 demand、allocation moves 與 reservations。

## Migration Plan

1. 先修改 delta specs 與 contract tests，固定 pending/no-execution、demand-id allocation identity 與 overlap precedence。
2. 重寫 allocation-demand baseline persistence，將 destination snapshot 納入 demand，更新 mapper/repository/schema tests。
3. 縮小 registrar，只保存/重播 immutable demand snapshot；更新 pending query/API tests。
4. 將 selection 改為 raw queue head + predecessor overlap，移除 queue-position types與 execution-aware candidate predicates。
5. 將 commit 改為原子建立 assigned moves/move lines，移除 outbound picking與跨 execution validator。
6. 將 committed event、WMS handoff與 outbound completion切到 demand-id allocation identity。
7. 簡化 pending/allocated cancellation與 health/query paths。
8. 更新 migration/backfill、seed、unit、integration、SIT、concurrency 與 architecture tests。
9. 執行 Palantir formatting、module tests、relevant deployment SIT 與 OpenSpec verify。

Rollback 在 commit 前以 source control revert 為界。由於這是 pre-release baseline/contract rewrite，不提供同 database 同時執行兩種 allocation identity 的 runtime switch。

## Open Questions

無。若 implementation 發現 pre-allocation WMS consumer 或已部署的 picking-id allocation records，視為前提失效並停止，而不是在本 change 內加入隱性 compatibility mode。
