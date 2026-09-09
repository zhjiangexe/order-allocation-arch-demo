# Allocation 操作與履約追蹤台 — Plan

- 日期：2026-09-09
- 狀態：T1 基線完成且使用者已確認；T2～T7 尚未實作
- 任務清單：[tasks.md](tasks.md)

## 目標與範圍

以 allocation 為專案核心，完善前端需求輸入、等待配貨、庫存補貨、批次分配與履約結果的操作閉環。
沿用既有業務 API、事件與 WMS 模擬；以最少後端唯讀補強，讓同一套前端支援 Events 與 Temporal。
Temporal 模式必須能由前端建單觸發 `OrderFulfillmentWorkflowImpl`，並驗證正常路徑完成。
目前已完成 T1 契約與基線工作；功能實作依 tasks.md 的逐 T 確認規則執行。

### 依 T1 基線校準的交付目標

T1 已證明既有後端可跑完雙模式正常履約；後續工作聚焦將已存在的能力接到可操作的前端，
並補齊唯讀查詢的模式與故障辨識。T1 的成功不代表新版前端已完成。

| 目標 | 基線與需要補上的成果 | 對應任務 |
| --- | --- | --- |
| 前端能有效建單 | 修正已實測會回 400 的 dispatchBy／releasePriority 缺漏 | T4.1 |
| 同一畫面支援雙模式 | 保留既有業務回應，新增模式／Workflow 可讀狀態；Events 不依賴 Temporal | T2、T3.3、T4 |
| 配貨與完成資訊可信 | 依實際 JSON、updatedAt、訂單／作業／Shipment 關聯與不同終態列舉建立判定 | T3.1、T3.2、T4.2 |
| 等待需求可追蹤 | 列出 CONFIRMED OUTBOUND 與最晚離倉時間，不宣稱固定 FIFO 或完整順位 | T5 |
| 補貨可安全往返 | 沿用 receiptId 冪等，區分收貨提交成功與訂單後續分配完成 | T6 |
| 從 UI 證明閉環 | 重用 UI-EVT-*／UI-TMP-* fixtures，完成雙模式六情境及批次驗收 | T7 |

文件分工：本 plan 定義目標與決策，tasks 定義待做工作與驗收；t1-baseline.md 及
[t1-contract-samples.json](t1-contract-samples.json) 保留當次觀測證據，不因 T2 契約更新而回寫成新的實測結果。
後續若發現與基線不同，先記錄差異及原因，再同步 plan／tasks，不直接覆蓋原始證據。

### 已確認的方向

- 新增一個「配貨佇列」頁及一個「訂單履約詳情」抽屜；擴充既有訂單與庫存頁，主檔頁維持唯讀。
- 前端支援兩種 orchestration mode；每個 deployment 啟動時選定一種，不提供前端切換模式。
- 暫不處理 cancellation 操作路徑。
- WMS 使用既有自動模擬，不新增 Pick／Pack／Stage／HandOver 操作頁或按鈕。
- 不新增手動啟動 Workflow、強制配貨或直接完成訂單的捷徑 API。

### 實作預設

以下是規劃採用的預設，並非使用者逐項指定的硬性需求：

- 開啟履約詳情時預設自動追蹤，每 2 秒更新；使用者可關閉。
- 補貨預填貨主、倉別、來源庫位與 SKU；數量由使用者確認，不自動當成缺口送出。
- 允許履約整合查詢新增模式與查詢狀態欄位；不改業務命令、資料庫 schema 或事件契約。
- 詳情採可由 URL 還原的抽屜，返回補貨前的訂單不依賴元件記憶體。

## 現況依據

