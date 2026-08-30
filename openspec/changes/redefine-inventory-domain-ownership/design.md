## Context

`inventory-context` 目前共有 149 個 production source files，其中 72 個位於 `allocation`。這不是單純的大小問題：Allocation 同時包含 pure planner、candidate/backlog selection、proposal commit、reservation release、Stock Operation completion、WMS cancellation process、Order intake 與 technical support。現有跨 capability 依賴也呈現同一個事實：Allocation Assignment 與 Lifecycle 都直接操作 Movement 與 Stock Quant。

另一方面，三個核心事實已相當清楚：

- `StockOperation`/`StockMove` 保存 source-neutral movement intent 與物化 lifecycle summary；
- `StockMoveLine` 保存目前有效的 move-to-quant commitment detail；
- `StockQuant` 保存 current on-hand/reserved position，而不是 transaction history。

`warehouse` 只有 Location 與 Operation Type，並沒有 Warehouse aggregate；WMS 的 Shipment、Wave、Warehouse Work 與 Pick Task 已由 `wms-context` 擁有。先前 `flatten-inventory-capability-packages` 明確限制為 package-only refactor，保留四個 capability 並禁止重新分配 Lifecycle/Cancellation ownership，因此該 change 的完成不代表四分法已經通過 domain analysis。

外部產品模型只用來驗證責任方向，不複製其模組或 schema：Oracle 將 reservation 定義為 supply-demand commitment，並把 pick 定義為 location-to-location movement directive；SAP EWM 允許 target、actual、difference 與 correction 分離。這支持本設計現在拆出 Reservation、但延後 Posting 的判斷。

## Goals / Non-Goals

**Goals:**

- 讓每個現有核心類別、介面與 transaction working model 有唯一、可解釋的 owner。
- 將 Inventory 表達成一個 bounded context 內的五個 domain modules：Movement、Allocation、Reservation、Position、Location。
- 將 Source、planning process、commitment target、external execution 與 current state 明確分開。
- 保留現有核心模型與行為，透過 package relocation、port extraction 與少量 semantic rename 建立邊界。
- 讓未來 Posting、Traceability、Inventory Control 與 richer Availability 可以加入，而不必再次拆解大型 Allocation package。
- 以 generic architecture fitness functions 保護依賴規則，不再過度指定檔名與方法形狀。

**Non-Goals:**

- 將五個 modules 拆成 microservices、Gradle modules、databases 或 bounded contexts。
- 新增 `StockReservation` aggregate/table、`StockPosting` ledger、Lot/Serial/LPN、cycle count、hold 或 adjustment。
- 修改 SHIP_COMPLETE、strict FIFO、FEFO、proposal revalidation、lock order、retry、Outbox、transaction 或 idempotency 行為。
- 修改 Flyway migration、資料表/欄位、REST、integration event、Temporal contract、WMS contract 或 persisted enum/string。
- 為相容舊 package 建立 deprecated forwarding types。
- 因為 Git 顯示 delete/add 就移除仍有實際行為的核心類別或 use case。

## Decisions

### 1. 五個名稱是 Domain Modules，不是五個 Bounded Contexts

目標概念模型如下：

```text
Movement Demand + Position Supply
                |
                v
        Allocation Proposal
                |
                v
           Reservation
                |
                v
        WMS actual execution
                |
                v
        [Posting: future]
                |
                v
            Position

Location supports Movement endpoints and Position identity.
```

五個 modules 的判準是目前已有獨立 ubiquitous language 與 rules：

| Module | Authoritative question | Current evidence |
| --- | --- | --- |
| Movement | 應移動什麼、從哪到哪、處於何種 lifecycle？ | `StockOperation`, `StockMove`, source/state/type |
| Allocation | 哪些 supply 應滿足哪些 demand？ | immutable demand/supply、pure planner、proposal、FIFO/FEFO |
| Reservation | 實際承諾了哪些 move-to-quant quantities？ | `StockMoveLine`, reserve/release, commit/revalidation |
| Position | 現在每個 stock identity 有多少 on-hand/reserved？ | `StockQuant` 與 quantity invariants |
| Location | 庫存可存在何處、Movement 的端點是什麼？ | `StockLocation`, usage, facility relation |

