## Context

目前 allocation 已具有清楚的 source／process／target 外形：`AllocationDemand` 保存 immutable source snapshot，`AllocationDemandPlanner` 產生暫態 FEFO／ship-complete plan，`AllocationCommitter` 在一個 local transaction reserve `StockQuant` 並建立 `StockMove`／`StockMoveLine`，WMS 再由 committed event 建立 `Shipment` 與 warehouse work。

這個模型消除了 pending placeholder execution，但成功後的 commitment 仍沒有自己的持久化語言：

- demand-to-supply mapping 只存在於 movement lines；
- `AllocationDemand.id` 同時充當 source requirement 與 committed allocation identity；
- demand 的 `ALLOCATED` state 只能表達一次完整配置，無法自然容納 release 後重配或後續多次 allocation；
- routing、move split／merge、WMS re-wave 若開始變動 execution rows，會間接改變系統對「哪些 supply 已被承諾」的回答；
- release 會刪除 movement lines，因而無法稽核曾經承諾後釋放的批次。

未來預期會加入 Odoo 等級的業務複雜度，但本系統的 bounded contexts 不同：Inventory allocation、physical movement 與 WMS work 必須維持分離，不能讓 `StockMove` 同時成為需求、reservation、route 與 warehouse execution 的共同狀態機。

四個持久化層固定為：

```text
Business Source          AllocationDemand          Allocation Commitment          Execution Targets
Order / Transfer   -->   normalized requirement --> Allocation + AllocationSlice --> StockMove / Shipment
                                                       ^
                                                       |
                                                  StockQuant supply
```

Planner、policy、transactional committer 與 target materializer 是層間 process，不是額外的 source of truth。`StockQuant` 是 commitment process 讀取與鎖定的 supply ledger，與四層垂直流程正交。

## Goals / Non-Goals

**Goals:**

- 建立獨立、持久化且可稽核的 `Allocation`／`AllocationSlice` commitment layer。
- 讓一個 demand 可在資料模型上由零到多個 allocations 滿足，而不把 execution state 放回 demand。
- 將 reservation 的 canonical fact 從 movement line 移到 allocation slice，並與 `StockQuant.reservedQuantity` 保持 transactionally consistent。
- 保留純 planner 與現行 strict shared-SKU FIFO、FEFO、ship-complete、single-location、integer base-unit 及 optimistic-locking 行為。
- 讓 movement routing 與 WMS grouping 可獨立演化，不改變 committed demand-to-supply coverage。
- 以 allocation identity 驅動 committed event、WMS idempotency、completion、release、cancellation 與 query trace。
- 提供可驗證的 schema／contract migration，且不導入雙 writer。

**Non-Goals:**

- 本 change 不開放 partial allocation、manual approval、reservation window、substitution、cross-location solving、route-chain configuration 或 FIFO bypass。
- 不持久化每次 planner attempt 或 insufficient proposal；失敗嘗試仍只留下 demand、metrics 與 log。
- 不建立 Odoo-style pending `StockMove` 或 inventory outbound `StockPicking`。
- 不以 event sourcing 重建完整 allocation ledger；使用 relational aggregate 與明確 terminal slice states。
- 不讓 allocation context 擁有 pick、pack、wave、shipment 或 carrier lifecycle。
- 不新增跨 context transaction；WMS handoff 仍使用 Outbox／Inbox eventual consistency。
- 不改變 inbound receipt 的 movement／picking 模型。

## Decisions

### 1. Commitment 是獨立 aggregate，不是 execution projection

新增 `Allocation` aggregate root 與其 `AllocationSlice` entities：

```text
Allocation
  id
  allocationDemandId
  policyCode
  committedAt
  version

AllocationSlice
  id
  allocationId
  allocationDemandLineId
  stockQuantId
  quantity
  state = RESERVED | RELEASED | CONSUMED
  reservedAt
  releasedAt?
  consumedAt?
```

