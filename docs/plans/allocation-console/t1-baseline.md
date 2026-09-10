# T1 — 契約與雙模式基線

- 日期：2026-09-09
- 原始碼基線：`8f243597873dbef8ef412c9ff3f86449d131081c`，加上本次規劃與 UI 專用 fixture。
- 範圍：T1.1／T1.2；不包含 T2 查詢契約變更或前端功能實作。
- 執行結果：見本文末「執行紀錄」。

## 1. 必須處理的前後端落差

| 項目 | 已確認事實 | 後續任務 |
| --- | --- | --- |
| 建單 | 前端缺少 dispatchBy 與 releasePriority；後端均必填 | T4.1 補表單及 request，T3.1 補 response 型別 |
| 建單冪等 | ownerId＋externalOrderNo 重複回 409，不回原 Order | T4.1 保留原內容，顯示衝突／結果待確認，不自動換號 |
| 收貨冪等 | 同 receiptId＋相同 payload 為 200；不同內容為 409 | T6.2 保留原 ID／payload；目前 StockPage 每次送出重建 UUID |
| Order 表示 | 列表 line 有 status，履約 line 有 orderLineId 而沒有 status | T3.1 分開建模，不共用不相容型別 |
| 批次 ID | stock-pool 使用 stockPoolId；履約 batches 使用 stockQuantId | T3.1 明確映射 |
| Workflow 時間 | JSON 為 updatedAt，代表目前 phase 進入時間 | T3／T4 不讀不存在的 phaseEnteredAt |
| Workflow 交接終態 | HANDED_OVER；Shipment 狀態是 HANDED_OVER_TO_CARRIER | T3.2 明確區分 |
| 排序 | FIFO／DISPATCH_DATE_FIRST；現行預設 DISPATCH_DATE_FIRST | T5 不標成固定 FIFO；不新增政策 API |
| 佇列範圍 | 僅 CONFIRMED OUTBOUND；limit 1..200，預設 100 | T5 明示截斷與局部篩選 |
| 可用性 | null Workflow 可指 Events 或查無執行，其他查詢錯誤尚未降級 | T2 增加模式／查詢狀態 |

## 2. 命令與錯誤契約

### POST /orders

必填 request：ownerId、externalOrderNo、facilityId、shipToZone、shipToAddress、
promisedDeliveryDate、dispatchBy、releasePriority、lines。
placedAt 可省略／null。lines 每項為 skuCode、quantity；至少一行、quantity 正整數，允許同 SKU 多行。
releasePriority 為 0..100 整數，dispatchBy 為 ISO instant，promisedDeliveryDate 為 YYYY-MM-DD。

成功 HTTP 200，回傳下節的一般 Order。重複 owner＋externalOrderNo 為 409；無效必填／SKU 為 400。
網路結果不明不能判定未建立；可在最近訂單中比對 owner＋externalOrderNo，未找到不等於不存在。
列表 limit 預設 20、最大 100，沒有 externalOrderNo 搜尋 API。

### POST /stock-receipts

request：receiptId、ownerId、facilityId、locationId、sku、inDate、expiryDate、quantity，均為必要業務資料。
UUID 為字串、日期為 YYYY-MM-DD、quantity 為正整數。成功 HTTP 200：receiptId、sku、quantity。
同 identity 與完整內容重送不重複加庫存；同 identity 不同內容為 409。其他無效參數為 400。

`StockReceiptApplicationFacade` 在同一 transaction claim request 並確認收貨；
HTTP 200 保證收貨與 availability Outbox 提交，不保證等待訂單已完成分配。
availability consumer 觸發分配，reconciliation scheduler 補漏。

## 3. 回應結構清單

下列是現有 JSON 欄位清單，不是 T2 新欄位。UUID／時間在 JSON 都是字串，未發生時間或未建立關聯可為 null。

