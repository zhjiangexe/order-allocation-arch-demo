# T2 — 履約唯讀查詢補強

- 日期：2026-09-09
- 範圍：T2.1／T2.2；未修改前端、業務命令、事件契約或資料庫 schema。
- 狀態：T2 已完成，等待使用者檢查；T3 尚未開始。

## 查詢回應

沿用 `GET /demo/orders/{orderId}/fulfillment`，保留 order、stockOperation、shipments、
temporalWorkflow 四個欄位，新增 orchestrationMode 與 workflowQueryStatus。

| orchestrationMode | workflowQueryStatus | temporalWorkflow | 意義 |
| --- | --- | --- | --- |
| events | NOT_APPLICABLE | null | 此 deployment 不使用 Temporal；不取得 reader 或呼叫 Temporal |
| temporal | AVAILABLE | 現有 snapshot | state Query 成功，包含原有 updatedAt |
| temporal | NOT_FOUND | null | 訂單存在，但 Temporal 查無該 Workflow；可能尚未建立或已超過保留期 |
| temporal | UNAVAILABLE | null | 連線不可用或逾時，保留其餘業務查詢結果 |

UNAVAILABLE 不表示 Workflow 執行失敗。查無訂單仍回 HTTP 404，不會執行 Workflow Query。
模式沿用 OrderFulfillmentProperties 的 Driver enum，由 Spring 綁定並驗證；demo 的
OrderFulfillmentQueryConfiguration 將 enum 透過 constructor 傳給 Service，僅在 API 回傳時轉為小寫字串。
Properties 移至 fulfillment-process 的 configuration package，讓 bootstrap 與 demo 共用，維持既有架構依賴規則。
Service 不使用 @Value、不重新驗證模式，也不從 snapshot 推測。
若 Temporal 模式缺少必要 reader，視為組態錯誤，不降級成 Events。

## 直接查詢與故障邊界

依使用者審查意見，TemporalWorkflowStateReader 直接呼叫 workflow.state()，
沿用共用 Temporal client 既有 timeout／retry。沒有專用排程器、gRPC Context 或額外查詢期限。
FulfillmentQueryProperties、FulfillmentQueryConfiguration 與其設定／測試已移除。
ORDER_PROMISING_FULFILLMENT_QUERY_TIMEOUT 不再是應用程式支援的設定。

代價：服務不可用時可能等待 SDK 的重試與 timeout，才回傳 UNAVAILABLE；不再承諾獨立的
3 秒期限。Workflow／Activity／共用 client 的設定均未修改。

降級範圍：

- WorkflowNotFoundException → NOT_FOUND。
- WorkflowServiceException 僅在 gRPC cause 為 UNAVAILABLE 或 DEADLINE_EXCEEDED 時回 UNAVAILABLE。
- Query 拒絕、其他 service failure、程式錯誤或 null snapshot 繼續拋出，不一律轉成不可用。

降級寫入含 Workflow ID 與 exception 的伺服器 WARN log；HTTP 回應不帶 stack trace。
未新增 history 查詢、失敗工作流重試控制或新的操作端點。

## 簡化後驗證

- REST：既有業務欄位、兩模式新欄位、AVAILABLE／NOT_FOUND／UNAVAILABLE、未知訂單 404。
- Composition：Events 不呼叫 reader；Temporal 不同結果保留 Order／StockOperation／Shipment。
- Reader：直接取得 snapshot、查無 Workflow、連線不可用／逾時分類，以及非預期錯誤繼續拋出。
- Configuration：驗證預設 Events、Temporal 模式確實傳入 Service，以及非法值在組裝時被拒絕。
- monolith 全部測試與既有架構規則；bootstrap 與 demo 不互相依賴。
- 移除專用期限及排程器的測試，不再保留已刪除功能的驗收要求。

執行命令：

```bash
cd backend
./gradlew spotlessApply spotlessCheck :fulfillment-process:test :deployments:monolith:test --console=plain
```

2026-09-09 改用共用 enum 設定後：格式檢查通過；fulfillment-process 24 個、monolith 50 個測試，
全部 0 failures／errors，其中履約查詢相關測試 26 個。
本次沒有重跑整套雙模式 HTTP E2E，也未重測故障等待時間；目前不提供獨立耗時保證。

## 初版執行證據的適用範圍

[t2-query-samples.json](t2-query-samples.json) 是 T2 初版從真實 HTTP 擷取的四種回應，
其 JSON 欄位與狀態分類在簡化後維持相同，作為回應範例保留；不是本次簡化後重新擷取的資料。

初版曾通過 17 個雙模式 HTTP E2E，並在專用 500ms deadline 下停掉 Temporal 驗證
HTTP 200／UNAVAILABLE、業務資料保留及約 0.529 秒返回。
**該耗時僅屬已移除的初版實作，不能用來描述目前版本的等待時間。**
T1 原始基線與 samples 均未改寫。

## 使用者檢查方式

1. Reader 現在只有直接查詢及必要的兩類 exception 分支，沒有排程器與巢狀 context 管理。
2. 確認四種回應狀態的語意符合預期，接受沿用 SDK 等待設定。
3. 確認簡化後的 T2，再進入 T3；本次沒有開始前端開發。