一個 `Allocation` 是一次原子 commit 的 header；一個 slice 是一條 demand line 對一個 supply quant 的正數 quantity。相同 allocation、demand line 與 quant 的 picks 在 commit 前合併成一個 slice，並由 database unique constraint 防止重複。

Slice state transition 只允許：

```text
RESERVED -> RELEASED
RESERVED -> CONSUMED
```

`RELEASED` 與 `CONSUMED` 都是 terminal。Reallocation 必須先 release reversible allocation，再以新 `Allocation.id` 建立新 slices；不能重啟、改量或覆寫舊 slice。

`Allocation` header 不持久化一個會隨 slice 組合爆炸的巨型 status。Header lifecycle 由 slices 投影：全為 `RESERVED` 是 active、全為 `RELEASED` 是 released、全為 `CONSUMED` 是 consumed；混合狀態在現行 ship-complete policy 下不合法，未來若 execution policy 允許部分 consume，query projection 可明確呈現 mixed coverage，而不修改 header enum。

**Alternative considered:** 繼續讓 `StockMoveLine` 作為 reservation ledger。這使 route re-materialization、move cancellation 與 WMS replanning 都能意外改變 allocation truth，因此不採用。

**Alternative considered:** 只新增 `Allocation` header，仍由 move lines 表達 picks。Header 無法回答 demand-to-quant quantity，也不能稽核 release／reallocation，沒有解決核心耦合，因此不採用。

**Alternative considered:** 使用 append-only allocation events。現階段沒有 event-store、replay、snapshot 或跨 aggregate temporal query需求，引入 event sourcing 的 operational cost 不成比例，因此不採用。

### 2. Demand 只保存 requirement lifecycle，coverage 由 slices 計算

`AllocationDemand` 保留 accepted source snapshot、canonical lines、scope、scheduling、destination intent 與 source cancellation checkpoint。現有 `PENDING/ALLOCATED/CANCELLED` persistence 改為 requirement lifecycle：

```text
ACTIVE
CANCELLED
```

`ACTIVE` 不代表尚未配置；它只表示 requirement 仍有效。每條 demand line 的數量投影為：

```text
reservedQuantity = SUM(slice.quantity WHERE state = RESERVED)
consumedQuantity = SUM(slice.quantity WHERE state = CONSUMED)
coveredQuantity  = reservedQuantity + consumedQuantity
openQuantity     = requiredQuantity - coveredQuantity
```

現行不支援 partial cancellation，因此 cancelled demand 的 candidate quantity 固定為零；未來若加入 line-level cancellation，必須新增明確 cancellation quantity fact，不能修改 accepted required quantity。

Candidate 定義改為 `ACTIVE demand AND any line.openQuantity > 0`。Shared-SKU predecessor 也只考慮較早、scope 相同、SKU footprint 相交且仍有 open allocation quantity 的 active demand；已完整 covered 的 demand 不阻擋後繼，即使 execution 尚未完成。

目前 ship-complete policy 保證一次 allocation 完整覆蓋 demand 的全部 open quantity，所以對外仍只觀察到 waiting 或 fully allocated。這個 change 不藉由測試注入 partial slices 來宣稱已有 partial feature，只驗證 aggregate／query invariants 能正確區分 requirement、coverage 與 execution。

**Alternative considered:** 保留 persisted `ALLOCATED` 並把它當 coverage cache。它會和 slice sums 形成雙重真相，而且在 release／reallocation 後需要跨 aggregate同步，因此不採用。

### 3. Planner proposal 不持久化，也不具有 authority

`AllocationDemandPlanner` 維持 repository-free pure function。輸入是 demand 的 open-line snapshot 與依 FEFO 排序的 allocatable batches，輸出改名或語意收斂為 `AllocationProposal`：

```text
AllocationProposal
  allocationDemandId
  demandVersion
  policyCode
  proposed slices (demandLineId, stockQuantId, quantity)
  missing quantities
```

