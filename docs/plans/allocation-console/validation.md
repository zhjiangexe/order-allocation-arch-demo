# Allocation console 最終驗收紀錄

日期：2026-09-09（Asia/Taipei）。T1～T6、T7.1 Events 已由使用者確認；使用者指示進入 T7.3，
完成 T7.2 Temporal 檢查點。T7.3 文件整併完成；使用者於 2026-09-09 指示 commit，最終檢查點通過。

本次範圍為 allocation 正常履約：前端建單、缺貨補貨、整單分配、FEFO、追蹤完成及 Temporal 跳轉。
沒有新增取消 UI、手動 WMS 作業或配貨政策。既有 cancellation HTTP 回歸保留。

## 環境與證據

| 項目 | Events | Temporal |
| --- | --- | --- |
| Compose project | archone-allocation-t7-events | archone-allocation-t7-temporal |
| 前端 | http://localhost:28495/orders | http://localhost:28595/orders |
| 後端 | localhost:28490 | localhost:28590 |
| PostgreSQL／Kafka／Connect | 28491／28492／28493 | 28591／28592／28593 |
| Temporal gRPC／UI | 未啟動 | 28594／28596 |
| fixtures | UI-EVT-* | UI-TMP-* |
| 詳細步驟、啟動與 runner 命令 | [Events review](t7-events-review.md) | [Temporal review](t7-temporal-review.md) |
| 初始、等待、完成與庫存快照 | [Events samples](t7-events-samples.json) | [Temporal samples，含 Workflow history](t7-temporal-samples.json) |

兩模式使用全新且彼此隔離的資料庫，沒有重置使用者既有環境或交接未完成訂單。
HTTP 正常路徑 WMS delay 0s、取消窗口 30s；UI 使用 10s。Temporal namespace 為 `default`，
Task Queue 為 `order-fulfillment-workflows`，Workflow Type 為 `OrderFulfillmentWorkflow`。
環境在交付時保留供檢查；本機程序與記憶體型 Temporal dev server 不保證重啟後仍保留現況。

## 回歸結果與重現命令

| 檢查 | 實際結果 |
| --- | --- |
| 前端 | T7.1 170 tests；Workflow 連結加入後 171 tests，typecheck／build 通過 |
| monolith | 強制重跑 50 tests 通過 |
| orchestration-temporal-runtime | 強制重跑 66 tests 通過 |
| fulfillment-process | 強制重跑 24 tests 通過 |
| Events HTTP | 12／12 scenarios，含 catalog、履約、取消與 connector catch-up |
| Temporal HTTP | 8／8 scenarios，含 catalog、履約與取消 |
| 真實 Chrome | 每模式 4 筆建單、2 次補貨，共 8 筆訂單完成 |

前端與指定後端模組命令：

```bash
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run build
cd backend
./gradlew :deployments:monolith:test --rerun --console=plain
./gradlew :orchestration-temporal-runtime:test --rerun :fulfillment-process:test --rerun --console=plain
```

HTTP 使用 `E2E_MODE=events`／`temporal` 分開執行；完整環境變數命令見各模式 review，
runner 用法見 [e2e/spec/README.md](../../../e2e/spec/README.md)。總計 20 次 scenario 執行，
包含兩模式各跑一次的 3 個共用 catalog 情境；不代表 20 個互異案例，也不是整個後端全模組測試。

本機產物為 `e2e/spec/build/t7-events/reports/`、`e2e/spec/build/t7-temporal/reports/`；
log 在 `/private/tmp/t7-events-*.log`、`/private/tmp/t7-temporal-*.log`，
連結增補檢查為 `/private/tmp/temporal-link-check.log`。這些不是版控保存的長期附件；
可持續查閱的結果與 API／Workflow 快照保存在上列 review／samples。

T7.3 僅整理文件，未再修改產品程式，因此沿用上述已完成的回歸結果。
文件檢查採本地 Markdown 連結核對、`git diff --check`、`bash -n e2e/spec/run.sh`。

## 真實 UI 步驟與完成條件

每模式均從 Chrome 表單建單，收貨亦由 UI 送出；唯讀 HTTP／Temporal CLI 用於交叉核對與保存證據。

| 情境 | UI 操作與核對 |
| --- | --- |
| HAPPY 有貨 | 初始有效量 20，建單 4 件；開啟詳情，追蹤分配、WMS 模擬交運、出庫與履約回寫。 |
| WAKE 缺貨 | 初始 0，建單 3 件；確認 CONFIRMED、沒有批次／Shipment。佇列篩選後前往補貨 3，返回保留篩選，原訂單完成。 |
| MULTI 整單 | A 初始 10、B 初始 0，下 A 2＋B 3；等待期間兩行皆無批次、預留 0。只補 B 3，兩行分配並完成。 |
| FEFO | 初始在手 17／ATP 10／過期 7，下 4；有效批 2026-01-01 與 2026-01-02 入庫、同為 2098-12-31 效期，各取 2；過期 7 未動。 |

