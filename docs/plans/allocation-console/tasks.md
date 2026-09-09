# Allocation 操作與履約追蹤台 — Tasks

- 日期：2026-09-09
- 狀態：T1／T2 已確認；T3 已完成、使用者同意提交；T4～T7 待實作
- 設計與範圍：[plan.md](plan.md)
- T1～T7 保留為里程碑；實作及勾選單位改為下列子任務。
- T1 證據：[t1-baseline.md](t1-baseline.md)，含實際雙模式 HTTP 驗證與契約樣本。

## 執行與使用者確認規則

依使用者最新指示，執行順序固定為 T1 → 確認 → T2 → 確認 → T3 → 確認 → T4 → 確認
→ T5 → 確認 → T6 → 確認 → T7 → 最終確認。此規則取代先前四個檢查點的建議。
同一個 T 的子任務連續完成；下方依賴表只說明技術依賴，不授權跨越檢查點提前實作。

每個 T 完成必要驗證後，提供成果、檢查步驟、驗證結果與限制，停下等待使用者明確確認。
使用者要求修正時留在目前 T，修正後再次交付；未回覆不能視為通過。
具體交付內容見 [plan.md 的使用者檢查點](plan.md#使用者檢查點2026-09-09-確認)。

- [x] T1 成果已交付，使用者確認可進入 T2（2026-09-09）。
- [x] T2 成果已交付並提交 7ebc34e，使用者確認可進入 T3（2026-09-09）。
- [ ] T3 成果已交付，使用者確認可進入 T4。
- [ ] T4 成果已交付，使用者確認可進入 T5。
- [ ] T5 成果已交付，使用者確認可進入 T6。
- [ ] T6 成果已交付，使用者確認可進入 T7。
- [ ] T7 驗收證據已交付，使用者完成最終確認。

## 依賴與拆分原則

| 里程碑 | 子任務 | 前置 |
| --- | --- | --- |
| T1 基線 | T1.1 契約、T1.2 環境與 fixtures | 無 |
| T2 後端查詢 | T2.1 模式契約、T2.2 Temporal 查詢降級 | T1.1 |
| T3 前端基礎 | T3.1 API 型別、T3.2 完成判定、T3.3 追蹤、T3.4 導航 context | T3.1 依賴 T2；T3.2／T3.3 依序；T3.4 依賴 T1.1 |
| T4 訂單與詳情 | T4.1 建單修復、T4.2 唯讀抽屜、T4.3 路由與追蹤串接 | T4.1 依賴 T1.1；T4.2 依賴 T3.1／T3.2；T4.3 依賴 T3／T4.1／T4.2 |
| T5 佇列 | T5.1 查詢列表、T5.2 導航與篩選 | T5.1 依賴 T3.1；T5.2 依賴 T3.4／T4.3／T5.1 |
| T6 補貨 | T6.1 定位與往返、T6.2 收貨重試 | T6.1 依賴 T3.4／T4.3；T6.2 依賴 T1.1／T6.1 |
| T7 驗收 | T7.1 自動化回歸、T7.2 雙模式 UI、T7.3 文件與證據 | T1.2 與 T2～T6 |

每個子任務應能個別 review，附明確驗收；尚未完成的整合按鈕不以無作用按鈕交付。
單元／元件／查詢測試隨子任務完成，T7 聚焦完整串接。依賴表不代表要求使用多 agent。

## T1：確認契約與雙模式基線

### T1.1：契約盤點

- [x] 核對 Order、StockOperation、Move、Shipment、Workflow 的實際 enum、JSON 與終態欄位。
- [x] 核對建單及收貨的 identity／冪等規則，記錄「請求結果不明」的既有可行處理方式。
- [x] 確認事件模式的 allocation、交接、出庫與訂單更新接線，以及 Temporal 正常路徑。

產出：在 plan 記錄完整 request／response 差異、完成關聯鍵、必填值與冪等限制；包含
已知缺少 dispatchBy／releasePriority、stockPoolId／stockQuantId 名稱差異。

### T1.2：可重現的雙模式基線

- [x] 確認目前 Compose／scripts 能分別啟動兩種模式；記錄生效模式、WMS 模擬與事件基礎設施。
- [x] 準備隔離測試資料，涵蓋有貨、無貨、有效與過期批次、多 SKU，避免其他共享 SKU 的排序需求干擾。

驗收：明確列出完成 predicate 所需欄位；若實際契約無法支援 plan，先更新規劃，不自行擴張業務範圍。

## T2：補強既有履約唯讀查詢

預計異動：monolith 的 `OrderFulfillmentView`、`OrderFulfillmentQueryService`、
`TemporalWorkflowStateReader` 與其測試；必要設定沿用既有 configuration。

### T2.1：模式及 additive response 契約

- [x] 新增 `orchestrationMode` 與 `workflowQueryStatus`；以 T1 實際 JSON 樣本核對既有欄位相容，樣本本身不覆寫。
- [x] 沿用 OrderFulfillmentProperties 綁定並驗證 Driver enum，由 demo 的 OrderFulfillmentQueryConfiguration 傳入 Service；properties 移至 fulfillment-process configuration，維持 bootstrap 依賴邊界。Service 不使用 @Value 或字串模式解析。
- [x] Events 回傳 NOT_APPLICABLE，驗證不存取 Temporal。

驗收：兩種模式的 JSON 契約測試通過，既有欄位相容。

### T2.2：直接查詢 Temporal 與必要的錯誤分類

- [x] Temporal 分別處理 AVAILABLE、NOT_FOUND 與預期查詢錯誤 UNAVAILABLE。
- [x] 直接呼叫 workflow.state()，沿用 SDK timeout／retry；移除專用期限設定、排程器與 gRPC Context。
- [x] 僅對連線不可用／逾時保留業務資料並回 UNAVAILABLE；查無 Workflow 回 NOT_FOUND，其他 Query／service 錯誤繼續拋出，不承諾獨立的 3 秒期限。
- [x] 加入模式、查無 Workflow、Temporal 不可用、訂單不存在的查詢測試；不吞掉非預期程式錯誤。
- [x] 不新增資料表、命令 API、事件或業務狀態轉換。

驗收：單一查詢可可靠辨識模式與 Workflow 可讀性，Temporal 故障不抹除可讀的業務結果。

T2 驗證與回應範例：[t2-query-review.md](t2-query-review.md)。

## T3：前端契約、狀態判定與追蹤機制

預計異動：`frontend/src/api/types.ts`、`client.ts`，新增履約狀態轉換及追蹤 hook。

### T3.1：API 型別及讀取 client

- [x] 依 t1-contract-samples.json 與 T2 新欄位加入履約整合、stock operation 佇列型別及 client functions；派生測試 fixture 與 T1 原始證據分開。
- [x] 明確採用 Workflow updatedAt、nullable outcome，以及 HANDED_OVER 與 HANDED_OVER_TO_CARRIER 的不同列舉。
- [x] 區分兩種 Order view；核對批次 JSON 名稱；讀取支援 AbortSignal，不改 mutation 的送出語意。

驗收：以實際 JSON fixture 檢查欄位，型別檢查通過。

### T3.2：業務證據與完成判定

- [x] 建立共用業務進度與模式各自的成功停止 predicate；容忍未知值、空值與短暫資料不同步。
- [x] 以 fulfilledByShipmentId／orderId／stockOperationId 關聯 Shipment，核對來源 ORDER／PRIMARY；Temporal 核對三個 IDs 與 shipmentTerminalStatus=HANDED_OVER。
- [x] 測試多 Shipment、缺失／矛盾關聯、未知終態，不以任意已交接 Shipment 代表成功。

驗收：兩種模式的純狀態轉換有測試，未知資料不假裝完成。

### T3.3：單一詳情追蹤生命週期

- [x] 預設每 2 秒追蹤，不重疊請求；支援手動重查、暫停及恢復。
- [x] 關閉、換單與卸載時清理；背景暫停；防止較舊回應覆蓋新訂單。
- [x] HTTP 查詢錯誤或 HTTP 200 且 workflowQueryStatus=UNAVAILABLE 時，保留最後快照及時間，暫停自動追蹤並提供重試；NOT_FOUND 不當作 Events。
- [x] 測試兩模式完成條件、Temporal 尚未建立／不可用、長時間缺貨、外部取消及 stale response。

驗收：Events 不等待不存在的 Workflow；Temporal 不僅憑 Order FULFILLED 顯示 Workflow 成功。

### T3.4：共用詳情與補貨 URL context

- [x] 定義 orderId、來源列表、篩選與補貨 owner／facility／location／SKU／returnOrderId 的解析與建構。
- [x] URL 僅接受固定站內返回頁面；主檔未載入不做錯誤預選。
- [x] 測試深連結、非法 UUID、返回原列表、返回詳情、瀏覽器上一頁／下一頁。

驗收：T4／T5／T6 使用同一導航契約，不各自組合不相容參數。

T3 實作與檢查方式：[t3-foundation-review.md](t3-foundation-review.md)。

## T4：訂單履約抽屜與訂單頁串接

預計異動：`OrdersPage`、`OrderTable`、路由與新增履約詳情元件。

### T4.1：修復建單必填欄位與不明結果處理

- [ ] 新增 dispatchBy 時間輸入及 releasePriority（0..100，預設 0），補齊 request 型別。
- [ ] 驗證使用者時區轉 ISO instant、日期不偏移、地址／分區與所有必填欄位。
- [ ] 依 T1 實測缺 releasePriority／dispatchBy 各回 400 的基線建立回歸；確認新增欄位後表單能成功送出，不能只用接受舊 payload 的 mock。
- [ ] 防重複送出；保留 ownerId＋externalOrderNo，處理 409／不明結果；最近列表查無結果不自動再建單。

驗收：有效表單可被目前 POST /orders 接受，無須新增後端建單能力。

### T4.2：唯讀履約抽屜內容

- [ ] 顯示訂單、業務進度、Move／批次及 Shipment 摘要；相同 SKU 多行使用 line／move identity。
- [ ] 顯示模式與必要技術資訊；Temporal 額外顯示 phase／outcome／updatedAt（目前階段進入時間），不製造歷史時間軸。
- [ ] 處理讀取中、找不到訂單、空 allocation／shipment、查詢故障及完成畫面。
- [ ] 處理焦點、Esc、返回觸發元件及窄螢幕布局。

驗收：Events／Temporal fixtures 與異常空值均如實顯示，不依賴輪詢才能測試內容。

### T4.3：訂單入口、路由及追蹤整合

- [ ] 每筆訂單新增「查看履約」；建單成功自動開啟對應訂單。
- [ ] 使用 T3.4 契約；訂單與佇列皆可開抽屜，關閉保留來源及篩選；支援直接開啟與重新載入。
- [ ] 新增重新整理、自動追蹤開關、複製 IDs 與逐品項前往補貨。
- [ ] 元件測試涵蓋建單後開啟、既有訂單、兩模式與抽屜關閉清理。

驗收：有貨訂單可從下單一路看到履約成功；未啟動的 Workflow 不被標示為 Events 或失敗。

## T5：配貨佇列頁

預計異動：`App.tsx`、`AppHeader`、新增配貨佇列頁及其測試。

### T5.1：等待作業列表

- [ ] 新增 `/allocations` 路由與主選單入口，查詢 CONFIRMED 作業。
- [ ] 呈現來源、貨主、來源庫位、入列時間、最晚離倉時間、作業 ID 與 SKU 需求明細；不標成固定 FIFO 順位。

驗收：唯讀佇列正確載入、展開且處理空值；只代表現有 confirmed outbound query 的範圍。

### T5.2：篩選與跨頁入口

- [ ] 新增重新整理、訂單來源的查看履約、逐品項前往補貨。
- [ ] 提供已載入範圍的篩選，不宣稱全量搜尋、精確缺口或全域 FIFO 名次。
- [ ] 顯示最多 200 筆及可能截斷提示，區分無資料與失敗。
- [ ] 處理非訂單來源與無法解析的主檔，不產生無效導航。
- [ ] 測試來源分支、載入上限、空清單、錯誤與導航 context。

驗收：等待作業可找到對應需求及補貨入口，不把所有等待一律說成缺貨。

## T6：庫存補貨往返

預計異動：`StockPage`、`StockPanel`、`ConfirmStockReceiptDialog` 與相關測試。

### T6.1：預填定位與返回

- [ ] 沿用 T3.4 補貨 URL context；來源庫位未確認時不提供錯誤定位。
- [ ] 等主檔載入後驗證歸屬並預填，查詢正確庫位；無效定位明確提示。
- [ ] 補貨數量由使用者確認，不以需求量自動提交；沿用既有日期預填及驗證。

- [ ] 新增返回履約並恢復詳情，或返回原佇列；測試非法參數與延遲主檔載入。
- [ ] 從佇列或直接進入庫存頁時，不產生不存在的返回訂單。

驗收：無 mutation 即可驗證正確定位及返回；不自動選第一庫位或提交需求量。

### T6.2：收貨提交與同請求重試

- [ ] 同一操作首次送出才產生 receiptId；修正 StockPage 每次呼叫都建立 UUID 的行為。
- [ ] 結果不明時保留原 payload／ID，提供重試原收貨；修改內容另起操作，不共用舊 ID。
- [ ] 未確認請求離頁時提示；不承諾重新載入後仍能恢復，不新增持久化命令佇列。
- [ ] 沿用 `POST /stock-receipts`，依 T1 冪等契約處理重送，送出期間防重複。
- [ ] 收貨成功保留實收結果與手動重查，說明 availability 後續推進分配；不把收貨 200 當成原訂單已配貨。返回履約取得新快照。
- [ ] 測試正確庫位、非法參數、延遲主檔載入、成功返回及結果不明情境。

驗收：使用者可以從缺貨訂單補到正確來源庫位，再返回查看分配與履約完成。

## T7：完整驗證與文件同步

### T7.1：自動化回歸與 HTTP E2E

- [ ] 重用 e2e/spec 的 runner／fixtures，補強必要查詢斷言；既有 cancellation 回歸不刪除。
- [ ] 前端測試、typecheck、build 與後端相關測試在各自目錄執行，記錄確切命令與結果。
- [ ] 不把 HTTP E2E 當成瀏覽器驗收；不新增測試專用業務 API。

- [ ] 執行 `cd frontend && npm test`、`npm run typecheck`、`npm run build`。
- [ ] 後端改 Java 後執行 `cd backend && ./gradlew spotlessApply`，執行相關查詢／模式測試與必要整合檢查。
- [ ] 提交 Java 變更前執行 `cd backend && ./gradlew spotlessCheck`。

驗收：已有基礎設施可重現兩模式，契約及元件回歸通過。

### T7.2：雙模式真實 UI 驗收

- [ ] 使用 T1 已準備的 UI-EVT-*／UI-TMP-* fixtures，在新隔離資料庫驗證初始數量；不沿用已消耗的庫存，也不重複建立另一套 fixture。
- [ ] 在真實瀏覽器完成下列矩陣，確保建單／補貨由 UI 送出，不以 curl 取代主要操作。
- [ ] 驗證抽屜深連結、返回佇列、補貨返回、自動追蹤停止及時區輸入。
- [ ] 異常查詢透過查詢／元件測試涵蓋；若環境可重現，再補瀏覽器證據，不修改 production 注入故障。

### 模式 × 業務情境

| 模式 | 情境 | 必須觀察到的結果 |
| --- | --- | --- |
| Events | 有貨訂單 | 建單 → 分配 → 模擬交接 → 出庫 DONE → Order FULFILLED；不依賴 Temporal |
| Events | 缺貨後補貨 | CONFIRMED 等待可見；前端補貨後完成履約 |
| Events | 多 SKU、其中一項不足 | 不預留部分整單；補齊後整單分配並完成 |
| Temporal | 有貨訂單 | 前端建單啟動 Workflow；業務完成且 FINISHED／FULFILLMENT_COMPLETED |
| Temporal | 缺貨後補貨 | ALLOCATION 等待；補貨後收到分配 Signal，繼續至成功終態 |
| Temporal | 多 SKU、其中一項不足 | ship-complete 成立；補齊後 Workflow 完成 |

- [ ] 依矩陣執行雙模式端到端驗證，記錄 Order／Operation／Shipment IDs；Temporal 記錄 Workflow 證據。
- [ ] 使用各模式 FEFO fixture 下 4 件，驗證取兩批 2＋2、過期批不參與，核對初始在手 17／ATP 10／過期 7；不重寫 allocation 演算法。
- [ ] 驗證 Workflow 尚未啟動、查詢不可用、未完成資料同步、抽屜關閉停止追蹤。
- [ ] 確認業務完成條件與 UI 顯示一致，記錄尚未涵蓋的執行環境限制。

### T7.3：操作文件與驗收證據

- [ ] 更新 frontend/README.md：四頁、詳情抽屜、自動追蹤例外與補貨往返。
- [ ] 更新根 README 的舊查詢端點；核對 docker/README.md 的雙模式啟動與切換限制。
- [ ] 新增同目錄 validation.md，記錄模式、時間、環境、UI 步驟、IDs、測試命令及未驗證項目。
- [ ] 記錄實際測試結果；僅在驗收完成後勾選任務，不將計畫當作完成證據。

## 基線與最終驗收的區別

T1 已完成 72 個既有前端測試與 17 個 HTTP E2E，這些只證明起始狀態。
T2 初版已完成雙模式回歸；簡化後重新執行 monolith 查詢與架構測試，驗證範圍見 T2 報告。
T3～T7 的方框維持未完成，T7 額外提供真實 UI 證據。
基線發現的問題已編入上述子任務，不另增加里程碑；每 T 完成仍須使用者確認。

## 完成定義

使用者可由前端建立訂單、查看等待／批次分配、必要時補貨、追蹤至履約完成。
同一前端適用兩種後端啟動模式；Temporal 正常路徑實際完成；沒有新增 cancellation 操作、
手動 WMS 作業或額外 allocation 業務規則。