| 能力 | 現有入口／實作 | 用法與限制 |
| --- | --- | --- |
| 建單與訂單查詢 | `POST /orders`、`GET /orders`、`GET /orders/{orderId}` | 建單事件由生效的 driver 推進履約 |
| 履約整合查詢 | `GET /demo/orders/{orderId}/fulfillment` | 回傳 order、stockOperation、shipments、temporalWorkflow |
| 等待作業 | `GET /stock-operations?state=CONFIRMED&limit=200` | 僅支援 CONFIRMED；上限 200，無完整分頁或總筆數 |
| 庫存 | `GET /stock-pool?ownerId=...&locationId=...` | 供需比較是查詢快照，不是正式配貨判定 |
| 收貨 | `POST /stock-receipts` | 沿用既有同步收貨及後續分配喚醒機制 |
| 主檔 | `/owners` 及既有商品、SKU、facility、location 查詢 | 用於名稱解析與選擇驗證 |
| Temporal | `TemporalFulfillmentEventBridge`、`OrderFulfillmentWorkflowImpl` | OrderPlaced 啟動；allocation 與 handover 事件轉 Signal |
| WMS 模擬 | `SimulatedWarehouseOperationsScheduler` | 預設建立 Shipment 約 10 秒後具備處理資格，非完成時限保證 |

程式位置：

- `frontend/src/App.tsx`、`frontend/src/api/client.ts`
- `backend/deployments/monolith/src/main/java/com/flowzati/archone/demo/orderfulfillment/`
- `backend/inventory-context/src/main/java/com/flowzati/archone/inventory/movement/entrypoint/rest/StockOperationRest.java`
- `backend/fulfillment-process/src/main/java/com/flowzati/archone/orderfulfillment/entrypoint/messaging/TemporalFulfillmentEventBridge.java`
- `backend/orchestration-temporal-runtime/src/main/java/com/flowzati/archone/orchestration/runtime/workflow/order/OrderFulfillmentWorkflowImpl.java`

本規劃經第二次程式碼核對；T1 已以隔離環境確認雙模式既有 HTTP 路徑，詳見 t1-baseline.md；新版 UI 尚未實作。根目錄 README 的
`/allocation-demands` 已與現況不符，實作時同步更新；不得據此舊端點設計前端。

## 頁面與按鈕

| 畫面 | 按鈕／互動 | 行為 |
| --- | --- | --- |
| 訂單 `/orders` | 既有建立訂單、重新整理；新增查看履約 | 建單成功自動開啟新訂單詳情；既有訂單可開啟詳情 |
| 配貨佇列 `/allocations` | 重新整理、查看履約、前往補貨 | 列出等待作業、來源、貨主、來源庫位、入列時間、SKU 與需求量 |
| 履約詳情抽屜 | 重新整理、自動追蹤開關、每品項前往補貨、複製 ID、關閉 | 顯示業務進度、allocation 明細、Shipment 摘要及 Temporal 額外資訊 |
| 庫存 `/stock` | 既有查詢、確認收貨、重新查詢；新增返回履約 | 接收補貨 context；送出仍經使用者確認；收貨完成後可返回追蹤 |
| 主檔 `/catalog` | 沿用既有互動 | 不增加主檔維護能力 |

### 配貨佇列

- 以 StockOperation 為列單位，展開需求 Moves；不把 CONFIRMED 一律翻譯為「確定缺貨」。
- 前端可在已載入資料內篩選貨主／SKU／來源；不得宣稱全量搜尋或全域 FIFO 名次。
- 明確顯示最多載入 200 筆；滿 200 筆時提示可能仍有其他資料。空清單與載入失敗分開。
- 僅可解析為訂單來源時顯示「查看履約」。非訂單來源仍可看作業資料，不組造 orderId。
- 補貨以來源庫位及需求品項定位；主檔無法解析或驗證時顯示原因，不默選另一庫位。
- 不提供權威 blocker、完整 queue position 或自動計算補貨缺口；這些需要額外後端語意，列為後續範圍。

### 履約詳情

