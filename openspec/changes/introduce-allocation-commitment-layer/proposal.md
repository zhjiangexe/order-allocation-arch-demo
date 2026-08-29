## Why

`separate-allocation-source-process-target` 已消除 pending demand、movement 與 picking 的重複生命週期，但成功配置仍直接把暫態 plan 轉成 `StockMove`／`StockMoveLine`，使「需求承諾使用哪些 supply」只能由 execution target 反推。當系統加入 partial allocation、backorder、reallocation、manual reservation、route split 或多 shipment 時，allocation commitment 與 warehouse execution 將再次耦合，因此現在需要在 demand source 與 execution target 之間建立獨立且可稽核的 commitment layer。

## What Changes

- 新增持久化 `Allocation` 與 `AllocationSlice`：`Allocation` 代表一次原子配置承諾，`AllocationSlice` 以 quantity 明確連結一條 `AllocationDemandLine` 與一個 `StockQuant`，成為 reservation 的 canonical fact。
- 保留 `AllocationDemand` 作為 source-normalized immutable requirement；需求是否仍有 open quantity、已配置多少與已履約多少，改由 demand quantities、active／consumed allocation slices 與 cancellation facts 計算，不再由 movement 是否存在或單一 `ALLOCATED` checkpoint 代表全部 coverage。
- 保留 repository-free planner，但將輸出明確定義為尚未生效的 proposal；只有 commitment transaction 重新驗證 demand、precedence 與 supply 後，才能原子建立 allocation slices、更新 quant reservation counter 並發布 committed fact。
- 將 ship-complete 定位為 allocation policy：本 change 的唯一啟用 policy 仍要求一個 demand 一次完整 commit，維持既有外部行為；partial commit、manual approval、reservation window、substitution 與 policy selection API 明確留給後續 changes，不在本 change 假裝實作。
- `StockMove`、movement lines 與 WMS work 改為由 committed allocation materialize 的 execution targets；routing、movement split／merge、wave 與 shipment grouping 不得成為 allocation truth，也不得回寫 accepted demand content。
- Allocation release、reallocation 與 consumption 以 allocation／slice lifecycle 保存 commitment history；quant reservation counter 必須與 active slices 在同一 transaction 保持一致。Execution 尚可逆時才能 release；已開始或完成的 physical execution 仍走既有 rejection／compensation 邊界。
- **BREAKING** canonical `allocationId` 從 `AllocationDemand.id` 改為 `Allocation.id`。Order allocation committed event、WMS shipment idempotency、outbound completion、cancellation、query views 與 movement references 必須在同一 repository change 中一起遷移；`allocationDemandId` 保留為 source trace identity，不再冒充 commitment identity。
- 移除「movement line 是唯一 reservation ledger」的前提；movement line 可引用對應 allocation slice 作 execution trace，但其建立、重排或清除不得改變 allocation coverage，除非明確執行 release／consume use case。
- 本 change 不新增 partial allocation 的外部能力，也不引入 Odoo-style pending moves、inventory outbound picking 或跨 context transaction；目標是先建立能安全承載後續複雜政策的模型邊界。

## Capabilities

### New Capabilities

- `allocation-commitment`: 定義 allocation header、demand-to-supply slices、reservation／release／consume lifecycle、coverage invariants、canonical allocation identity 與 target materialization boundary。

### Modified Capabilities

- `allocation-demand`: demand 保持 immutable source requirement，但 lifecycle 與 coverage 改由 allocation commitments 回答，並允許後續由多個 allocation 逐步滿足同一 demand，而不把 execution state 放回 demand。
- `stock-allocation`: planner output 改為 non-authoritative proposal；commit transaction 先建立 allocation facts與 quant reservation，再 materialize targets，且現行 ship-complete 行為由明確 policy gate 保證。
- `stock-movement`: outbound movement 改為 allocation commitment 的 execution target，movement／move-line identity 與取消、完成流程改以 allocation／slice trace 串接，不再擔任 reservation 的 canonical ledger。

## Impact

- **Prerequisite:** `refine-allocation-workflow-boundaries`、`generalize-allocation-demand` 與 `separate-allocation-source-process-target` 已完成；建立本 change 的 delta specs 前須依相依順序 archive／sync，讓 `allocation-demand`、`stock-allocation` 與 `stock-movement` 的最新 requirements 成為 main specs baseline。
- **Inventory allocation:** 新增 allocation commitment aggregate、slice entity、repositories、transactional commit／release／consume use cases、coverage queries 與 invariants；調整 planner result、allocator、cancellation、health checks、metrics 與 tests。
- **Inventory balance and movement:** `StockQuant` reservation counter 改由 active slices 支撐並可對帳；outbound `StockMove`／`StockMoveLine` 改引用 allocation／slice，movement completion 透過 commitment consumption 更新 coverage。Inbound movement 不受影響。
- **Persistence:** 新增 allocation header／slice tables、unique and quantity constraints、optimistic-locking／lock-order rules、既有 allocated demand＋move-line reservation backfill，以及 demand-id allocation identity 到 allocation-id 的 staged migration。
- **Integration and WMS:** 同步更新 committed event、Shipment／Wave／PickTask idempotency、outbound completion 與 cancellation contracts；仍以 Outbox／Inbox 維持跨 context eventual consistency。
- **Documentation and verification:** 更新四層 source／demand／commitment／execution 架構文件、sequence／state diagrams、SIT、Events／Temporal E2E、concurrency、rollback、release／consume 與 migration tests；不新增外部 library。
