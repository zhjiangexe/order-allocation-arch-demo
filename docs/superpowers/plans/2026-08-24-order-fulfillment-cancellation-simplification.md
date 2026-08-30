# Order Fulfillment 取消流程簡化計畫

**狀態：** 已實作並通過 unit test、PostgreSQL SIT 與 Events／Temporal Karate E2E
**目標：** 讓 `OrderFulfillmentWorkflowImpl` 只協調跨 Context 的取消命令與 Shipment 終態，不再理解
WMS 內部的 `PUTBACK_REQUIRED`、停止作業或回庫細節；同時讓 Events 與 Temporal 模式收斂到相同的業務語意。

## 設計結論

- `CancellationScope` 不用於業務取消。它適合取消 Temporal command／Activity，不會替代 Shipment 回庫與
  Order compensation。
- 本階段不使用 Async Activity Completion。`CancelWmsShipment` 只需短時間提交冪等 command，真正的晚到結果
  已有 Outbox、Kafka、Inbox 與 Workflow Signal 可可靠傳遞。
- WMS 對外只發布兩個 Shipment 物理終態：`ShipmentCancelledIntegrationEvent` 與既有的
  `ShipmentHandedOverIntegrationEvent`。
- Workflow 保留具體且可獨立演進的 `ShipmentCancelledSignal` 與既有
  `ShipmentHandedOverToCarrierSignal`；兩者只在 Workflow 內部收斂成 `ShipmentCheckpoint`。
- 移除把中間狀態當成結果的 `PUTBACK_REQUIRED`：取消生命週期使用 `ShipmentCancellationState`，command
  受理結果使用 `CancelShipmentStatus`。
- 已開始 Pick／Pack／Stage 的 Shipment 先進入 `CANCELLING`；WMS 完成必要的停止作業與回庫後才進入
  `CANCELLED` 並發布事件。
- `requestedAt` 表示外部提出要求的時間；`cancelledAt` 表示 Shipment 安全停止、Order 可正式取消的時間。
  Events 與 Temporal 都必須使用後者推進 Order 終態。
- 已完成 handover 的取消仍拒絕，等待未來獨立的 Return workflow；本次不把退貨混進 fulfillment cancellation。
- Events 與 Temporal 的 producer 不依 `orchestration-mode` 分流；兩種模式消費相同的 canonical events。

## 目標流程

```text
取消發生在 Shipment 建立前
  -> Workflow／Events coordinator 直接取消 Order
  -> OrderCancelledIntegrationEvent
  -> Inventory 釋放 reservation

取消發生在 Shipment 建立後、handover 前
  -> 提交 WMS cancellation command
  -> WMS CREATED／WAVE_PLANNED／RELEASED：立即 CANCELLED
  -> WMS PICKING／PICKED／PACKED／READY_FOR_DISPATCH：CANCELLING
       -> WMS 內部完成停止作業／putback
       -> CANCELLED
  -> ShipmentCancelledIntegrationEvent
       -> Temporal：ShipmentCancelledSignal -> CancelOrder Activity
       -> Events：Ordering consumer -> CancelOrderUsecase
  -> OrderCancelledIntegrationEvent
  -> Inventory 釋放 reservation

取消與 handover 競爭
  -> 若 WMS cancellation transaction 先提交：Shipment 進入 CANCELLING／CANCELLED，handover 失敗
  -> 若 handover transaction 先提交：取消回覆 CancelShipmentStatus.REJECTED，既有 handover event 導向 handover Signal
  -> Workflow 收到 HANDED_OVER 後繼續完成 outbound 與 Order fulfillment
```

## Task 1：重塑 WMS cancellation state 與 command result

### 預計異動

- 修改 `CancelShipmentCommand`：`requestId` 改為 `UUID`，並帶入 `reason`。
- 新增 `ShipmentCancellationState { REQUESTED, COMPLETED, REJECTED }`，只描述 cancellation 生命週期。
- 新增 `CancelShipmentStatus { ACCEPTED, ALREADY_ACCEPTED, REJECTED }`，只描述 command 受理結果。
- 移除 `ShipmentCancellationStatus` 與 `PUTBACK_REQUIRED`、`CANCELLED`、`ALREADY_CANCELLED`、
  `REJECTED_AFTER_HANDOVER` 混合中間狀態和結果的模型。
