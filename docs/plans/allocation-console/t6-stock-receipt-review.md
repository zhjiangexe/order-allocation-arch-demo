# T6 庫存補貨往返

- 日期：2026-09-09
- 狀態：使用者驗收通過並確認提交（2026-09-09）；T7 未開始。
- 前置：T5 已提交 c6f5ef8。

## 檢查方式

1. 從訂單詳情或配貨佇列的品項按「前往庫存補貨」，核對貨主、設施、來源庫位與「本次補貨品項」。
2. 按該列「收貨」，核對日期、修改實收數量後才確認；日期沿用原有未過期批次預填規則。
3. 收貨成功保留 receiptId、SKU、數量、日期與實收結果；按「重新查詢」手動刷新庫存。
4. 按「返回履約」取得新快照，或「返回原佇列」只回列表，保留原篩選。
5. 需要再次收貨時按「開始另一筆收貨」，新操作在首次送出建立新 ID。

結果不明情境由測試模擬網路失敗：原請求／ID 保留，「重試原收貨」不改內容；
「另起收貨操作」需再次確認原收貨可能已入帳。站內離頁可留在本頁或明確離開；
重新載入／關閉使用瀏覽器原生提示。沒有持久化，因此離頁後無法恢復原命令。

## 實作範圍

- StockPage：驗證定位、查詢舊回應隔離、單次收貨命令、重試與返回導航。
- StockPanel：預選範圍、目標 SKU 標示、操作鎖定與實收結果說明。
- useCatalogState：公開 loading／error；既有 useCatalog 保留原介面，避免將尚未載入誤判為非法主檔。
- main：改用 createBrowserRouter／RouterProvider，讓 useBlocker 攔截站內連結與歷史導航。
- ConfirmStockReceiptDialog 沿用既有日期預填與數量驗證，無需修改。
- 無後端變更；收貨成功不是原訂單配貨完成，allocation 政策與排程維持原樣。

## 自動化驗證

```bash
npm --prefix frontend run build
npm --prefix frontend test -- --reporter=dot
git diff --check
```

- build 通過；15 個測試檔、170 個測試通過（T5 為 156，本次新增 14）。
- 延遲主檔、主檔失敗、非法 ID／歸屬／SKU／返回來源、直接進場無虛構返回連結。
- 正確庫位及原始收貨內容、相同 ID 重試、送出防重複、另起操作新 ID。
- 結果未確認時站內導航攔截、beforeunload；成功後解除提示及返回保留篩選。
- 既有日期預填、輸入驗證、手動庫存查詢測試持續通過。
- 測試使用 Node 原生 AbortController 配合 React Router 的 Node Request，避免 jsdom 跨 realm Signal 型別不相容。

## 實際瀏覽器驗證（Events）

既有 T5 測試訂單已由使用者完成，因此沿用獨立 SKU UI-T5-QUEUE 建立新需求 1 件，
透過正常 POST /orders 建單；再從真實 Chrome 的佇列入口收貨 1 件，未直接修改業務狀態。

- 上游單號：UI-T6-RECEIPT-20260909。
- orderId：01a085de-2002-77db-9828-244c2bd2555a。
- stockOperationId：01a085de-20fd-7bdf-bd34-3a1458759bac。
- receiptId：c3ab0cea-aca7-4c9a-895e-93cae141437f。
- 貨主／設施／來源庫位：甲貨主／北部倉／北部倉庫存。
- 日期預填：2026-09-09／2026-10-07，實收數量手動改為 1。
- 收貨成功畫面顯示原查詢快照與實收 1 件，沒有自動宣稱訂單完成。
- 返回 `/allocations?sku=UI-T5&orderId=01a085de-2002-77db-9828-244c2bd2555a`，篩選保留。
- 最終 FULFILLED／DONE／HANDED_OVER_TO_CARRIER；完成時間 2026-09-09 19:13:28（Asia/Taipei）。
- Shipment：01a085df-4aa5-7de7-8ef5-2ee51974129d。

本階段未執行 Temporal 瀏覽器情境；完整雙模式矩陣仍由 T7 處理。