Proposal 沒有 allocation id、不能發布 event，也不修改任何 model。Insufficient proposal 不保存；manual approval 若未來需要 durable proposal，必須以獨立 capability 定義 expiry、revalidation 與 approval identity。

唯一啟用的 `SHIP_COMPLETE` policy gate 要求：

- proposal 覆蓋 demand 的全部 open quantities；
- 每條 line coverage 精確等於其 open quantity；
- 同 SKU 多 lines 仍按 canonical line sequence 分配 FEFO batches；
- 任一 SKU 不足時 proposal 不含可 commit slices。

Policy 是 domain interface／value，不是可由未授權 request 任意指定的 transport field。本 change 由 source adapter 固定選擇 `SHIP_COMPLETE`；後續 policy-selection change 才能定義來源權限與 persisted policy snapshot。

**Alternative considered:** 先持久化 proposal，再由另一個 transaction approve。這會把會過期的 ATP snapshot變成待同步 lifecycle，現行沒有人工 checkpoint，故不採用。

### 4. 一個 transactional facade 依序 commit facts 與 materialize Inventory targets

Application boundary 使用一個 `CommitAllocationUsecase`（名稱可依既有 package convention 調整）包住同一 database transaction：

1. 以 id 載入並鎖定 active demand，驗證 version 與 open quantities。
2. 在 transaction 內重新查 strict FIFO predecessor；blocked 時不寫入。
3. 依全域 `StockWriteOrder` 載入／鎖定 proposal 涉及的 quants。
4. 重新驗證 owner、location、SKU、expiry 與 ATP。
5. 執行 `SHIP_COMPLETE` coverage validator。
6. 建立 `Allocation` 與 `RESERVED AllocationSlice` rows。
7. 逐 quant 增加 reserved counter。
8. 呼叫 allocation-owned target materializer，建立 outbound assigned moves 與 movement lines。
9. movement line 引用對應 `AllocationSlice.id`；outbound move 引用 `Allocation.id` 與 demand line trace。
10. 由 committed aggregate snapshot 寫入 Outbox event。

任一步驟失敗，allocation、slices、quant counters、moves、move lines 與 Outbox 全部 rollback。這保留目前單一 local transaction 的強一致性，新增資料邊界但不新增 eventual-consistency window。

Code responsibility 分為：

```text
PendingDemandAllocator       select + invoke planner
AllocationCommitmentWriter   validate + create allocation/slices + reserve quants
AllocationTargetMaterializer create inventory execution targets from committed facts
CommitAllocationUsecase      transaction boundary + outbox publication
```

這些名稱表示責任，不要求每項必然是一個 public class；implementation review 應優先避免只有一個 method 的 ceremony。Pure validation 可留在 aggregate/value objects，transaction orchestration 留在 application layer。

**Alternative considered:** 先 commit allocation，再以 Outbox 非同步建立 Inventory moves。兩者目前同 database 且 fulfillment contract 要求 committed event 帶完整 movement set；拆 transaction 會新增 orphan commitment、materialization retry 與事件排序問題，因此不採用。WMS targets 因跨 context，仍在 committed event 後非同步建立。

### 5. AllocationSlice 是 reservation truth，quant counter 是 concurrency counter

`StockQuant.reservedQuantity` 保留，因為 ATP 計算與 optimistic locking 需要 bounded row-local counter；它不是 allocation identity 或 history。Invariant 固定為：

```text
StockQuant.reservedQuantity
= SUM(AllocationSlice.quantity WHERE stockQuantId = quant.id AND state = RESERVED)
```

Slice transition 與 counter mutation必須在同一 transaction：

- reserve：create `RESERVED` slice + increment counter；
- release：`RESERVED -> RELEASED` + decrement counter；
- consume：`RESERVED -> CONSUMED` + decrement reserved and decrement physical on-hand according to existing stock semantics。