Alternative considered：保留原四個名稱，只增加 Reservation。否決，因 `warehouse` 仍會錯誤暗示 WMS ownership，Operation Type 與 Location 也沒有共同 aggregate 或 lifecycle；`balance` 仍把 current position 與 receipt workflow 混為同一概念名稱。

Alternative considered：現在直接建立八至九個 domains。否決，因 Posting、Traceability、Inventory Control 與 richer Availability 目前沒有獨立 authoritative fact 或 rule；空 package/interface 只會建立 speculative abstractions。

### 2. Movement owns intent, configuration, completion and cancellation

Movement 保留 `operation`、`registration` 與 `visibility`，並接收下列 ownership：

```text
inventory/movement/
  operation/{domain,application,application/port,infrastructure}
  operationtype/{domain,infrastructure}
  registration/{application}
  completion/{application}
  cancellation/{domain,application,application/port,entrypoint,infrastructure}
  visibility/{application,application/port,entrypoint,infrastructure}
```

- `StockOperationType` 與 `StockOperationDirection` 從 `warehouse/operationtype` 移至 Movement，因為它們定義 Movement 的方向與預設端點。
- `CompleteOutboundMovementsUsecase`、normalized completion command 與 lifecycle／fulfillment publication 歸 Movement
  completion/operation ownership；Event 與 Temporal entrypoint 共用此一 Usecase 完成 canonical Movement，尚未建立
  Posting。Usecase 只發布一個 `StockOperationCompleted` Application Event，再由單一 infrastructure adapter 在同一
  transaction 內原子產生 lifecycle audit 與 fulfillment notification 兩個既有 Integration Event contracts。
- `StockOperationCancellation`、repository、transactions、WMS cancellation port 與 event consumer 歸 Movement cancellation。`AllocationCancellationState` rename 為 `StockOperationCancellationState`；schema 的 state strings 不變。
- `StockOperationComposite` 與 `StockOperationLifecycleSnapshot` 是 transaction-scoped Movement working models，沒有 repository、persistence identity 或獨立 lifecycle，歸 Movement Application。

Alternative considered：把 cancellation 升成第六個 domain。否決，因其 identity、target 與終局狀態完全依附 Stock Operation；它是 durable process manager，而不是可獨立存在的 inventory fact。

### 3. Allocation narrows to selection and pure planning

Allocation 目標為：

```text
inventory/allocation/
  planning/{domain,application,application/port,infrastructure}
```

它擁有：

- `StockOperationDemand`, `StockMoveDemand`；
- `StockAllocationSupply`, `StockQuantSupply`；
- `StockAllocationPlanner`, `MovementAssignmentPlanner`；
- `StockAllocationProposal`, `ProposedMoveLine`, `SkuQuantities`；
- `AssignmentQueueKey`, candidate/predecessor/backlog projection 與其 repository ports/adapters；
- planner bean configuration。

Planner 與 proposal 保持 immutable/non-authoritative。Candidate 與 supply repositories 仍可使用高效率 JDBC projection，不載入 persistence aggregates，也不寫任何資料。

`MovementAssignmentPolicy` 暫時留在 Movement intent，因 source 在建立 Stock Operation 時就指定它；Allocation 解讀該 policy。此 change 不新增跨 module shared-kernel package。

Alternative considered：讓 Allocation 繼續擁有 commit，並只把 release 稱為 Reservation。否決，因 proposal commit 正是產生 authoritative reservation 的邊界；讓 planner 與 commit 共享 owner 會再次模糊 non-authoritative/authoritative truth。

### 4. Reservation owns assignment commit and release without a second ledger

Reservation 目標為：

```text
inventory/reservation/
  intake/{application,application/port,entrypoint,infrastructure}
  assignment/{application,application/port,entrypoint,infrastructure}
  release/{application}
```

- Order intake 先呼叫 Movement registration，再呼叫 Reservation assignment；Order aggregate 不進入 Inventory。
- `StockOperationAssignmentCoordinator` 讀 Allocation candidate/supply、呼叫 planner，ready 時交給 `StockAllocationCommitter`。
- `StockAllocationCommitter`、`MoveQuantAllocationSet`、assignment result/factory/publisher、wake-up consumer/scheduler/reconciler 歸 Reservation Assignment。
- `ReleaseStockOperationUsecase` 歸 Reservation Release。
- `StockMoveLine` 保留既有 class name、schema、identity 與 create/delete lifecycle，但 domain ownership 從 Movement 移至 Reservation。

