## Context

目前 allocation 以 order 的 `Demand` 作為決策輸入，並以 `StockMove.state = CONFIRMED` 間接表示仍在等待配貨。這在單一訂單出庫流程中可以工作，但無法自然表達 internal transfer、replenishment、production issue 或 manual outbound 等非訂單、且仍需要競爭庫存的需求。

`StockMove` 與 `StockPicking` 是倉庫執行模型：move 表示一條搬運，picking 表示作業分組；它們不應同時承擔所有來源的業務需求生命週期。入庫則是 supply 流程，只增加 `StockPool`，不應進入 allocation demand queue。

本設計新增 allocation context 自己擁有的通用需求模型，並保留 allocation decision 與 warehouse execution 的邊界。第一階段以相同的 FIFO、FEFO、ship-complete 與 optimistic-locking 語意替換 order-specific queue；來源 context 再逐一接入通用 demand。

## Goals / Non-Goals

**Goals:**

- 讓任何符合第一版 allocation policy 與 inventory-dimension 限制、且需要競爭庫存的 outbound requirement 都能建立 allocation demand，不要求存在 `orderId`。
- 由 allocation context 擁有等待配貨與已配貨狀態，避免依賴 ordering context 的落後狀態。
- 明確區分 `AllocationDemand`（需求）、`StockMove`（執行搬運）與 `StockPicking`（作業分組）。
- 保持每個 allocation demand 在單一 owner、facility、source location 範圍內，讓 FIFO、FEFO、鎖定與交易邊界可定義。
- 讓 allocation planner 只產生不可變的 allocation plan，再由 committer 在一個 transaction 中套用庫存與執行狀態。
- 讓配貨完成與取消結果攜帶通用 source reference 與 allocation unit identity，order 只是其中一個 consumer。
- 以 source allocation unit 的唯一性與 transaction boundary 支援重送、並行配貨及 idempotency。

**Non-Goals:**

- 不把入庫、盤點調整或單純增加 supply 的 movement 建立成 `AllocationDemand`。
- 不在本 change 實作跨 source location 的整單配貨；跨 location 的來源需求必須先拆成多個 allocation demand。
- 不在 allocation units 之間提供 source-level all-or-nothing completion；需要等待多個 location 全部配成的來源，由後續 source adapter/change 負責聚合。
- 不支援建立後原地修改 allocation demand 的 scope、scheduling snapshot、execution intent、line 或數量。第一版 order 在 acceptance 後不可 amendment 或 reopen；取消是終態，replacement 必須建立新的 `orderId`。未來需要 revision 的來源必須提供新的穩定 revision/split identity，不能重用已取消 allocation unit 的 identity。
- 不支援 partial allocation、shared-SKU FIFO bypass 或依 release priority 重排 allocation queue；第一版只接受 shared-SKU strict FIFO + all-or-nothing 的來源。
- 不支援 reservation 因 short pick、品質隔離、報廢或盤差而撤銷後的 `ALLOCATED → PENDING` 重新配貨。
- 不支援小數數量、多 UOM、指定 lot/serial 或 quality constraints；第一版只處理以整數 base unit 表示、同 owner/location/SKU 下可互換的庫存。
- 不讓 `AllocationDemand` 管理 picking、packing、carrier handover 或來源單據的 fulfillment lifecycle。
- 不在本 change 實作 WMS 已開始作業後的取消、退揀或其他物理補償流程。
- 不把所有來源系統的完整業務模型或外部識別碼治理搬進 allocation context；只保存 source type、canonical source id、allocation unit key 與 source line reference。
- 不在本 change 交付 transfer、replenishment、production 或 manual outbound 的正式 source adapter；這些來源分別以後續 change 接入。
- 不新增 tenant 維度；目前 inventory isolation boundary 是 `ownerId + facilityId + locationId`。若未來 tenant 與 owner 分離，必須同時修改 StockPool、movement、demand 與存取控制，不能只在 `AllocationDemand` 加欄位。
- 不改變 FEFO 批次選擇、FIFO 候選順序、ship-complete 或 stock pool optimistic-locking 規則。
- 不引入新的訊息 broker、workflow runtime 或外部 persistence dependency。

## Decisions

### 1. AllocationDemand 是 allocation context 的持久化需求根

新增 `AllocationDemand` 與 `AllocationDemandLine`。需求根至少包含：

- `allocationDemandId`
- `sourceType`（例如 `ORDER`、`TRANSFER`、`REPLENISHMENT`、`PRODUCTION`、`MANUAL`）
- `sourceId`
- `allocationUnitKey`
- `ownerId`
- `facilityId`
- `locationId`
- `requiredBy`
- `releasePriority`
- `enqueuedAt`
- allocation status