Repository lock order 先 demand、再依 `StockWriteOrder` 排序 quants、最後 allocation/slices／moves，所有 reserve、release、consume paths 一致。Health indicator／SIT 提供 reconciliation query，但正常 command 不以全表 sum 取代 row-local optimistic lock。

**Alternative considered:** 每次 ATP 即時計算 active slices sum並移除 quant counter。這會把高頻 allocation query 變成大範圍 aggregation，並弱化現有 quant row optimistic-lock contention boundary，因此不採用。

### 6. Execution target 必須可追溯 commitment，但不能擁有 commitment

現行 outbound target mapping 為：

```text
Allocation 1 -------- N StockMove
AllocationSlice 1 --- 1 StockMoveLine   (本 change 的 single-leg outbound route)
```

每個 covered demand line materialize 一個 move；每個 slice materialize 一個 move line，move-line quantity 與 slice quantity 相同。`StockMove` 保存 endpoints、SKU、demand quantity與 execution state；`StockMoveLine` 保存 execution 使用的 quant／lot detail並引用 slice。

Inbound move lines 沒有 allocation slice，reference 保持 nullable。未來 route-chain change 若一個 slice產生多段 execution，必須明確指定只有 stock-consuming leg 引用 slice，其他 legs 引用 upstream movement/output；不能移除 1:1 constraint 後讓所有 legs都重複計算 reservation。

Movement target 可以被 cancel、split、re-group 或重建，但這些 operation 本身不改變 slice state。只有 allocation release／consume use case 可改變 commitment；target operation 必須呼叫該 coarse-grained boundary，而不是自行更新 quant。

**Alternative considered:** 讓 `StockMove` 成為 allocation aggregate child。Movement 也服務 inbound／internal logistics，且 routing lifecycle 與 commitment lifecycle 不同，合併會重新建立 overloaded aggregate，因此不採用。

### 7. Release、consume 與 cancellation 以 allocation identity 執行

Release 流程：

1. 以 idempotent operation id 取得 WMS cancellation result；若尚未 handoff 可略過 external call。
2. Local transaction 載入完整 allocation、slices 與 target movement set。
3. 要求所有 slices 仍為 `RESERVED`，且 movements 仍可逆。
4. 將 slices 轉為 `RELEASED`、遞減 quant counters、取消 reversible moves並清除不再有效的 execution lines。
5. 保留 slice history與 release timestamp，發布 release／source-specific cancellation outcome。

Consume 流程：

1. Command 使用 `allocationId` 並攜帶 WMS 完成的 movement id set。
2. Local transaction 比對 persisted complete movement set，拒絕 subset、superset、foreign allocation 或重複 quant consumption。
3. 依 lock order 將所有 `RESERVED` slices 轉為 `CONSUMED`，執行既有 physical stock consumption，完成 movements。
4. 重送得到 idempotent success；`RELEASED` allocation 不可 consume。

Source cancellation 仍先由 source adapter 找 demand：沒有 commitment 時只取消 demand；有 active commitment 時以 allocation identity走 external checkpoint與 release。已 consumed 的 commitment 不可藉由取消刪除，必須使用未來 return／compensation capability。

### 8. Allocation identity 與 source trace identity 分離

Identity 語意固定為：

```text
allocationDemandId = accepted source requirement
allocationId       = one committed demand-to-supply allocation
allocationSliceId  = one committed demand-line-to-quant quantity
movementId         = one inventory execution leg
shipmentId         = WMS execution grouping
```

`OrderAllocationCommittedIntegrationEvent.allocationId` 改用 `Allocation.id`，並明確攜帶 `allocationDemandId` 供 trace。WMS 以 allocation id 冪等建立 Shipment；outbound completion、shipment cancellation與 Inventory query 都以 allocation id 尋找 commitment及完整 movement set。

