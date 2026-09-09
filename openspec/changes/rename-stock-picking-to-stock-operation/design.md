## Context

目前 Inventory 已是 move-centric model：一個 `StockPicking` 保存 operation type、direction、owner、source trace、route、assignment policy、排程與 moves 的同質狀態摘要；`StockMove` 保存 SKU 與 quantity intent，`StockMoveLine` 保存 move-to-quant reservation／exact execution detail，`StockQuant` 保存物化餘額。WMS 另有自己的 `Shipment`、`Wave`、`WarehouseWork` 與 `PickTask`。

`StockPicking` 因此不是 WMS picking，也不是 DOM `FulfillmentUnit`。目前一張 outbound order 固定一個 facility，Inventory operation 和 WMS Shipment 以同一 UUID correlation 串接；inbound operation 也使用相同 aggregate。REST/query 層已使用 `StockOperationRest`、`StockOperationQueryService` 與 `StockOperationView`，但 endpoint、nested view、domain、database 與 wire contracts 仍使用 picking vocabulary。

這是一個跨 Inventory、WMS、Temporal、integration contracts、PostgreSQL schema、測試與文件的 breaking rename。主要限制如下：

- 已發布 Flyway migrations 不得修改；fresh database 仍會先建立舊名稱，再由新的 forward migration 改名。
- V29 的 deferred constraint triggers 與 PL/pgSQL functions 直接引用 `stock_pickings`、`picking_id` 與 `TG_TABLE_NAME = 'stock_pickings'`，table rename 不會安全地重寫所有 function body 字串。
- Outbox、DLT 與既有 event payload 可能仍包含 V1/V2 `pickingId`；不得改寫歷史 payload。
- Temporal history 已持久化 signal name `pickingAssigned` 與 `PickingAssignmentSnapshot` payload；直接移除會使既有 workflow 無法接收舊 signal 或 replay。
- `make-stock-move-allocation-core` 已完成但尚未同步／archive，其 delta specs 與 delivered code 才是本 change 的行為基線；main specs 仍含已被取代的 allocation-demand 行為。

## Goals / Non-Goals

**Goals:**

- 讓 Inventory ubiquitous language 統一使用 `StockOperation`、`StockOperationType`、`StockOperationDirection`、`StockOperationState` 與 `stockOperationId`。
- 讓 domain、application、persistence、REST、events、Temporal checkpoint、WMS correlation、SQL、測試與現行文件採用同一語言。
- 保留既有 UUID、資料列、source identity、state、timestamps、version、lock order 與所有 allocation／completion invariants。
- 以 forward-only、無資料重建的 schema migration 完成 table、column、constraint、index、trigger 與 function rename。
- 讓舊 event／Temporal history 在明確的 compatibility window 內仍可讀，legacy vocabulary 只存在於 boundary adapter。
- 將 assignment 的多入口收斂到單一 façade，讓 source facts、pure planning 與 transactional target apply 有清楚邊界。
- 將 aggregate command stores 與 candidate、backlog、supply、view、reconciliation queries 分開，避免共享 repository 成為所有 SQL 的堆放處。
- 集中 lifecycle transaction 的完整集合與 reservation invariants，並讓 operation list read 的 SQL 數量不隨 operation 數量成長。

**Non-Goals:**

- 不新增或具體化 `FulfillmentPlan`／`FulfillmentUnit`；每張 order 仍由上游固定一個 facility。
- 不將 `StockMoveLine` 改名為 `StockAllocation`，也不改變其 assignment 與 exact-execution 語意。
- 不新增 `StockPosting`、reversal ledger、partial allocation、batch substitution 或 planned-versus-actual batch confirmation。
- 不新增持久化 working-set aggregate、通用 repository framework 或第二套 reservation truth。
- 不改變 WMS 中真正表示現場揀貨的 `PickTask`、`WarehouseWork.pickingWork`、short-pick 與 picking UI vocabulary。
- 不修改已發布 Flyway migrations、歷史 Outbox payload 或已完成／archive 的 OpenSpec 歷史文件。