- 修改 `Shipment`：保存 cancellation request ID、`requestedAt`、reason、state，以及實際 `cancelledAt`。
- 新增 `Shipment.completeCancellation(Instant completedAt)`，只允許 `CANCELLING -> CANCELLED`。
- 修改 `WmsShipmentEntity`、`ShipmentView` 與 rehydrate mapping。
- 新增 Flyway `V20` migration：
  - `cancellation_request_id VARCHAR -> UUID`
  - `cancellation_outcome` 改為 `cancellation_state`
  - 新增 `cancellation_requested_at`
  - 新增 `cancellation_reason`
  - 新增 `cancelled_at`
  - 補強 cancellation 欄位一致性 constraint。
- migration 在型別轉換前先檢查既有非 null request ID 是否皆為合法 UUID；資料不合法時應以可診斷錯誤中止，
  不得靜默改寫或捨棄 correlation。
- DB constraints 至少保證：
  - state 為 null 時，所有 cancellation metadata 與 `cancelled_at` 都是 null；
  - `REQUESTED`／`REJECTED` 有完整 request metadata，但 `cancelled_at` 為 null；
  - `COMPLETED` 有完整 request metadata 與 `cancelled_at`；
  - `REQUESTED` 只搭配 Shipment `CANCELLING`，`COMPLETED` 只搭配 `CANCELLED`，`REJECTED` 只搭配
    `HANDED_OVER_TO_CARRIER`。

### 邊界

- 不把既有 `PickTask.PICKED` 改寫成 `CANCELLED`，也不清除 `pickedQuantity`／`confirmedAt`；那是已發生的作業歷史。
- 本次不建立完整 Putaway／Putback aggregate。`completeCancellation` 代表 WMS 已從外部作業流程取得回庫完成事實；
  simulation 只模擬這個 checkpoint。
- `ShipmentStatus.CANCELLING` 表示 Shipment 尚未完成實體 recovery；`ShipmentCancellationState.REQUESTED`
  表示 cancellation request 尚未完成。兩者雖然同步轉換，仍分別回答 Shipment 作業與 cancellation audit 問題。

### 驗收

- 第一次要求取消回 `ACCEPTED`；同一 request 重送回 `ALREADY_ACCEPTED`；不同 request 對同一 Shipment
  仍為 conflict。
- handover 後取消回 `REJECTED`，並保存 `ShipmentCancellationState.REJECTED` 供 audit。
- started work 取消後為 `ShipmentStatus.CANCELLING`／`ShipmentCancellationState.REQUESTED`，未完成 recovery
  前不可 handover。
- recovery 完成後為 `ShipmentStatus.CANCELLED`／`ShipmentCancellationState.COMPLETED`，原 PickTask 歷史仍可查詢。
- 可立即取消的 Shipment 在同一 transaction 直接進入上述兩個完成狀態。

## Task 2：發布 canonical `ShipmentCancelledIntegrationEvent`

### 預計異動

- 在 `integration-contracts` 新增 `ShipmentCancelledIntegrationEvent`，欄位至少包含：
  - `eventId`
  - `shipmentId`
  - `orderId`
  - `cancellationRequestId`
  - `cancellationRequestedAt`
  - `cancellationReason`
  - `cancelledAt`
- 在 `FulfillmentChannels` 新增獨立 destination，例如 `SHIPMENT_EVENTS = "wms.shipment-events"`。
- 新增 `ShipmentCancelledPublicationFactory`。
- `CancelShipmentUsecase` 在直接進入 `CANCELLED` 時，與 aggregate save 同 transaction 發布事件。
- 新增 `CompleteShipmentCancellationUsecase`；deferred recovery 完成時發布相同事件。
- replay／重試若沒有新的 `CANCELLED` transition，不得重複建立 publication。
- 更新 bootstrap event type allow-list、JSON contract 與 event wiring tests。
- event publication 的 `occurredAt` 使用實際 `cancelledAt`，不可使用較早的 request time。

### 為何不用既有 `FULFILLMENT_HANDOFFS`

`FULFILLMENT_HANDOFFS` 表示 outbound handover 鏈；把 cancellation event 混入會迫使 Inventory handover 與
Ordering cancellation consumers 都訂閱不屬於自己的 event type。獨立 Shipment destination 的語意與 subscriber
ownership 較清楚。

## Task 3：建立獨立的 cancellation recovery backlog

### 預計異動

