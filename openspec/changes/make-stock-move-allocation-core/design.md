## Context

目前 Inventory 同時存在兩個 requirement model：`AllocationDemand/AllocationDemandLine` 保存等待中的
source snapshot，而 `StockMove` 保存已配置後的 movement。Outbound 因此沒有
`CONFIRMED -> ASSIGNED` 的完整 movement lifecycle；commit 反而從 demand lines 建立新的
`ASSIGNED StockMove`。尚未提交的 commitment-layer work 又加入 `Allocation/AllocationSlice`，使
`AllocationSlice` 與 `StockMoveLine` 同時保存 `line/move × stock quant × quantity`。

既有 `StockPicking` 已經具備正確的 grouping shape：沒有 SKU／quantity、state 是 moves 的摘要、可保存
operation type 與 scheduling，而且和 WMS `Shipment` 分屬不同 bounded context。既有 `StockMove` 也已具備
`CONFIRMED`、`ASSIGNED`、`DONE`、`CANCELLED` 與 created/assigned timestamps。這次變更應恢復並完成這套
move-centric model，而不是新增第三個 `ReservationGroup`。

約束如下：

- 保留 strict shared-SKU FIFO、FEFO、`SHIP_COMPLETE`、owner/location isolation 與全域 quant lock order。
- Stock and movement writes、Outbox publication 必須維持單一 transaction。
- Inventory 不得載入或修改 Order／Transfer aggregate；source adapter 只傳 normalized command。
- WMS 繼續擁有 Shipment、Wave、PickTask；Inventory `StockPicking` 只是 movement operation group。
- 歷史 Flyway migrations 不得改 checksum；worktree-only、尚未發布的 draft migrations 可在發布前取代。
- Java 修改後必須執行 Palantir/Spotless formatting 與 checks。

## Goals / Non-Goals

**Goals:**

- 讓 stock-consuming intent 從接受到完成都由同一個 `StockMove` identity 表達。
- 用 `StockPicking` 表達需要共同套用 allocation policy 的 move set，而不複製 line facts。
- 讓目前 reservation detail 只有一份 canonical persistence：`StockMoveLine`。
- 把 planner 保持為 pure deterministic function，把 revalidation、locking 與 state change 集中在單一 assign use case。
- 移除 `AllocationDemand`、`AllocationDemandLine`、`Allocation`、`AllocationSlice` 及其衍生 identity 與 reconciliation complexity。
- 讓 source、movement intent、supply selection 與 WMS execution 的 boundary 可直接從 schema 與 API 看出。

**Non-Goals:**

- 本次不實作 partial availability、split shipment、backorder picking、multi-leg route、push/pull rule、MTO 或 UoM。
- 本次不讓 Inventory 直接建立或修改 WMS records。
- 本次不保留每次未成功 proposal；proposal、blocker 與 retry attempt 仍是 ephemeral process data。
- 本次不在 active stock tables 保存 released reservation history；需要的稽核由 business event、Outbox 與 observation 記錄。
- 本次不把 planner、repository、FIFO policy 或 WMS behavior 塞進 `StockMove` aggregate。

## Decisions

### 1. `StockPicking` 是 policy group，`StockMove` 是核心 identity

Stock-consuming source acceptance 會在同一 transaction 建立一個 `StockPicking(CONFIRMED)` 與每個 canonical
source line 對應的一個 `StockMove(CONFIRMED)`：

```text
Order / Transfer / Manual source
        │ application adapter
        ▼
StockPicking(CONFIRMED)
        ├── StockMove(CONFIRMED)
        ├── StockMove(CONFIRMED)
        └── StockMove(CONFIRMED)
```

Picking 保存 operation-level facts：

- `pickingTypeId`、direction、owner、from/to locations；
- canonical `sourceType + sourceId + allocationUnitKey`；
- `policyCode`、`requiredBy/dispatchBy`、`releasePriority`、`enqueuedAt`；
- moves 的 summary state 與 optimistic-lock version。

