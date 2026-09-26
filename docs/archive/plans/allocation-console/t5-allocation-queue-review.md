# T5 配貨佇列

- 日期：2026-09-09
- 狀態：實作完成，使用者已確認提交；未開始 T6。
- 前置：T4 已驗收並提交 d2929e1。

## 檢查方式

開啟 http://localhost:28295/allocations ，主選單也已加入「配貨佇列」。

1. 查看作業來源、貨主／設施／來源庫位、入列與最晚離倉時間、釋放優先級、作業與來源 ID。
2. 展開「SKU 需求明細」，查看每行 SKU、數量、Move ID 與前往庫存補貨入口。
3. 使用貨主、設施、來源或 SKU 篩選；僅篩選目前載入的資料，不另查後端。
4. 點「查看履約」開啟詳情，關閉仍回到原列表與 URL 篩選。
5. 手動重新整理佇列；關閉詳情不會自動刷新整個列表。

最多 200 筆。達上限時明確提示可能截斷；查詢失敗與沒有等待作業分開呈現，刷新失敗則保留舊資料並提醒可能過時。

## 功能範圍

- `AllocationsPage.tsx`：單次進場載入、手動刷新、AbortSignal 清理、篩選、作業與逐行需求、導航。
- `App.tsx`／`AppHeader.tsx`：加入新路由與選單。
- 使用既有 listConfirmedStockOperations、Catalog、FulfillmentDrawerRoute 與 T3 navigation helpers。
- 不輪詢整份佇列，只對目前開啟的訂單詳情追蹤。
- 作業數量是原始需求，不表示實際缺口；CONFIRMED 不一律標示為缺貨。
- 非 ORDER／PRIMARY 或非法來源 UUID 不提供訂單履約入口。
- 缺少主檔時仍呈現原始 ID，不猜測庫位；無法核對主檔則不提供定位補貨。
- 補貨連結帶貨主、設施、庫位、SKU、返回訂單與篩選；T6 才接入庫存頁自動預選與返回。
- 前序訂單阻擋仍依使用者決議另案處理，未改動後端規則。

## 驗證

```bash
npm --prefix frontend run build
npm --prefix frontend test -- --reporter=dot
git diff --check
```

- TypeScript／Vite build 通過。
- 15 個測試檔、156 個測試通過（T4 145 個，本次新增 11 個）。
- 覆蓋 200 筆限制、來源分支、空值、無主檔、讀取失敗／保留舊快照、組合篩選、展開、補貨 URL、
  詳情關閉保留篩選與卸載取消請求。
- 真實 Chrome：佇列載入 1 筆；SKU 篩選 UI-T5，展開明細，開啟實際 PENDING 訂單詳情，
  關閉後 URL 回到 /allocations?sku=UI-T5。
- 真實 Chrome：390×844 窄螢幕檢查，篩選垂直排列、卡片可讀；檢查後已還原 viewport。
- 本階段未重新執行 Temporal 端到端流程，完整雙模式矩陣仍留於 T7。

## 留給使用者檢查的獨立測試資料

原佇列已清空，因此在本次 archone-ui-dev 本機環境新增獨立 SKU 與一筆正常 API 建立的訂單：

- 貨主：甲貨主（OWNER-A），北部倉／庫存。
- SKU：UI-T5-QUEUE，規格名「T5 佇列驗證專用」，隸屬 P-TEA，未建立庫存。
- 上游單號：UI-T5-QUEUE-20260909。
- 數量：1；目前為 PENDING／CONFIRMED。
- orderId：01a085b3-ff20-773f-bc70-e5692db1d561。
- stockOperationId：01a085b4-01c0-74f9-9d4d-d83be0f8f10e。

只在開發環境新增該 SKU 的主檔，再經正常 POST /orders 建單；沒有直接修改業務狀態，
也沒有清除或重置既有資料。此 SKU 與原商品分離，不阻擋使用者原商品的配貨需求。