- `ShipmentStore` 新增只回傳 ID 的 `findCancelling(int limit)`。
- `JpaWmsShipmentRepository`／`JpaShipmentStoreAdapter` 以 `status = CANCELLING`、穩定排序與 limit 實作。
- 保留 `ProcessDueShipmentsUsecase` 專門處理 CREATED Shipment simulation。
- 新增 `ProcessCancellingShipmentsUsecase`，專門掃描 CANCELLING backlog。
- 每張 Shipment 透過 `CompleteShipmentCancellationUsecase` 在獨立 transaction 完成。
- WMS simulation scheduler 每次 tick 先執行 cancellation backlog，再執行一般 due Shipment；兩者使用獨立
  batch limit，避免 CREATED backlog 延遲已接受的取消。
- 兩條 backlog 都保留 per-shipment exception isolation 與 log；optimistic conflict 留待下一次 scan 重試。

### 競爭語意

Simulation 與取消可能同時載入 Shipment。JPA version 必須保證只能有一方提交：

- simulator 先提交 handover：取消重試後讀到 handover，拒絕取消；
- cancellation 先提交：simulator conflict，下一次 scan 不再處理 CANCELLING Shipment；
- 不新增 JVM lock，也不建立跨 instance 的隱式 single-writer 假設。

## Task 4：把 WMS Activity 改成 command acknowledgement

### 預計異動

- `WmsActivities.cancelShipment(...)` 改為不回傳業務終態；建議 Java method 命名為
  `requestShipmentCancellation(...)`，但保留 Temporal Activity wire name `CancelWmsShipment`。
- 移除 `CancelShipmentActivityStatus`。
- `TemporalWmsActivitiesAdapter` 呼叫 `CancelShipmentUsecase` 後立即完成：
  - `ACCEPTED`／`ALREADY_ACCEPTED`：視為 command 已處理；
  - `REJECTED`：也不製造另一套 Activity result，後續由既有 handover fact 收斂；
  - request conflict：維持 non-retryable `ApplicationFailure`。
- Activity 不輪詢 DB、不等待 Kafka，也不持有 worker thread 等待實體回庫。

### Temporal 相容性注意

Activity return type 與 Workflow orchestration 變更會影響既有 Workflow History replay。實作前先確認本專案是否
需要保留已啟動的 production executions；若需要，必須透過 `Workflow.getVersion`／Worker Versioning 保留舊路徑
與舊 Activity contract，並加入 history replay test。若目前只處於開發環境，可直接切換 contract 並重建 Temporal
dev data。

## Task 5：簡化 `OrderFulfillmentWorkflowImpl`

### Signal contracts

```java
void shipmentHandedOverToCarrier(ShipmentHandedOverToCarrierSignal signal);

void shipmentCancelled(ShipmentCancelledSignal signal);
```

保留既有 `shipmentHandedOverToCarrier` Signal name，避免只為了共用 wait condition 而破壞具體事件語意與既有
Workflow message contract。新增的 `ShipmentCancelledIntegrationEvent` 由 Temporal adapter 映射成
`ShipmentCancelledSignal`；Workflow 不直接依賴 Integration Event classes。

兩個 Signal handler 只負責 correlation，並在 Workflow 內部寫入同一個 `ShipmentCheckpoint`。該
checkpoint 可以使用內部 `ShipmentTerminalState` 辨識 `CANCELLED`／`HANDED_OVER`，不把通用 terminal enum
暴露成 transport contract。

### 主線改寫

```text
create Shipment
await handover／cancelled fact OR cancellation request

if cancellation requested and terminal 尚未出現:
    requestShipmentCancellation Activity
    await handover／cancelled fact

ShipmentCancelledSignal:
    cancel Order at Shipment cancelledAt -> finish ORDER_CANCELLED

ShipmentHandedOverToCarrierSignal:
    正常履約路線勝出；保留 cancellation request 作為 audit fact
    -> complete outbound -> record fulfilled
```

### 可移除複雜度

- `cancelShipmentInWms()` 的 `CANCELLED／REJECTED` switch。
- `ensureCancellationNotAfterHandover()` 與 `WMS_SHIPMENT_FACT_CONFLICT`。
- Activity 根據 `PUTBACK_REQUIRED`／`REJECTED` 決定是否重新等待 handover 的特殊分支。
- Workflow 對 Pick／putback 能否完成的判斷。

### 必須保留

