# Workflow hierarchy：如何辨認 top-level、child、peer 與 activity

狀態：分析草案，尚未決定導入 Temporal

日期：2026-08-03

## 這份文件回答什麼

本文件記錄自動化履約流程如何切成多段，以及何時應建立 top-level Workflow、Child
Workflow、共享 Coordinator Workflow 或 Activity。

它不把 Temporal 加進目前 runtime，也不改變現有 Kafka、Inbox、Outbox 與 Integration Event
契約。真正導入 Temporal 前，仍須另做 workflow identity、Activity result replay、worker、
task queue、migration 與 dual-run policy 的 proposal。

相關現行範圍：

| 文件 | 關係 |
| --- | --- |
| [system-layer-map.md](system-layer-map.md) | bounded context、跨層事件與系統終點 |
| [fulfillment-minimal-scope.md](fulfillment-minimal-scope.md) | 最小履約生命週期 |
| [fulfillment-full-scope.md](fulfillment-full-scope.md) | pick、pack、depart、putback、inbound putaway 等增量 |
| [stock-reservation-design.md](stock-reservation-design.md) | 現行 allocation、FIFO、FEFO 與 stock movement |

---

## 核心結論：系統可以有很多「主流程」

不要尋找一條涵蓋整間公司的唯一主流程。`main workflow` 是相對說法，正式設計時改用
下列四個詞：

| 類型 | 意義 |
| --- | --- |
| **Top-level Workflow** | 可由外部事件或 API 獨立啟動，擁有自己的 business key 與生命週期 |
| **Child Workflow** | 由一個 parent 建立，生命週期與取消政策主要由 parent 擁有 |
| **Peer / Coordinator Workflow** | 與其他 Workflow 平行，管理共享資源或跨多個個體的公平性 |
| **Activity** | 一次可重試的外部副作用或完整本地交易；本身不保存長期等待狀態 |

因此可以同時存在多個 top-level workflows。它們不是互相競爭誰才是「真正主流程」，而是
各自代表一個有獨立身分的長期 business process。

---

## Parent/child 不是流程圖上的縮排

業務流程圖可以寫成：

```text
訂單履約
  ├── 庫存配置
  ├── 倉儲執行
  └── 離倉
```

但這不表示三段都必須是 Temporal Child Workflow。

Temporal parent/child 表達的是**執行與生命週期所有權**：誰建立誰、parent 關閉時 child
怎麼辦、結果主要回給誰。Temporal 官方也明確說明，不應只為了程式碼組織而建立 Child
Workflow；問題規模有界時，先從一條 Workflow 加 Activities 開始較簡單。

跨 bounded context 時，更常見的形狀是多個 top-level peer workflows 透過 Integration Event
協作：生產者陳述事實，消費端決定是否啟動自己的流程。這能保住目前系統「一個 context 不擁有
另一個 context 的聚合與生命週期」的邊界。

---

## 判斷順序

### 1. 先找 business outcome，不先找 class

先用一句話完成：

> 這個流程從＿＿開始，到＿＿才算完成；失敗、取消或逾時時由＿＿負責。

若這句話無法獨立成立，它通常不是一條 Workflow。

例：

| 候選流程 | 開始 | 完成 |
| --- | --- | --- |
| Order fulfillment | 訂單被接受 | 訂單完成履約或取消 |
| Shipment execution | Shipment／倉儲執行單建立 | 貨離倉或作業取消 |
| Inbound operation | 到貨／ASN 可收貨 | 貨進入可配置位置或入庫作業取消 |
| Allocation coordination | 某爭用範圍收到可用庫存 | bounded wake rounds 收斂 |

### 2. 找穩定 business key

Workflow 應有自然且穩定的識別：

| Workflow | 候選 key |
| --- | --- |
| Order fulfillment | `orderId` |
| Shipment execution | `shipmentId`；若執行聚合最後採 stock picking，才改用對應 execution id |
| Inbound operation | `inboundOperationId` / inbound picking id |
| Allocation coordinator | 現行 single-writer 爭用範圍，例如 `ownerId + facilityId`；SKU 作為輸入，不先假定它是 Workflow key |

找不到穩定 key，通常代表流程邊界尚未找對。

### 3. 找 durable wait

以下才是 Workflow state 的主要來源：

