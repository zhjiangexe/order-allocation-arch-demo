## Why

`inventory-context` 目前以 `allocation`、`balance`、`movement`、`warehouse` 四個同級 capability 組織，但這四個名稱混合了決策、物化狀態、移動意圖與倉儲設定等不同分類軸。尤其 Allocation 同時承擔純規劃、reservation commit/release、完成與 WMS cancellation coordination，而 Warehouse 並不存在對應 aggregate；若繼續沿用此 ownership，後續 DOM、3PL 與 WMS actual execution 成長時，既有 package 會再次成為流程與持久化責任的混合容器。

## What Changes

- 將 Inventory 明確定義為一個 bounded context，內含五個目前已有程式行為支持的 domain modules：`movement`、`allocation`、`reservation`、`position` 與 `location`；這些 modules 不成為獨立服務或 bounded contexts。
- 縮小 Allocation，只保留 demand/supply projection、selection/backlog policy、pure planning 與 non-authoritative proposal。
- 從 Allocation/Movement 的既有程式中辨識 Reservation ownership：commit/release、move-to-quant commitment detail、reservation transaction working models 與 committed-result publication 歸 Reservation；第一階段不新增另一套 reservation table 或 aggregate truth。
- 將 Balance 精準化為 Position：`StockQuant` 繼續是 on-hand/reserved current position 與 quantity invariant 的唯一 command model，既有 `stock_pools` schema 不改名。
- 移除 `warehouse` umbrella ownership：Location 成為獨立 module；`StockOperationType` 與 `StockOperationDirection` 歸 Movement configuration。真正的 warehouse execution 仍由 `wms-context` 擁有。
- 將 cross-domain workflows 依其 authoritative result 歸位：order intake/assignment 歸 Reservation application、registration/completion/cancellation 歸 Movement application、receipt 歸 Position application；Visibility 保持 read side，technical Support 拆回 owning entrypoint 或 observability infrastructure。
- 允許抽出或重新分配 Application ports，使 repository/publisher/coordinator 名稱與完整屬性名稱反映唯一 owner；禁止僅為 package 外觀新增空 interface、空 aggregate、通用 repository framework 或 compatibility wrapper。
- 保留所有核心業務模型與 use cases；類別可以 package move 或在名稱錯置時 rename。Git 可能將 move 顯示為 delete/add，但不得刪除仍承擔既有行為的核心能力。
- 保留 `posting`、`traceability`、`inventory-control` 與 richer `availability` 為 future extension seams；本 change 不建立 placeholder entity、table、repository 或空 package。
- 保持 allocation algorithm、SHIP_COMPLETE/FIFO/FEFO semantics、lock order、transaction boundary、idempotency、Outbox atomicity、database schema、REST API、integration events 與 E2E observable behavior 不變。

## Capabilities

### New Capabilities

- `inventory-domain-ownership`: Defines the five evidence-backed Inventory domain modules, their owned model and port responsibilities, cross-domain workflow placement, dependency direction, and future-extension guardrails.

### Modified Capabilities

None. This change reorganizes internal ownership without changing business requirements or externally observable behavior.

## Impact

- Affects package declarations and imports across `backend/inventory-context`, direct Inventory callers in monolith composition, WMS, Temporal activities, tests, fixtures, architecture tests, and living architecture documentation.
- May split mixed persistence/application ports and rename clearly misplaced internal types such as Allocation-named Stock Operation cancellation state; adapter SQL and persisted enum/string values remain unchanged.
- Does not add or drop database tables, edit existing Flyway migrations, change public schemas, create a Posting ledger, or alter runtime business behavior.
- Requires focused Inventory tests, architecture tests, persistence/SIT coverage, the complete backend suite, full E2E, formatting checks, and strict OpenSpec validation before completion.
