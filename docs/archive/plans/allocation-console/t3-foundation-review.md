# T3 前端契約、狀態判定與追蹤機制

- 日期：2026-09-09
- 狀態：實作完成，使用者同意提交（2026-09-09）；未開始 T4。
- 前置：T2 已確認，commit `7ebc34e`。

## 實作範圍

| 檔案（相對 frontend/src） | 用途 |
| --- | --- |
| api/types.ts | 分開列表 OrderView 與 FulfillmentOrderView；新增作業、Shipment、Workflow 契約 |
| api/client.ts | 新增 getOrderFulfillment、listConfirmedStockOperations；唯讀函式支援 AbortSignal |
| fulfillment/progress.ts | 共用業務完成證據、Events／Temporal 成功停止條件及未知／取消提示 |
| hooks/useFulfillmentTracking.ts | 單一詳情追蹤、手動刷新／暫停／恢復、背景處理及舊回應隔離 |
| fulfillment/navigation.ts | 詳情開關、補貨往返、ID 與主檔歸屬驗證 |
| test/fixtures/fulfillment.json | 從 T1 真實樣本衍生的測試資料，補 T2 additive 欄位 |

沒有新增後端、套件、頁面或命令。既有 PlaceOrderCommand 的 dispatchBy／releasePriority 修復仍屬 T4.1。

## 完成判定

共同要求 Order FULFILLED、PRIMARY ORDER outbound 作業 DONE，以及 fulfilledByShipmentId 指向唯一
Shipment。核對 orderId、stockOperationId、貨主／設施，Shipment 必須為 HANDED_OVER_TO_CARRIER。
多 Shipment 不以任意一筆已交接代替；缺失或矛盾關聯保留「尚無法確認完成」。

Events 要求 NOT_APPLICABLE，不等待 Workflow。Temporal 另要求 AVAILABLE、FINISHED／
FULFILLMENT_COMPLETED、三個 IDs 一致及 shipmentTerminalStatus=HANDED_OVER。
Workflow 的 updatedAt 保持 phase 進入時間語意；前端另用 fetchedAt 記錄取得快照時間。
未知狀態與外部取消停止自動追蹤，仍能手動讀取；NOT_FOUND 保持 Temporal 模式並可繼續追蹤。

## Hook 的使用契約

`useFulfillmentTracking(orderId)`；傳 null 表示關閉。回傳 data、fetchedAt、loading、paused、error、
progress，以及 refresh／pause／resume。T4 串接時同一詳情只使用一個 hook。

- 首次開啟立即查詢；前次完成後等待 2 秒再查，不重疊，手動刷新也共用在途鎖。
- pause 停止後續排程；已送出的查詢仍可更新。refresh 執行一次查詢，尊重使用者暫停。
- resume 清除手動暫停／錯誤停止並立即查詢；完成或未知狀態會再次停止。
- 關閉、換訂單、卸載會取消請求並忽略晚到回應。背景取消讀取並暫停，前景在未手動暫停／停止時刷新。
- HTTP 錯誤或 HTTP 200 UNAVAILABLE 保留上一份 data／fetchedAt，設定 error 並停止排程。
  首次即 UNAVAILABLE 時仍提供本次可讀業務資料與取得時間，但必須搭配 error 提示。
- UI 必須呈現 error 與 paused，不能只依保留快照的 progress 判斷資料新鮮度。
- 不設定缺貨等待上限，不重新觸發建單／收貨／Workflow。

## URL 契約

- 詳情：`/orders?orderId=...`、`/allocations?orderId=...`；開關只修改 orderId，保留其餘 query params。
- 補貨：`/stock?ownerId=...&facilityId=...&locationId=...&sku=...&returnOrderId=...&returnPage=/allocations`。
- 返回篩選：return_ownerId、return_facilityId、return_sku、return_source、return_limit。
  returnPage 僅接受 `/orders`／`/allocations`，不接受外部 URL。
- UUID 無效不產生可用 context。SKU 經 URLSearchParams 編碼，保留特殊字元。
- validateReceiptContext 回傳 pending／valid／invalid；主檔未載入或還是其他貨主的資料時回 pending，
  不預選第一個庫位。呼叫方必須提供該貨主的 facilities／skus，再核對 location 的 facilityId。
- T4／T5／T6 負責接入真實路由、取得主檔及呈現錯誤；此階段測試 URL 函式與 MemoryRouter 歷史變化。

## 驗證

```bash
npm --prefix frontend run build
npm --prefix frontend test -- --reporter=dot
git diff --check
```

建置包含 TypeScript 型別檢查；120 個測試通過（原有 72 個、新增 48 個），共 12 個測試檔。
涵蓋 API 路徑與取消訊號、樣本型別、完成關聯、未知／取消、慢請求、背景切換、舊回應、
錯誤後保留快照、NOT_FOUND 長時間等待、手動操作、補貨 context 與上一頁／下一頁。

本階段未做真實雙模式瀏覽器驗收，尚未接入新 UI；完整操作驗收留給 T4～T7。

## 建議檢查順序

1. progress.ts：完成條件及未知／取消處理是否符合預期。
2. useFulfillmentTracking.ts：refresh／pause／resume 的語意及錯誤後保留資料。
3. navigation.ts：固定返回頁面與保留的篩選參數，供下一階段共用。

確認 T3 後才進入 T4 的建單修復與履約抽屜。