一個 demand 可有多個歷史 allocations；同一時間現行 policy只允許一個 active full-coverage allocation。這個限制由 demand lock、open coverage 與 ship-complete validator保證，不建立會阻止未來 partial allocations 的 `UNIQUE(allocation_demand_id)`。

**Alternative considered:** 繼續用 demand id 作 allocation id，額外增加 attempt sequence。所有 wire contracts 最終仍需要 compound identity，而且 consumer 容易忽略 sequence，因此直接建立 allocation id。

### 9. Query 與 observability 分開呈現四層

Allocation detail query 分區呈現：

- source/demand：origin、scope、scheduling、required quantities、cancellation；
- commitment：allocations、slice states、reserved／consumed／released quantities、open quantities；
- supply：quant／lot、FEFO dates與 current ATP；
- execution：moves、movement lines與 composition query取得的 WMS shipment/work。

Pending list 由 active demand與 open quantities產生；moves 為空不再具有任何 eligibility 語意。Health checks 驗證：

- quant reserved counter 等於 active slices sum；
- slices 不超過 demand line required quantity；
- current ship-complete allocation 精確覆蓋所有 open lines；
- 每個 reserved slice 有正確的 outbound execution trace；
- released／consumed slices 不計入 ATP reservation。

Metrics 使用 allocation id 作 attempt success／release／consume correlation，並保留 demand id、source type、scope 與 blocker dimensions；禁止使用 high-cardinality source id／quant id 作 metric tags。

### 10. Relational schema 與 constraints

新增 tables：

```text
allocations
  id PK
  allocation_demand_id FK
  policy_code
  committed_at
  version

allocation_slices
  id PK
  allocation_id FK
  allocation_demand_line_id FK
  stock_quant_id FK
  quantity CHECK quantity > 0
  state CHECK state IN ('RESERVED', 'RELEASED', 'CONSUMED')
  reserved_at NOT NULL
  released_at NULL
  consumed_at NULL
  UNIQUE (allocation_id, allocation_demand_line_id, stock_quant_id)
```

State/timestamp constraints：`RESERVED` 不得有 terminal timestamp；`RELEASED` 只需 `released_at`；`CONSUMED` 只需 `consumed_at`。Indexes至少涵蓋：

- allocations by demand and committed order；
- active slices by demand line；
- active slices by stock quant；
- slices by allocation；
- moves by allocation；
- move lines by allocation slice。

`stock_moves` 新增 non-null-for-outbound `allocation_id`；既有 `allocation_demand_id` 只在 migration window 保留作 backfill／validation，V29 cutover 後移除，execution query改經 allocation join取得 demand trace。`stock_move_lines` 對 outbound allocation新增 `allocation_slice_id`，inbound 保持 null。

Database constraints 保護 row-local shape；deferred target-trace constraints 保護 move、move line 與 slice 的 identity／quantity／state 對應。跨 rows 的 ship-complete coverage與counter sum仍由 transaction domain service、cutover validation、SIT及health reconciliation共同保護，不用 trigger 實作 allocation policy。

## Risks / Trade-offs