- 訂單摘要：Order ID、貨主、品項、數量、狀態與既有時間戳。
- 配貨明細：StockOperation ID／狀態、各 Move 需求與狀態、批次數量、庫位、入庫日、效期。
- Shipment 摘要：Shipment ID、狀態及既有揀貨結果；多個 shipment 逐一列出，不只取第一個。
- 技術資訊區顯示執行模式、correlation IDs；Temporal 額外顯示 phase、outcome、updatedAt（目前階段進入時間）。
- 使用真正的階段／資料狀態，不播放假進度。沒有完整歷史時，不產生每階段的假時間或耗時。
- 抽屜可用 Esc 關閉、管理焦點並回復觸發按鈕；載入及錯誤訊息可被輔助技術辨識。

## 雙模式與完成判定

後端使用 `ORDER_PROMISING_FULFILLMENT_ORCHESTRATION_MODE=events|temporal`，預設 events。
沿用 `make dev-up` 與 `make dev-up-temporal`；Events 驗證應確認有效設定沒有被環境覆寫。
同 deployment 所有 replicas 使用同一模式。模式切換須先完成既有流程或另外處理遷移，
本次不開發進行中流程遷移。開發用 Temporal server 的記憶體儲存限制沿用 docker/README.md 說明。

共同主畫面以業務證據呈現「等待配貨、已配貨／倉內執行、出庫已完成／等待訂單更新、履約完成」。
跨 context 查詢可能短暫不同步；顯示實際事實及「同步中」，不把查詢組合當成原子快照。

| 模式 | 額外顯示 | 自動追蹤成功停止條件 |
| --- | --- | --- |
| Events | 不顯示 Workflow 進度 | Order 為 FULFILLED，對應 StockOperation 為 DONE，Shipment 有交接完成證據 |
| Temporal | ALLOCATION → WAREHOUSE_EXECUTION → INVENTORY_FINALIZATION → ORDER_COMPLETION → FINISHED | 業務完成證據齊全，且 Workflow 為 FINISHED／FULFILLMENT_COMPLETED |

完成證據必須關聯同一筆訂單與作業：使用 order.fulfilledByShipmentId 尋找對應 Shipment，
核對其 orderId、stockOperationId，且 status 為 HANDED_OVER_TO_CARRIER。Temporal 再核對 snapshot 的
orderId、stockOperationId 與 shipmentId，並要求 shipmentTerminalStatus=HANDED_OVER。其他 Shipment 仍列出，但不能用任意一筆交接完成推定本次成功。
關聯資料缺少或矛盾時顯示「尚無法確認完成」，不顯示成功，也不自動重新執行業務命令。
Temporal 的 null snapshot 不代表 Events，也不代表 Workflow 失敗。
外部取消或未知狀態提供唯讀提示並暫停自動追蹤，仍可手動刷新；不新增取消按鈕或取消編排。

## 最小後端查詢補強

擴充既有 `OrderFulfillmentView`，保留現有四個欄位，預計新增：

- `orchestrationMode`: `events` 或 `temporal`，取後端生效設定。
- `workflowQueryStatus`: `NOT_APPLICABLE`、`AVAILABLE`、`NOT_FOUND`、`UNAVAILABLE`。

Events 回傳 NOT_APPLICABLE，不呼叫 Temporal。Temporal snapshot 存在時為 AVAILABLE；
查無 execution 為 NOT_FOUND，前端說明可能尚未建立；預期的連線／查詢失敗為 UNAVAILABLE。
UNAVAILABLE 不等於 Workflow 執行失敗，NOT_FOUND 也不保證之後一定會啟動。
預期的 Temporal 查詢失敗應保留成功取得的業務資料，並限制查詢等待時間；不可用廣泛捕捉掩蓋程式錯誤。
記錄伺服器診斷資訊，但前端不顯示原始 stack trace。完整 Temporal execution history、重試控制與
failed/timed-out execution 診斷不在本次範圍。

不另增全域模式 API；模式先放履約詳情。若未來需要未選訂單時的全域模式徽章，再評估。
維持 composition query 透過各 context 公開介面，不新增跨 context SQL，不改 allocation 或 WMS usecase。

## 自動追蹤與送出可靠性

