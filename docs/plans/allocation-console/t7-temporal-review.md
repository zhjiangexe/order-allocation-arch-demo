# T7.2 Temporal 驗證

- 日期：2026-09-09，Asia/Taipei。
- 狀態：使用者已指示進入 T7.3；以下保留 Temporal 階段的執行紀錄。最終進度見 [validation.md](validation.md)。
- 前置：使用者確認繼續 T7，沿用 T7.1 共用回歸；Workflow 連結增補後前端 171 tests 與 build 已通過。
- 本階段沒有新增產品程式修正。

## 隔離環境

- Compose project：`archone-allocation-t7-temporal`，全新資料庫，未接手 Events 訂單。
- 前端：http://localhost:28595；後端：http://localhost:28590。
- PostgreSQL 28591、Kafka 28592、Connect 28593、Temporal gRPC 28594、Temporal UI 28596。
- namespace：default；Workflow Type：OrderFulfillmentWorkflow；Task Queue：order-fulfillment-workflows。
- 沿用 T1 UI-TMP-* fixtures，與 HTTP 測試 SKU 分離；未重置使用者既有環境。
- Events 四筆 UI 測試均已完成；原 Events seed／契約測試等待資料留在原隔離環境。

## 執行命令與測試結果

```bash
cd backend
./gradlew :orchestration-temporal-runtime:test --rerun :fulfillment-process:test --rerun --console=plain
```

runtime 66 tests、fulfillment-process 24 tests，全部重新執行通過，0 failures／errors／skipped。
T7.1 的 monolith 50 tests 及連結增補後的前端 171 tests／build 均通過，期間無產品程式再次修改。

HTTP runner（repo root）：

```bash
COMPOSE_PROJECT_NAME=archone-allocation-t7-temporal \
ARCHONE_POSTGRES_PORT=28591 ARCHONE_KAFKA_PORT=28592 ARCHONE_CONNECT_PORT=28593 \
ARCHONE_TEMPORAL_PORT=28594 ARCHONE_TEMPORAL_UI_PORT=28596 ARCHONE_KAFKA_UI_PORT=28597 \
E2E_APP_PORT=28590 E2E_MODE=temporal \
E2E_BUILD_DIR=e2e/spec/build/t7-temporal \
KEEP_E2E_STACK=true ./e2e/spec/run.sh
```

| Feature | 結果 |
| --- | --- |
| catalog-and-idempotency | 3／3 |
| temporal-fulfillment | 3／3 |
| temporal-cancellation | 2／2 |
| 合計 | 8／8，包含既有取消案例 |

報告：`e2e/spec/build/t7-temporal/reports/`；log：`/private/tmp/t7-temporal-*.log`。
runner 結束停止 app 後，以前景常駐程序啟動 UI 驗證環境：

```bash
ORDER_PROMISING_PORT=28590 \
ORDER_PROMISING_DB_URL=jdbc:postgresql://localhost:28591/order_promising \
ORDER_PROMISING_KAFKA_BOOTSTRAP_SERVERS=localhost:28592 \
ORDER_PROMISING_FULFILLMENT_ORCHESTRATION_MODE=temporal \
ORDER_PROMISING_TEMPORAL_TARGET=localhost:28594 \
ORDER_PROMISING_WMS_SIMULATION_PROCESSING_DELAY=10s \
java \
-jar backend/deployments/monolith/build/libs/archone-monolith.jar --spring.profiles.active=dev

ARCHONE_BACKEND_ORIGIN=http://localhost:28590 \
VITE_TEMPORAL_UI_URL=http://localhost:28596 VITE_TEMPORAL_NAMESPACE=default \
npm --prefix frontend run dev -- --host 127.0.0.1 --port 28595
```

以上命令使用 PATH 上的 Java 25。HTTP 情境 delay 0s／取消窗口 30s；UI 使用 10s。
目前保留 app、前端及隔離容器供使用者檢查。

## 真實 UI 驗證

四筆訂單均由 Chrome 表單送出，兩筆補貨均由收貨視窗送出；HTTP 與 CLI 僅保存及核對證據。

| 上游單號 | 過程 | 最終結果 |
| --- | --- | --- |
| UI-TMP-HAPPY-T7-20260909 | 初始有效量 20，下 4；觀察 ALLOCATED、WAREHOUSE_EXECUTION，再到終態 | 業務完成＋Workflow FINISHED |
| UI-TMP-WAKE-T7-20260909 | 初始 0，下 3；ALLOCATION／REQUESTED、CONFIRMED、無批次或 Shipment；佇列補貨 3 | 原 Workflow 完成，保留 sku=UI-TMP-WAKE 返回篩選 |
| UI-TMP-MULTI-T7-20260909 | A 初始 10、B 初始 0；下 A 2＋B 3，兩行無批次、庫存預留 0；只補 B 3 | 同時分配兩行，Shipment 2 行，Workflow 完成 |
| UI-TMP-FEFO-T7-20260909 | UI 在手 17／預留 0／ATP 10／過期 7，下 4 | 有效批各取 2，過期批保留 7，Workflow 完成 |

四筆共同核對：