## Decisions

### 1. Aggregate 採 `StockOperation`，不用 `StockSession`、`StockMovementGroup` 或 `FulfillmentUnit`

`StockOperation` 是持久、可鎖定、有 identity 與 lifecycle summary 的 Inventory operation；它不依賴 operator、device、login 或 start/end session，因此 `StockSession` 會錯誤暗示短生命週期互動。`StockMovementGroup` 雖結構正確，卻弱化了 operation type、source、route、policy 與 scheduling 的業務責任。`FulfillmentUnit` 只適用 outbound，無法涵蓋 inbound/internal。

核心命名採以下對照：

| 舊名 | 新名 |
| --- | --- |
| `StockPicking` | `StockOperation` |
| `PickingState` | `StockOperationState` |
| `PickingDirection` | `StockOperationDirection` |
| `PickingDefinition`／picking type vocabulary | `StockOperationType` |
| `pickingId` | `stockOperationId` |
| `pickingTypeId` | `stockOperationTypeId` |
| `StockMovementGroupRegistrar` | `StockOperationRegistrar` |
| `RegisterStockMovementGroupCommand` | `RegisterStockOperationCommand` |
| `StockMovementRegistrationResult` | `StockOperationRegistrationResult` |
| `MovementAssignmentAttempt` | `StockOperationAssigner` |
| `PendingPickingSelection` | `AssignmentCandidateQuery` |
| `PendingPickingSelection.Selection` | `AssignmentCandidate` |
| `PendingPickingQueueKey` | `AssignmentQueueKey` |
| `AssignPickingUsecase` | `StockOperationAssignmentTransaction` |
| `PendingPickingBacklogAssignmentUsecase` | `StockOperationBacklogReconciler` |
| `PickingAssignmentResult` | `StockOperationAssignmentResult` |
| `PickingPredecessor` | `StockOperationPredecessor` |
| `StockPickingCancellationOperation` | `StockOperationCancellation` |

`StockOperationView.Picking` 改成 `StockOperationView.Operation`。真正屬於 WMS 的 `PickTask`、`PickTaskStatus`、`WarehouseWork.pickingWork` 與使用者看見的 picking action 不在 mechanical rename 範圍；implementation 必須以 package／ownership 判斷，而不是對整個 repository 做無差別文字替換。

### 2. Rename 不改任何 lifecycle 或 transaction behavior

`StockOperation` 仍是 moves 的 transactionally maintained summary：

```text
CONFIRMED <=> every move CONFIRMED, no move lines
ASSIGNED  <=> every move ASSIGNED, exact move-line coverage
DONE      <=> every move DONE, retained move-line evidence
CANCELLED <=> every move CANCELLED, no active move lines
```

Allocation 仍以 operation 為 selection／policy／atomicity boundary，lock order 仍為：

```text
StockOperation -> StockMove ID order -> StockQuant global write order
```

Alternatives considered：趁 rename 引入 `StockPosting` 或拆出 `StockAllocation`。否決，因為那會改變 execution truth、schema cardinality、completion behavior 與 WMS contract，無法再用現有 characterization tests 證明 rename 前後等價。

### 3. Assignment 只有一個 canonical application façade

三種觸發來源都呼叫 `StockOperationAssigner`：source registration 後的首次嘗試、availability event 的 queue-key 嘗試，以及 scheduler 啟動的 backlog reconciliation。它是 application façade，協調三個角色而不承擔 domain calculation：

```text
Order / Availability Event / Backlog Reconciler
                       |
                       v
             StockOperationAssigner
               /        |                 \
AssignmentCandidateQuery  MovementAssignmentPlanner  StockOperationAssignmentTransaction
          source facts           pure proposal                 target apply
```

`AssignmentCandidateQuery` 只回傳 immutable `MovementPlanningSnapshot` 與 optional `StockOperationPredecessor`，不得把可變 `StockOperation` aggregate 暴露給 planner 或入口。`MovementAssignmentPlanner` 保持 pure concrete service；目前只有一個 policy implementation，不為模式名稱增加無用 interface。`StockOperationAssignmentTransaction` 是 internal transaction boundary，負責重新 lock、重新讀取與 revalidate 後 apply proposal，不是另一個可被 adapter 任意呼叫的 use case。

