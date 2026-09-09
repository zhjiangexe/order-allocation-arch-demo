# T4 建單修復與履約抽屜

- 日期：2026-09-09
- 狀態：使用者已確認 T4 驗收通過（2026-09-09）；依使用者要求提交，未開始 T5。
- T3 commit：4e0ac2d。

## 使用者可直接檢查

開啟 http://localhost:28295/orders ，後端目前使用 Events 模式。

1. 建單新增「最晚離倉時間」與「出庫釋放優先級」。前者自行填寫，顯示當地時區；後者預設 0。
2. 選擇貨主、設施與商品，填寫新上游單號後送出；成功會自動開啟履約抽屜。
3. 原「履約完成」區塊改為 PENDING／ALLOCATED／FULFILLED／CANCELLED 的橫列，滑鼠移過或鍵盤聚焦狀態即顯示自訂說明框；目前狀態使用綠字與 aria-current。訂單、庫存作業、Shipment、Workflow 與技術資訊之間加上分隔線及留白。未知、等待或取消提示另保留於該區塊下方。可看分配批次、Shipment 與完成狀態，並操作重新查詢、暫停／恢復、複製 IDs。
4. 列表「查看履約」可再次開啟；Esc／關閉返回列表，保留篩選參數與焦點。
5. 可直接查看此次已完成測試單：
   http://localhost:28295/orders?orderId=01a0856a-a495-7ed4-ba9e-94d283e1f090

列表不隨詳情輪詢，關閉後仍保留原快照，可按列表重新整理。種子訂單僅作歷史展示，
不要以 seed 狀態判斷新建單流程；本次驗證使用新上游單號。

## 實作與邊界

- PlaceOrderForm／PlaceOrderCommand：新增兩個必填欄位，地址／分區／日期／時間與優先級驗證，
  送出中鎖定整份表單；同 SKU 多行保留獨立 identity。
- OrdersPage：在途鎖防重送。400 類輸入錯誤可修正；409／408／5xx／網路錯誤視為需確認，
  保留原貨主、單號、payload，提供最近 20 筆查單。查不到不代表未建立，不自動重送。
  使用者可明確開始另一張空白訂單；未建立跨重載的命令保存。
- FulfillmentDrawer：原生 modal dialog 負責焦點限制，Esc 與關閉清理追蹤並返回觸發元件，
  觸發元件不可用時回到列表標題。窄螢幕使用全寬。
- FulfillmentDetails：純內容元件，呈現業務資料、分配批次、Shipment 與模式；Temporal 才顯示
  Workflow 查詢狀態／phase／outcome／updatedAt（階段進入時間）。HTTP 404 明確顯示找不到訂單。
- 既有 T3 的錯誤保留與成功 predicate 直接沿用；未新增後端 API、排程器或套件。
- 共用 route 元件對 /orders 與 /allocations context 都有測試。T5 才建立實際佇列頁與選單。
- 每行的補貨連結帶 T3 context，僅在主檔與 PRIMARY ORDER outbound 來源相符時顯示。
  目前庫存頁仍須手動選擇貨主／設施／庫位／SKU；自動定位及返回詳情留在 T6。

## 驗證結果

```bash
npm --prefix frontend run build
npm --prefix frontend test -- --reporter=dot
git diff --check
```

- TypeScript／Vite build 通過。
- 14 個測試檔、145 個測試通過（T3 為 120 個，本次新增 25 個）。
- 覆蓋時間轉換、優先級上下界／非法值、必填驗證、表單鎖定、建單後開啟、409／網路結果不明查單，
  Events／Temporal 顯示、空值、NOT_FOUND／404／UNAVAILABLE、路由關閉、AbortSignal、焦點返回與 IDs 複製。
- 真實 Chrome：新建訂單 → 自動開啟抽屜 → 分配批次與 Shipment → 履約完成／停止追蹤。
- 真實 Chrome：既有列表入口、詳情 URL 重新載入、Esc 關閉與 dialog 內鍵盤焦點。
- 390×844 viewport：dialog 寬 390px、scrollWidth 389px，無抽屜水平溢出；檢查後還原 viewport。

## 真實後端契約與 Events 證據

見 [t4-http-samples.json](t4-http-samples.json)。此檔記錄本次真實回應，非 mocks：

- 缺 releasePriority：HTTP 400，`Release priority is required`。
- 缺 dispatchBy：HTTP 400，`Dispatch deadline is required`。
- 瀏覽器建單：UI-T4-EVENTS-20260909-1705。
- orderId：01a0856a-a495-7ed4-ba9e-94d283e1f090。
- stockOperationId：01a0856a-a795-75df-9100-efa89001567f。
- shipmentId：01a0856a-a940-7995-abd4-f88db8ffaab1。
- 當地輸入 2099-01-01 12:00（Asia/Taipei），後端 dispatchBy 為 2099-01-01T04:00:00Z；releasePriority=0。
- 結果：FULFILLED／DONE／HANDED_OVER_TO_CARRIER、Events／NOT_APPLICABLE。

使用 archone-ui-dev 本機環境及既有 WMS simulator（10 秒 processing delay）。本次只新增一張正常
測試訂單並消耗 SKU-AVAILABLE 1 件，未重置舊資料。

Temporal 本階段使用 T1 真實衍生 fixtures 做元件與狀態驗證，尚未重新跑真實 Temporal 瀏覽器流程。
完整雙模式、有貨／缺貨補貨／多 SKU 的實際操作矩陣由 T7 驗收。


## 使用者補貨驗證追查：PO-99991

2026-09-09 使用者回報缺貨補貨後未完成，唯讀追查結果如下；當時此情境尚未通過，後續結果見下方驗收結論：

- 訂單 01a0858e-bef9-71f7-86d7-b57cbfa255a5：乙貨主、南部倉／庫存、SKU-AVAILABLE 10000 件。
- 09:46:21 UTC 收貨 10001 件已入帳；有效批次 ATP 10051、reservedQuantity=0。
- StockAvailabilityIncreasedIntegrationEvent 01a0858f-b05f-7343-b6a6-f8804ad0c60e 已被
  allocation-inventory-events 寫入 inbox，connector／task 均 RUNNING。
- 前序 SEED-B-0002 需要 SKU-AVAILABLE 5 件及 SKU-EMPTY 3 件，SHIP_COMPLETE 下不能部分分配；
  更前序 SEED-B-0001 另需 SKU-EMPTY 2 件。目前該庫位 SKU-EMPTY ATP=0。
- 現有順序政策不允許後單越過共享 SKU 的前序需求，因此 PO-99991 保持 CONFIRMED／PENDING。
- 未自行新增收貨、取消前單或改排序政策。後續驗證須先處理前序需求，或使用隔離 SKU／主檔。
- 補貨事件一次最多嘗試一個作業；其餘等待需求另由後續事件或 reconciliation 掃描處理，預設掃描間隔 15 分鐘。

本次也顯示 UI 尚未解釋「被前序需求阻擋」；不能將所有 CONFIRMED 直接稱為本單缺貨。


## 最終驗收結論

使用者補足前序需求後，依要求重啟後端，讀取 PO-99991 確認為 FULFILLED、庫存作業 DONE、
Shipment HANDED_OVER_TO_CARRIER，Events 模式不變。使用者已確認 T4 驗證完畢。

「被其他訂單阻擋」的分配規則與相關改善由使用者決定日後另案處理；本次不修改排序、
SHIP_COMPLETE 或排程間隔。上述追查紀錄保留為當時狀態，不代表訂單目前仍卡住。
