## 1. 行為基線與架構護欄

- [x] 1.1 為現行 strict shared-SKU FIFO、FEFO、ship-complete、single-location 與 one-demand-per-transaction 行為補齊 characterization tests，固定 commitment layer 導入前後必須相同的配置結果
- [x] 1.2 為 demand acceptance、initial attempt、availability wake、reconciliation、cancellation 與 outbound completion 建立 source／demand／commitment／target 測試資料對照，讓後續測試不再以 movement existence 推論 pending demand
- [x] 1.3 在 `InventoryBoundaryArchitectureTest` 加入四層 ownership 規則：source adapter 不得寫 commitment／target、planner 不得依賴 repository、movement 不得直接修改 reservation、WMS type 不得進入 allocation domain
- [x] 1.4 加入 architecture test，限制只有 commitment application boundary 能同時 mutation `AllocationSlice` state 與 `StockQuant` reservation／physical counters

## 2. Allocation commitment domain

- [x] 2.1 建立 `AllocationId`、`AllocationPolicyCode`、`AllocationSliceId` 與 `AllocationSliceState` value types，固定 `RESERVED -> RELEASED|CONSUMED` 且 terminal state 不可重啟
- [x] 2.2 建立 `Allocation` aggregate 與 `AllocationSlice` entity，實作正數 quantity、同 allocation／demand line／quant 合併與唯一性、scope／SKU 一致性及 immutable history invariants
- [x] 2.3 實作 allocation lifecycle projection，從完整 slice state set 推導 active／released／consumed，並拒絕本版 ship-complete policy 下不合法的 mixed terminal state
- [x] 2.4 實作 allocation release domain operation，保留 released slices 與 `releasedAt`，禁止改量、刪除或重用 terminal slices
- [x] 2.5 實作 allocation consume domain operation，驗證完整 movement set 與 slice／quant／quantity 對應，並提供 completed replay 的 idempotent result
- [x] 2.6 為 aggregate construction、duplicate-pick merge、invalid scope、terminal transitions、reallocation history、partial movement set 與 idempotent replay 建立 unit tests

## 3. Expand schema 與 commitment persistence

- [x] 3.1 新增下一版 Flyway expand migration，建立 `allocations` 與 `allocation_slices` tables、positive quantity／state／timestamp checks、foreign keys、optimistic version 與設計要求的 query indexes
- [x] 3.2 在 expand migration 為 `stock_moves.allocation_id` 與 `stock_move_lines.allocation_slice_id` 新增 nullable references，保留 inbound rows 為 null 並暫不啟用 outbound non-null constraints
- [x] 3.3 建立 allocation／slice JPA entities、mappers、Spring Data repositories 與 domain repository ports，支援按 allocation、demand、demand line、quant 及 reserved state 查詢
- [x] 3.4 實作 repository lock APIs，固定先鎖 demand 或 allocation、再依 `StockWriteOrder` 鎖 quants、最後載入 slices／moves，且不得依賴資料庫 incidental ordering
- [x] 3.5 建立 persistence integration tests，驗證 unique triple、state/timestamp checks、FK deletion behavior、inbound nullable refs、optimistic locking 與 required indexes
- [x] 3.6 新增 deterministic backfill migration：對既有 allocated／done demand 建立 `Allocation.id = AllocationDemand.id`，pending 或 cancelled-without-reservation demand 不建立 allocation
- [x] 3.7 從既有 outbound moves／move lines backfill `RESERVED` 或 `CONSUMED` slices 及 movement references，不為已刪除的 released history 製造推測資料
- [x] 3.8 建立 migration integration tests，涵蓋 fresh database、既有 assigned／done／pending／cancelled fixtures、重跑安全性及 backfill 前後 quantity invariants

## 4. Demand lifecycle 與 coverage projection

- [x] 4.1 將 `AllocationDemandStatus` 與 aggregate transition 收斂為 `ACTIVE`／`CANCELLED` requirement lifecycle，移除 persisted `ALLOCATED` coverage checkpoint
- [x] 4.2 實作 demand-line coverage query，從 slices 計算 reserved、consumed、covered 與 open quantities，排除 released slices並拒絕 coverage 小於零或超過 immutable required quantity
- [x] 4.3 更新 `AllocationDemandView`、`AllocationDemandLineView` 與 query repository，一次載入 bounded candidates 所需的 current open quantities，避免逐 demand／line N+1
- [x] 4.4 更新 pending candidate selection 為 `ACTIVE AND any openQuantity > 0`，移除 movement、picking、shipment existence／state eligibility predicates
- [x] 4.5 更新 exact predecessor query，只讓同 inventory scope、較早、仍有 open quantity且 open SKU footprint 相交的 demand 阻擋 candidate
- [x] 4.6 建立 unit／repository tests，涵蓋 fully covered execution-incomplete demand 不阻擋、release 後重新 eligible、consumed coverage、cancelled demand 與 repeated-SKU lines

