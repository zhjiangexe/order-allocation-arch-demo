## 1. 固定行為基線與變更邊界

- [x] 1.1 同步或 archive 已完成的 `make-stock-move-allocation-core`，確認 main specs 已移除 legacy `AllocationDemand` 行為並以既有 `StockMove`／`StockMoveLine` 模型為基線
- [x] 1.2 在 rename 前執行 Inventory、WMS、integration-contract、Temporal、monolith SIT 與 `make e2e` characterization suite，記錄可比較的通過結果
- [x] 1.3 以 bounded-context allowlist 分類 current source 中的 `StockPicking`、`Picking*`、`pickingId` 與 SQL picking 命中，明確保留 WMS 真正的 `PickTask`／`pickingWork` 及歷史 migration/artifact
- [x] 1.4 記錄各部署環境的 topic、Outbox、DLT 與 Temporal history retention／最長 workflow duration，將 legacy reader 的移除門檻留給後續 cleanup change

## 2. 先建立 integration 與 Temporal 相容讀取

- [x] 2.1 新增 `promising.v3.OrderAllocationCommittedIntegrationEvent` 與 JSON contract fixture，使用 `stockOperationId` 並保留既有 event type、channel、correlation 與 partition semantics
- [x] 2.2 新增 fulfillment v3 handover、cancellation、outbound-completion contracts 與 fixtures，以 `stockOperationId` 取代 `pickingId`
- [x] 2.3 新增 `inventory.v2.StockOperationLifecycleIntegrationEvent` 與 fixture，使用 `stockOperationId`／`stockOperationTypeId` 及 `StockOperation` aggregate vocabulary
- [x] 2.4 讓 Ordering、Inventory、WMS 與 bootstrap messaging consumers 同時註冊 legacy 與新版 contracts，並在 ingress 立即 normalize 成 canonical stock-operation command/value object
- [x] 2.5 讓跨 compatibility window 的 Outbox/history read side 同時接受 legacy `StockPicking` 與 canonical `StockOperation` aggregate type，驗證相同 UUID 只形成一個 operation identity
- [x] 2.6 在 workflow contract 新增 `stockOperationAssigned(StockOperationAssignmentSnapshot)`，並保留 deprecated `pickingAssigned(PickingAssignmentSnapshot)` history-visible signal
- [x] 2.7 在 workflow implementation 將新舊 signal normalize 成同一個 `StockOperationAssignmentCheckpoint`，以 replay test 證明舊 history 的 command sequence、activity、timer 與 branching 不變

## 3. 建立 forward-only PostgreSQL rename migration

- [x] 3.1 新增 V30 migration，以 metadata rename 將 `stock_picking_types`、`stock_pickings`、`stock_picking_cancellation_operations` 及指定 relationship columns 改為 stock-operation terminology，不修改 V4 或 V21–V29
- [x] 3.2 在 V30 同步 rename foreign keys、unique indexes、check constraints 與 indexes，確認 schema 不留下 current-code 依賴的 picking-named metadata
- [x] 3.3 在 rename 前 drop V29 operation-group deferred triggers/functions，rename 後以 `assert_stock_operation`／`enforce_stock_operation_group` 及 canonical trigger names 重建完全相同的 invariants
- [x] 3.4 重建或 replace 仍引用 `stock_moves.picking_id` 的 reservation/reconciliation SQL functions，使它們只使用 `stock_operation_id`
- [x] 3.5 擴充 migration/SIT，驗證 fresh migration 與既有 V29 資料升級都保留 UUID、row count、source identity、state、timestamps、versions 與 relationships
- [x] 3.6 擴充 deferred-invariant SIT，分別證明 homogeneous state、exact move-line coverage、SHIP_COMPLETE coherence 與 reserved-counter constraints 在 renamed schema 的 commit boundary 仍會拒絕不一致交易

## 4. Rename Inventory domain 與 operation type model