每條 demand line 具有 allocation context 自己產生的 `allocationDemandLineId`，並保存 `sourceLineId`、`skuCode`、需求數量與 allocation-owned `lineSequence`。`sourceLineId` 可以是 order line id，也可以是 transfer line 或 production material line id，但只用於來源追蹤；planner、allocation plan、`StockMove` 與 `StockMoveLine` 一律以 `allocationDemandLineId` 關聯。acceptance 先依 canonical `sourceLineId` 排序並產生唯一、不可變的 `lineSequence`，transport payload 的列舉順序不影響該 sequence。

source allocation-unit identity 固定為 `(sourceType, sourceId, allocationUnitKey)`，並具備唯一約束。`sourceId` 是 source adapter 提供的 canonical internal id，而且必須在同一 `sourceType` 內全域唯一；若來源原始 id 只在某個外部系統或 owner 內唯一，adapter 必須先把 namespace 納入 canonical id，不能讓 allocation core 猜測。外部系統的原始識別碼及其映射由 source context/adapter 管理，不進入 allocation core。`allocationUnitKey` 由 source adapter 根據來源自己的穩定業務拆分識別產生，不能使用每次重送都不同的隨機值，也不能直接使用可能因 picking/location 設定變更而漂移的衍生值。第一階段每張 order 只建立一個 allocation unit，固定使用 `PRIMARY`；未來若一個來源真的拆成多個 unit，必須由來源提供穩定的 shipment/split identifier。

相同 identity 與相同正規化 accepted content 的 command 或 integration event 是冪等重送，回傳既有 demand。accepted content 包含 `ownerId`、`facilityId`、`locationId`、`requiredBy`、`releasePriority`、source-provided execution intent，以及每條 line 的 `sourceLineId`、SKU 與 quantity。line 以 `sourceLineId` 做 canonical ordering，transport payload 的列舉順序不影響內容相等性；同一 allocation unit 內重複的 `sourceLineId` 必須拒絕。由 canonical ordering 衍生的 `lineSequence` 是 allocation-owned execution ordering，不要求來源額外提供可變的 payload position。

`AllocationDemand` header 保存 `acceptedContentVersion = 1`；正規化欄位仍放在各自擁有它們的 demand header、demand lines 與 move/picking execution records，不能為了冪等比較把 execution intent 複製進 demand aggregate。重送時由 acceptance comparator 載入同一個本地 consistency boundary 的既有 records 做 structural equality；move/picking 只比較首次 acceptance 保存的 immutable execution intent，例如 from/to location、picking type/direction 與 grouping policy，不比較 state、reservation、move lines、實際批次或 completion timestamp。identifier 使用 adapter 已 canonicalize 的精確值，時間先正規化為 persistence 支援的 UTC precision，nullable/default 採 V1 固定表示。不以未版本化的 opaque serialized hash 作為唯一正確性依據。若實作另存 hash 作快速比較，hash input 必須由版本化 canonical representation 產生，並在 hash 不同時以正規化欄位確認 conflict。`enqueuedAt`、`allocationDemandId`、`pickingId`、`moveId`、建立時間等 allocation-generated values 不屬於 accepted content。

每條 line quantity 與同一 SKU 的 aggregate quantity 都必須是 persistence 支援範圍內的正整數。aggregate 使用 checked arithmetic；溢位或超過上限時，整個 acceptance transaction 以 invalid demand 拒絕，不能讓 overflow 後的負數或截斷值進入 planner、movement 或 reservation。

相同 identity 若帶有任一不同 accepted content，必須回報 `SourceDemandConflict`，不得更新既有 demand 或建立第二筆競爭需求。這也包含 outbound picking 設定在首次 acceptance 後發生變更的情況：相同 `ORDER/orderId/PRIMARY` 會因 location 或 execution intent 不同而 conflict，而不是產生第二筆 demand。本 change 不保存完整來源 payload，只保存 allocation 所需的版本化 canonical snapshot。

`requiredBy` 與 `releasePriority` 是來源 adapter 正規化後的 warehouse scheduling snapshot，供 picking 與 fulfillment handoff 使用，不是來源 status，也不改寫 shared-SKU FIFO precedence。order adapter 分別由 `dispatchBy` 與既有 priority 映射；未來沒有明確期限的來源必須在自己的 change 定義預設政策。`enqueuedAt` 由 allocation context 在首次 acceptance 時設定，冪等重送不得刷新；每個 SKU queue 固定依 `(enqueuedAt, allocationDemandId)` 排序。