- 同一抽屜最多一個在途查詢；前次結束後再安排下一次，不累積重疊請求。
- 關閉、換訂單、離開頁面時取消／忽略舊請求；背景頁面暫停，回到前景再刷新。
- 失敗保留最後成功快照與更新時間，提示資料可能過時；避免持續每 2 秒重試故障服務，提供手動重試。
- 缺貨可持續等待，不以固定等待時間判定業務失敗；使用者可暫停自動追蹤。
- 送出期間停用建單／收貨按鈕。網路結果不明時不產生新 identity 盲目重送。
- 實作前核對現有命令冪等契約；若支援相同請求重試，重用同一 identity 與 payload；不支援則先查詢／提示確認。
- 補貨 URL 僅承載定位 ID，不自動執行 mutation；日期沿用既有有效批次預填規則並驗證主檔相容性。

## 驗收與交付

兩種模式各驗證有貨、缺貨後補貨及多 SKU 整單配貨；Temporal 必須核對 Workflow outcome，
Events 必須確認不依賴 Temporal 服務。驗證使用隔離資料，不能假定 seed ATP 永遠相同。
詳細工作與檢核見 tasks.md。首版不擴增 backend allocation 演算法、WMS、取消、歷史投影或全量統計。


## 第二次審查補充：必須先修正的契約落差

前次盤點所述「既有前端即可建單啟動」需要修正：後端接線存在，但目前
`PlaceOrderForm` 與 `PlaceOrderCommand` 沒有送出後端必填的 `dispatchBy`、`releasePriority`。
`OrderRest` 會拒絕缺少 releasePriority；`DeliveryTerms` 也要求 dispatchBy。這是第一個實作關卡。

- 建單新增「最晚離倉時間」與「出庫釋放優先級（0..100）」；優先級預設 0，離倉時間由使用者填寫。
- 時間輸入顯示使用者時區，轉成 ISO instant 傳送；日期欄位仍為日期，不套用 UTC 位移。
- 不從 promisedDeliveryDate 假造承運時效／截單規則；不新增後端排程計算。
- 完整核對既有表單必填欄位及錯誤訊息，保留同 SKU 多行，不用 skuCode 當唯一 React key。
- 履約查詢的 Order view 與列表 Order view 欄位不同，分開定義或明確映射，不直接強制轉型。
- 庫存批次 JSON 使用 stockPoolId，履約分配批次使用 stockQuantId；前端明確映射，不順便改後端契約。

## 導航、查詢與重送的具體規則

- 詳情 URL 支援 `/orders?orderId=...` 與 `/allocations?orderId=...`，抽屜共用，保留原列表篩選。
  關閉只移除詳情參數；直接連結開啟時回到所在列表，不呼叫不可靠的 history.back()。
- 補貨 context 在跨頁前以共用型別定義，僅接受列舉的返回頁面與合法 IDs；不接受任意 return URL。
  庫位由 operation.fromLocationId 決定，facility 可由既有 locations 主檔反查；不默用第一個庫位。
- 訂單若尚未建立 StockOperation，無法確認來源庫位時暫不提供自動定位補貨。
- 明確的補貨導航可自動讀取庫存並定位 SKU，但不自動送出收貨。返回詳情立即取得新快照。
  收貨不保證補到的量由該張訂單取得；既有貨主排序政策可能先配給其他需求。
- 追蹤僅作用於目前詳情，不輪詢全部訂單或佇列。手動刷新共用在途鎖。
  關閉抽屜後列表保持原快照並保留刷新入口，避免順便擴張全域快取系統。
- HTTP 失敗或 workflowQueryStatus=UNAVAILABLE 時保留資料並暫停自動追蹤，明確提供重試；
  HTTP 200 的 UNAVAILABLE 也要處理。NOT_FOUND 可繼續追蹤，但不能無限宣稱「即將啟動」。
- Temporal Query 採獨立、可設定的等待上限（預設目標 3 秒），不改共用 Activity／Workflow timeout。
  於 T2 核對現有 SDK 可用設定及例外，並以測試驗證，不額外加 execution history 查詢。
