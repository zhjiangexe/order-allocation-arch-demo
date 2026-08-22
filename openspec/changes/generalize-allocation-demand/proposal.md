## Why

目前配貨等待佇列是由訂單需求與 `StockMove` 狀態間接推導而來，導致 allocation 模型假設所有出庫需求都來自 order。實際業務也可能有內部調撥、補貨、生產領料或人工出庫；需要一個不依賴 order 的通用需求模型，將「誰需要庫存」與「倉庫如何執行搬運」分開。

## What Changes

- 新增通用的 `AllocationDemand`，代表任何符合第一版 shared-SKU strict FIFO、all-or-nothing 與整數 base-unit 限制、且需要競爭可用庫存的需求，並以 `sourceType + canonical sourceId + allocationUnitKey` 連結 order、transfer、replenishment、production 或 manual request；canonical source id 必須在同一 source type 內全域唯一，allocation unit key 必須由來源的穩定業務拆分識別產生，不能直接依賴可變的 location/picking 設定。第一階段每張 order 只有一個 unit，固定使用語意 key `PRIMARY`；order 接受後不支援 amendment/reopen，replacement 必須使用新的 `orderId`。
- 將 `AllocationDemand` 維持為薄的共通 allocation 模型；來源特有的建立、取消、完成與後續狀態轉換由各來源 adapter 處理，不把 order、transfer 或 production 的欄位與流程集中到 allocation demand。
- **BREAKING** 將等待配貨的主要判斷從 order-specific `Demand` / `orderId` 與 `StockMove` 推導，改為持久化的 allocation demand 狀態；`PENDING` demand 才是配貨佇列的主要來源。
- stock-consuming source 的 inbox claim、`AllocationDemand`、demand lines、outbound `StockMove` 與必要 picking 在 allocation context 的同一個本地 acceptance transaction 建立；來源 aggregate 的提交仍透過 event 最終一致，不引入跨 context transaction。相同 source identity 的 scope、scheduling snapshot、immutable execution intent 與 canonical demand lines 全部相同才是冪等重送；任一內容不同則拒絕為 source conflict，不默默覆寫需求。accepted content 使用有版本的 canonical snapshot 直接比較，只比較來源提供且建立後不可變的 execution intent，不把 move/picking state、reservation 或其他執行結果納入比較，也不依賴未版本化 opaque hash；每個 SKU 的加總數量使用 checked arithmetic，超過 persistence 上限時整筆拒絕。
- 明確定義 execution source location：第一階段 order adapter 在首次 acceptance 時將 facility outbound `PickingType.defaultFromLocationId` 保存為 location snapshot，但 order 的 allocation unit key 維持 `PRIMARY`；`AllocationDemand.locationId`、outbound `StockMove.fromLocationId` 與被配置的 `StockPool.locationId` 必須一致。destination 與 operation type 屬於 execution intent，不塞入 demand aggregate，但仍屬於不可變的 accepted content。
- 讓入庫與其他只增加 supply 的 movement 不建立 `AllocationDemand`；入庫完成後增加 `StockPool`，再觸發等待需求重新配貨。
- 以複合查詢找出可處理的候選需求：`AllocationDemand` 仍為 `PENDING`、存在尚未 `ASSIGNED` 的出庫 `StockMove`、相關 `StockPicking` 可執行，且 scope 有可用庫存。
- 將純配貨決策與執行結果套用分開：allocation service 產生 allocation plan，另一個 application component 將 plan 套用到 `StockPool`、`StockMove`、`StockMoveLine` 與 `StockPicking`；同 SKU 多條 demand lines 先以 aggregate quantity 判斷是否完整可配，再依 acceptance 時固定的 canonical line sequence 將 FEFO batch picks 穩定分回各 line。
- 以內部 `allocationDemandLineId` 串接 planner、allocation plan 與 `StockMove`；`sourceLineId` 只保留來源追蹤用途，不再作為 allocation core 的關聯鍵。
- 將配貨完成結果抽象為 allocation context 內部的通用 allocation completion result；第一階段由 order publication factory 發布單一 canonical `OrderAllocationCommittedIntegrationEvent` v1。Ordering 與 fulfillment driver 以不同 subscription fan-out 消費同一 event；新來源於後續 change 定義自己的 integration contract。
- 將取消限制在可逆執行邊界：`PENDING` 可直接取消；`ALLOCATED` 只有在 warehouse cancellation coordinator 已確認外部執行可停止，且本地執行資料仍可逆時才能釋放庫存。取消操作分別保存外部決策與本地完成狀態；若外部已確認取消但 process 在本地 transaction 前失敗，同一 operation retry 必須繼續完成 reservation release，而不是只回傳先前結果。無法取得確認時拒絕一般取消，不在本 change 實作物理作業補償。
- 每次取消以 `cancellationOperationId` 識別；order path 直接使用 `OrderCancelledIntegrationEvent.eventId`，讓同一事件重送得到同一結果。來源 adapter 必須保證同一 allocation unit 的 lifecycle command 有序；無法保證者須在自己的 change 加入 cancellation tombstone。
- 第一版配貨政策固定為 shared-SKU strict FIFO、FEFO stock selection、all-or-nothing/ship-complete 與 optimistic-locking concurrency semantics。較晚 demand 只會被相同 inventory scope 中、與它共享至少一個 SKU 的較早 pending demand 阻擋；完全不共享 SKU 的需求可由自己的 queue 繼續配貨。每個 allocation transaction 只提交一個 demand；前序 demand 成功後，後續 demand 由下一個 bounded iteration 重新判斷，不把整條 FIFO queue 鎖在同一個 transaction。需要部分配貨或優先權越過共享 SKU precedence 的來源不得接入本版本。
- 移除配貨流程對 `picking.orderId IS NOT NULL` 作為通用需求判斷的依賴；order linkage 僅作為 order source 的識別資料。