- allocation 前取消：直接取消 Order。
- Signal correlation：只接受相同 `orderId`／`shipmentId`。
- 互斥終態保護：同一 Shipment 若同時出現 cancellation 與 handover fact，不得靜默採用先到者；Workflow
  必須暴露 invariant conflict。
- Update idempotency：`ACCEPTED`、`ALREADY_REQUESTED`、`ALREADY_CANCELLED`；handover 已成立時回 `REJECTED`，
  但不為 Workflow 新增第三條 `REJECTED` 路線。
- handover 與 cancellation race 的 deterministic 結果。
- `ShipmentCancelledSignal` 必須保留 `cancellationRequestId`，先與 Update 記錄的 request 完成 correlation；
  `CancellationCheckpoint.cancelledAt` 必須取自 Signal 的實際 `cancelledAt`，不可再使用 request time。
- Ordering／Inventory Activity retry 與既有 Query snapshot。
- Query snapshot 明確暴露 `shipmentTerminalStatus` 與 `shipmentTerminalAt`，不要求維運端從 Workflow outcome
  反推 WMS Shipment 結果。

## Task 6：讓 Events mode 使用同一個終態事件

### `EventDrivenFulfillmentCancellationCoordinator`

- 無 Shipment：維持同步呼叫 `CancelOrderUsecase`。
- 有一筆 Shipment：只呼叫 `CancelShipmentUsecase`。
  - `ACCEPTED`、`ALREADY_ACCEPTED`：回 HTTP 202／`ACCEPTED`，不在 HTTP transaction 直接取消 Order。
  - `REJECTED`：回 HTTP 409／`REJECTED`，直到 Return flow 存在。
- 多筆 Shipment：維持目前 ship-complete conflict。
- 搭配既有 read-query refactor 時，改用輕量 shipment ID query，不載入 `ShipmentView` lines／PickTasks。

### Ordering consumer

- 新增 `OrderingShipmentCancellationEventConsumer`，只在 `events` mode 啟用。
- 消費 `ShipmentCancelledIntegrationEvent`，以原 request ID、reason 與事件的實際 `cancelledAt` 呼叫
  `CancelOrderUsecase`。
- 將 `CancelOrderCommand.requestedAt` 與 `CancelOrderActivityInput.requestedAt` 改名為 `cancelledAt`；Ordering
  command 表示「現在可以正式取消 Order」，不再假裝該時間永遠是外部 request time。
  - 無 Shipment 時取消立即成立，使用 cancellation request 的 `requestedAt` 作為 `cancelledAt`；
  - 有 Shipment 時，Events 與 Temporal 都使用 `ShipmentCancelledIntegrationEvent.cancelledAt`；
  - `OrderCancelledIntegrationEvent.cancelledAt` 因此始終表示 Order 實際進入 `CANCELLED` 的業務時間。
- Ordering 本次不新增 `cancellation_requested_at`；原始 request time 由 WMS cancellation audit 與 Temporal History
  保存。若未來 Ordering 自己需要呈現受理到完成的 SLA，再為 Order 建立獨立 request timestamp，不能混用
  `cancelled_at`。
- Order cancellation 仍發布既有 `OrderCancelledIntegrationEvent`，Inventory compensation 不需新增分支。
- Temporal mode 不啟用此 consumer，避免 Workflow 與 event consumer 同時成為 Order cancellation command driver。

## Task 7：測試矩陣

### Workflow unit tests

- Shipment 建立前取消：直接取消 Order。
- Shipment 建立後取消：Activity 返回後 Workflow 仍等待，不能提早取消 Order。
- 收到 `ShipmentCancelledSignal`：先 Shipment terminal，再以實際 `cancelledAt` 取消 Order。
- 收到 `ShipmentHandedOverToCarrierSignal`：取消標為 rejected，繼續 outbound 與 fulfillment。
- cancellation command 與 handover Signal 競爭。
- 不相干 order／shipment Signal 被忽略。
- 重複 Update／Signal 保持冪等。
- 同一 Shipment 收到互斥的 cancelled 與 handover facts 時，明確回報 invariant conflict。
- 若保留既有 production executions，使用實際 history fixture 執行 replay test。

### WMS tests

- CREATED／WAVE_PLANNED／RELEASED 直接進入 cancellation `COMPLETED` 並發布一則 cancellation event。
- PICKING／PICKED／PACKED／READY_FOR_DISPATCH 先進入 cancellation `REQUESTED`／Shipment `CANCELLING`，
  completion 後才發布事件。