選擇持久化 demand，而不是每次從 `StockMove` 推導，因為 waiting queue 是業務需求的生命週期，不應依賴執行資料是否已被建立或某個 picking 是否帶有 `orderId`。

替代方案是繼續以 `StockMove` 作為通用需求來源。這會少一張表，但仍把「是否需要庫存」與「如何執行搬運」綁在一起，且無法可靠支援沒有 order 的來源，因此不採用。

`AllocationDemand` 只保存所有來源共通的 allocation 資料。order status、transfer status、production status、來源特有的取消規則與完成後動作不得放進 demand aggregate；這些責任由 `OrderAllocationAdapter`、`TransferAllocationAdapter` 或其他 source adapter 處理。source identity 是尋址與冪等資訊，不是把來源 context 的整個模型搬進 allocation。

### 2. Demand 只擁有 allocation lifecycle

allocation status 定義為：

```text
PENDING   → ALLOCATED
PENDING   → CANCELLED
ALLOCATED → CANCELLED
```

`PENDING` 表示仍需要競爭庫存；`ALLOCATED` 表示所需數量已成功保留；`CANCELLED` 表示 allocation 不再有效。

第一版沒有 `ALLOCATED → PENDING`。reservation 一旦提交，只能完成執行或在可逆取消流程中釋放；short pick、品質問題與盤差造成的 deallocation/reallocation 另開 change，不能假裝成一般取消。

不新增 `FULFILLED` 到 `AllocationDemand`。實際揀貨、包裝、完成與來源單據關閉，分別由 `StockMove`、`StockPicking` 或來源 context 管理，避免複製另一套 fulfillment state machine。

取消 `PENDING` demand 時取消尚未 assigned 的 moves。取消 `ALLOCATED` demand 只有在 warehouse cancellation coordinator 已確認外部執行尚未開始或已成功停止，且所有本地 movement/picking 仍可逆時才成立；allocation context 不自行查詢或推測 WMS 的物理狀態。取得確認後，同一個本地 transaction 先釋放 reservation，再取消執行資料並將 demand 轉為 `CANCELLED`。

若 coordinator 無法確認外部執行已停止，通用取消 use case 必須回傳不可取消結果，並保持 demand、reservation 與 execution state 不變。需要退揀、回庫或其他物理補償的情況屬於後續流程，不以 `ALLOCATED → CANCELLED` 掩蓋。`createShipment` 只有登錄而尚未開始作業時，仍可由 coordinator 先取得 WMS cancellation acknowledgement，再走可逆取消。

每次取消操作以 `(allocationDemandId, cancellationOperationId)` 識別。operation record 至少區分 `STARTED`、`EXTERNAL_REJECTED`、`EXTERNAL_CONFIRMED` 與 `COMPLETED`：外部 coordinator 以相同 operation id 提供冪等結果；拒絕是終態，已確認則表示本地 reservation release 尚可重試，只有本地 demand/reservation/execution cancellation transaction 提交後才成為 `COMPLETED`。process 若在外部確認後、本地 commit 前失敗，同一 operation retry 必須從 `EXTERNAL_CONFIRMED` 繼續本地 transaction，不能只回傳成功。order adapter 直接使用 `OrderCancelledIntegrationEvent.eventId` 作為 operation id，不修改既有 wire payload；既有 workflow 只有在 WMS cancellation 成功後才發布該事件，因此可將它視為 order path 的 external confirmation。來源 adapter 必須保證同一 allocation unit 的 create/cancel lifecycle command 有序；無法保證者必須在自己的 change 保存 cancellation tombstone，避免 cancel 先到後又被晚到的 create 復活。

order path 不新增 allocation → WMS 查詢。它沿用既有 fulfillment workflow：shipment 尚未建立時直接進行 order cancellation；shipment 已登錄時，workflow 先呼叫 `WmsActivities.cancelShipment`，只有結果為 `CANCELLED` 才執行 ordering cancellation，讓 order adapter 接續取消 demand/release reservation。結果為 `REJECTED` 時不產生 order cancellation，因此 allocation state 維持不變。未來其他來源必須在自己的 adapter 提供等價的外部執行取消確認。

### 3. Demand 與 location scope 的邊界固定

一筆 `AllocationDemand` 只允許一個 `ownerId + facilityId + locationId`。來源若需要從多個 location 取貨，必須在建立 demand 時拆成多筆 demand，或由更上層建立多個 allocation units；每個單元以不同、可重現的 `allocationUnitKey` 區分。本 change 不實作跨 location planner。