`StockMoveRepository` 只保留 Stock Move persistence。新增 Reservation-owned `StockMoveLineRepository` Application port，承接 `saveLines`、`findLinesOf` 與 `deleteLinesOf` 的既有行為；對應 JPA/entity/mapper/adapters 可重新分包，但 SQL、constraint 與 transaction 不變。這個 port 不宣稱 Stock Move Line 是 aggregate root，只表達 persistence ownership。

Alternative considered：新增 `StockReservation` aggregate/table，並與 Move Line dual-write。否決，因 current exact-execution invariant 下 `StockMoveLine` 已是唯一 durable active commitment detail；第二套 truth 會增加 reconciliation 與 rollback 複雜度。

Alternative considered：只 move `StockMoveLine` class，保留 line methods 在 `StockMoveRepository`。否決，因 package 看似分開但 persistence authority 仍由 Movement port 擁有，無法形成可檢查的 ownership boundary。

### 5. Position replaces Balance as the current-state module

Position 目標為：

```text
inventory/position/
  onhand/{domain,infrastructure}
  receipt/{application,application/port,entrypoint,infrastructure}
  visibility/{application,application/port,entrypoint,infrastructure}
```

- `StockQuant`, `StockQuantRepository`, `StockWriteOrder` 與 persistence 從 `balance/onhand` move 至 `position/onhand`。
- receipt workflow 與 stock query read side 從 `balance` move 至 `position`，但仍是 Application/Visibility slices，不升為 domains。
- `StockQuant` 繼續同時保存 on-hand 與 reserved counters，以同一 optimistic lock 維護 `0 <= reserved <= onHand`。
- Reservation 與 completion/receipt workflows 可在同一 local transaction 使用 Position repository；module boundary 不代表 distributed transaction。
- 本 change 不 rename `availableToPromise()`，避免把 ownership refactor混入 internal API semantics；design 文件註明它目前只計算 physical available-to-reserve，未來 richer Availability 另開 change。

Alternative considered：將 reserved counter 移出 Quant，以 `SUM(StockMoveLine)` 即時計算。否決，因這會改變 concurrency/performance model 與 lock authority，不是 ownership-only refactor。

### 6. Location replaces the Warehouse umbrella

Location 目標為：

```text
inventory/location/{domain,application,entrypoint,infrastructure}
```

`StockLocation`, `LocationUsageType`, repository、listing use case、REST response/controller 與 persistence 直接歸 Location。原 `inventory/warehouse` package 在 Location 與 Operation Type 全部搬離後消失。真正 WMS models 不移入 Inventory。

Alternative considered：將 Location 與 Operation Type 保留在 `topology` umbrella。否決，因 current Location 尚無 hierarchy/routing graph，新增 umbrella 只會重建本 change 正在移除的分類層；未來出現 network/topology rules 時可另行提案。

### 7. Workflow placement follows the authoritative result

不為 Application workflow 建立額外 domains：

| Workflow/read side | Owner |
| --- | --- |
| Order source intake | Reservation Intake |
| Movement registration | Movement Registration |
| Assignment wake/reconcile | Reservation Assignment |
| Release | Reservation Release |
| Completion | Movement Completion（未來由 Posting 接收 actual） |
| WMS-backed cancellation | Movement Cancellation |
| Receipt | Position Receipt |
| Operation visibility/reconciliation | Movement Visibility |
| Quant visibility | Position Visibility |
| Location listing | Location Application/Entrypoint |

`AllocationEventSubscriptions` 不再作為 business ownership bucket：三個 stable subscriber identities 分別移至 Reservation Intake、Movement Cancellation 與 Reservation Assignment entrypoints。跨這些 consumers 的 optimistic-lock retry observer 移至 context-level `inventory.infrastructure.observability`，這是 technical exception，不得被 business code 當成 shared package。

Temporal adapter 保持 context-level entrypoint，因它刻意協調多個 domain modules。

### 8. Preserve runtime and persisted representations

本 change 是 source ownership refactor，不做資料 migration：