- deferred completion 不清除既有 PickTask 作業歷史。
- handover 後拒絕、不發布 cancellation event。
- 同 request retry 不重複發布；不同 request conflict。
- repository 能持久化 request metadata、CANCELLING 與 cancelledAt。
- scheduler 能隔離單筆失敗與 optimistic conflict。
- aggregate state 與 Outbox publication 必須原子提交；rollback 時兩者都不存在。
- event 的 `cancellationRequestedAt` 與 `cancelledAt` 各自保持原始語意。

### Events／Temporal wiring tests

- canonical event type 可序列化、反序列化並通過 allow-list。
- Temporal cancellation event 映射為 `ShipmentCancelledSignal`。
- handover event 維持映射為既有 `ShipmentHandedOverToCarrierSignal`。
- Events mode 的 Ordering consumer 會取消 Order；Temporal mode 不建立該 dispatcher。
- Events coordinator 對 deferred WMS cancellation 回 202，不再回 409。

### E2E

- Events 與 Temporal 都保留：allocation 前取消、CREATED Shipment 取消、handover 後拒絕。
- 兩種模式對相同場景盡量驗證同一組終態：Order、Allocation、Shipment、Workflow（Temporal only）。
- started-work／putback 分支先以 WMS + integration test 覆蓋；目前 simulation 在單一 transaction 內一次完成
  Pick／Pack／Stage／Handover，沒有穩定的 E2E 可觀察窗口。除非另行設計 staged simulation，不新增 test-only API。

## Task 8：文件與圖表

完成程式後更新：

- `docs/order-fulfillment-temporal-hybrid-architecture.md`
- `docs/order-fulfillment-process-cancellation-flow.puml` 與 PNG
- `docs/order-fulfillment-process-execute-flow.puml` 與 PNG
- `docs/order-fulfillment-process-execute-sequence.puml` 與 PNG
- `docs/order-fulfillment-process-method-map.puml` 與 PNG
- `docs/order-fulfillment-end-to-end-activity.puml` 與 PNG
- `docs/wms-business-process-decomposition.md`

文件要明確區分：

- Activity 是 command acknowledgement；
- `ShipmentCancelled`／`ShipmentHandedOver` Integration Event 與具體 Signal 才是物理終態；
- `CANCELLING` 與 putback 屬於 WMS 內部；
- handover 後不是 cancellation compensation，而是尚未實作的 Return flow。

## 執行與 Commit 順序

1. `refactor(wms): separate cancellation state and command status`
2. `feat(wms): complete deferred shipment cancellations`
3. `feat(events): publish shipment cancellation outcome`
4. `refactor(temporal): await concrete shipment terminal facts`
5. `refactor(fulfillment): align event-driven cancellation semantics`
6. `test(e2e): align cancellation scenarios across orchestration modes`
7. `docs: update fulfillment cancellation architecture`

每一批 Java 修改後執行：

```bash
cd backend
./gradlew spotlessApply
```

提交前執行：

```bash
cd backend
./gradlew spotlessCheck
```

完整驗證：

```bash
cd backend
./gradlew test
./gradlew :deployments:monolith:sit
cd ..
./e2e/spec/run.sh
```

## 非本次範圍

- Return／RMA workflow。
- 完整的 PutbackTask、reverse StockMove 或庫位建議模型。
- 真實外部 WMS callback API。
- Async Activity Completion；未來外部 WMS 若以 callback 完成 command，可在不改 Workflow 業務主線的前提下
  重新評估。
- 拆單、多 Shipment cancellation policy。
- 用 Temporal cancellation 取代業務 compensation。

## 與既有計畫的關係

本計畫會取代
`docs/superpowers/plans/2026-08-24-order-fulfillment-read-query-refactor.md` Task 2 中
「`PUTBACK_REQUIRED`、`REJECTED_AFTER_HANDOVER` 與正常取消語意不變」的部分：

- 輕量 shipment ID query 的效能目標仍保留；
- `PUTBACK_REQUIRED` 被 `ShipmentCancellationState.REQUESTED` 與 `ShipmentStatus.CANCELLING` 取代，不再是
  command result；
- handover 後的 `CancelShipmentStatus.REJECTED` 外部語意維持拒絕，等待 Return flow。