- 等人員揀貨、複核或上架；
- 等外部承運商、設備或其他 bounded context；
- 等 cancellation、timeout 或人工介入；
- 等一個將來才會到的 Integration Event；
- 需要跨重啟保存目前走到哪裡。

沒有 durable wait、只需在一個資料庫交易內完成的動作，通常是 transactional use case，包成
Activity 即可，不應拆成 Workflow。

### 4. 判斷是專屬還是共享

| 問題 | 結論 |
| --- | --- |
| 這段工作只服務一個 parent，取消與結果也由 parent 擁有 | Child Workflow 候選 |
| 這段工作有自己的外部觸發、識別、狀態與營運畫面 | Top-level Workflow 候選 |
| 這段工作同時服務多張訂單或管理共享資源公平性 | Peer / Coordinator Workflow |
| 只是 parent 內的一個 phase | 留在同一 Workflow 的 method/state |
| 只是一個本地交易或外部 API 呼叫 | Activity |

### 5. 最後才看 history、worker 與 scale

即使業務上可留在同一條 Workflow，仍可能因下列原因拆 Child Workflow：

- 需要獨立 task queue 或 worker 團隊；
- Event History 很大，需要分區；
- 有獨立 retry、timeout 或 cancellation policy；
- 子流程可被多種 parent 重用；
- 子流程本身是一個可查詢、可操作的資源。

相反地，只有「檔案太長」「想分 package」「步驟名稱不同」都不是拆 Child Workflow 的理由。

---

## 本系統的建議 Workflow catalog

### Top-level workflows

| Workflow | Scope | 為何是 top-level |
| --- | --- | --- |
| `OrderFulfillmentWorkflow` | 一張訂單 | 以 `orderId` 啟動，可能長期等待 allocation、取消與全部 Shipment 完成 |
| `ShipmentExecutionWorkflow` | 一次倉儲交付 | 有自己的 Shipment 狀態、現場 signal、短揀、離倉與取消補償 |
| `InboundOperationWorkflow` | 一次入庫作業 | Receive、可選 QC、Putaway 與異常處理有獨立生命週期 |
| `StockAllocationCoordinatorWorkflow` | 一個 single-writer 爭用範圍 | 同時服務很多訂單，維持 FIFO 與 bounded wake；不屬於任何一張訂單 |

`ReturnWorkflow`、`WaveWorkflow`、`CycleCountWorkflow` 等未來也可能是 top-level。數量多不是
問題；問題是兩條 Workflow 是否重複擁有同一個 business state 或同一個副作用。

### 建議的協作形狀

依目前 bounded-context 邊界，預設採 peer workflows：

```text
OrderFulfillmentWorkflow(orderId)
  │
  ├─ AllocateOrderActivity
  │    └─ AllocateOrderUsecase 〔transaction〕
  │
  ├─ BACKORDERED 時等 OrderAllocationCompleted / OrderCancelled / SLA timeout
  │
  └─ 等 ShipmentDeparted（若日後一單多 Shipment，等全部）

OrderAllocationCompleted / OrderAllocated
  │
  └─ start ShipmentExecutionWorkflow(shipmentId)
         ├─ release / pick
         ├─ wait Picked or ShortPicked
         ├─ optional pack
         ├─ depart
         └─ publish ShipmentDeparted

StockAllocationCoordinatorWorkflow(ownerId, facilityId)
  ├─ receive StockBecameAllocatable(sku, ...)
  ├─ CompleteInboundAndWakeFirstRoundActivity
  └─ WakeNextRoundActivity × N
       └─ publish OrderAllocationCompleted per successful order

InboundOperationWorkflow(inboundOperationId)
  ├─ receive
  ├─ optional quality inspection
  └─ putaway into allocatable stock location
       └─ notify allocation coordinator
```

這裡 `ShipmentExecutionWorkflow` 是否改成 `OrderFulfillmentWorkflow` 的真正 Child Workflow，
取決於組織邊界：

| 條件 | 建議 |
| --- | --- |
| 同一 orchestration service 擁有兩者，Shipment 只由該 Order 建立且跟著取消 | 可以是 Child Workflow |
| 履約層是獨立 bounded context、可獨立操作 Shipment、或一張訂單未來可拆多 Shipment | 保持 top-level peer，以 Integration Event 協作 |

依本系統現有事件邊界與未來一單多 Shipment 的方向，**預設建議 top-level peer**，不要為了
畫面上看起來像子流程就先建立 Temporal parent/child coupling。

---