這個限制讓候選查詢、FEFO stock pool、FIFO queue 及 optimistic lock 都在同一個庫存 scope 內運作，也避免 `Demand` 帶有單一 location 卻實際包含多個 location moves 的錯誤。

order 只提供 facility，因此第一階段由 order adapter 讀取該 facility 的 outbound `PickingType`，在首次 acceptance 時使用 `defaultFromLocationId` 作為 demand source-location snapshot，但 allocation-unit identity 固定為 `ORDER/{orderId}/PRIMARY`。不得像舊 `demand_lines` view 一樣 join facility 底下所有 internal locations，否則一個 order 會被展開成多筆競爭需求。冪等重送若解析出不同 location，必須對既有 `PRIMARY` demand 回報 `SourceDemandConflict`。

所有 stock-consuming execution 必須滿足：

```text
AllocationDemand.locationId
= StockMove.fromLocationId
= 被 reserve 的 StockPool.locationId
```

`facilityId` 必須與該 internal location 的 facility 一致。destination location、picking type/direction 與是否建立 picking 由 source adapter 提供的 execution intent 決定並持久化到 move/picking，不放進 `AllocationDemand`。第一階段 order execution intent 使用 outbound `PickingType.defaultToLocationId` 作為 destination；未來 transfer/replenishment adapter 必須在各自 change 定義 destination 與 operation type。

### 4. StockMove 是 demand line 的執行投影

每個需要倉庫執行的 demand line 必須建立對應 outbound `StockMove`，並以 `allocationDemandId` / `allocationDemandLineId` 關聯。stock-consuming source 的 inbox claim、demand header、所有 lines、outbound moves 與必要 picking 在 allocation context 的同一個本地 acceptance transaction 寫入；不能留下缺少 move 的 `PENDING` demand，也不能留下缺少 demand 的 outbound demand move。來源 aggregate 先在自己的 context 提交，再以 event/command 觸發這個本地 transaction；兩者不是 distributed transaction。`StockMove` 狀態仍描述執行狀態，例如 `CONFIRMED`、`ASSIGNED`、`DONE`、`CANCELLED`。

入庫 move 沒有 allocation demand reference；它仍可有 inbound picking，但不會被 waiting allocation query 選出。候選查詢以 allocation demand 與 outbound move 的關聯判斷，不再用 `picking.orderId IS NOT NULL` 排除非訂單資料。

`StockPicking` 保留為可選的 warehouse work grouping。它的狀態是底下 moves 的 materialized summary，不是 allocation queue 的主真相。`StockPicking` 的 outbound/inbound 不變式必須由 picking type/direction 判斷，不再用 `orderId` 是否存在判斷；非 order outbound 因此可以有 deadline/priority。第一階段的 order adapter 仍在 acceptance transaction 建立 picking，並驗證 picking/move 的 source location 等於 demand location；沒有 picking 的正式 outbound/WMS handoff policy 不在本 change 啟用。

`AllocationDemand.status` 與 `StockMove.state` 不是兩份相同真相：前者回答需求是否仍競爭庫存，後者回答倉庫搬運執行到哪裡。兩者的跨模型一致性由 acceptance transaction、allocation commit transaction 與 cancellation transaction 維護；reconciliation 另外提供 anomaly query，發現缺少或不一致的 execution references 時記錄錯誤並隔離，不把異常資料當作正常 waiting candidate。

### 5. 候選查詢以 demand 為主，其他狀態作一致性篩選

reconciliation 與 availability wake-up 使用同一個用途導向的查詢，回傳 `WaitingAllocationCandidate`，內容至少包含 demand、demand lines、對應 move references 與 allocation scope。

FIFO queue 的競爭範圍固定為 `(ownerId, facilityId, locationId, skuCode)`，不是整個 location 的單一 global queue。一筆 demand 只有在它需要的每個 SKU queue 中，都不存在較早 `PENDING` demand 時，才具備 allocation precedence。換句話說，較早 demand 即使因另一個 SKU 不足而無法 ship-complete，也會阻擋後來與它共享任一 SKU 的 demand；完全不共享 SKU 的 demand 屬於不同 queue，可以繼續處理。

查詢條件為：

```text
AllocationDemand.status = PENDING
AND 存在尚未 ASSIGNED 的 outbound demand move
AND 若存在 picking，picking 不是 CANCELLED / DONE
AND scope 內存在今天仍可配且尚有 ATP 的 stock pool
```

