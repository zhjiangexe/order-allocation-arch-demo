## Why

Inventory 的 `StockPicking` 實際上涵蓋 inbound、outbound 與後續 internal movement，負責共同的 source、route、operation type、assignment policy、排程與 move lifecycle 摘要；`Picking` 會把這個 Inventory operation 誤解成 WMS 揀貨工作。現有 REST/query vocabulary 已使用 `StockOperation`，應在更多 integration contract、migration 與 movement 類型加入前，讓 domain、persistence、API 與文件統一採用同一名稱。

## What Changes

- **BREAKING**：將 Inventory aggregate `StockPicking` 改名為 `StockOperation`，相關 `PickingState`、repository、mapper、entity、application use case、command/result、query 與 lifecycle publication vocabulary 一併改為 stock-operation terminology。
- **BREAKING**：跨 layer 與跨 context 的 canonical grouping identity 由 `pickingId` 改名為 `stockOperationId`；Inventory assignment/completion/cancellation 與 WMS `Shipment` correlation 使用新名稱，但 identity value 與一對一關聯不變。
- 將 Inventory operation-type vocabulary 從 picking type 收斂為 `StockOperationType`，保留 inbound、outbound、internal direction 與 default source/destination semantics。
- 以 forward database migration 將 `stock_pickings`、`stock_picking_types` 及其 foreign keys、indexes、constraints、columns 改為 stock-operation terminology；不修改既有 migration checksum。
- Breaking integration contracts 採新 contract version 或明確的 coordinated cutover；需要 replay 的既有 consumer 在 producer 切換前同時接受舊 `pickingId` 與新 `stockOperationId` vocabulary。
- `StockOperation` 仍只是 Inventory operation，與 WMS `Shipment`、`Wave`、`WarehouseWork`、`PickTask` 保持分離；不將它改名或升格為 `FulfillmentUnit`。
- 保留 `StockMove`、`StockMoveLine` 與 `StockQuant` 名稱、責任與 persistence shape。`StockMoveLine` 仍是目前 assignment 時的 reservation detail，以及在 exact-execution invariant 下完成後保留的 evidence。
- 明確記錄目前只採 Oracle-like 的責任隔離，不宣稱具備完整 Oracle inventory accounting model：`MovementAssignmentProposal` 是非持久化規劃結果，`StockMoveLine` 在 exact-execution 前提下同時保存 committed reservation detail 與完成 evidence，WMS 擁有現場 execution，`StockQuant` 只代表 current materialized balance 而不是 immutable transaction ledger。
- 將 assignment application topology 收斂為單一 `StockOperationAssigner` façade：initial order、availability event 與 backlog reconciliation 共用 candidate query、pure planner 與 transactional apply，不再以 use case 呼叫 use case 或以重複 command 包裝相同 queue key。
- 將 persistence command stores 與 application read queries 分離。Aggregate store 只負責 identity、lock 與 persistence；candidate、backlog、FEFO supply、REST projection 與 reconciliation 各有按用途命名的 query port。
- 以 application-internal、非持久化的 `LockedStockOperation` 與 `QuantReservationSet` 集中 operation 完整集合、exact coverage、scope 與 reservation-counter invariants，供 assignment、release、completion 與 cancellation transaction 共用。
- 將 stock-operation collection read 改為 bounded projection query，禁止依 operation 數量增加的 per-operation move、move-line 或 quant follow-up query；移除沒有 production caller 的 backlog-age API。
- 不改變 `SHIP_COMPLETE`、strict shared-SKU FIFO、FEFO、lock order、reservation counters、release/cancellation/completion state transitions、Outbox audit 或 WMS ownership。
- 保留未來演進為 `Fulfillment orchestration -> durable reservation/allocation -> WMS actual execution -> StockPosting/material transaction -> StockQuant projection` 的責任接縫，但不在本 change 新增 `FulfillmentPlan`、`FulfillmentUnit`、`StockPosting`、平行 reservation ledger、partial allocation、batch substitution 或 WMS planned-versus-actual batch confirmation。

## Capabilities

### New Capabilities

無。這次變更統一既有 Inventory ubiquitous language，不增加產品能力。

### Modified Capabilities

- `stock-movement`: 將 canonical Inventory operation aggregate、state、type 與 identity 的 normative vocabulary 從 stock picking 改為 stock operation，同時維持 move-centric lifecycle、Inventory/WMS 邊界、集中 lifecycle invariants、bounded read projection，以及 current balance 與 future transaction ledger 的明確 truth boundary。
- `stock-allocation`: allocation selection、planning snapshot、assignment、release、completion、lock order 與 integration result 改以 `StockOperation`／`stockOperationId` 表達；收斂 canonical assignment façade，並分離 candidate/backlog/supply queries 與 command stores，行為與原子性不變。
- `allocation-demand`: 已完成的 move-centric migration 中，source registration、idempotency、precedence、cancellation 與 compatibility requirements 改以 stock-operation vocabulary 表達，不重新引入 `AllocationDemand` aggregate。

## Impact

- Inventory movement、allocation、balance modules 的 domain types、application façade、transaction services、command stores、query ports、JPA/native-query adapters、mappers、controllers、architecture rules 與 test fixtures。
- Ordering source adapter、WMS Shipment correlation、Temporal activities/workflows、integration contracts、event consumers/producers、Outbox/DLT replay compatibility 與 demo read models。
- Database tables、columns、foreign keys、indexes、constraints、native SQL、seed data、SIT、concurrency tests 與 e2e assertions。
- 架構文件、流程圖與 OpenSpec requirements 中所有把 Inventory operation 稱為 picking 的內容；WMS 中真正的 picking、PickTask 與 picking work terminology 不變。
- 本 change 以已完成的 `make-stock-move-allocation-core` 為行為基線；實作前必須確認該 change 的 delta specs 已同步或在本 change 中以同一基線解析，避免以仍含 legacy allocation-demand 行為的 main specs 規劃 rename。