- 建單目前沒有「同鍵重送回傳既有訂單」：相同 ownerId＋externalOrderNo 回 409。
  結果不明時保留原單號與內容，可手動重查最近訂單，以這組鍵比對；最近列表未找到不代表未建立。
  找到才提供查看履約，找不到顯示限制；不自動換新單號重送，也不新增按外部單號搜尋 API。
- 收貨已有 receiptId 冪等。同一次收貨在第一次送出時產生 ID，結果不明時保留 ID 與原 payload，
  提供「重試原收貨」；目前 StockPage 每次呼叫產生新 UUID 的行為必須修正。
  修改內容須明確開始另一筆操作，不用同 ID 送不同內容；此次只保證頁面存續期間的重試，
  離頁／重載造成未確認請求遺失時顯示離頁提示，不新增持久化命令佇列。

## 驗證層次與範圍邊界

既有 `e2e/spec` 是 Karate HTTP E2E，能證明後端流程，不能單獨證明使用者從前端走完。
重用既有 fixtures／runner，不新增另一套後端環境；增加或更新必要契約斷言。
另以真實瀏覽器在兩種模式操作 plan 的情境矩陣，記錄步驟、結果與 correlation IDs。
本次不為此強制新增瀏覽器測試框架；可使用既有可用工具執行並記錄可重現的人工驗收。

測試預算與 UI 業務等待分離：測試須有有限 timeout，超時記錄卡住階段；UI 不以測試期限認定業務失敗。
既有 cancellation 測試維持回歸，本次不新增 cancellation 功能或矩陣。
完成後新增 validation.md 記錄實際環境、指令、結果與未驗證項目；現在不預填成功結果。


## 使用者檢查點（2026-09-09 確認）

依使用者最新指示，每完成一個 T1～T7 都停下來請使用者檢查，取得明確確認後才進入下一個 T。
此規則取代先前提出的四個檢查點；不合併檢查點，也不因依賴已滿足就提前實作下一個 T。
同一個 T 內的子任務可連續完成，無須逐一要求確認。

每次檢查前先完成該 T 的工作與必要驗證，提供具體可檢查的成果：

| 檢查點 | 交付使用者檢查的成果 |
| --- | --- |
| T1 | 契約差異、雙模式基線、測試資料與已知限制 |
| T2 | 履約查詢回應範例、模式辨識與查詢降級測試結果 |
| T3 | 前端型別、完成判定、追蹤及導航規則與測試結果；此階段不要求完整畫面 |
| T4 | 可操作的建單與履約詳情、兩模式呈現及驗證結果 |
| T5 | 可操作的配貨佇列、篩選與導航，以及尚待 T6 完成的補貨串接邊界 |
| T6 | 可操作的補貨往返、同請求重試及驗證結果 |
| T7 | 雙模式完整驗收證據、操作文件與剩餘限制 |

交付時列出異動文件／操作入口、檢查步驟、驗證結果與未完成項目。
若使用者要求調整，先修正目前 T 並重新交付檢查；未收到回覆不視為確認。
任務實作完成與使用者確認分開記錄；最後的使用者確認前不宣稱整體驗收完成。


## T1 契約與基線補充

詳細契約、雙模式接線、隔離測試資料及執行結果見 [t1-baseline.md](t1-baseline.md)。

- 現行需求排序支援 FIFO 與 DISPATCH_DATE_FIRST，migration 與無設定時的預設均為後者。
  UI 不應一律宣稱 FIFO；此次不加政策查詢／編輯 API，只呈現入列時間與最晚離倉時間，
  並說明列表順序不代表實際分配順位。
- Workflow JSON 時間欄位是 updatedAt，語意是目前 phase 進入時間；前端直接採此契約。
- Shipment 狀態 HANDED_OVER_TO_CARRIER 與 Workflow shipmentTerminalStatus=HANDED_OVER
  是不同列舉，須明確對應。
- 共用業務成功條件須使用履約 OrderView 的 fulfilledByShipmentId；一般訂單列表回應沒有此欄。