Move 保存 line-level facts：

- picking identity、stable `sourceLineId`、SKU、demand quantity、from/to locations；
- movement state、created/assigned timestamps 與 optimistic-lock version。

`(sourceType, sourceId, allocationUnitKey)` 在 picking 上唯一；`(pickingId, sourceLineId)` 在 move 上唯一。
同 identity retry 會比較 normalized immutable picking/move content；相同內容回傳既有 group，不同內容拒絕為 conflict。

不保留 generic core 的 `orderId/orderLineId`。Order adapter 把它們 canonicalize 為 source identity/string line identity；
source-specific result publisher 若需要 UUID，必須在 adapter boundary 驗證並轉換。

**Alternative considered — persisted `ReservationGroup`:** rejected because it would duplicate the existing picking identity,
policy and scheduling fields while every movement still needs a picking/operation group.

**Alternative considered — no group, only moves:** rejected because `SHIP_COMPLETE` atomicity, one enqueue position and
source-unit idempotency are group-level invariants. Repeating those fields on every move would create drift.

### 2. `StockMoveLine` is the only current reservation/execution detail

`StockMoveLine(moveId, stockQuantId, quantity)` means:

- parent move `ASSIGNED`: this quant quantity is currently reserved;
- parent move `DONE`: this quant quantity was physically consumed/moved;
- parent move `CONFIRMED` or `CANCELLED`: no move lines may exist for stock-consuming moves.

There is no persisted `Allocation` or `AllocationSlice`. `StockQuant.reservedQuantity` remains a transactionally maintained
counter and must equal the sum of move-line quantities belonging to `ASSIGNED` stock-consuming moves for that quant.

Release performs one transaction:

1. lock picking, moves and referenced quants in the global order;
2. decrement quant reserved counters;
3. delete the active move lines;
4. change still-valid moves and picking from `ASSIGNED` back to `CONFIRMED`.

Source cancellation of an assigned picking first asks the WMS cancellation coordinator, keyed by picking and cancellation-operation
identity, to confirm that warehouse work has not started or has stopped. Only that durable confirmation permits the local transaction
to use the same unreserve operation and transition moves/picking to `CANCELLED`; rejection or uncertainty changes no Inventory fact.
When a trusted source-terminal fact such as the orchestrated `OrderCancelled` contract is guaranteed to exist only after the WMS-safe
checkpoint, the source adapter passes that checkpoint explicitly. Inventory durably records it under the event/cancellation-operation
identity and does not make a second WMS call. Direct and untrusted source requests retain the fail-closed coordinator path.
Completion decrements both on-hand and reserved quantities, retains move lines as execution evidence, and transitions moves/picking
to `DONE`.

Release, cancellation and completion append their lifecycle audit fact to Outbox in the same transaction when they change state.
Metrics and traces supplement those facts but do not replace durable audit.

**Alternative considered — `AllocationSlice` as an immutable commitment ledger:** rejected because it duplicates every current
move line and forces coverage, lifecycle and reconciliation to join two representations. Released-attempt history does not affect
current ATP or physical movement and belongs in events/observability.

### 3. Planning is a pure move-to-quant assignment

Rename the process vocabulary around its actual role:

```text
PendingPickingSelection
        ↓
MovementAssignmentPlanner
        ↓
AssignmentProposal
        ↓
AssignPickingUsecase
```

`AssignmentProposal` is immutable and contains:

```text
pickingId
pickingVersion
expected move ids + versions
policyCode
reservation drafts: (moveId, stockQuantId, quantity)
missing quantities by SKU
```

The planner receives an immutable open-move snapshot and FEFO-sorted allocatable batches. It does not read repositories, mutate
domain objects or generate durable identities. Under `SHIP_COMPLETE`, an insufficient proposal contains every SKU shortfall and no
reservation drafts; a ready proposal exactly covers every confirmed move.

Canonical source-line ordering remains deterministic, but the ordering key is materialized on moves (`lineSequence`) so planner
behavior does not depend on transport order or UUID ordering.