`AllocationDemand.status` 是「是否需要配貨」的主要判斷；move/picking 條件確認它是否仍可執行；stock pool 條件只是候選預篩選，最後的完整數量與 ship-complete 判斷仍由 allocation planner 負責。

triggering SKU query 先回傳包含該 SKU 的 demands，再載入每筆 demand 的全部 lines；FIFO selector 必須同時檢查這些 lines 在其他 SKU queues 的較早 predecessor，不能只因某個 SKU 的 wake-up 就越過另一個 SKU queue 的隊首。stock-availability prefilter 不得把缺貨 predecessor 從 precedence 檢查排除。候選結果按 `(enqueuedAt, allocationDemandId)` 排序，並以 demand 數量限制 batch；picking 是否存在不改變 limit unit。每個 allocation transaction 最多提交一筆 demand；前序成功提交後，reconciliation loop 才在下一個 bounded iteration 重新查詢並處理後續 demand，不在同一 transaction 串接或鎖住整批候選。相同 demand 可能因多 SKU scope 被多次喚醒，但 optimistic locking 與 idempotent commit 必須讓只有一個 transaction 成功套用。

### 6. 將配貨決策與結果套用分離

保留純 domain `AllocationFifoSelector` 與 `AllocationDemandPlanner`。Selector 只判斷 candidate 是否在每個 required SKU queue 都位於 head-of-line；Planner 輸入 source-agnostic `AllocationDemandSnapshot` 與 `AllocatableBatches`，輸出不可變 `AllocationPlan`。輸入、plan line 與 batch pick 都以 `allocationDemandId` / `allocationDemandLineId` 識別，不保留 `orderId` / `orderLineId` 命名。同 SKU 多條 lines 先用 aggregate quantity 驗證 ship-complete，再依固定 `lineSequence` 將 FEFO batch quantity 分回各 demand line，確保 move lines 與 completion details 可重現。Selector 與 Planner 都不查 repository、不寫 `StockPool`、不更新 move，也不發布事件。

新增或重整 application-level `AllocationCommitter`，負責：

1. 依 `BatchPick` reserve stock pool。
2. 將 demand line 對應的 moves 轉為 `ASSIGNED`。
3. 建立 `StockMoveLine`。
4. 更新存在的 picking summary。
5. 將 `AllocationDemand` 從 `PENDING` 轉為 `ALLOCATED`。

上述變更在同一個 transaction 中提交；任何 optimistic-locking failure 都使整個 allocation attempt rollback，由 availability trigger 或 scheduler 重新嘗試。

每條 `AllocationDemandLine` 在 execution 層最多只能對應一筆 `StockMove`；資料庫以
`(allocation_demand_id, allocation_demand_line_id)` partial unique index 保證，避免把正常流程的
結構性 invariant 重複留給 Committer 做 defensive validation。

所有會修改 allocation execution 的本地 transaction 採一致的 lock hierarchy：先 claim inbox/cancellation operation，再鎖定 `AllocationDemand`，接著依穩定全域順序鎖定 `StockPool`，最後依 id 順序更新 `StockMove`、`StockPicking` 與 outbox/result records。只修改其中部分資料的路徑仍遵守相同相對順序。warehouse cancellation coordinator 的外部呼叫必須在本地 database transaction 之外完成；取得 acknowledgement 後才進入上述 lock hierarchy，避免在持有資料庫鎖時等待物理世界。

### 7. 使用通用完成 result，再由來源 publication 處理

allocation context 在 allocation commit transaction 產生通用 `AllocationCommitResult`，攜帶：

- `allocationDemandId`
- `sourceType`
- `sourceId`
- `allocationUnitKey`
- optional execution group / `pickingId`
- `allocationDemandLineId`、`sourceLineId`、`moveId`、source location、quantity 與 allocation details
- allocation time

第一階段的 `OrderAllocationCommittedPublicationFactory` 在同一個 transaction 將 `sourceType = ORDER` 的 result 轉成單一 `OrderAllocationCommittedIntegrationEvent` v1。Ordering 使用其中的 `orderId/committedAt` 推進狀態；event-driven WMS 或 Temporal bridge 使用完整 snapshot 繼續履約。`allocationId` 語意仍是 order picking id，WMS 依它冪等讀取 execution，因此 factory 必須填入 `pickingId`，不得改填新的 `allocationDemandId`。line 的 `orderLineId` 由 generic result 的 order `sourceLineId` 映射。未來 transfer、replenishment、production 各自在自己的 change 定義 integration event 或 consumer，避免 allocation core 內出現 source-specific event policy。