- [x] 4.1 將 `StockPicking`、`PickingState` 與 `PickingDirection` 改為 `StockOperation`、`StockOperationState` 與 `StockOperationDirection`，保持 aggregate fields、UUID 與 state-transition behavior 不變
- [x] 4.2 將 Inventory picking-type aggregate/entity/repository vocabulary 改為 `StockOperationType`，保持 inbound、outbound、internal direction 與 default endpoints semantics 不變
- [x] 4.3 將 operation 與 move 的 canonical relationship 從 `pickingId` 改為 `stockOperationId`，維持 `(stockOperationId, sourceLineId)` 及 source allocation-unit idempotency invariants
- [x] 4.4 將 cancellation aggregate、key、repository 與 mapper 改為 `StockOperationCancellation` vocabulary，保持 stable cancellation-operation ID、durable WMS decision 與 retry behavior 不變
- [x] 4.5 更新 Inventory domain/architecture tests，證明 `StockOperation` 只作 moves 的 transactional lifecycle summary，且未引入 `FulfillmentUnit`、`StockPosting`、持久化 working set 或平行 reservation ledger

## 5. 收斂 assignment application topology

- [x] 5.1 將 `MovementAssignmentAttempt` 收斂為唯一 canonical façade `StockOperationAssigner`，提供 operation-id initial attempt 與 `AssignmentQueueKey` next-candidate attempt，且 façade 只協調 candidate query、pure planner 與 transaction apply
- [x] 5.2 將 `PendingPickingSelection` 改為 `AssignmentCandidateQuery`，讓 `AssignmentCandidate` 只包含 immutable `MovementPlanningSnapshot` 與 optional `StockOperationPredecessor`，不再攜帶 mutable `StockOperation`
- [x] 5.3 移除 `AssignPendingPickingCommand` 與只有 transaction forwarding 的 `PendingPickingAssignmentUsecase`，讓 availability consumer 與其他 entry points 直接傳遞 `AssignmentQueueKey`
- [x] 5.4 將 `AssignPickingUsecase` 改為 internal `StockOperationAssignmentTransaction`，保留 final operation/move/quant reload、exact predecessor recheck、proposal revalidation、lock order 與 rollback semantics
- [x] 5.5 將 `PendingPickingBacklogAssignmentUsecase` 改為 `StockOperationBacklogReconciler`，讓 scheduler 只負責觸發 reconciliation，reconciler 逐一透過同一 `StockOperationAssigner` 嘗試 queue candidate
- [x] 5.6 保持 `MovementAssignmentPlanner`、`MovementPlanningSnapshot`、`MovementAssignmentProposal` 與 `MoveReservationDraft` 為 pure immutable planning model，在沒有第二種 policy implementation 前不新增 planner interface 或 Strategy registry
- [x] 5.7 新增 architecture/application tests，禁止 adapter 直接呼叫 assignment transaction、禁止 application use case 呼叫另一個 use case、禁止 candidate 暴露 aggregate，並證明三種觸發來源共用相同 façade

## 6. 拆分 persistence/query ports 並集中 transaction invariants

