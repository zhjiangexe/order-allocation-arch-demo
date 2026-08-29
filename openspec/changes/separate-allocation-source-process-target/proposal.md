## Why

`generalize-allocation-demand` 已讓 `AllocationDemand` 成為等待配置的持久化主真相，但 acceptance 仍預先建立 `CONFIRMED StockMove` 與 outbound `StockPicking`。同一筆 pending requirement 因而同時存在於 demand、movement 與 picking，迫使 candidate query、idempotent replay、commit、cancellation、health check 與 outbound completion 持續維護跨模型一致性。

這個 change 以已經存在的 demand root 為 source、allocation transaction 為 process、成功後的 inventory movements 與 WMS shipment 為 targets。它保留 strict shared-SKU FIFO、FEFO、ship-complete、optimistic locking 與既有物理庫存語意，但移除 pending execution 與 inventory outbound picking 的重複生命週期。

## What Changes

- `AllocationDemand` 在 acceptance 時保存完整且不可變的通用 source snapshot：origin、inventory scope、scheduling、canonical lines 與 resolved outbound destination；`PENDING` demand 不再建立 movement 或 picking。
- Shared-SKU FIFO eligibility 改以單一 precedence relation 表達：candidate 只有在同 scope 中不存在更早且 SKU footprint 相交的 `PENDING` demand 時才可配置；不再建立每個 required-SKU queue head 的位置快照。
- 純 FEFO／ship-complete planner 保留；allocation process 在同一個 transaction reserve stock、建立已 `ASSIGNED` 的 outbound `StockMove`／`StockMoveLine`、標記 demand 為 `ALLOCATED` 並發布 completion fact。
- Inventory outbound allocation 不再建立或更新 `StockPicking`。`AllocationDemand.id` 成為 canonical allocation identity；WMS 在 committed event 後建立並擁有 `Shipment`、wave 與 `PickTask`。
- Pending cancellation 只取消 demand；allocated cancellation 依 demand identity 取消 movements 並釋放 reservation。Inbound receipt 的 picking/movement 流程保持不變。
- 移除 pending execution consistency predicates、anomaly health query，以及 demand/move/picking 的 acceptance replay comparison。
- **BREAKING** `OrderAllocationCommittedIntegrationEvent.allocationId` 與 outbound completion command 的 allocation identity 從 inventory `StockPicking.id` 改為 `AllocationDemand.id`。所有同 repo consumers SHALL 一起遷移；若既有部署資料需要 rolling compatibility，必須以獨立 migration plan 處理，不能同時賦予一個欄位兩種語意。

## Capabilities

### New Capabilities

無。

### Modified Capabilities

- `allocation-demand`: pending demand 保存完整 immutable source snapshot、獨立存在且不要求預建 execution，並以 demand identity 驅動配置、重送與取消。
- `stock-allocation`: strict shared-SKU FIFO 改以 predecessor overlap relation 判定；成功配置原子地 materialize assigned movements，canonical allocation identity 為 demand id。
- `stock-movement`: stock-consuming outbound movement 由成功配置產生，不再代表未滿足需求；outbound allocation 不使用 inventory picking，inbound movement/picking 語意維持不變。

## Impact

- **Inventory allocation:** demand registrar、pending selection、planner orchestration、committer、cancellation、query views、health indicator 與 repositories。
- **Inventory movement:** outbound move creation/completion 與 picking usage；inbound receipt 不變。
- **Persistence:** allocation demand 保存 destination snapshot；candidate SQL、movement grouping query、migration fixtures 與 constraints 調整。
- **Integration contract and WMS:** allocation committed/completion identity 改用 demand id；WMS shipment idempotency key 跟隨 canonical allocation identity。
- **Tests and documentation:** strict FIFO、FEFO、ship-complete、retry、cancellation、WMS handoff、outbound completion與 concurrency tests 依新 state space 更新。
- **Prerequisite:** 本 change 建立在已完成但尚未 archive 的 `generalize-allocation-demand` 行為與 schema 上，並明確取代其中「pending demand 必須已有 confirmed outbound movement/picking」的要求。
