# Move-centric allocation 與 precedence policy

狀態：Implemented<br>
對應變更：`openspec/changes/make-stock-move-allocation-core/`

## 一個 durable model，三種角色

```text
source document       inventory operation          execution / balance
Order / Transfer  ->  StockOperation -> StockMove -> StockMoveLine + StockQuant -> WMS Shipment
```

- **Source** 是 Inventory 外部的業務要求。Inventory 只保存穩定的 `sourceType + sourceId +
  allocationUnitKey`，不讀 source aggregate 來完成配貨。
- **StockOperation** 是一次 allocation unit 的 operation group，保存 policy、enqueue time、共同起訖與
  moves 的摘要狀態；沒有 SKU 或數量。
- **StockMove** 是需要移動的數量真相：source line、SKU、quantity、from/to location 與 lifecycle。
- **StockMoveLine** 是 move 目前或已完成時實際使用的 quant/quantity detail；它不是另一套 lifecycle。
- **StockQuant** 是 supply balance。`reservedQuantity` 是 assigned move lines 的受控 counter。
- **WMS Shipment** 是倉內工作分組；它以 `stockOperationId` 冪等建立，但擁有自己的 shipment/wave/task identity。

Planner、selection、retry 與 proposal 都是 process，不是第五份 durable truth：

```text
select confirmed operation -> check predecessor -> plan -> lock/revalidate -> assign existing moves
```

## Source document 與 source location 不同

| 名稱 | 問題 | 例子 |
| --- | --- | --- |
| source document identity | 誰要求這次 operation？ | `ORDER / orderId / PRIMARY` |
| source line identity | 哪一條業務明細？ | `orderLineId` 的 canonical string |
| from/source location | 貨從哪個位置移出？ | `WH-A/Stock` |
| to/target location | 貨要移到哪個位置？ | `Customers` |

`StockOperation.source` 可以在 inbound 為空；`fromLocationId` 與 `toLocationId` 永遠是 movement route，
不能拿 source document ID 代替。

## 貨主配貨順序與 shared-SKU precedence

confirmed operation `C` 被較早 operation `P` 阻擋，若且唯若：

```text
P.state = C.state = CONFIRMED
owner(P) = owner(C)
fromLocation(P) = fromLocation(C)
sequenceKey(P, ownerPolicy) < sequenceKey(C, ownerPolicy)
confirmedSKUs(P) intersects confirmedSKUs(C)
```

貨主預設使用 `DISPATCH_DATE_FIRST`（`dispatchBy, enqueuedAt, id`），保留 `FIFO`（`enqueuedAt, id`）策略。
候選選取與 predecessor 比較共用 Infrastructure 的固定 SQL tuple，沒有任意 SQL、權重公式或規則引擎。
Destination、release priority 與當下 ATP 不改變 precedence。缺貨的優先 operation 仍會阻擋共享 SKU 的後繼；
SKU 集合不相交的 operation 不互相阻擋。FEFO 與 `SHIP_COMPLETE` 不受此設定影響。

目前不提供設定 API。V32 migration 將既有貨主 seed 為 `DISPATCH_DATE_FIRST`。

設定歸 Inventory 的 `owner_allocation_policies`，不修改 Logistics Data 的 Owner aggregate。新貨主無資料列時
同樣使用 `DISPATCH_DATE_FIRST`。設定更新只影響之後讀取策略的配貨嘗試，不重新分配已 assigned/done 的 operation。
Coordinator 透過 OwnerAllocationPolicyStore 讀取一次策略，再傳給 Candidate Store 查詢；
predecessor 僅在規劃前檢查。已選中開始規劃的需求，不因晚到的優先需求而在提交時讓位。
Committer 不接收排序策略、不查 predecessor，也不依賴 Candidate Store 或 Policy Store。
這不保證並行提交的全域嚴格順序；Operation／Move 版本檢查、庫存鎖定及完整配貨驗證維持不變。
Candidate Store 不依賴其他 Store；OwnerAllocationPolicyStore 僅負責設定讀取與儲存。

## Pure planner 與 SHIP_COMPLETE

`MovementAssignmentPlanner` 只接受 immutable `StockOperationDemand` 與 `StockAllocationSupply`，其中每筆供給是
`StockQuantSupply`，輸出 `StockAllocationProposal`：

- proposal 沒有 durable identity，也不讀寫 repository；
- 相同輸入必須產生相同 `moveId -> stockQuantId -> quantity` drafts；
- 任一 SKU 不足時不輸出 partial reservation；
- 足量時每個 move 都被精確覆蓋；同 SKU 多行依 `lineSequence, moveId` 分配；
- policy 由 trusted source adapter 選擇，目前只有 `SHIP_COMPLETE`。

## Lifecycle 與 reservation truth

