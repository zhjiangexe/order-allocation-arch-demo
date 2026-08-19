# Allocation demand：從入口看懂每個階段

## 先記住一句話

`AllocateOrderUsecase` 做的是：**接受一筆訂單需求，然後立刻嘗試配置一次**。

它不是背景 queue loop，也不保證這次一定會配到庫存。若 FIFO 尚未輪到，或任何一個 SKU
不足，需求會保留為 `PENDING`，等 availability event 或 reconciliation scheduler 再嘗試。

## 三個入口，從不同階段開始

| 入口 | 誰觸發 | 從哪個階段開始 | transaction 邊界 | 一次呼叫何時結束 |
| --- | --- | --- | --- | --- |
| `AllocateOrderUsecase` | `OrderPlaced` | 階段 1：接受需求 | acceptance、一次 allocation attempt、成功事件 Outbox 都在同一個 transaction | 接受後嘗試一次；成功或暫時無法配置都結束 |
| `TransactionalAllocationAttempt` | availability consumer，或 reconciliation 委派 | 階段 2：嘗試配置 | 自己擁有一個 transaction | 最多 commit 一筆 demand；沒有可配需求也結束 |
| `ReconcileWaitingDemandUsecase` | scheduler | 先找等待 scopes，再從階段 2 開始 | 本身不包住整輪；每個 scope 呼叫一次獨立的 `TransactionalAllocationAttempt` | 在全域工作預算內 round-robin；失敗 scope 留到下次 tick |

另外，`CancelAllocationDemandUsecase` 是獨立的取消／反向流程，不屬於下面這條配置主線。

## Use case 與階段總覽

![Allocation use case 與階段總覽](images/allocation-order-activity.png)

[查看 Mermaid 原始檔](diagrams/allocation-order-activity.mmd)

讀圖時先看紫色入口，再沿著箭頭看它從哪個階段開始：

- `AllocateOrderUsecase` 會先走「接受需求」，再進入「嘗試配置」。
- availability event 直接呼叫 `TransactionalAllocationAttempt`，不會再建立 demand。
- scheduler 先由 `ReconcileWaitingDemandUsecase` 找 scope keys；每個 key 只包含
  `ownerId / facilityId / locationId / skuCode`，不是 demand List。
- reconciliation 每次從 queue 首端取一個 scope，呼叫獨立 transaction 的
  `TransactionalAllocationAttempt`；實際 demand 會在 transaction 裡依 scope 重新查詢。
- 一次 attempt 最多 commit 一筆 demand。成功時 scope 放回 queue 尾端，公平地再嘗試
  successor；沒有成功時不放回，避免同一個 scheduler tick 無效重試。

## `ReconcileWaitingDemandUsecase` queue activity

![ReconcileWaitingDemandUsecase queue activity](images/reconcile-waiting-demand-activity.png)

[查看 Mermaid 原始檔](diagrams/reconcile-waiting-demand-activity.mmd)

這張圖的 `true` 不是「整個 scope 已配完」，而是「剛剛成功 commit 一筆
demand」。因此 scope 需要放回隊尾，之後重新查詢是否還有 successor；`false`
才表示這個 scope 在本次 tick 不應繼續重試。

## `AllocateOrderUsecase` 每個階段做什麼

| 階段 | 主要元件 | 輸入與讀取 | 寫入／狀態變化 | 本階段可能結果 |
| --- | --- | --- | --- | --- |
| 0. 進入 | `AllocateOrderUsecase` | 收到 `orderId` | 不直接寫 domain data | 開始同一個本地 transaction |
| 1. 轉接來源 | `OrderAllocationDemandAdapter` | 讀 order 提供給 allocation 的 read model | 不寫資料 | 找不到或已取消：整個 use case 直接結束；否則產生共用 command |
| 2. 接受需求 | `AllocationDemandRegistrar` | 依 `ORDER / orderId / PRIMARY` 查 demand、moves、picking | 首次建立 `PENDING` demand、canonical lines、`CONFIRMED` moves／picking；冪等重播不重建 | 新建成功、回傳既有 demand，或 immutable content 衝突而失敗 |
| 3. 準備喚醒 | `AllocateOrderUsecase` | 從已接受 demand 的 canonical 第一條 line 取 SKU | 不寫資料 | 得到 `triggeringSku`；它只縮小查詢，不代表只配這個 SKU |
| 4. 選 demand 與規劃 | `PendingDemandAllocator`、repositories、`AllocationFifoSelector`、`AllocationDemandPlanner` | 讀 `PENDING` candidates、所有 required SKU 的 FIFO predecessors、所有 required SKU 的 FEFO stock batches | Selector 與 Planner 都是純演算法，不寫資料庫 | FIFO 未輪到或任一 SKU 不足：維持 `PENDING`；全部可行：得到 immutable plan |
| 5. Commit 配置 | `AllocationCommitter` | 重新驗證 demand、moves、picking、stock pools 與 plan | reserve stock；moves／picking 變 `ASSIGNED`；demand 變 `ALLOCATED` | 成功產生 `AllocationCommitted`；任何 invariant／lock failure 使整個 transaction rollback |
| 6. 完成事件 | `AllocationCompletionRouter` | 接收 generic `AllocationCommitted` | ORDER adapter 轉成既有 v1 events，寫入 transactional Outbox | transaction commit 後，由既有訊息管線發布 |