- Order FULFILLED、StockOperation DONE、所屬 Shipment HANDED_OVER_TO_CARRIER。
- orchestrationMode=temporal、workflowQueryStatus=AVAILABLE。
- Workflow phase=FINISHED、outcome=FULFILLMENT_COMPLETED、allocationState=COMMITTED、shipmentTerminalStatus=HANDED_OVER。
- 訂單 fulfilledByShipmentId、Workflow orderId／stockOperationId／shipmentId 與業務證據一致。
- 最晚離倉 UI 輸入 2026-09-10T12:00（Asia/Taipei），API 為 2026-09-10T04:00:00Z；詳情還原正確。
- 完成後顯示「自動追蹤已停止」；重新載入 FEFO 深連結可恢復同一筆詳情與終態。

FEFO 取用：2026-01-01 入庫、2098-12-31 效期批 2 件，以及 2026-01-02 入庫、相同效期批 2 件。
履約回應的批次顯示順序不是取批順序保證；以批次身分與分配量核對，不將陣列順序當成演算法證據。

## Workflow 跳轉與歷史

已實際按「在 Temporal UI 查看 Workflow（新分頁）」：

- 原操作台留在該筆履約，新分頁開啟 localhost:28596 的 default namespace。
- UI 將無 runId 的連結導到正確 run 的 Timeline。
- HAPPY Workflow 顯示 Completed，Type 為 OrderFulfillmentWorkflow。
- HAPPY runId：01a08615-b19c-7d98-aed0-5b7102c508d2。
- Timeline 可見 RequestOrderAllocation → stockOperationAssigned → CreateWmsShipment → shipmentHandedOverToCarrier → CompleteOutboundMovements → RecordOrderFulfillment。

四筆 Workflow 都用唯讀 CLI 保存 Event History，確認兩個 Signal 及成功結束事件：

```bash
docker exec archone-allocation-t7-temporal-temporal-1 \
  temporal workflow show --namespace default \
  --workflow-id order-fulfillment/ORDER_ID --output json
```

每筆都有同樣四個 Activity，最後為 EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED。
WAKE 的完整歷史保留從等待到補貨 Signal 再完成的同一執行，不是另建第二張訂單。

## 關聯 IDs

| 情境 | Order | StockOperation | Shipment |
| --- | --- | --- | --- |
| HAPPY | 01a08615-aef7-7b8d-b74d-9ca6d456cf0f | 01a08615-b26f-7da9-8050-fae3ed5bf312 | 01a08615-b56b-7d7c-a19c-fdac84247493 |
| WAKE | 01a08617-38c7-73be-96f0-422347cca073 | 01a08617-3cbc-798d-905c-86e7ac9a1c4f | 01a08619-289d-7f12-a5b3-df9daa529097 |
| MULTI | 01a0861a-7f20-7e97-abf6-e257bb25b063 | 01a0861a-8319-794b-b623-706dad2295e5 | 01a0861c-a10b-729f-8430-43bd48fbd010 |
| FEFO | 01a0861e-38a3-71c6-8ebb-93afb127a133 | 01a0861e-3b7f-7922-af0a-12e1dc82bc55 | 01a0861e-3f25-7287-8ea7-c61ac9356bb9 |

receiptId：WAKE 2ce58c93-6af8-4312-9da7-21cc6964310f；MULTI-B 92e396d0-860a-4c48-b8be-10f150ceb392。
均實收 3 件，日期 2026-01-01／2099-12-31。

完整初始量、等待與完成快照、Workflow 歷史：[t7-temporal-samples.json](t7-temporal-samples.json)。

## 異常與驗證邊界

- 實際 UI 開啟無 Workflow 的 dev seed SEED-A-0001，顯示 NOT_FOUND 與「查無 Workflow，可能尚未建立；不保證稍後一定啟動」，仍保留 Temporal 模式及跳轉入口。
- 初始 NOT_FOUND 是既有 seed 無對應流程，不是刻意延遲新 Workflow；新流程啟動競態由測試覆蓋。
- HAPPY 曾呈現 Shipment 已交運但 StockOperation／Workflow 尚在推進，UI 仍顯示進行中，未提前宣稱整體完成。
- UNAVAILABLE／DEADLINE_EXCEEDED、晚到回應隔離、關閉取消請求及停止排程由已通過的 monolith／tracking／progress 測試涵蓋；沒有實際停掉 Temporal 或注入 production 故障，亦未另量測瀏覽器網路請求數。
- 同一隔離環境的共用單號冪等測試會留下 PENDING Workflow，dev seed 也保留原樣；四筆 UI 驗收流程皆已完成，不清理或接手這些額外資料。
- 本文件記錄 Temporal 檢查點成果；T7.3 文件整併見 validation.md。

## 使用者檢查入口

開啟 http://localhost:28595/orders，查看四筆 UI-TMP-*-T7-20260909。
除業務完成狀態外，確認 Temporal 區塊 FINISHED／FULFILLMENT_COMPLETED，並按連結開啟同一筆 Workflow。
FEFO 應為 2＋2，MULTI 為 A 2／B 3。使用者確認後再進入 T7.3。
