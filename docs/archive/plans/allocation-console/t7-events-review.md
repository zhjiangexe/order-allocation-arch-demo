# T7.1 Events 驗證

- 日期：2026-09-09，Asia/Taipei。
- 狀態：本檢查點已由使用者確認；以下保留 Events 階段的執行紀錄。最終進度見 [validation.md](validation.md)。
- 程式基線：d46b2b7（T6 功能提交為 97e1b5f）；本階段只修改 E2E runner／文件，未改產品程式。

## 環境與命令

使用全新 Compose project `archone-allocation-t7-events`，資料庫由 dev migration 與既有
`e2e/spec/fixtures/e2e-catalog.sql` 建立。未重置 archone-ui-dev 或 archone-karate-e2e。

- 前端：http://localhost:28495；後端：http://localhost:28490。
- PostgreSQL 28491、Kafka 28492、Connect 28493。
- 未啟動此 project 的 Temporal；app target 指向未啟動的 localhost:28494，Events 成功不依賴 Temporal。
- HTTP 正常情境 WMS delay 0s、取消窗口 30s；UI 情境 10s。
- UI 與 Karate 使用不同 SKU；UI-EVT-* 在瀏覽器驗證前未消耗。

共用回歸：

```bash
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run build
cd backend
./gradlew :deployments:monolith:test --rerun --console=plain
```

HTTP runner：

```bash
COMPOSE_PROJECT_NAME=archone-allocation-t7-events \
ARCHONE_POSTGRES_PORT=28491 ARCHONE_KAFKA_PORT=28492 ARCHONE_CONNECT_PORT=28493 \
ARCHONE_TEMPORAL_PORT=28494 ARCHONE_TEMPORAL_UI_PORT=28496 ARCHONE_KAFKA_UI_PORT=28497 \
E2E_APP_PORT=28490 E2E_MODE=events \
E2E_BUILD_DIR=e2e/spec/build/t7-events \
KEEP_E2E_STACK=true ./e2e/spec/run.sh
```

runner 完成會停止 app；UI 驗證另以前景常駐程序啟動：

```bash
ORDER_PROMISING_PORT=28490 \
ORDER_PROMISING_DB_URL=jdbc:postgresql://localhost:28491/order_promising \
ORDER_PROMISING_KAFKA_BOOTSTRAP_SERVERS=localhost:28492 \
ORDER_PROMISING_FULFILLMENT_ORCHESTRATION_MODE=events \
ORDER_PROMISING_TEMPORAL_TARGET=localhost:28494 \
ORDER_PROMISING_WMS_SIMULATION_PROCESSING_DELAY=10s \
java \
-jar backend/deployments/monolith/build/libs/archone-monolith.jar --spring.profiles.active=dev

ARCHONE_BACKEND_ORIGIN=http://localhost:28490 \
npm --prefix frontend run dev -- --host 127.0.0.1 --port 28495
```

需使用 Java 25；全新重跑須選新 project／資料庫，不能重用已消耗的 fixture 當初始資料。
目前保留此環境供使用者檢查。

## 回歸結果

| 檢查 | 結果 |
| --- | --- |
| 前端 | 15 個測試檔、170 tests passed；typecheck／build 通過 |
| monolith | 強制重跑 50 tests，0 failures／errors／skipped |
| catalog-and-idempotency | 3／3 |
| events-fulfillment | 5／5 |
| events-cancellation | 2／2 |
| connector-catch-up | 1／1 |
| events-shipment-cancellation | 1／1 |
| Events HTTP 合計 | 12／12，既有取消案例保留 |
| runner 語法／patch | bash -n、git diff --check 通過 |

報告在 `e2e/spec/build/t7-events/reports/`；前後端 log 在 `/private/tmp/t7-events-*.log`。
完整快照保存在 [t7-events-samples.json](t7-events-samples.json)，不依賴 ignored reports 的保存期限。

## 真實 Chrome 情境

四筆訂單都從 UI 表單送出；兩筆補貨也從 UI 收貨視窗送出。
唯讀 HTTP 僅補充保存與核對狀態、關聯 IDs 和庫存，不代替主要操作。