## 最容易混淆的狀態時間線

| 時點 | Demand | Moves / Picking | StockQuant reservation | Outbox completion |
| --- | --- | --- | --- | --- |
| acceptance 前 | 不存在 | 不存在 | 無 | 無 |
| acceptance 完成後 | `PENDING` | `CONFIRMED` | **尚未 reserve** | 無 |
| FIFO blocked／缺貨後 | `PENDING` | `CONFIRMED` | **尚未 reserve** | 無 |
| allocation commit 後 | `ALLOCATED` | `ASSIGNED` | **已 reserve** | 已寫入 |

所以 `demandRegistrar.register(...)` 的 registration 是**系統已登記並保存需求**，不是「庫存已配置」。
FIFO 排隊資格由 `AllocationFifoSelector` 決定；真正的 demand／supply 演算在
`AllocationDemandPlanner`；真正扣住庫存則在 `AllocationCommitter`。

## `AllocateOrderUsecase` 單次交易循序圖

![AllocateOrderUsecase 單次交易循序圖](images/allocation-order-sequence.png)

[查看 Mermaid 原始檔](diagrams/allocation-order-sequence.mmd)

這張圖只描述首次訂單路徑。兩個正常的提早結束點是：

1. shared-SKU strict FIFO 尚未輪到這筆 demand；
2. 任一 required SKU 的總供給不足。

兩者都不是 exception，也不會 partial allocation；資料會保留在 acceptance 完成後的狀態，等待下一次
`TransactionalAllocationAttempt`。

## 演算法到底在哪裡

配置決策刻意拆成兩個純領域元件：

1. `AllocationFifoSelector.selectFirstEligible`：candidate 必須在每個 required SKU 都是同一
   scope 的 queue head。
2. `AllocationDemandPlanner.plan`：先以 SKU aggregate 計算完整的缺少數量；全部足夠後，才依
   canonical line sequence 與每個 SKU 各自的 FEFO batch queue 建立 picks。

任一 SKU 不足時，結果會記錄 `missingQuantities` 且 `picks` 為空，因此沒有 partial allocation。Coordinator
負責準備資料與編排，Planner 負責決策，Committer 負責寫入；三者的責任不要混在一起看。

## 建議的程式閱讀順序

1. [`AllocateOrderUsecase`](../order-promising/src/main/java/com/flowzati/archone/inventory/allocation/application/usecase/AllocateOrderUsecase.java)：看首次訂單入口與 transaction。
2. [`AllocationDemandRegistrar`](../order-promising/src/main/java/com/flowzati/archone/inventory/allocation/application/demand/AllocationDemandRegistrar.java)：看 acceptance 建立了什麼。
3. [`PendingDemandAllocator`](../order-promising/src/main/java/com/flowzati/archone/inventory/allocation/application/PendingDemandAllocator.java)：看決策需要哪些 repository 資料。
4. [`AllocationFifoSelector`](../order-promising/src/main/java/com/flowzati/archone/inventory/allocation/domain/service/AllocationFifoSelector.java)：只看 shared-SKU FIFO eligibility。
5. [`AllocationDemandPlanner`](../order-promising/src/main/java/com/flowzati/archone/inventory/allocation/domain/service/AllocationDemandPlanner.java)：只看 all-or-nothing 與 FEFO demand／supply planning；ready plan 建立時就會驗證每條 demand line 的 picks 總量。
6. [`AllocationCommitter`](../order-promising/src/main/java/com/flowzati/archone/inventory/allocation/application/AllocationCommitter.java)：依「load data → validate cross-model scope → reserve → assign moves → assign pickings → complete demand → fact」閱讀。`AllocationCommitData` 只保存資料；`AllocationCommitValidator` 只保留無法由 plan、DB constraint 或單一 aggregate 保證的跨模型檢查，兩者都不是另外的 use case。
7. [`AllocationCompletionRouter`](../order-promising/src/main/java/com/flowzati/archone/inventory/allocation/application/event/AllocationCompletionRouter.java)：看 generic result 如何轉回 ORDER events。
8. [`ReconcileWaitingDemandUsecase`](../order-promising/src/main/java/com/flowzati/archone/inventory/allocation/application/usecase/ReconcileWaitingDemandUsecase.java)：最後再看背景補配；它從階段 2 開始，不重做 acceptance。
