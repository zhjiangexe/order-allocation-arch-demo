## Context

目前 allocation 以 order 的 `Demand` 作為決策輸入，並以 `StockMove.state = CONFIRMED` 間接表示仍在等待配貨。這在單一訂單出庫流程中可以工作，但無法自然表達 internal transfer、replenishment、production issue 或 manual outbound 等非訂單、且仍需要競爭庫存的需求。

`StockMove` 與 `StockPicking` 是倉庫執行模型：move 表示一條搬運，picking 表示作業分組；它們不應同時承擔所有來源的業務需求生命週期。入庫則是 supply 流程，只增加 `StockPool`，不應進入 allocation demand queue。

本設計新增 allocation context 自己擁有的通用需求模型，並保留 allocation decision 與 warehouse execution 的邊界。第一階段以相同的 FIFO、FEFO、ship-complete 與 optimistic-locking 語意替換 order-specific queue；來源 context 再逐一接入通用 demand。

## Goals / Non-Goals

**Goals:**

- 讓任何需要競爭庫存的 outbound requirement 都能建立 allocation demand，不要求存在 `orderId`。
- 由 allocation context 擁有等待配貨與已配貨狀態，避免依賴 ordering context 的落後狀態。
- 明確區分 `AllocationDemand`（需求）、`StockMove`（執行搬運）與 `StockPicking`（作業分組）。
- 保持每個 allocation demand 在單一 owner、facility、source location 範圍內，讓 FIFO、FEFO、鎖定與交易邊界可定義。
- 讓 allocation planner 只產生不可變的 allocation plan，再由 committer 在一個 transaction 中套用庫存與執行狀態。
- 讓配貨完成與取消結果攜帶通用 source reference，order 只是其中一個 consumer。
- 以 source reference 的唯一性與 transaction boundary 支援重送、並行配貨及 idempotency。

**Non-Goals:**

- 不把入庫、盤點調整或單純增加 supply 的 movement 建立成 `AllocationDemand`。
- 不在本 change 實作跨 source location 的整單配貨；跨 location 的來源需求必須先拆成多個 allocation demand。
- 不讓 `AllocationDemand` 管理 picking、packing、carrier handover 或來源單據的 fulfillment lifecycle。
- 不把所有來源系統的完整業務模型搬進 allocation context；只保存 source type、source id 與 source line reference。
- 不改變 FEFO 批次選擇、FIFO 候選順序、ship-complete 或 stock pool optimistic-locking 規則。
- 不引入新的訊息 broker、workflow runtime 或外部 persistence dependency。

## Decisions

### 1. AllocationDemand 是 allocation context 的持久化需求根

新增 `AllocationDemand` 與 `AllocationDemandLine`。需求根至少包含：

- `allocationDemandId`
- `sourceType`（例如 `ORDER`、`TRANSFER`、`REPLENISHMENT`、`PRODUCTION`、`MANUAL`）
- `sourceId`
- `ownerId`
- `facilityId`
- `locationId`
- `requiredBy`
- `releasePriority`
- allocation status

每條 demand line 保存 `sourceLineId`、`skuCode` 與需求數量。`sourceLineId` 可以是 order line id，也可以是 transfer line 或 production material line id；allocation 不依賴特定來源 context 的 aggregate。

`(sourceType, sourceId)` 必須具備唯一約束。來源 command 或 integration event 重送時，必須回到同一筆 allocation demand，而不是建立第二筆競爭需求。

選擇持久化 demand，而不是每次從 `StockMove` 推導，因為 waiting queue 是業務需求的生命週期，不應依賴執行資料是否已被建立或某個 picking 是否帶有 `orderId`。

替代方案是繼續以 `StockMove` 作為通用需求來源。這會少一張表，但仍把「是否需要庫存」與「如何執行搬運」綁在一起，且無法可靠支援沒有 order 的來源，因此不採用。

### 2. Demand 只擁有 allocation lifecycle

allocation status 定義為：

```text
PENDING   → ALLOCATED
PENDING   → CANCELLED
ALLOCATED → CANCELLED
```