## `OrderFulfillmentWorkflow` 應擁有什麼

它擁有的是訂單層的 orchestration state，不是其他 context 的聚合內容：

```text
RECEIVED
  → ALLOCATING
  → WAITING_STOCK | READY_FOR_EXECUTION
  → FULFILLING
  → FULFILLED

分支：CANCELLED / TIMED_OUT / MANUAL_REVIEW
```

它可以記住：

- `orderId`；
- allocation 是否完成；
- 預期要等哪些 Shipment；
- 哪些 Shipment 已離倉；
- cancellation／SLA deadline；
- orchestration stage。

它不應複製：

- `StockPool` 數量；
- FIFO queue；
- `StockMove` 狀態；
- `Shipment`／`PickTask` 的完整狀態；
- FEFO 決策結果的第二份真相。

其他 context 的狀態由 business event 摘要成「主流程是否能前進」即可。

---

## Activity 與 transactional use case 的對應

一個 Activity 包一個完整 transactional facade：

| Activity | transactional boundary |
| --- | --- |
| `AllocateOrderActivity` | `AllocateOrderUsecase` |
| `ConfirmStockReceiptActivity` | `ConfirmStockReceiptUsecase` |
| `AllocateBackordersActivity` | `AllocateWaitingDemandUsecase` |
| `ReleaseShipmentActivity` | 未來的 release use case |
| `DepartShipmentActivity` | 未來的 depart use case |

不把 `StockOperationRecorder`、`MovementAssigner`、repository save 或 domain event publication 分拆成
不同 Activities，因為它們之間沒有可獨立 commit、等待或補償的 checkpoint。

任何會寫資料庫且回傳值供 Workflow branching 的 Activity，都必須使用穩定 invocation id，
並在業務交易內保存原始結果。若資料庫已 commit、Worker 卻在 Temporal 記錄 completion 前失敗，
重試必須回傳第一次的結果，不能把 Inbox duplicate 當成新的 `NO_OP` 結果。

---

## 建立 Workflow 的實務步驟

每次只做以下七步，不先畫完整企業樹：

1. 寫出 process 的開始、成功終點、取消與逾時。
2. 選定穩定 business key。
3. 列出所有 durable waits 與外部訊息。
4. 將每個副作用圈成 transactional use case／Activity。
5. 標出哪些資源是某個 process 專屬，哪些由多個 process 共享。
6. 專屬且有獨立生命週期者才考慮 Child；共享者改成 peer coordinator。
7. 檢查每個副作用只有一個 driver：目前由 availability event + Scheduler 驅動；若改由 Temporal
   loop 主動編排，必須明確停用對同一流程的 Scheduler driver。

完成後用這張表檢查：

| 問題 | 若答案是「否」 |
| --- | --- |
| 這條 Workflow 有明確 outcome 嗎？ | 留在 method 或 Activity |
| 有穩定 business key 嗎？ | 重新找流程邊界 |
| 有 durable wait 或跨交易狀態嗎？ | 多半不需要 Workflow |
| Child 的生命週期真的由 parent 擁有嗎？ | 改成 top-level peer |
| 共享公平性是否集中管理？ | 建 coordinator，不讓每個 entity 自己競爭 |
| Activity retry 能回原始 committed result 嗎？ | 不可上線 Temporal adapter |
| Kafka 與 Temporal 是否只有一個 orchestration driver？ | 關閉其中一條控制路徑 |

---

## 現階段決定

- 可以有多個 top-level workflows，不建立全系統唯一 mega workflow。
- `OrderFulfillmentWorkflow` 是訂單視角的長期 process manager，不直接擁有共享 allocation queue。
- allocation fairness 由共享 coordinator 管理，backordered order 只等待完成事實。
- bounded context 之間預設用 Integration Event 啟動或通知 peer workflow。
- Child Workflow 只在 parent 真正擁有其生命週期時使用，不因流程圖縮排或程式碼分檔而使用。
- 目前先保留為設計草案；導入 Temporal 時另立 change，連 durable result replay 與單一 driver
  migration 一起交付。

## 參考

- [Temporal Workflows](https://docs.temporal.io/workflows)
- [Temporal Child Workflows](https://docs.temporal.io/child-workflows)
- [Temporal Workflow message passing](https://docs.temporal.io/encyclopedia/workflow-message-passing)
- [Temporal Activity idempotency](https://docs.temporal.io/activity-definition)