| Operation / Move state | Move lines | Quant effect |
| --- | --- | --- |
| `CONFIRMED` | 無 | 無 reservation |
| `ASSIGNED` | 精確覆蓋 demand quantity | `reserved += line.quantity` |
| `DONE` | 保留 execution evidence | `onHand -= quantity`, `reserved -= quantity` |
| `CANCELLED` | 無 | 無 reservation |

Release 是 `ASSIGNED -> CONFIRMED`：先扣 reserved counter、刪除 active move lines，再退回同一組 move
identity。Cancellation 若已 assigned，必須先取得 WMS reversible confirmation，再 release 並進入
`CANCELLED`。若 source terminal fact 的契約保證它只會在 WMS safe checkpoint 後發布（目前的
`OrderCancelledIntegrationEvent`），source adapter 會明確傳入既有 confirmation，Inventory 先持久化該決定再做
local cancellation，不重複呼叫 WMS；其他入口仍 fail closed。`DONE` 是 terminal。

Release、cancellation、completion 都在狀態交易內寫入 `StockOperationLifecycleIntegrationEvent`，其中保存
變更前的 operation、move、quant 與 quantity snapshot。這份 Outbox fact 提供 audit；不新增 reservation
history table，也不讓 observation 成為寫入依據。

## Transaction 與 lock order

所有會碰 reservation 的路徑固定：

```text
StockOperation -> ordered StockMoves -> globally ordered StockQuants
```

`StockAllocationCommitter.commit` 是唯一 assignment commit boundary。它鎖定後重驗 operation/move version、
scope、expiry、ATP 與 exact coverage，以 `MoveQuantAllocationSet` 套用 quant reservation 與 move lines，再由
`StockOperationAssignmentResultFactory` 組裝結果並寫 Outbox。任一步失敗全部 rollback。

## 重送與過期 proposal

Coordinator 保留 `Optional<StockOperationAssignmentResult>`；查不到可配需求時直接略過，不再回查狀態。
這包含不存在、已 assigned／done／cancelled 或不符合配貨條件的 Operation。缺貨與 predecessor 明細
在判斷處寫入 debug log，不新增整條流程共用的 outcome 型別。

Committer 維持 `StockOperationAssignmentResult` 成功契約：新配貨發布事件，已 assigned 的重送驗證並重建既有
結果，不重複預留或發布。Proposal 在提交前遇到完成、取消或其他失效情況時，拋出 stale proposal，讓外層
rollback 後重新執行 selection／planning；不得在已失敗交易內重試同一份 proposal。

JPA pessimistic lock 不代表 refresh：同一交易先載入 confirmed operation，另一交易配貨提交後再鎖定，
並行整合測試確認會拋出 optimistic-lock failure；新交易則可讀到 assigned 並重建既有結果。
`AssignmentRetryConflictTranslator` 攔截實際的 `allocation.application.service.StockOperationAssignmentCoordinator`，
將 stale proposal 轉成訊息層識別的 optimistic-lock failure。

## Backlog sweep

Reconciliation 每輪以 `(ownerId, fromLocationId, skuCode)` 的固定順序進行 keyset pagination，
每頁 200 個 queue。200 是讀取批次大小，不是整輪處理上限。頁內成功的 queue 輪流繼續配貨，
缺貨、沒有候選或發生錯誤的 queue 停止於本輪；處理完此頁後接著讀下一頁，直到查不到 queue。

Cursor 是 execute 方法內的區域變數，每輪從頭開始，不保留跨輪狀態；不再使用 synchronized、
全域嘗試次數或執行時間額度。配貨優先順序仍由貨主策略及 shared-SKU precedence 決定。
這是對持續變動資料的巡檢，並非快照：已掃過位置新增的需求可能留待事件或下一輪，
持續流入的需求也可能拉長執行時間。

Scheduler 預設啟動 30 秒後首次執行；使用 fixedDelay，完成後等待 15 分鐘再執行。
同一排程不重疊，但多個應用實例仍可能同時掃描；原有 commit lock 與重驗負責防止重複預留。
每張需求維持獨立 assignment transaction，不把整輪巡檢包成一個交易。

## Reconciliation

`StockOperationReconciliationStore` 唯讀檢查三件事：

1. operation 非空且與所有 moves 狀態同質；
2. assigned/done move-line quantity 精確覆蓋 demand，confirmed/cancelled 沒有 lines；
3. 每個 quant 的 reserved counter 等於 assigned move-line sum。

Health indicator 只告警並提供 bounded samples，`repairPolicy = manual-only`；不得在 health/read path
自動改帳。

## Review checklist

1. 新需求是否先成為 confirmed operation/moves，而不是另一個 demand table？
2. proposal 是否仍是純值，沒有 repository 或 durable ID？
3. reservation 是否只由 assigned move lines 表達？
4. transaction 是否遵守 operation -> moves -> quants lock order？
5. WMS 是否只以 operation snapshot 建立自己的工作，不讀 Inventory tables？
6. source document、source line、from location、to location 是否各自清楚？
7. legacy v1 contract 是否只存在相容 reader，沒有新 producer？