`PENDING` 表示仍需要競爭庫存；`ALLOCATED` 表示所需數量已成功保留；`CANCELLED` 表示 allocation 不再有效。

不新增 `FULFILLED` 到 `AllocationDemand`。實際揀貨、包裝、完成與來源單據關閉，分別由 `StockMove`、`StockPicking` 或來源 context 管理，避免複製另一套 fulfillment state machine。

取消 `PENDING` demand 時取消尚未 assigned 的 moves；取消 `ALLOCATED` demand 時，先依 allocation reservation/release 規則釋放已保留庫存，再取消執行資料。兩條路徑都必須以 source reference 與 demand idempotency 保證可重送。

### 3. Demand 與 location scope 的邊界固定

一筆 `AllocationDemand` 只允許一個 `ownerId + facilityId + locationId`。來源若需要從多個 location 取貨，必須在建立 demand 時拆成多筆 demand，或由更上層建立多個 allocation units；本 change 不實作跨 location planner。

這個限制讓候選查詢、FEFO stock pool、FIFO queue 及 optimistic lock 都在同一個庫存 scope 內運作，也避免 `Demand` 帶有單一 location 卻實際包含多個 location moves 的錯誤。

### 4. StockMove 是 demand line 的執行投影

每個需要倉庫執行的 demand line 可建立對應 outbound `StockMove`，並以 `allocationDemandId` / `allocationDemandLineId` 關聯。`StockMove` 狀態仍描述執行狀態，例如 `CONFIRMED`、`ASSIGNED`、`DONE`、`CANCELLED`。

入庫 move 沒有 allocation demand reference；它仍可有 inbound picking，但不會被 waiting allocation query 選出。候選查詢以 allocation demand 與 outbound move 的關聯判斷，不再用 `picking.orderId IS NOT NULL` 排除非訂單資料。

`StockPicking` 保留為可選的 warehouse work grouping。它的狀態是底下 moves 的 materialized summary，不是 allocation queue 的主真相。若某個來源不需要 picking，allocation 仍可先完成；是否建立 picking 由 execution policy 決定。

### 5. 候選查詢以 demand 為主，其他狀態作一致性篩選

reconciliation 與 availability wake-up 使用同一個用途導向的查詢，回傳 `WaitingAllocationCandidate`，內容至少包含 demand、demand lines、對應 move references 與 allocation scope。

查詢條件為：

```text
AllocationDemand.status = PENDING
AND 存在尚未 ASSIGNED 的 outbound demand move
AND 若存在 picking，picking 不是 CANCELLED / DONE
AND scope 內存在今天仍可配且尚有 ATP 的 stock pool
```

`AllocationDemand.status` 是「是否需要配貨」的主要判斷；move/picking 條件確認它是否仍可執行；stock pool 條件只是候選預篩選，最後的完整數量與 ship-complete 判斷仍由 allocation planner 負責。

候選結果仍按 demand 到達順序排序，並以 demand/picking 數量限制 batch。相同 demand 可能因多 SKU scope 被多次喚醒，但 optimistic locking 與 idempotent commit 必須讓只有一個 transaction 成功套用。

### 6. 將配貨決策與結果套用分離

保留純 domain `AllocationService`，輸入 `List<Demand>` 與 `AllocatableBatches`，輸出 `AllocationPlan` / `OrderAllocation` 類型的不可變決策結果。它不查 repository、不寫 `StockPool`、不更新 move，也不發布事件。

新增或重整 application-level `AllocationCommitter`，負責：

1. 依 `BatchPick` reserve stock pool。
2. 將 demand line 對應的 moves 轉為 `ASSIGNED`。
3. 建立 `StockMoveLine`。
4. 更新存在的 picking summary。
5. 將 `AllocationDemand` 從 `PENDING` 轉為 `ALLOCATED`。

上述變更在同一個 transaction 中提交；任何 optimistic-locking failure 都使整個 allocation attempt rollback，由 availability trigger 或 scheduler 重新嘗試。

### 7. 使用通用完成 fact，再由來源 context 處理

allocation context 發布通用 `AllocationCompleted` fact，攜帶：