## 5. Pure proposal 與 SHIP_COMPLETE policy

- [x] 5.1 將 `AllocationDemandPlan` 收斂為 immutable `AllocationProposal`，包含 demand id/version、authorized policy、proposed slices 與完整 missing quantities，但不包含 allocation id
- [x] 5.2 更新 `AllocationDemandPlanner` 只接受 open-line snapshot 與 FEFO quants，保持 repository-free、side-effect-free 並對相同 snapshot 產生 deterministic proposal
- [x] 5.3 實作 `SHIP_COMPLETE` policy validator，要求每條 line 精確覆蓋 open quantity、同 SKU aggregate 足量、任一 short SKU 時沒有 committable slices
- [x] 5.4 將 policy 固定由可信任 source／application configuration 選擇，不新增 request-level policy selector、registry UI 或 partial-allocation API
- [x] 5.5 更新 planner／policy tests，涵蓋 all-shortfall reporting、same-SKU canonical distribution、FEFO、integer overflow、no-side-effect 與 no-allocation-id assertions

## 6. Atomic commitment 與 target materialization

- [x] 6.1 建立 `AllocationCommitmentWriter` responsibility，在 transaction 內重新驗證 demand version、open quantities、exact predecessor、quant scope／SKU／expiry／ATP 及 `SHIP_COMPLETE` coverage
- [x] 6.2 讓 writer 建立 allocation／`RESERVED` slices並依 `StockWriteOrder` 增加 quant reserved counters，任何 optimistic conflict 或 invariant failure 都回滾全部 commitment facts
- [x] 6.3 建立 `AllocationTargetMaterializer`，由 committed facts 建立每 demand line 一個 assigned `StockMove`、每 slice 一個 `StockMoveLine`，並寫入 allocation／demand-line／slice trace references
- [x] 6.4 建立或調整 `CommitAllocationUsecase` 作唯一 transaction facade，依序執行 revalidation、commitment write、target materialization 與 Outbox publication
- [x] 6.5 更新 `PendingDemandAllocator` 只負責 selection、planner invocation 與 transaction facade call；initial 與 wake flows 直接共用這些 responsibilities而不互相呼叫 use case
- [x] 6.6 更新 `AllocationCommitResult` 與 publication factory，回傳 allocation id、allocation-demand/source identities、slice ids 與完整 materialized movement snapshot
- [x] 6.7 加入 rollback integration tests，逐一注入 slice、counter、movement、movement-line 與 Outbox persistence failure，驗證 demand open quantity及所有 target tables保持不變
- [x] 6.8 加入 concurrency SIT，驗證同 demand duplicate attempt、同 quant hot contention、predecessor race 與 event／scheduler overlap只產生一個有效 commitment且 counter不漂移

## 7. Release 與 source cancellation

- [x] 7.1 將 reservation release 收斂為 allocation-id based application boundary，載入完整 slices／quants／targets並驗證全部仍為 `RESERVED` 且 execution 可逆
- [x] 7.2 在同一 transaction 將 slices 轉成 `RELEASED`、依 lock order遞減 counters、取消 reversible moves、清理失效 execution lines並保留 slice history
- [x] 7.3 更新 cancellation operation persistence與 external coordinator，以 `(allocationId, cancellationOperationId)` 保存 durable decision及 incomplete-local-work resume state
- [x] 7.4 更新 `CancelAllocationDemandUsecase`：無 active commitment時只取消 demand；有 reserved commitment時先協調外部 cancellation再 release；consumed commitment回覆不可取消
- [x] 7.5 更新 order cancellation adapter，仍以原 integration event id作 operation id，但透過 demand查出 active allocation後再呼叫 allocation cancellation
- [x] 7.6 建立 release／cancellation tests，涵蓋 no-commitment、confirmed／rejected external decision、lost acknowledgement retry、counter exactly-once、released reallocation及 irreversible rejection

## 8. Outbound consumption

- [x] 8.1 將 `CompleteOutboundMovementsCommand`／result改為攜帶 canonical allocation id與完整 movement id set，保留必要 demand trace但不再以 demand id尋找 reservation
- [x] 8.2 更新 `CompleteOutboundMovementsUsecase`，拒絕 subset、superset、foreign allocation、missing slice、quant／quantity mismatch及 `RELEASED` allocation
- [x] 8.3 在一個 transaction內依 shared lock order將所有 `RESERVED` slices轉成 `CONSUMED`、遞減 reserved counters與 physical stock並完成 movements
- [x] 8.4 實作 completed replay idempotency，確保重送相同 allocation／movement set成功但不重複扣 stock；不同 set則回覆 conflict
- [x] 8.5 建立 consume tests與 release-vs-consume concurrency SIT，驗證只有一條 terminal transition獲勝且 loser不造成 counter、physical stock或movement漂移