`AssignmentQueueKey` 直接作為 availability/backlog 入口的 transport-neutral value object；移除只重複其欄位的 `AssignPendingPickingCommand` 與只包一層 transaction 的 `PendingPickingAssignmentUsecase`。Scheduler 使用 `StockOperationBacklogReconciler` 表達「尋找並補償尚未 assignment 的 operation」，reconciler 仍逐一呼叫同一 façade，不複製 planner/commit 流程。

Alternatives considered：保留 `Selector -> Planner -> Committer` 三個對等公開 service。否決，因 selector 會洩漏 aggregate、committer 會被誤當獨立 use case，且多個入口容易各自重組流程。三個技術階段仍存在，但只有 façade 是 application entry。

### 4. Command stores 與按用途命名的 query ports 分離

Command side 保持小而明確：`StockOperationStore`、`StockMoveStore` 與 `StockQuantStore` 只提供 aggregate identity lookup、save、必要 lock 與 receipt identity 等 persistence 行為。它們不承擔 queue discovery、FEFO planning、REST projection 或跨 aggregate reconciliation。

Read side 依使用目的分成：

- `AssignmentCandidateQuery`：取得 candidate、下一個 queue candidate 與 exact predecessor facts。
- `AssignmentBacklogQuery`：發現可重試的 `AssignmentQueueKey`，不提供無 production caller 的 oldest-enqueued API。
- `AllocatableStockQuery`：為 planner 一次取得 owner/location/SKU scope 內 deterministic FEFO supply。
- `StockOperationViewQuery` 與 `StockQuantViewQuery`：直接產生 REST/UI projection。
- `StockOperationReconciliationQuery`：只處理 durable state reconciliation。

Query adapter 可使用 JDBC projection 或針對性 native SQL，不必經過共用 JPA aggregate repository。`StockOperationViewQuery` 列出 N 個 operations 時，SQL statement 數量必須有固定上限，不能出現先列 operation 再逐筆查 moves、move lines、quants 的 `1 + 3N` pattern。

這不是 CQRS infrastructure project：不新增 generic base repository、event-sourced read model 或獨立資料庫。目的只是讓每個 port 的 caller、transaction semantics 與 query shape 可被看懂和測試。

### 5. Lifecycle transaction 共用兩個 ephemeral working models

Assignment、release、completion 與 cancellation 都需要 lock 完整 operation/moves、驗證 homogeneous state 與 exact coverage、按 quant 彙總並以全域順序 lock quants。這些共同規則以兩個 application-internal working models 表達：

- `LockedStockOperation`：當次 transaction 已鎖定的 operation、完整 moves 與 move lines；提供 state、group completeness、exact coverage 與 lifecycle snapshot invariants。
- `QuantReservationSet`：由 move lines/proposal 推導的 per-quant quantities 與 ordered quant IDs；驗證 owner/location/quant scope 並產生 reservation-counter delta。

兩者沒有 repository、persistence identity 或獨立 lifecycle，也不成為第二套 reservation ledger。它們只在 transaction 內保存已載入 facts 與衍生驗證，transaction service 仍明確負責 lock、save、Outbox 與 rollback。這能消除四個 transaction scripts 的重複 invariant plumbing，而不把 domain aggregate 改造成大型 god object。

Cancellation 的 durable `StockOperationCancellation`、checkpoint、status 與 external WMS port 保留；只在單一 coordinator/transaction 使用的 preparation、target、step-result、external-decision microtypes 應內聚為 nested/internal types。`StockOperationLifecycleSnapshot` 歸 movement application ownership，不放在 allocation result package。

Alternatives considered：建立完整 Unit of Work／Saga framework。否決，因 transaction boundary 已由 Spring 與 PostgreSQL 明確提供；通用 framework 會隱藏 lock order 與 rollback semantics。