- `allocationDemandId`
- `sourceType`
- `sourceId`
- `sourceLineId` 與 allocation details
- allocation time

order consumer 只處理 `sourceType = ORDER` 的 fact，transfer、replenishment、production 各自處理自己的 source。這避免 allocation core 內出現 order-specific event policy。

### 8. 採分階段 migration，不一次刪除既有 order path

先建立通用 demand schema、domain model、candidate projection 與 commit path，再將 order allocation 改為建立/使用 `AllocationDemand`。確認 order flow、availability wake-up、scheduler、cancel/release 測試通過後，才移除 `demand_lines` 與 `picking.orderId` 導向的 waiting query。

非訂單來源在各自 use case 完成 adapter 後才可建立 demand；沒有 source adapter 的流程不應被自動推導成 allocation demand。

選擇 staged migration 而不是直接替換，是因為現有 order path 已包含 FIFO、FEFO、ship-complete、事件去重與 concurrency 測試；先保留一個可比較的行為基線，能降低大改造成 silent over-allocation 或需求遺失的風險。

## Risks / Trade-offs

- **[雙重需求]** 同一來源同時存在舊 order demand 與新 allocation demand → 以 `(sourceType, sourceId)` 唯一約束及 migration backfill/去重檢查避免重複建立。
- **[狀態不一致]** demand 已 `ALLOCATED` 但 move 尚未 `ASSIGNED`，或反向成立 → 在同一 transaction 更新；reconciliation 提供 anomaly query，不以任一不一致狀態默默繼續配貨。
- **[跨 location 需求]** 一筆來源單據需要多個 location → 建立時拆成多個 allocation demand；本 change 不嘗試在 planner 內跨 location 配貨。
- **[取消競爭]** demand 配貨與來源取消並行 → 以 demand version、stock pool version 與 source-specific cancellation handler 保證只有一方提交有效狀態，另一方採 idempotent no-op 或 retry。
- **[非訂單來源缺少完成 consumer]** 通用 completion fact 發出後沒有來源 adapter → source adapter 必須與 demand creator 一起交付；沒有 adapter 的 source 不得啟用 allocation demand。
- **[查詢量增加]** candidate query 需要 join demand、move、picking、stock pool → 使用 scope/index 導向的 projection 與 batch limit；先保留複合查詢，只有有實際 query plan/metrics 證據時才物化額外欄位。

## Migration Plan

1. 建立 allocation demand/demand line 表、source type、allocation status、version 與唯一約束。
2. 建立 allocation context 的 repository、候選 projection 與 domain model；先加入 read/consistency tests。
3. 將現有 order intake 接成 `ORDER` demand creator，並為既有 pending/confirmed order movement 建立 migration mapping。
4. 讓初次配貨與 waiting reconciliation 共用新的 planner/committer，但保留既有 event adapter 直到通用 fact 驗證完成。
5. 將 cancellation/release 接到 allocation demand，驗證 `PENDING` 與 `ALLOCATED` 兩種取消競爭。
6. 將 inbound receipt 保持為 supply-only 流程，確認 availability fact 仍能喚醒 demand candidate。
7. 逐一加入 transfer、replenishment、production、manual source adapter；每個來源都必須有建立、取消、完成 fact consumer 與 idempotency test。
8. 確認雙寫/雙讀期間沒有重複配貨後，移除 order-specific waiting query 與只依賴 `orderId` 的候選排除條件。

Rollback 以 application feature switch 或保留舊 adapter 為界：在新 demand creator 或 candidate query 尚未通過整合測試前，不刪除既有 order allocation path；資料庫 migration 採新增欄位/表與可重跑 backfill，避免直接破壞原有 movement audit data。

## Open Questions

- 外部來源的 `sourceId` 是否全域唯一；若不是，source identity 必須由 `(sourceType, sourceSystem, sourceId)` 組成。
- `AllocationDemand` 是否需要保存原始 source payload snapshot，或只保存 source references 與 demand lines。
- 沒有 picking 的 outbound demand 在 WMS handoff 前，是否由 allocation context 建立 picking，或交給 WMS adapter 建立。