## 9. Integration contracts、WMS 與 Temporal cutover

- [x] 9.1 更新 `OrderAllocationCommittedIntegrationEvent` 與 JSON／schema contract tests，使 `allocationId` 使用 `Allocation.id` 並新增明確 `allocationDemandId` trace
- [x] 9.2 同步更新 Ordering consumer，只使用 source／demand trace更新 ordering lifecycle，不把 allocation id誤當 order或 demand identity
- [x] 9.3 更新 WMS create-shipment command、aggregate、persistence與 Inbox idempotency，使用 allocation id作 Shipment execution grouping且保存 demand trace
- [x] 9.4 更新 shipment handover／completion／cancellation events與 Inventory consumers，所有 commitment操作使用 allocation id並驗證完整 movement set
- [x] 9.5 更新 Events orchestration與 `fulfillment-workflow-contract`／runtime／Temporal adapters，讓 Workflow／Activity payload在 replay時保留 allocation與demand兩種 identity
- [x] 9.6 更新 bootstrap event registration、serialization fixtures、seed data與所有 event factories，移除 demand-id-as-allocation-id assumptions
- [x] 9.7 建立 contract、WMS unit、Inbox transaction及 Temporal replay tests，驗證 duplicate committed event只建立一個 Shipment且 completion／cancellation回到正確 allocation

## 10. Queries、health、metrics 與文件

- [x] 10.1 將 allocation detail query分成 source/demand、commitment、supply與execution sections，並提供 allocations／slice states／coverage／open quantity／movement trace
- [x] 10.2 更新 pending backlog與 waiting-reason queries，以 open coverage及 predecessor blocker回答問題，不再從 movement absence或demand `ALLOCATED` status推論
- [x] 10.3 擴充 health reconciliation，檢查 quant reserved counter等於 `RESERVED` slice sum、line coverage上限、ship-complete exact coverage與reserved slice target trace
- [x] 10.4 更新 metrics/log correlation，使用 allocation id作 success／release／consume correlation並保留 demand id、source type、scope與blocker；禁止 source id／quant id high-cardinality tags
- [x] 10.5 更新 REST response、demo composition query與前端型別，清楚區分 demand id、allocation id、slice、movement及WMS shipment identity
- [x] 10.6 更新 allocation flow、state、sequence及 stock lifecycle 文件／Mermaid diagrams，呈現四層 truth與 planner／commit／materialize／release／consume processes
- [x] 10.7 檢查新增 public classes與interfaces，合併只有轉呼叫且沒有獨立 invariant／transaction／adapter責任的 ceremony wrappers，並以 architecture tests固定剩餘邊界

## 11. Constraint cutover、回滾與完整驗證

- [x] 11.1 新增 migration validation queries，逐項證明 demand-line coverage、quant counter、allocation slice、movement set與allocation-id event／Shipment references一致，任一 mismatch即中止cutover
- [x] 11.2 在 validation通過後啟用 outbound `stock_moves.allocation_id`、`stock_move_lines.allocation_slice_id`、FK／unique／state constraints，並保留 inbound nullable semantics
- [x] 11.3 移除 legacy demand `ALLOCATED` writes、movement-line reservation mutation APIs、demand-id allocation lookups及不再需要的 migration-window columns／repositories
- [x] 11.4 加入 single-writer configuration與startup validation，禁止 demand-id writer與allocation-id writer同時啟用，並測試新 event發布後不得熱切回legacy
- [x] 11.5 建立 rollback rehearsal tests：writer切換前可回退、切換後未發布event可由slices重建refs、已發布後必須pause／drain／reconcile再forward-fix或reverse-migrate
- [x] 11.6 執行 `cd backend && ./gradlew spotlessApply`，檢查格式diff後執行 `./gradlew spotlessCheck`
- [x] 11.7 執行 `cd backend && ./gradlew :inventory-context:test :integration-contracts:test :ordering-context:test :wms-context:test :fulfillment-workflow-contract:test :fulfillment-workflow-runtime:test`
- [x] 11.8 執行 `cd backend && ./gradlew :deployments:monolith:test :deployments:monolith:sit`，涵蓋migration、rollback、FIFO／FEFO、hot-SKU concurrency、Events chain、WMS transaction與Temporal wiring
- [x] 11.9 執行 `make check`，確認完整backend checks與SIT通過且沒有architecture／format／schema regression
- [x] 11.10 執行 `make e2e`，確認隔離的Karate v2 Events與Temporal E2E全部通過並自動清理containers、networks與runtime artifacts
- [x] 11.11 執行 `openspec validate introduce-allocation-commitment-layer --strict --no-interactive` 與 implementation verification，逐條對照所有scenarios且不宣稱支援partial、manual approval、multi-leg route或FIFO bypass