**Alternative considered — planning from source Order or `AllocationDemandLine`:** rejected because it either crosses the bounded
context or recreates a second requirement model.

### 4. Assignment mutates existing moves atomically

`AssignPickingUsecase` is the single transaction boundary. It:

1. locks/reloads the candidate picking and its moves;
2. verifies picking/move versions, `CONFIRMED` state and exact shared-SKU predecessor relation;
3. locks proposal quants using `StockWriteOrder.BY_GLOBAL_ORDER`;
4. revalidates owner, location, SKU, expiry, ATP and exact per-move coverage;
5. increments quant reserved counters and creates move lines;
6. calls `move.assign(occurredAt)` for every move and `picking.assign()` once all moves are assigned;
7. publishes the source-specific committed fact through Outbox.

Any failure rolls back every counter, line, state and Outbox write. An idempotent retry of an already assigned picking reconstructs
the committed result from picking/moves/move lines instead of reserving again. Initial acceptance and availability wake-up both call
the same selection/planning/assignment responsibility; neither application use case calls the other.

Every mutating flow follows one lock order:

```text
StockPicking -> StockMove ids ascending -> StockQuant global write order
```

This includes assign, release, cancel and complete so hot-SKU contention cannot introduce a reverse lock cycle.

### 5. FIFO precedence is evaluated between confirmed pickings

A picking `C` is blocked by earlier picking `P` iff:

```text
C and P are stock-consuming and CONFIRMED
owner(P) = owner(C)
fromLocation(P) = fromLocation(C)
(P.enqueuedAt, P.id) < (C.enqueuedAt, C.id)
confirmedSKUs(P) intersects confirmedSKUs(C)
```

Required-by, release priority, destination and current ATP do not reorder strict FIFO. An unavailable predecessor remains a blocker.
Indexes support:

- confirmed picking scope/order: `(owner_id, from_location_id, enqueued_at, id)`;
- confirmed move intersection: `(picking_id, state, sku_code)` and `(owner_id, from_location_id, sku_code, state, picking_id)`;
- move group loading: `(picking_id, line_sequence, id)`.

One transaction assigns at most one picking. Event-triggered wake and periodic reconciliation use the same bounded queue selection.

### 6. Picking state is a checked summary, not a second lifecycle

For current `SHIP_COMPLETE` stock-consuming groups, persisted states must be homogeneous:

| Picking state | Move state | Move lines |
| --- | --- | --- |
| `CONFIRMED` | all `CONFIRMED` | none |
| `ASSIGNED` | all `ASSIGNED` | exact demand coverage |
| `DONE` | all `DONE` | retained execution detail |
| `CANCELLED` | all `CANCELLED` | none |

Application transactions update picking and moves together. Database CHECK/FK/unique constraints protect row-local facts; a health
reconciliation query detects cross-row state or counter drift. Future partial policies may extend the picking summary without changing
the fact that move state is canonical for each quantity.

### 7. Inventory picking and WMS shipment remain separate

Inventory publishes a snapshot containing `pickingId`, source trace, moves and batch details after assignment. WMS creates its own
`Shipment` idempotently from `pickingId`; it never reads or writes Inventory tables. Shipment/Wave/PickTask answer how warehouse work
is organized, while StockPicking/Move answer what inventory operation must happen.

Cross-context contracts replace `allocationDemandId` and allocation-slice identities with `pickingId`, `moveId` and batch-pick facts by
introducing a new move-centric contract version. Historical payloads are never rewritten. Consumers deploy tolerant readers for both
legacy and move-centric versions before producers switch to the new version. Legacy readers remain until source-topic retention,
Outbox re-snapshot exposure and DLT replay windows have all expired; removing them is a later change, not part of this cutover.
During that window, a V1 reader resolves the canonical `pickingId` from the event's retained `moveId` values at the composition
boundary. It must never reinterpret the legacy `allocationId` as a `pickingId`, and this translation must not restore an
AllocationDemand/Allocation persistence model.