| 表示 | 欄位 |
| --- | --- |
| 一般 Order | orderId, ownerId, externalOrderNo, facilityId, shipToZone, shipToAddress, promisedDeliveryDate, dispatchBy, releasePriority, lines, status, receivedAt, placedAt, allocatedAt, cancelledAt, fulfilledAt |
| 一般 Order line | lineNo, skuCode, quantity, status |
| 履約頂層 | order, stockOperation, shipments, temporalWorkflow |
| 履約 Order | 一般 Order header，另含 cancellationRequestId, cancellationReason, fulfilledByShipmentId；lines 為履約 line |
| 履約 Order line | orderLineId, lineNo, skuCode, quantity |
| StockOperation | source, operation, moves |
| source | type, sourceId, operationUnitKey；訂單來源為 ORDER／orderId／PRIMARY |
| operation | stockOperationId, stockOperationTypeId, direction, ownerId, fromLocationId, toLocationId, assignmentPolicy, enqueuedAt, dispatchBy, releasePriority, state |
| move | moveId, sourceLineId, lineSequence, skuCode, quantity, state, createdAt, assignedAt, batches |
| batch | stockQuantId, locationId, skuCode, inDate, expiryDate, quantity |
| Shipment | shipmentId, stockOperationId, orderId, ownerId, facilityId, status, waveId, createdAt, dispatchBy, releasePriority, cancellationRequestId, cancellationRequestedAt, cancellationReason, cancelledAt, cancellationState, lines, pickTasks |
| Shipment line | orderLineId, moveId, skuCode, sourceLocationId, quantity |
| PickTask | pickTaskId, orderLineId, moveId, skuCode, sourceLocationId, requestedQuantity, pickedQuantity, status, confirmedAt |
| Workflow snapshot | orderId, phase, allocationState, cancellationState, cancellationRequestId, cancellationRequestedAt, outcome, stockOperationId, shipmentId, shipmentTerminalStatus, shipmentTerminalAt, cancelledAt, updatedAt |
| stock-pool | skus 陣列，每項 sku、batches；batch 為 stockPoolId, inDate, expiryDate, onHandQuantity, reservedQuantity, availableToPromise, expired |

stockOperation 可為 null、shipments 可為空、未分配 batches 為空。多筆 Shipment 完整列出。
一般訂單列表沒有 fulfilledByShipmentId；必須從履約查詢取得完成關聯。

## 4. 狀態與完成 predicate

- Order：PENDING、ALLOCATED、FULFILLED、CANCELLED。
- StockOperation／Move：CONFIRMED、ASSIGNED、DONE、CANCELLED。
- operation.direction：INBOUND、OUTBOUND、INTERNAL；assignmentPolicy 現有 SHIP_COMPLETE。
- source.type：ORDER、TRANSFER、REPLENISHMENT、PRODUCTION、MANUAL。
- Shipment：CREATED、WAVE_PLANNED、RELEASED、PICKING、PICKED、PACKED、READY_FOR_DISPATCH、
  HANDED_OVER_TO_CARRIER、CANCELLING、CANCELLED。
- Workflow phase：NOT_STARTED、ALLOCATION、WAREHOUSE_EXECUTION、INVENTORY_FINALIZATION、
  ORDER_COMPLETION、CANCELLING、FINISHED。
- Workflow allocationState：NOT_REQUESTED、REQUESTED、COMMITTED。
- Workflow outcome：進行中 null，終態 FULFILLMENT_COMPLETED 或 ORDER_CANCELLED。
- Workflow shipmentTerminalStatus：尚無結果 null，終態 HANDED_OVER 或 CANCELLED。

共同成功：order.status=FULFILLED、stockOperation.operation.state=DONE，
source 指向該訂單 PRIMARY；以 order.fulfilledByShipmentId 找到 Shipment，
其 orderId／stockOperationId 對應本訂單／作業，且 status=HANDED_OVER_TO_CARRIER。
Temporal 再要求 phase=FINISHED、outcome=FULFILLMENT_COMPLETED，以及 snapshot 的
orderId／stockOperationId／shipmentId 與上述一致，shipmentTerminalStatus=HANDED_OVER。
缺失／矛盾資料提示未能確認，不以任意一張已交接 Shipment 代替。