| 上游單號 | UI 操作與觀察 | 結果 |
| --- | --- | --- |
| UI-EVT-HAPPY-T7-20260909 | 初始有效量 20，下 4；看到 ALLOCATED／ASSIGNED／Shipment CREATED，再自動完成 | FULFILLED／DONE／HANDED_OVER_TO_CARRIER |
| UI-EVT-WAKE-T7-20260909 | 初始 0，下 3；PENDING／CONFIRMED、無批次／Shipment；佇列篩選後前往補貨 3 | 原訂單完成，返回保留 sku=UI-EVT-WAKE |
| UI-EVT-MULTI-T7-20260909 | A 初始 10、B 初始 0；下 A 2＋B 3，兩行皆無批次，庫存預留均 0；只補 B 3 | 兩行同時分配，Shipment 2 行並完成 |
| UI-EVT-FEFO-T7-20260909 | UI 初始在手 17／預留 0／ATP 10／過期 7；下 4 | 兩批各取 2，過期 7 件未消耗，完成履約 |

所有訂單最晚離倉輸入為 `2026-09-10T12:00`（Asia/Taipei），API 儲存
`2026-09-10T04:00:00Z`，詳情還原下午 12:00，沒有偏移。

FEFO 所取批次：

| stockQuantId | 入庫日 | 效期 | 分配量 |
| --- | --- | --- | --- |
| 027b2658-1739-3dcd-d450-950ee5aad1d9 | 2026-01-01 | 2098-12-31 | 2 |
| da26ed56-3530-a329-7db3-6e37607c4208 | 2026-01-02 | 2098-12-31 | 2 |

補貨 receiptId：WAKE `811dc626-1d89-4d0e-9e4d-4223f420e217`；MULTI-B
`b6b99b78-286f-45d5-ac33-97d077a45e61`。兩者實收均 3 件，日期沿用現有批次 2026-01-01／2099-12-31。

## 訂單與關聯 IDs

| 情境 | Order | StockOperation | Shipment |
| --- | --- | --- | --- |
| HAPPY | 01a085f9-b40c-78f5-8186-0c44fd992dfe | 01a085f9-b847-75e3-aa4f-0da0451caf48 | 01a085f9-bc4a-7ac8-90df-64b2406654d1 |
| WAKE | 01a085fb-1a44-7a3d-b3d7-589ec8b5b20b | 01a085fb-2116-71d7-97d4-b6a4ef428a3f | 01a085fc-4bab-792b-a5e9-bcdb7c71746c |
| MULTI | 01a085fd-7120-7bae-bb9f-c1935bffafef | 01a085fd-7496-7b0a-879c-066b176fb7e7 | 01a085fe-f85a-75df-b160-ac905a8059ee |
| FEFO | 01a08600-af54-7af5-a1a0-53c3d0bf523a | 01a08600-b162-74f9-8a2c-f7471e44c3d8 | 01a08600-bd03-73ca-9f4f-eaaf893f8cab |

四筆皆核對 `fulfilledByShipmentId`、Shipment 的 orderId／stockOperationId 與 ORDER／PRIMARY 來源一致。
全部 `orchestrationMode=events`、`workflowQueryStatus=NOT_APPLICABLE`、`temporalWorkflow=null`。

## 導航、追蹤與限制

- 真實 UI 已驗：訂單成功自開詳情、關閉回原列表、配貨佇列篩選與補貨返回、直接重新載入深連結恢復原詳情。
- 四筆最終皆顯示「自動追蹤已停止」；狀態列 FULFILLED 為綠色。
- 關閉／卸載取消請求且不再排程、背景暫停、晚到回應隔離及資料不同步不誤判完成，由本次通過的 tracking／progress 測試驗證；沒有另用網路攔截器量測瀏覽器請求次數。
- 未在 production 注入查詢錯誤。NOT_FOUND／UNAVAILABLE 等覆蓋來自既有查詢與元件測試，不能視為 Temporal 的真實 UI 證據。
- 驗證期間一次瀏覽器自動審核逾時；重讀頁面並在 runner 結束後重啟 app，未重複提交命令。
- 本階段沒有產品缺陷需要修正，未改 allocation 政策或排程。
- 本次四筆 UI 訂單均完成。共用契約測試留下的重複單號案例 PENDING 與 dev seed 訂單保留在此 Events 環境；不移交 Temporal，不影響 UI 專用 SKU。
- T7.2 需全新 Temporal 隔離環境；此次不啟動、不執行 Temporal 情境。

## 使用者檢查

開啟 http://localhost:28495/orders ，找四筆 UI-EVT-*-T7-20260909，查看完成狀態與批次。
FEFO 詳情應有 2＋2；MULTI 應有兩行，WAKE 補貨返回篩選的證據見上文及 samples。
T7.1 使用者確認後才進入 T7.2。