## Capabilities

### New Capabilities

- `allocation-demand`: 定義通用配貨需求、來源類型、需求生命週期、需求行與等待配貨候選查詢。

### Modified Capabilities

- `stock-allocation`: 配貨佇列改以 `AllocationDemand` 為主，支援非訂單出庫來源，並維持 FIFO/FEFO、整單配貨與配貨完成事件語意。
- `stock-movement`: 明確區分 supply movement、demand movement 與 allocation demand；`StockMove` 和 `StockPicking` 作為執行狀態與作業分組，不再單獨代表通用配貨需求。

## Impact

- 影響 `order-promising` 的 allocation domain/application model、等待需求查詢、scheduler、availability event consumer、`MovementAssigner` 及其測試。
- 新增 allocation demand persistence、來源類型與狀態轉換，並調整 `StockMove` / `StockPicking` 的關聯與候選查詢。
- 第一階段只調整 order allocation，並以 source-agnostic contract tests 證明非 order identity 可運作；internal transfer、replenishment、production 與 manual outbound 的實際 adapter 分別由後續 change 實作。入庫流程只負責確認 supply 與發布 availability fact。
- 新增內部 generic allocation completion result 與單一 order publication；`OrderAllocationCommittedIntegrationEvent` 的 `allocationId` 仍是 `pickingId`，不得改成新的 `allocationDemandId`。此 wire contract 合併取代原本分開的 order lifecycle 與 fulfillment handoff events。
- order cancellation adapter 必須沿用 fulfillment workflow 的既有順序：shipment 尚未建立時可直接取消；已登錄 WMS 時，只有 `cancelShipment` 回覆 `CANCELLED` 後才能取消 ordering 並釋放 allocation。WMS 回覆 `REJECTED` 時不得釋放 reservation。
- migration 必須同時涵蓋尚未建立 move 的 order demand、`CONFIRMED` waiting moves 與尚未完成的 `ASSIGNED` moves，保留原始 FIFO 時間且不得因 backfill 重發 completion；新增 movement references 採 nullable → backfill → validated constraints 的 staged migration。shadow comparison 期間只能有一條 allocation writer 寫 reservation/event，且 legacy view 因展開 facility 全部 internal locations 或未完整執行跨 SKU precedence 所產生的已知差異必須先正規化或明確分類，不能要求新舊 raw rows 逐列相等。最終 writer cutover 採短暫 quiescence：暫停並排空 legacy allocation consumers/scheduler、執行最後一次可重跑 backfill 與 anomaly validation、切換 single writer 後再恢復處理；恢復 new writer 前可直接切回 legacy，恢復且已有 new-path commit 後則必須再次 pause、drain、reconcile 才能回切，不允許熱切換雙 writer。
- shared-SKU strict FIFO 必須提供 pending age、blocking predecessor 與 blocked SKU 的 metrics/alerts；長期缺貨由人工取消或後續明確政策處理，本 change 不允許 scheduler 為了清隊列而暗中 bypass FIFO。
- 需要新增 migration、repository projections、application use cases 與 domain/application tests；不新增外部依賴。