### 6. 採 Oracle-like 責任隔離，但不假裝已具備完整 inventory accounting

Oracle 在概念上分開 Order Management orchestration、Inventory reservation、WMS allocation/task、material transaction 與 on-hand balance。官方文件將 schedule、reserve 與 ship 描述為不同 fulfillment tasks；WMS allocation 將 order detail 關聯到特定 inventory，之後才形成 execution task；on-hand detail 則由 material transactions 建立或更新。這些資料用來確認責任方向，不表示本專案要複製 Oracle 的 product modules、schema 或 class names：

- [Oracle Fulfillment Tasks](https://docs.oracle.com/en/cloud/saas/supply-chain-and-manufacturing/26a/faiom/fulfillment-tasks.html)
- [Oracle WMS Allocation](https://docs.oracle.com/en/cloud/saas/warehouse-management/26b/owmwr/allocation.html)
- [Oracle On-Hand Quantity Detail](https://docs.oracle.com/en/cloud/saas/supply-chain-and-manufacturing/26a/oedsc/invonhandquantitiesdetail-6068.html)

本 change 與 Oracle 概念層的對照如下：

| Responsibility | Oracle reference concept | 本 change 的 current model | 對齊程度 |
| --- | --- | --- | --- |
| Order／fulfillment orchestration | Order Management orchestration tasks | Ordering + Temporal，facility/flow 仍較固定 | 部分對齊 |
| Inventory commitment | Inventory reservation | `StockMoveLine` + `StockQuant.reservedQuantity` | 已隔離 commit，但 reservation 尚非獨立 durable concept |
| Warehouse allocation/execution | WMS allocation、wave、task | WMS `Shipment`、`Wave`、`WarehouseWork`、`PickTask` | ownership 已隔離 |
| Inventory transaction history | Material transaction | 尚無 `StockPosting`／transaction ledger | 未實作 |
| Current stock balance | On-hand quantity | `StockQuant` | 責任對齊 |

因此「Oracle-like」只指責任隔離方向：order 不成為 Inventory aggregate，planner 不寫庫存，reservation commit 不建立 WMS work，WMS execution 不直接擁有 Inventory balance，current balance query 不等於 transaction history。它不表示 current model 已能獨立表達 reservation、planned execution、actual execution、posting 與 reversal。

目前 truth boundary 必須保持明確：

```text
MovementAssignmentProposal  = non-durable planning result
StockMoveLine               = committed reservation detail
                              + exact-execution 成立時的 retained evidence
WMS Shipment/Work/PickTask  = warehouse execution truth
StockQuant                  = current materialized on-hand/reserved balance
                              != immutable inventory transaction history
```

在 current exact-execution invariant 下，planned `LOT-A 10` 必須也是完成 evidence `LOT-A 10`，所以不需要為不存在的差異預先建立 posting layer。若未來允許 `LOT-A 10` 規劃後實際確認 `LOT-A 8 + LOT-B 2`、partial execution、substitution、correction 或 reversal，必須以獨立 capability change 引入下列責任，而不是把 `StockOperation` lifecycle、`StockMoveLine` 或 `StockQuant` 偷偷擴張成 ledger：

```text
Fulfillment orchestration
          -> durable StockReservation / StockAllocation
          -> WMS actual execution confirmation
          -> immutable StockPosting / MaterialTransaction
          -> StockQuant projection
```

這個 future path 是 extension seam，不是本 change 的 hidden implementation scope。Current code 不建立 placeholder entity、empty table、generic posting interface 或 dual-write；只有 boundary names、events ownership 與文件不得阻礙未來分層。

Alternatives considered：現在就引入 `StockAllocation` 與 `StockPosting`。否決，因 current business invariant 不允許 planned-versus-actual divergence；預先持久化沒有獨立 business fact 的層次只會增加 schema、transaction 與 reconciliation 複雜度。

### 7. PostgreSQL 使用單一 forward migration 做 transactional metadata rename

新增下一個 Flyway migration；不編輯 V4、V21～V29。migration 保留所有 UUID 與資料，只改 metadata：

| 舊 schema | 新 schema |
| --- | --- |
| `stock_picking_types` | `stock_operation_types` |
| `stock_pickings` | `stock_operations` |
| `stock_picking_cancellation_operations` | `stock_operation_cancellations` |
| `stock_operations.picking_type_id` | `stock_operation_type_id` |
| `stock_moves.picking_id` | `stock_operation_id` |
| `stock_operation_cancellations.picking_id` | `stock_operation_id` |
| `stock_operation_cancellations.operation_id` | `cancellation_operation_id` |
| `wms_shipments.picking_id` | `stock_operation_id` |

Foreign keys、unique indexes、check constraints 與 index names 一併改名。因 V29 function body 及 `TG_TABLE_NAME` 判斷含舊名稱，migration 必須先 drop 三個 operation-group constraint triggers 和兩個舊 PL/pgSQL functions，完成 table／column rename，再以 `assert_stock_operation`、`enforce_stock_operation_group` 及新 trigger names 重建相同 deferred invariants。reserved-quant functions 只需在引用 renamed `stock_moves.stock_operation_id` 的部分重建或 `CREATE OR REPLACE`；不得留下執行時才爆出的舊欄位 SQL。

不採 shadow table／dual-column，因本部署是單一應用版本在 startup 時先跑 Flyway、成功後才接流量，沒有舊、新 binary 同時寫同一 schema 的需求。直接 metadata rename 比 dual-write 少一份 identity truth，也不需要 backfill。

### 8. Integration events 升版，舊版本只在 ingress compatibility layer 存活

JSON field rename 是 wire breaking change，不能只在原 class 改 `@JsonProperty`。新增：

- `promising.v3.OrderAllocationCommittedIntegrationEvent`
- `fulfillment.v3.ShipmentHandedOverIntegrationEvent`
- `fulfillment.v3.ShipmentCancelledIntegrationEvent`
- `fulfillment.v3.OutboundMovementsCompletedIntegrationEvent`
- `inventory.v2.StockOperationLifecycleIntegrationEvent`

新版本使用 `stockOperationId` 與 `stockOperationTypeId`；event type／channel 與 partition/correlation key 保持既有業務值，contract version 遞增。Producer cutover 後只發新版本，不 dual-publish，避免 WMS 建立兩次 Shipment 或 completion 被處理兩次。Consumer 在 compatibility window 同時註冊舊版與新版 handler，立刻 normalize 成新 command／value object；domain/application 不保留 `getPickingId()` alias。

既有 Outbox、DLT 與 event fixture 不重寫。舊 `AggregateReference` 值 `StockPicking` 保留可讀，新 publication 使用 `StockOperation`；需要按 aggregate type 查歷史的 read side 在 compatibility window 接受兩者。舊 reader 的移除是後續 cleanup，不和 producer cutover 同時發生。

Alternatives considered：在同一 contract version 加 `@JsonAlias("pickingId")`。否決，因 producer 的 JSON shape 會在未升版下改變，schema registry／fixture 無法區分新舊，且舊 producer 與新 consumer 的相容性變得隱含。

### 9. Temporal 以新 signal 加 legacy forwarding handler，不直接 rename history-visible 名稱

Temporal 的 `@SignalMethod(name = "pickingAssigned")` 是持久化協定，不是普通 Java method。新增 `stockOperationAssigned(StockOperationAssignmentSnapshot)` signal 作為 canonical path；保留 deprecated `pickingAssigned(PickingAssignmentSnapshot)` signal，在 workflow implementation 內轉成同一個 `StockOperationAssignmentCheckpoint`。新 event adapter 只送新 signal，舊 signal 留到所有可能含舊 history／DLT replay 的 workflow 結束。

Workflow snapshot、activity input/result 與 checkpoint 改用 `StockOperationAssignmentSnapshot`／`stockOperationId`。Legacy DTO 不進 Inventory/WMS domain，只存在 workflow contract compatibility package。若某些既有 payload 由相同 DTO 型別反序列化，compatibility DTO 明確接受舊 JSON property；不得依賴全域 ObjectMapper naming hacks。

Workflow command sequence、timer、activity name 與 branching 不變，因此不新增 Temporal version marker。若 implementation 為了兼容 signal 而改變 command emission，則必須另以 Temporal patch/version API 保護；純 DTO normalize 不需要。

### 10. REST/read model 直接提供 canonical endpoint，舊 route 不在 domain 留 alias

`StockOperationRest` 的 route 從 `/stock-pickings` 改為 `/stock-operations`，錯誤訊息與 `StockOperationView` JSON shape 改為 operation vocabulary：nested `operation`、`stockOperationId`、`stockOperationTypeId`、`direction`、`state`。目前 endpoint 是同 repository 內 demo/read API，consumer 與測試在同 change 一起切換，因此不建立永久雙 route。

若部署環境存在 repository 外部 client，應在 ingress gateway 暫時提供 deprecated `/stock-pickings` alias 或 API version，而不是讓 domain controller 同時暴露兩套名稱；該 compatibility alias 的下線時間由部署環境管理。

### 11. 現行文件更新，歷史 artifact 保持不可變

更新 architecture docs、diagrams、README、current main specs 與新 change artifacts，使現況圖使用 `StockOperation -> StockMove -> StockMoveLine <-> StockQuant`。WMS 章節中的真正 picking 語意保留。

已發布 Flyway SQL、已完成／archive change 的 proposal/design/tasks/spec delta 是決策歷史，不做 retroactive rename。搜尋仍會在這些歷史檔案找到 `StockPicking`，architecture test 不得用「整個 repository 零命中」作為完成條件；完成條件應限定 current source、current schema mapping、living docs 與 active contracts。

在建立本 change 的 delta specs 前，先同步或 archive 已完成的 `make-stock-move-allocation-core`，使 main specs 反映 delivered move-centric baseline。否則新 delta 會對著已失效的「outbound 沒有 picking」requirement 做文字 rename，產生邏輯衝突。

## Risks / Trade-offs

- **[Risk] 無差別 rename 誤傷 WMS 真正的 picking 語言。** → 以 bounded context allowlist 執行；Inventory operation vocabulary 改名，WMS `PickTask`／`pickingWork` 保留，review 時逐一分類剩餘命中。
- **[Risk] Table rename 成功但 V29 deferred trigger function 到 commit 才引用不存在的舊欄位。** → migration 明確 drop/recreate functions 與 triggers；SIT 在同 transaction 分別驗證 homogeneous state、move-line coverage 與 reserved counter constraint。
- **[Risk] V2 Outbox/DLT replay 到只認 V3 的 consumer。** → consumer-first dual-version read、producer single-version cutover、以 retention/DLT backlog 為移除門檻。
- **[Risk] Temporal 舊 workflow 收不到 renamed signal 或 replay payload 失敗。** → 保留 `pickingAssigned` legacy signal 與 DTO conversion；以舊 history fixture/replay test 驗證，不只跑新 workflow happy path。
- **[Risk] Hibernate mapping、native SQL、trigger、seed 與 tests 只改一部分。** → migration 後跑 schema SIT、native-query coverage、repository tests 與全域 `rg` 分類；禁止依賴 Hibernate auto-DDL 補洞。
- **[Risk] Mixed aggregate type 讓 operation audit 看似斷裂。** → identity UUID 不變；history query 在 compatibility window 將 `StockPicking` 與 `StockOperation` normalize 成同一顯示類型。
- **[Risk] Query/store 拆分改變 FIFO ordering、transaction lock 或 snapshot 時點。** → 以 characterization/concurrency tests 固定排序、scope 與 lock order；candidate 只做 optimistic facts，transaction 仍做 final exact predecessor recheck。
- **[Risk] Working model 演變成另一套持久化 domain model。** → architecture tests 禁止其 repository/entity/independent ID；它只能由 transaction 已載入 facts 建立並在同 transaction 消亡。
- **[Risk] Projection 改寫造成漏列或 N+1 回歸。** → 對相同 fixture 比對既有 JSON semantics，並以 query-count test 限制 operation list 的 SQL statement 上限與 operation 數量無關。
- **[Risk] 文件或後續程式把 Oracle-like isolation 誤讀成完整 Oracle accounting equivalence。** → 明列 current truth table 與未實作能力；architecture docs 禁止將 `StockQuant` 稱為 ledger，並禁止將 `StockMoveLine` 宣稱為可獨立保存 planned-versus-actual divergence 的 posting。
- **[Trade-off] Direct DB rename 無法讓舊 binary 與新 binary 同時服務。** → 接受此限制，因部署模型是 Flyway-before-traffic 的單版本 monolith；若未來需要 rolling mixed-version deploy，另做 expand/contract migration，而不是在此 change 預留永久 dual schema。
- **[Trade-off] 歷史 migrations/artifacts 仍包含舊名稱。** → 接受，因它們描述當時 schema；current code與living docs 的語意一致比重寫歷史更重要。

## Migration Plan

1. 將已完成的 `make-stock-move-allocation-core` delta specs 同步／archive，確認 current specs 與 delivered code 都以 operation-group + canonical moves 為基線。
2. 建立 rename 前 characterization baseline：Inventory unit/SIT、integration JSON fixtures、Temporal workflow replay、WMS handoff、cancellation、concurrency 與 e2e 全部通過。
3. 先新增 V3/V2 integration contract classes、新版 fixtures、dual-version consumers、Temporal canonical signal 與 legacy forwarding handler；producer 仍發舊版本。
4. 新增 forward Flyway migration，drop/recreate operation-group triggers/functions，rename tables/columns/constraints/indexes，並以 schema/invariant SIT 驗證既有資料、UUID、row count 與 constraints 不變。
5. 在跨 context cutover 前先收斂 Inventory application topology：建立單一 `StockOperationAssigner`、immutable candidate boundary、internal assignment transaction、backlog reconciler，以及依用途拆分的 command stores/query ports。
6. 引入 ephemeral `LockedStockOperation`／`QuantReservationSet`，依序重構 assignment、release、completion、cancellation；以 characterization、rollback、concurrency 與 query-count tests 證明 invariants、lock order、JSON semantics 不變。
7. 原子切換 Inventory domain/application/persistence、WMS correlation、Temporal checkpoint、REST/query、native SQL、seed 與 tests 至 `StockOperation` vocabulary；producer 改發新 contract version。
8. 執行 `cd backend && ./gradlew spotlessApply`，再跑受影響 module tests、migration/SIT、integration contracts、Temporal replay、WMS flow、full backend test 與 e2e；完成前執行 `cd backend && ./gradlew spotlessCheck`。
9. 更新 living docs、diagrams 與 current specs；分類剩餘 `StockPicking`／`pickingId` 命中，僅允許歷史 migrations/artifacts、legacy contract readers 與 WMS 真正 picking 語意。
10. 部署後觀察舊版本 Outbox/DLT backlog 與 in-flight Temporal workflows。超過 retention window 且無舊 workflow 後，以獨立 cleanup change 移除 V1/V2 readers、legacy signal/DTO、舊 aggregate-type normalization 與任何 gateway alias。

Rollback 不修改或刪除資料。若新 binary 尚未發布 V3 event，可停止服務並以新的 forward recovery migration 將 schema metadata 改回舊名，再部署舊 binary；不得手工改 Flyway history。若已發布 V3 event，回退目標必須至少是同時理解 V2/V3 的 compatibility release，否則採 forward fix。必要時由部署前 snapshot 恢復，但 metadata-only rename本身不需要資料 backfill rollback。

## Open Questions

- 各部署環境的 Outbox、DLT 與 Temporal history retention／最長 workflow duration 是多少？這不阻擋 canonical rename，但決定 legacy contract、signal、aggregate type 與可能 gateway alias 的最早移除日期；實作 tasks 必須記錄實際門檻，不得以「測試通過」代替 compatibility window。