- [x] 6.1 定義最小 `StockOperationStore`、`StockMoveStore` 與 `StockQuantStore` command APIs，只保留 identity lookup、save、必要 lock 與 receipt identity 行為
- [x] 6.2 建立 `AssignmentCandidateQuery` 與 `AssignmentBacklogQuery` 的專用 JDBC/native-query adapters，將 exact FIFO predecessor、queue-head selection 與 backlog discovery 從共用 JPA aggregate repository 移出
- [x] 6.3 建立 `AllocatableStockQuery`，一次讀取 planner 所需 owner/location/multi-SKU FEFO supply；將 UI location stock read 分離為 `StockQuantViewQuery`
- [x] 6.4 保留獨立 `StockOperationReconciliationQuery`，移除只有 persistence test caller 的 `findOldestEnqueuedAt`／`findOldestConfirmedEnqueuedAt` API 與實作
- [x] 6.5 縮小 JPA repositories 與 adapter dependency graph，禁止 candidate、backlog、view 與 reconciliation adapters 共同注入一個包辦所有 read roles 的 JPA aggregate repository
- [x] 6.6 新增 application-internal `LockedStockOperation`，集中完整 operation/moves/move-lines 載入結果、homogeneous state、group completeness、exact coverage 與 movement lifecycle snapshot derivation
- [x] 6.7 新增 application-internal `QuantReservationSet`，集中 per-quant quantities、ordered quant IDs、owner/location scope validation 與 reservation-counter delta derivation
- [x] 6.8 依序重構 assignment、release、completion 與 cancellation transactions 使用兩個 working models，保持 `StockOperation -> StockMove ID order -> StockQuant global write order`、save/outbox 順序與 failure rollback 不變
- [x] 6.9 將只服務單一 cancellation coordinator/transaction 的 preparation、target、step-result 與 external-decision microtypes 內聚為 nested/internal types，保留 durable cancellation aggregate、status、checkpoint 與 external WMS port
- [x] 6.10 將 `StockOperationLifecycleSnapshot` 移至 movement application ownership，保持 assignment result、integration DTO 與 Temporal snapshot 為各自 boundary-specific types
- [x] 6.11 建立 `StockOperationViewQuery` bounded projection，一次或固定批次取得 operation、moves、move lines 與 referenced quants，禁止 operation list 的 `1 + 3N` follow-up query pattern
- [x] 6.12 增加 repository architecture、SQL ordering、query-count、working-model non-persistence 與 lifecycle invariant equivalence tests，證明 list query statement 上限不隨 operation 數量增加

## 7. 切換 Inventory application、REST、WMS 與 workflow canonical path

- [x] 7.1 將 registrar、registration command/result 改為 `StockOperationRegistrar`、`RegisterStockOperationCommand` 與 `StockOperationRegistrationResult`，驗證 source replay 的相同內容 idempotent、衝突內容 rejected
- [x] 7.2 將 predecessor、assignment result、release、completion 與 cancellation APIs 改為 stock-operation vocabulary，不保留 canonical application API 的 `getPickingId()` alias
- [x] 7.3 更新 JPA entities、mappers、剩餘 native queries、seed data 與 reconciliation queries 至 V30 table/column names，禁止依賴 Hibernate auto-DDL 補齊 rename
- [x] 7.4 將 Inventory REST collection 改為 `/stock-operations`，並將 `StockOperationView.Picking`、JSON fields、錯誤訊息與 controller tests 改為 `operation`／`stockOperationId`／`stockOperationTypeId`
- [x] 7.5 將 WMS `Shipment`、command、view、entity、repository 與 use-case correlation 改為 `stockOperationId`，保持 shipment identity/value 與一對一 idempotency 不變
- [x] 7.6 更新 fulfillment workflow snapshot、activity input/result、runtime checkpoint 與 Inventory/WMS Temporal adapters 使用 canonical stock-operation DTO 與新 signal
- [x] 7.7 更新 order source adapter、inbound registrar 與 test fixtures，以 `stockOperationId` 註冊一個 source unit 的 operation 與 canonical moves，且不建立或引用 `AllocationDemand`
- [x] 7.8 驗證 Inventory assignment 不寫入 WMS tables，而 WMS 仍獨立建立 `Shipment`、`Wave`、`WarehouseWork` 與 `PickTask`，並保留真正的 picking/short-pick vocabulary

## 8. 最後切換 producers 與 current audit vocabulary