來源 adapter 也負責來源特有的建立、取消、版本檢查與完成後動作；`AllocationDemand` 只驗證共通的 source identity、scope、數量與 allocation transition。如此可讓多個來源共用 allocation engine，而不讓 allocation demand 變成包含所有業務流程的萬用 aggregate。本 change 只交付 order adapter；其他來源以 contract fixture 證明 core 不依賴 order，正式 adapter 各自另開 change。

### 8. 採分階段 migration，不一次刪除既有 order path

先建立通用 demand schema、domain model、candidate projection 與 commit path，再將 order allocation 改為建立/使用 `AllocationDemand`。確認 order flow、availability wake-up、scheduler、cancel/release 測試通過後，才移除 `demand_lines` 與 `picking.orderId` 導向的 waiting query。

migration/backfill 必須涵蓋三種 active 資料：

1. `demand_lines` 中尚未建立 move 的 order lines：以目前 outbound default location 建立 `PENDING` demand、lines 與 execution records。
2. 已有 `CONFIRMED` moves：以既有 move/picking source location 建立 `PENDING` demand 並連回既有 moves。
3. 已有 `ASSIGNED`、尚未 `DONE/CANCELLED` 的 moves：以既有 move/picking source location 建立 `ALLOCATED` demand 並連回既有 reservations/move lines。

backfill 的 `enqueuedAt` 優先沿用既有 move 最早 `createdAt`；尚無 move 時使用原始 order intake time，不得使用 migration 執行時間。backfill 只建立狀態映射，不發布 allocation completion，避免 workflow/WMS 重複建立 shipment。

shadow phase 可以同時執行 legacy/new candidate read 並比較結果，也可以維護新 projection，但任何時刻只能有一套 allocation committer 寫入 reservation、movement state 與 completion event。feature switch 以 source allocation unit 為切換單位；unique constraint 無法阻止 legacy path 與 new path 同時配貨，不能把 dual-write 當作安全機制。

legacy `demand_lines` view 會將 order 展開到 facility 底下所有 internal locations，而新 adapter 刻意只使用一個 execution source location，因此 shadow comparison 不能直接比較 raw rows。已有 move/picking 的 unit 必須以既有 execution source location 作為 canonical location；尚無 move 的 order 才使用目前 outbound picking type 的 default source location。比較器再將結果正規化成同一個 `ORDER/{orderId}/PRIMARY`，或將額外 legacy location rows 分類為已知且預期被修正的差異。legacy waiting path 對 multi-SKU demand 的檢查主要由 triggering SKU candidate batch 驅動，可能沒有完整檢查其他 SKU queue predecessor；新 path 因 shared-SKU precedence 拒絕該類跨 queue 超車時，也應分類為已知正確性修正。只有無法解釋的需求、數量、FIFO、FEFO 或 allocation outcome 差異才阻擋 cutover。

movement link 的 migration 採三階段：先新增可為 null 的 demand/demand-line references，讓舊程式與 supply-only movement 繼續運作；完成 active data backfill 與 anomaly scan 後，再驗證 pairwise/FK constraints。對 demand-consuming outbound movement，兩個 references 必須同時存在；對 inbound 與 supply-only movement，兩者必須同時不存在。歷史 movement 不因 demand 刪除而 cascade delete。

為避免線上 backfill 與 legacy writer 永遠互相追逐，最終 cutover 採短暫 quiescence，而不是新增 per-order routing registry：先暫停 legacy order-allocation consumer、availability consumer 與 reconciliation scheduler，等待進行中的 allocation transaction 排空；接著執行最後一次冪等 backfill、anomaly scan、identity/constraint validation 與 shadow gate；然後原子切換 single-writer feature flag，確認所有 consumer instance 使用相同 writer mode，最後以 new path 恢復 consumers/scheduler。order intake 可繼續寫 ordering context，尚未處理的事件留在 broker；恢復後由 new path 處理，若同一 order 已被 final backfill 建立 demand，acceptance idempotency 會回到既有 demand。

rollback boundary 分成兩段：new consumers 尚未恢復前，可在維持 quiescence 下直接切回 legacy mode；一旦 new path 已提交 reservation、movement state 或 completion event，就不得熱切回 legacy writer。此時若必須回切，先再次 pause/drain，停止 new writer，對帳 new-path commits 與 shared movement/outbox state，執行必要的 forward reconciliation/backfill，確認 legacy 不會重複處理後才恢復 legacy。若無法證明安全，維持停寫並以前向修正處理。