### 8. Read models derive business answers from one fact per question

- waiting/backlog: confirmed picking + confirmed moves;
- requested quantity and route: moves;
- currently reserved batches: assigned move lines;
- ATP: quant on-hand minus reserved counter;
- completed physical movement: done moves + retained move lines;
- source trace: picking source identity + move source-line identity;
- warehouse execution: WMS shipment/task projections.

No read path derives pending status from missing target rows, and no read path computes current reservation from historical attempt
records.

## Risks / Trade-offs

- **[Released reservation rows are not retained]** → Publish a release/cancellation business fact with picking, move, quant and quantity
  snapshots in the same transaction; use logs/metrics only as supplementary diagnostics. Physical execution lines remain retained on DONE.
- **[Picking looks similar to WMS Shipment]** → Enforce package and repository boundaries, use `pickingId` only as an integration reference,
  and document that Inventory never owns waves/tasks/operator progress.
- **[Picking summary can drift from moves]** → Update them in one transaction, add persistence integration tests for every transition and
  run reconciliation for picking/move/line/counter invariants.
- **[Exact FIFO predecessor joins can be expensive]** → Use the confirmed partial indexes above, bounded queue discovery and query-plan
  integration tests; keep final correctness check inside the assign transaction.
- **[Removing order-specific FKs weakens direct relational integrity]** → Enforce source identity uniqueness and structural replay equality
  inside Inventory; source adapters validate the source read model before registration.
- **[Concurrent assign/cancel/release can race]** → Lock the same picking first, verify optimistic versions and follow the shared global
  lock order before touching quants.
- **[Assigned work may already be executing in WMS]** → Require durable WMS reversible-execution confirmation before local unreserve and
  cancellation; uncertainty or rejection leaves the picking assigned.
- **[Large worktree contains a superseded commitment implementation]** → Change tasks remove it in vertical slices with compilation and
  focused tests after each slice; do not mix unrelated user changes or use destructive git reset/checkout.

## Migration Plan

1. Restore V1–V20 migration files to their published content; no historical checksum is changed by this design.
2. Replace the worktree-only, untracked V21–V29 commitment drafts before publication. If any non-disposable database has already applied
   them, create forward repair migrations instead; local disposable databases may be recreated only with explicit approval.
3. Expand `stock_pickings` with generic source identity, policy, enqueue timestamp and required indexes; expand `stock_moves` with stable
   source-line identity/sequence where missing. Keep old demand columns/tables readable during backfill.
4. Deterministically backfill one picking per allocation demand, attach or create its moves, copy immutable scheduling/source facts, and
   preserve current CONFIRMED/ASSIGNED/DONE/CANCELLED meaning. Backfill current reservations into move lines only; do not create a second
   ledger.
5. Validate source-unit uniqueness, one move per source line, homogeneous ship-complete states, exact assigned move-line coverage and
   `quant.reservedQuantity = SUM(active assigned move lines)`.
6. Deploy consumers that accept both legacy and move-centric contract versions, then switch application writers, queries, Temporal and
   WMS adapters to the move-centric producer. During writer coexistence, only one writer mode may be enabled at startup.
7. Move cancellation-operation idempotency from allocation-demand key to picking key, then remove allocation-demand/line references,
   tables, repositories and APIs after all runtime readers are gone.
8. Run unit, architecture, migration, persistence, concurrency, integration and full e2e suites. Keep legacy readers after this change;
   remove them only in a later change after topic, Outbox and DLT replay windows expire.

Before the contract/drop step, rollback means redeploying the prior writer while old tables remain. After destructive table/column removal,
rollback requires restoring a database backup or applying an explicitly tested forward repair; schema downgrade is not automatic.

## Open Questions

None. The design intentionally fixes the grouping choice (`StockPicking`), current reservation truth (`StockMoveLine`) and lifecycle owner
(`StockMove`) before implementation begins.