- [x] 8.1 將 assignment、lifecycle、handover、cancellation 與 outbound-completion publication factories 切換到新版 contracts，讓每個新 business fact 只發布一個版本而不 dual-publish
- [x] 8.2 將新 Outbox aggregate reference 與 current audit/read vocabulary 改為 `StockOperation`，保留歷史 payload 與 legacy aggregate references 原樣可讀
- [x] 8.3 增加 consumer-first／producer-cutover contract tests，證明 legacy replay 可 idempotently normalize、新事件不會造成重複 Shipment 或重複 completion

## 9. 格式化並執行完整驗證

- [x] 9.1 更新 Inventory unit tests，證明 SHIP_COMPLETE、strict shared-SKU FIFO、FEFO、release、completion、cancellation、reservation counters 與 retry idempotency 在 topology/rename 前後等價
- [x] 9.2 執行 `cd backend && ./gradlew spotlessApply`，確認所有 Java rename 與 refactor 都符合 Palantir Java Format 120-column 設定
- [x] 9.3 執行受影響 modules 的 unit tests：`integration-contracts`、`fulfillment-temporal-contract`、`fulfillment-temporal-runtime`、`inventory-context`、`wms-context` 與 `deployments:monolith`
- [x] 9.4 執行 `cd backend && ./gradlew :deployments:monolith:sit`，確認 migration、native SQL、transaction rollback、concurrency、FIFO/FEFO、bounded projection、WMS handoff 與 event-chain SIT 全數通過
- [x] 9.5 執行 Temporal legacy replay 與新 signal workflow tests，確認 history compatibility 與 canonical path 都通過
- [x] 9.6 執行 integration JSON fixtures 與 dual-version/DLT replay tests，確認 producer 只發新版、consumer 在 compatibility window 可讀新舊版
- [x] 9.7 執行 `cd backend && ./gradlew spotlessCheck` 及 `cd backend && ./gradlew check`，修正所有 formatting、unit、architecture 與 SIT failures
- [x] 9.8 執行 repository root 的 `make e2e`，確認新版 events、Temporal 及完整 order-to-allocation-to-WMS flow 使用 `stockOperationId` 通過
- [x] 9.9 比較 rename/refactor 前後 characterization 結果，確認沒有新增 partial assignment、lock-order、state-transition、event cardinality 或 WMS ownership 差異

## 10. 更新現行文件並稽核剩餘舊語彙

- [x] 10.1 更新 living architecture docs、README、diagrams 與 current OpenSpec main specs，使用 `StockOperation -> StockMove -> StockMoveLine <-> StockQuant` 並說明 WMS execution boundary
- [x] 10.2 文件化 `trigger -> StockOperationAssigner -> AssignmentCandidateQuery / MovementAssignmentPlanner / StockOperationAssignmentTransaction` 以及 command-store/query-port 分責圖
- [x] 10.3 文件化 Oracle-like responsibility comparison 與限制：`MovementAssignmentProposal` 是 plan、`StockMoveLine` 是 current exact-execution reservation/evidence、WMS records 是 execution truth、`StockQuant` 是 materialized balance 而非 ledger，且 future `StockReservation/StockAllocation -> execution confirmation -> StockPosting -> StockQuant` 只是 extension seam
- [x] 10.4 增加 living-doc/architecture terminology assertion，禁止把 current `StockQuant` 描述成 immutable transaction ledger，或宣稱 `StockMoveLine` 已支援 planned-versus-actual batch divergence、correction 或 reversal
- [x] 10.5 對 current source、current schema mapping、active contracts、tests 與 living docs 執行最終 `StockPicking`／`Picking*`／`pickingId`／SQL picking 搜尋，逐項分類並移除非相容層或非 WMS picking 的命中
- [x] 10.6 確認剩餘 legacy vocabulary 僅存在於已發布 migrations、歷史/archived artifacts、明確標記的 contract/Temporal compatibility readers 與 WMS 真正 picking model
- [x] 10.7 記錄 deployment cutover 與 rollback runbook：Flyway-before-traffic、consumer-first、producer single-version cutover，以及發布新版事件後只能回退至 dual-reader compatibility release