非訂單來源在後續各自 use case 完成 adapter 後才可建立正式 demand；沒有 source adapter 的流程不應被自動推導成 allocation demand。本 change 的非訂單案例只使用測試 fixture 驗證 source-agnostic persistence、candidate query 與 planner contract。

選擇 staged migration 而不是直接替換，是因為現有 order path 已包含 FIFO、FEFO、ship-complete、事件去重與 concurrency 測試；先保留一個可比較的行為基線，能降低大改造成 silent over-allocation 或需求遺失的風險。

## Risks / Trade-offs

- **[雙重需求]** 同一 allocation unit 同時存在舊 order demand 與新 allocation demand → 以 `(sourceType, sourceId, allocationUnitKey)` 唯一約束及 migration backfill/去重檢查避免重複建立。
- **[來源重送內容不同]** 相同 identity 的 retry 帶入不同 scope/line/quantity → 回報 `SourceDemandConflict`，不覆寫既有 demand。
- **[identity 隨設定漂移]** 相同 order 在 outbound location 設定變更後被視為另一筆需求 → order unit key 固定為 `PRIMARY`，location/picking 設定作為 immutable accepted content；不同設定重送時回報 conflict。
- **[取消後重建]** 已取消的 `ORDER/orderId/PRIMARY` 被修改內容後重新送入 → 第一版 order 不支援 amendment/reopen；相同內容重送回到既有 `CANCELLED` demand，不同內容回報 conflict，replacement 使用新的 `orderId`。
- **[數量溢位]** 多條相同 SKU 的正 quantity 加總後超過整數上限 → acceptance 使用 checked arithmetic 並整筆拒絕，不讓 overflow 值進入 allocation。
- **[同 SKU line 映射漂移]** 同一 SKU 的多條來源 lines 在不同執行中取得不同 batch details → acceptance 固定 canonical `lineSequence`，planner 先聚合判斷再按 sequence 分配 FEFO picks。
- **[跨 SKU 超車]** 較晚的 A+B demand 因 A wake-up 而消耗 B，越過較早的 B-only demand → demand 必須在每個所需 SKU queue 都通過 predecessor 檢查；完全不共享 SKU 的需求不受阻擋。
- **[FIFO 長期阻擋]** 較早需求因停產或長期缺貨 SKU 永遠 pending，持續阻擋共享 SKU 後續需求 → 提供 pending age、blocking predecessor、blocked SKU metrics/alerts 與人工取消 runbook；第一版不得自動 bypass。
- **[狀態不一致]** demand 已 `ALLOCATED` 但 move 尚未 `ASSIGNED`，或反向成立 → acceptance 與 allocation 分別在同一 transaction 更新；reconciliation 提供 anomaly query 並隔離異常，不以任一不一致狀態默默繼續配貨。
- **[位置錯配]** demand 從 location A 配貨，move 卻由 picking type 的 location B 出庫 → order adapter 先以 outbound picking type 決定 source location，建立與 commit 時都驗證 demand/move/stock pool location 相等。
- **[跨 location 需求]** 一筆來源單據需要多個 location → 建立時以穩定的 `allocationUnitKey` 拆成多個 allocation demand；本 change 不嘗試在 planner 內跨 location 配貨。
- **[取消競爭]** demand 配貨與來源取消並行 → 以 demand version、stock pool version 與 source-specific cancellation handler 保證只有一方提交有效狀態，另一方採 idempotent no-op 或 retry；未取得外部執行取消確認時拒絕釋放 reservation。
- **[外部已取消但本地未提交]** coordinator 已確認停止後 process crash，reservation 仍留在本地 → cancellation operation 保存 `EXTERNAL_CONFIRMED`，重試繼續本地 cancellation transaction，直到 `COMPLETED`。
- **[取消早於建立]** lifecycle command 亂序使已取消來源被晚到的 create 復活 → 第一階段 order path 依 workflow/partition ordering；未來無法保證順序的 adapter 必須保存 cancellation tombstone。
- **[雙 allocator]** legacy/new path 同時看到同一需求並各自 reserve → shadow phase 只允許雙讀比較，透過 feature switch 保證單一 committer；新表唯一鍵不能取代 single-writer gate。
- **[shadow 假失敗]** legacy view 展開所有 internal locations，且舊 multi-SKU path 可能未完整檢查其他 SKU predecessor → 已有 execution 時沿用其 location、尚無 move 時使用 outbound default，並將額外 location rows 與新 shared-SKU precedence 拒絕分類為已知正確性修正；不以 raw-row equality 作為 cutover gate。
- **[backfill 無法收斂]** legacy writer 在 final scan 期間繼續建立或配貨 → cutover 前暫停並排空 legacy allocation consumers/scheduler，再做最後 backfill、validation 與原子 writer switch。
- **[切換後不安全回滾]** new path 已提交新語意結果後直接恢復 legacy writer → 回切前再次 pause/drain 並對帳 shared movement/outbox；無法證明不會重複處理時採前向修正。
- **[重複 handoff]** backfill 或 generic event rollout 重新發出 order fulfillment event → backfill 禁止發布 completion，order adapter 保留既有 v1 event contract 與每個 demand transition 單次 outbox publication。
- **[非訂單來源缺少完成 consumer]** 通用 completion fact 發出後沒有來源 adapter → source adapter 必須與 demand creator 一起交付；沒有 adapter 的 source 不得啟用 allocation demand。
- **[查詢量增加]** candidate query 需要 join demand、move、picking、stock pool → 使用 scope/index 導向的 projection 與 batch limit；先保留複合查詢，只有有實際 query plan/metrics 證據時才物化額外欄位。