`GET /stock-operations` 的輸出順序是 enqueuedAt／id，並非完整 allocation 決策順位。
實際競爭範圍為 ownerId＋fromLocationId＋skuCode，整單跨 SKU 同時滿足才能分配。
DISPATCH_DATE_FIRST 使用 dispatchBy／enqueuedAt／id，FIFO 使用 enqueuedAt／id。

## 5. 雙模式接線

共同：POST /orders → OrderPlaced Outbox → Debezium → Kafka。

Events：AllocationOrderPlacedEventConsumer → AllocateOrderUsecase → OrderAllocationCommitted；
WmsFulfillmentHandoffEventConsumer → CreateShipmentUsecase → WMS 模擬交接 → ShipmentHandedOver；
ShipmentHandoverEventConsumer → CompleteOutboundMovementsUsecase → OutboundMovementsCompleted；
OrderingFulfillmentCompletionEventConsumer → RecordOrderFulfillmentUsecase。

Temporal：TemporalFulfillmentEventBridge 啟動 Workflow → requestAllocation Activity；
OrderAllocationCommitted 轉 stockOperationAssigned Signal → releaseToWarehouse Activity；
WMS 模擬交接事件轉 shipmentHandedOverToCarrier Signal → completeOutboundMovements Activity
→ recordOrderFulfillment Activity → FINISHED／FULFILLMENT_COMPLETED。

兩模式均使用 OrderingAllocationResultEventConsumer 更新訂單配貨狀態；出庫與完成的 driver 互斥。
WMS 模擬預設開啟，預設 delay 10 秒；E2E 正常案例使用 0 秒，取消窗口使用 30 秒。
模式由後端環境變數指定，前端不呼叫 Temporal。此階段不修改任何 consumer／usecase。

## 6. 隔離資料與可重現方式

既有 `e2e/spec/fixtures/e2e-catalog.sql` 新增 10 個 UI 專用 SKU，既有 Karate scenarios 不消耗它們。
沿用 owner `00000000-0000-0000-0000-000000000001`、facility `...0011`、location `...0021`、product P-TEA。

| SKU（各有 UI-EVT- 與 UI-TMP- 前綴） | 初始資料 | 後續 UI 驗收操作 |
| --- | --- | --- |
| HAPPY | 有效在手 20、預留 0 | 下 4 件，預期完成 |
| WAKE | 有效在手 0 | 下 3 件，觀察等待，再收 3 件 |
| MULTI-A／MULTI-B | A 有 10，B 有 0 | 同單 A 2、B 3，先無整單預留，再收 B 3 |
| FEFO | 有效批 2＋3＋5、過期批 7；在手 17，ATP 10 | 下 4 件，先取同效期較早入庫的 2，再取另一批 2 |

每模式每情境獨立 SKU，避免與既有 E2E 或另一模式共享競爭佇列。fixtures 僅適用新建的隔離 DB；
ON CONFLICT DO NOTHING 只防重插，不會還原已消耗庫存。重跑 UI 請用新隔離 project／資料庫，
不要清除使用者既有環境。有效批效期 2098／2099、過期批效期 2000；超過有效期間需更新 fixture。

重用既有 runner，使用獨立埠與 project：

```bash
COMPOSE_PROJECT_NAME=archone-allocation-t1 \
ARCHONE_POSTGRES_PORT=28491 ARCHONE_KAFKA_PORT=28492 ARCHONE_CONNECT_PORT=28493 \
ARCHONE_TEMPORAL_PORT=28494 ARCHONE_TEMPORAL_UI_PORT=28496 ARCHONE_KAFKA_UI_PORT=28497 \
E2E_APP_PORT=28490 \
KEEP_E2E_STACK=true ./e2e/spec/run.sh
```