所有訂單核對 Order `FULFILLED`、StockOperation `DONE`、所屬 Shipment
`HANDED_OVER_TO_CARRIER`，包含 ORDER／PRIMARY 來源及 fulfilledByShipmentId 等關聯。
Events 為 `NOT_APPLICABLE`，無 Workflow；Temporal 為 `AVAILABLE`、`FINISHED`／
`FULFILLMENT_COMPLETED`、`COMMITTED`／`HANDED_OVER`，Workflow 與業務 IDs 一致。
FEFO 按批次身分及數量核對，履約回應陣列順序不是取批順序保證。

另已確認深連結重新載入、關閉返回、補貨往返與篩選、完成後顯示停止追蹤、當前 FULFILLED 綠色。
最晚離倉本地輸入 `2026-09-10T12:00`，API 為 `2026-09-10T04:00:00Z`，UI 正確還原。

## 訂單 IDs

完整上游單號格式為 `UI-{EVT|TMP}-{情境}-T7-20260909`。
StockOperation、Shipment 與 receiptId 詳見各模式 review；以下可直接在對應環境開啟詳情。

| 模式 | 情境 | Order ID |
| --- | --- | --- |
| Events | HAPPY | 01a085f9-b40c-78f5-8186-0c44fd992dfe |
| Events | WAKE | 01a085fb-1a44-7a3d-b3d7-589ec8b5b20b |
| Events | MULTI | 01a085fd-7120-7bae-bb9f-c1935bffafef |
| Events | FEFO | 01a08600-af54-7af5-a1a0-53c3d0bf523a |
| Temporal | HAPPY | 01a08615-aef7-7b8d-b74d-9ca6d456cf0f |
| Temporal | WAKE | 01a08617-38c7-73be-96f0-422347cca073 |
| Temporal | MULTI | 01a0861a-7f20-7e97-abf6-e257bb25b063 |
| Temporal | FEFO | 01a0861e-38a3-71c6-8ebb-93afb127a133 |

Temporal Workflow ID 均為 `order-fulfillment/{orderId}`。四筆 history 皆有
`stockOperationAssigned`、`shipmentHandedOverToCarrier` Signal，以及
RequestOrderAllocation、CreateWmsShipment、CompleteOutboundMovements、RecordOrderFulfillment
Activity，最後為 `EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED`。
HAPPY 已實際由詳情連結開啟新分頁 Timeline，正確導向 runId
`01a08615-b19c-7d98-aed0-5b7102c508d2`，顯示 Completed。

## 驗證界線與剩餘限制

- 真實 Temporal UI 驗證了 seed 無 Workflow 的 `NOT_FOUND` 與連結保留；未人工延遲新流程啟動。
- 真實觀察 Shipment 已交運而作業／Workflow 尚未完成時，UI 仍維持進行中，沒有提前宣稱完成。
- `UNAVAILABLE`／`DEADLINE_EXCEEDED`、晚到回應、關閉取消請求與背景暫停由測試覆蓋；
  沒有實際停掉 Temporal 注入故障，也沒有額外量測瀏覽器請求數。不能把測試覆蓋當成真實故障演練。
- HTTP 包含既有取消案例，但本次沒有取消 UI，亦未驗證 production 部署、負載或在途流程跨模式遷移。
- 標準 Compose／Make 啟動說明依設定與腳本核對；本次 UI 環境採 E2E 基礎設施加 Java 程序，
  不宣稱 T7 另外重跑了標準 `make dev-up*` 部署。
- 前序訂單可能阻擋後序訂單，使用者已決定另案處理；補貨不保證優先供給指定訂單。
- 共用契約測試及 dev seed 仍有等待資料，留在各自環境；八筆 UI 驗收訂單皆已完成。
- 收貨待確認命令不跨瀏覽器 reload 保存；佇列最多 200 筆，非完整排名或精確缺口報表。
- 全新重跑須用獨立資料庫；fixtures 的 `ON CONFLICT` 不會還原已消耗庫存。

## 最終檢查點

- [x] 雙模式回歸與 UI 證據整併。
- [x] 更新 [前端操作說明](../../../frontend/README.md)、[根 README](../../../README.md)、[部署說明](../../../docker/README.md)。
- [x] 更新 plan／tasks，區分執行完成與使用者確認。
- [x] 使用者確認 T7.3 與整體最終成果（2026-09-09 指示 commit）。