## Migration Plan

1. 建立 allocation demand/demand line 表、完整 source allocation-unit identity、allocation status、version 與唯一約束，並定義 order source location 來自 outbound picking type。
2. 建立 allocation context 的 repository、候選 projection 與 domain model；先加入 read/consistency tests。
3. 將現有 order intake 接成 `ORDER` demand creator，並 backfill 尚無 move、`CONFIRMED` 與 active `ASSIGNED` 三種資料，不發布 completion。
4. 讓初次配貨與 waiting reconciliation 共用新的 FIFO selector、planner 與 committer；先以正規化的 shadow read 比較 legacy/new candidates，已有 execution 時沿用其 source location、尚無 move 時使用 outbound default，並將其餘 legacy 多 location 展開列與跨 SKU precedence 修正標記為已知差異。
5. 將 cancellation/release 接到 allocation demand，驗證 `PENDING`、已確認外部停止的可逆 `ALLOCATED` 與未確認時拒絕取消三種情況。
6. 將 inbound receipt 保持為 supply-only 流程，確認 availability fact 仍能喚醒 demand candidate。
7. 以非訂單 contract fixture 驗證 generic demand 不依賴 `orderId`；正式 transfer、replenishment、production、manual source adapter 分別留給後續 change。
8. 暫停並排空 legacy allocation consumers/scheduler，完成最後 backfill、anomaly/constraint validation 與 shadow gate後原子切換 single writer，再以 new path 恢復處理；確認沒有重複配貨後，移除 order-specific waiting query 與只依賴 `orderId` 的候選排除條件。

Rollback 以 application feature switch 或保留舊 adapter 為界：在 new consumers 恢復前可於 quiescence 中直接切回；new path 已提交後，回切必須再次 pause/drain、對帳並 reconcile，不能熱切換。新 demand creator 或 candidate query 尚未通過整合測試前，不刪除既有 order allocation path；資料庫 migration 採新增欄位/表與可重跑 backfill，避免直接破壞原有 movement audit data。

## Deferred Decisions

- 正式非 order adapter 的 `allocationUnitKey` 產生規則由各來源 change 定義，但必須穩定、可重現並遵守本設計的唯一鍵契約。
- order amendment/reopen 與 source revision identity 由後續 change 定義；第一版取消後只能以新的 `orderId` 建立 replacement order。
- 沒有 picking 的 outbound/WMS handoff policy 留給需要該模式的來源 change；第一階段 order path 仍建立 picking。
- WMS 已開始作業後的退揀、回庫與其他物理補償另開 change，不擴充 `AllocationDemand` 的 allocation-only state machine。
- partial allocation、FIFO bypass 與不同 allocation policy 留給實際需要它的來源 change；不先加入萬用 policy bag。
- short pick、品質隔離、報廢或盤差後的 deallocation/reallocation 另開 change，屆時再決定是否加入 `ALLOCATED → PENDING` 或獨立 replacement demand。
- decimal quantity、UOM conversion、指定 lot/serial 與 quality constraints 另開 inventory-model change，不先把來源特有條件塞進 demand line。
- 跨 location allocation units 的 source-level 完成聚合由各來源 adapter 負責；本 change 的每個 allocation unit 獨立配貨與完成。
- tenant 維度只有在平台明確區分 tenant 與 inventory owner 時，才跨 StockPool、movement、demand 與 ACL 一起設計。