省略 `E2E_JAVA_BIN` 時由 runner 偵測 Gradle toolchain。
KEEP_E2E_STACK 只保留基礎設施，runner 結束仍會停止其啟動的 app。
保留目的為 fixture 檢查；本次檢查後僅清理自己建立的 project，不影響既有 archone-karate-e2e。

## 7. 執行紀錄

執行時間：2026-09-09，Asia/Taipei。T1.1／T1.2 已完成，使用者於 2026-09-09 確認；T2 尚未實作。

| 檢查 | 結果 |
| --- | --- |
| Docker Server | 29.4.0；以獨立 archone-allocation-t1 project 執行 |
| Java | shell 預設 21；Gradle 偵測到 Temurin 25，runner 明確使用 Java 25 |
| monolith bootJar | BUILD SUCCESSFUL |
| 前端既有測試 | npm test：8 files、72 tests passed |
| catalog-and-idempotency | 3／3 passed |
| events-fulfillment | 5／5 passed |
| events-cancellation | 2／2 passed |
| connector-catch-up | 1／1 passed |
| events-shipment-cancellation | 1／1 passed |
| temporal-fulfillment | 3／3 passed |
| temporal-cancellation | 2／2 passed |
| 既有 HTTP E2E 合計 | 17／17 passed，7 features |
| 額外 Events 獨立性檢查 | Temporal target 指向 localhost:28495；建單成功至 FULFILLED，temporalWorkflow=null |
| 建單缺欄位實測 | 缺 releasePriority 為 400／Release priority is required；補 priority 後缺 dispatchBy 為 400／Dispatch deadline is required |
| UI fixtures HTTP 檢查 | 10 個 SKU 均無預留；兩個 FEFO SKU 各 4 批，在手 17、ATP 10、過期 7 |

實際完成回應見 [t1-contract-samples.json](t1-contract-samples.json)，從本次 Karate HTML 中的 JSON
response 擷取，保留現有 nullable 欄位，沒有加入尚未實作的 T2 欄位。可用它檢查 T3 型別與關聯。

- Events Order：`01a084f0-ff13-7000-8037-41e71939b078`。
- Temporal Order：`01a084f2-af2b-792f-a21f-35db4cc3a7b9`；phase=FINISHED、outcome=FULFILLMENT_COMPLETED。
- 額外 Events Order（Temporal target 不可用）：`01a084f5-6614-716c-889a-0a5a9bd464f6`。

本機完整報告：[Events](../../../e2e/spec/build/reports/events/karate-summary.html)、
[Temporal](../../../e2e/spec/build/reports/temporal/karate-summary.html)。報告是 ignored build artifacts，
可能被後續執行覆寫；此文件與 JSON samples 為本次保留的摘要證據。

未驗證／限制：

- 尚未修改或操作新版 UI；72 個既有測試通過也不能證明現有表單符合最新後端契約。
- T1 不新增瀏覽器功能，不完成 T7 的六個 UI 情境或新版查詢故障驗收。
- 額外檢查驗證 Events 無需可用 Temporal endpoint；runner 本身仍會啟動 Temporal 容器供後段測試。
- make dev-up／make dev-up-temporal 的 script／Compose 接線已核對；本次實際啟動使用相同 Compose
  基礎設施與獨立 Java monolith 的既有 E2E runner，沒有重啟使用者既有 dev deployment。
- T1 新增 fixture 資料已驗證，FEFO 的實際 UI 選批驗收留到 T7。
- UI fixture 是準備資料，不是保留的 live 測試環境；本次測試 app 已停止，專屬容器／volumes 於驗證後清理。


## 8. 審查入口與下一步

T1 僅交付契約、基線與資料準備。T2 預計新增 orchestrationMode／workflowQueryStatus，
目前它們仍不存在。T3～T6 的前端問題已定位但未修改，真實 UI 六情境留在 T7。
使用者已確認本文件與同步後的規劃，下一階段為 T2。