- 不修改或新增 Flyway；
- 不 rename `stock_operations`, `stock_moves`, `stock_move_lines`, `stock_pools`, `stock_locations`, `stock_operation_types`, `stock_operation_cancellations`, `stock_receipt_requests`；
- 不改 Entity table/column mapping、SQL predicates/order、database constraints 或 persisted state strings；
- 不改 transaction annotations、`REQUIRES_NEW` cancellation checkpoints、lock acquisition order、Outbox publisher timing；
- 不改 REST/events/Temporal/WMS contracts。

Git rename detection 不是驗收條件。驗收以每個既有 core type/use case 在新 owner 下仍存在、編譯、測試與 runtime behavior 相同為準。允許移除 empty directories、被抽出方法後不再有責任的 obsolete port，以及已拆分內容的 technical holder；禁止刪除仍承擔現有行為的核心能力。

### 9. Architecture tests protect stable ownership policies

`InventoryBoundaryArchitectureTest` 改為檢查：

- Inventory bounded-context isolation 與 foreign-table ownership；
- 五個 domain modules 的 package placement 與 dependency direction；
- Domain 不依賴 Application/Infrastructure/Entrypoint/framework；
- Application 不依賴 transport/contracts；
- adapter implements owning Application/Domain port；
- retired `inventory.balance`、`inventory.warehouse`、`allocation.lifecycle`、`allocation.support` ownership 不再存在；
- future domain placeholder 不存在。

不得列舉全部 production files、比對 method body/comment/local variable/class modifier，或把這份 living design 的文字變成 source assertion。Behavioral invariants 繼續由 unit/SIT/E2E 驗證。

## Risks / Trade-offs

- **[Risk] 大量 package/import diff 掩蓋行為改動。** → 先建立 source inventory，按 module 分批 move；每批 compile/test，最後檢查 non-package/non-import diff。
- **[Risk] 拆 Stock Move Line port 意外改變 flush、lock 或 delete ordering。** → 先以 persistence integration tests 固定既有 call behavior；adapter 僅搬移相同 repository/SQL implementation，不改 transaction owner。
- **[Risk] Reservation 與 Position 看似分開，開發者誤以為需要 distributed consistency。** → 文件與 tests 明定它們是同 bounded context/local transaction 內的 modules，commit/release 必須維持原子性。
- **[Risk] Movement Completion 在沒有 Posting 時仍直接更新 Position。** → 明確標記 current exact-execution limitation；不以假 ledger 美化現況，未來 actual divergence 另開 change。
- **[Risk] `StockMoveLine` 名稱讓人誤認只屬 Movement。** → 保留 ubiquitous/persisted vocabulary，透過 package、port ownership 與文件說明它目前是 Reservation detail；不為架構純度做劇烈 rename。
- **[Risk] Context-level observability 變成新的 shared/support 雜物桶。** → architecture rule 只允許 technical framework adapters，禁止 Domain/Application business types。
- **[Risk] Dirty worktree 中的既有完成 changes 被覆蓋。** → 不 reset/revert；以明確檔案 inventory、package-sensitive search 與 scoped patches 保留所有現有內容。

## Migration Plan

1. 建立完整 production/test/fixture/SIT/configuration/AspectJ/document package inventory，記錄核心類別與 port method baseline。
2. 先補 architecture/persistence tests，固定五個 target ownership、禁止 implementation-shape assertions，並釘住 MoveLine persistence/transaction behavior。
3. 移動 Location 與 Movement Operation Type，消除 `warehouse` ownership；compile/test。
4. 移動 Position on-hand/receipt/visibility，消除 `balance` ownership；compile/test。
5. 縮小 Allocation 至 planning/selection projection；建立 Reservation slices並搬移 intake、assignment、release。
6. 抽出 Reservation-owned Stock Move Line persistence port/adapter，保持相同 entity/schema/SQL/transaction behavior。
7. 將 completion/lifecycle publication 與 cancellation process 移至 Movement；做必要 semantic rename，保持 persisted strings。
8. 拆分 subscription identities，移動 retry observer 與 Temporal/direct callers，移除只剩空殼的 retired packages。
9. 執行 Palantir formatting、`spotlessCheck`、focused unit/architecture tests、Inventory SIT、full backend tests、full E2E、`git diff --check` 與 strict OpenSpec validation。

Rollback 是 source-level reversal；因沒有 schema、contract 或 persisted-value migration，不需要資料 rollback。

## Open Questions

None. Posting、Traceability、Inventory Control 與 richer Availability 的觸發條件已記錄，但不屬於本 change。