- **[Risk] 新增 commitment layer 反而增加 class／table 數量。** → 只新增一個 aggregate與一種 child entity；移除 demand allocated checkpoint、move-line reservation authority與跨 target inference，評估以 state combinations和 ownership而非檔案數判斷複雜度。
- **[Risk] Slice ledger 與 quant counter漂移。** → 所有 reserve／release／consume共用固定 lock order與 local transaction，加入 rollback、concurrency、reconciliation SIT及 health invariant。
- **[Risk] Coverage aggregation使 pending／detail query變慢。** → 以 demand-line／slice-state indexes與 bounded query projection處理；只有量測證明必要時才新增明確 projection table，不先增加同步 write model。
- **[Risk] allocation identity change造成 WMS duplicate Shipment或 completion找不到資料。** → 同 repository contracts原子切換、既有 committed rows採 deterministic backfill id，加入 event／Inbox／WMS idempotency migration tests。
- **[Risk] Movement operation繞過 allocation use case直接修改 reservations。** → architecture tests禁止 outbound movement package依賴 `StockQuant.reserve/release/consume` mutation；只有 commitment application boundary可寫 slice state與 quant counter。
- **[Risk] 未實作 partial卻提前抽象過度。** → 本 change只允許 `SHIP_COMPLETE`，不新增 policy registry UI/API或 partial state；multiple-allocation cardinality與 slice lifecycle是 identity／audit正確性所需，不建立未使用 workflow。
- **[Risk] release與 consume競爭。** → 兩條 path鎖相同 allocation、slices與 quants；只有 `RESERVED` 可 transition，loser取得 terminal state後依 idempotency rule回覆或拒絕。
- **[Trade-off] Released slices永久保留，資料量增加。** → 歷史是 reallocation與稽核所需；以 state／time indexes及日後明確 retention policy管理，不在 command path刪除。
- **[Trade-off] Inventory target仍與 commitment同 transaction。** → 保留一致性與既有 event contract；若未來 routing成為獨立服務，再以 target-materialization capability與 durable state machine拆分，不能預先引入 orphan window。

## Migration Plan

1. 依相依順序 archive／sync `refine-allocation-workflow-boundaries`、`generalize-allocation-demand`、`separate-allocation-source-process-target`，先使 main specs成為實際 baseline；若 archive verification失敗，停止本 change。
2. 新增 allocation／slice domain與 persistence，不切換 writer；建立 schema constraints、repository tests與 coverage query。
3. 以 staged migration新增 nullable allocation references。對既有 allocated／done demand建立一個 deterministic allocation，`Allocation.id = AllocationDemand.id`；pending／cancelled-without-reservation demand不建立 allocation。
4. 由既有 outbound moves／move lines backfill slices：assigned reservation映射為 `RESERVED`，done consumption映射為 `CONSUMED`；既有已取消且已刪除 move lines沒有可恢復的 release history，不製造猜測資料。
5. Backfill `stock_moves.allocation_id` 與 outbound `stock_move_lines.allocation_slice_id`，核對 demand-line coverage、quant reserved counter與movement set後驗證 constraints。
6. 將 planner result改為 proposal，導入 `SHIP_COMPLETE` policy gate與 transactional commitment writer；在單一 writer cutover中改由 allocation/slices reserve並 materialize targets。
7. 同步切換 committed event、WMS idempotency、completion、cancellation、queries與observability到 allocation id；event另外保留 allocation demand trace。
8. 將 demand persistence由 `PENDING/ALLOCATED/CANCELLED`轉成 `ACTIVE/CANCELLED`，candidate／FIFO eligibility改讀 open coverage；移除舊 allocated checkpoint write與movement-line-ledger assumptions。
9. 啟用 non-null／FK／unique constraints，移除 legacy allocation-demand execution references中不再需要的欄位與repository APIs。
10. 更新unit、repository、migration、SIT、Events／Temporal E2E、concurrency、rollback、release／consume、architecture與OpenSpec verification；確認所有E2E stack自動清理。

Rollback 分界：

- 新 writer啟用前，可回退application並保留未使用的新 tables／nullable columns。
- 新 writer啟用且尚未發布新 allocation-id event前，可在停止 consumers後回退並由 slices重建舊 target reference。
- 新 allocation-id event已發布後，不允許熱回退；必須 pause／drain Inventory與WMS consumers、reconcile allocations／shipments／Inbox，再以明確 reverse migration或forward fix恢復。不得同時啟用 demand-id與allocation-id兩種 writer。

## Open Questions

無。本 change固定只啟用 `SHIP_COMPLETE`、single-location與single-leg outbound materialization；partial policy、manual approval、multi-leg routing及deployed-environment rolling compatibility必須分別以後續 OpenSpec change定義，不能在implementation期間隱性擴張scope。
