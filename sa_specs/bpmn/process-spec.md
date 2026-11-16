# Archone 現況業務流程規格書

## 1 基本資訊與模型預覽

- 範圍：現有訂單配貨、倉儲履約、一段式收貨、取消協調與庫存釋放。
- 模型：BPMN 2.0 分析模型，isExecutable=false；實際流程由 Events／Temporal 驅動。
- 模型與預覽：[流程圖集](../02_BPMN_PROCESS_MODELS.md)。
- 未涵蓋：主檔管理 CRUD、操作台查詢、DLT 查詢與底層訊息重試細節。這些不是本次訂單履約流程的獨立步驟。
- 不包含：人工審單、自動選倉、跨倉拆單、客戶簽收及退貨流程。

| 圖 | Process ID | 起訖與範圍 |
| --- | --- | --- |
| 01 | Process_Fulfillment | 建單請求至正常 FULFILLED；配貨需求已取消時停止主線。倉內取消例外分列 04、06 |
| 02 | Process_Allocation | 訂單需求至 ASSIGNED 或已確認取消；可長時間等待 |
| 03 | Process_Warehouse | 已提交配置至 HANDED_OVER_TO_CARRIER，僅正常倉內路徑 |
| 04 | Process_Cancellation | 合法取消請求首次受理至協調結果；以 Events process manager 為具體基準 |
| 05 | Process_StockReceipt | 收貨請求至成功提交、冪等回覆或業務拒絕 |
| 06 | Process_StockCancellation | 已收到 OrderCancelled 事實至庫存取消結果 |

01 的 callActivity 是分析分解。只有配置成功與 WMS 交運的正常結果可繼續向右推進；取消結果不能當成正常活動完成。跨圖並未建立可執行引擎的輸入輸出映射、錯誤傳播或事件訂閱。

## 2 參與者與泳道職責矩陣

| 泳池／泳道 | 權責 | 不擁有的事實 |
| --- | --- | --- |
| 外部上游／操作端 | 提交訂單、貨主、出貨設施及收貨資料 | 不直接修改內部庫存或 Shipment |
| Archone／Ordering | 訂單身分、明細、PENDING／ALLOCATED／FULFILLED／CANCELLED | 不判斷實體倉內作業是否可撤銷 |
| Archone／Inventory | StockOperation／Move、可用量、預留、收貨與出庫扣帳 | 不自行宣告 Shipment 已交運或取消 |
| Archone／WMS | Shipment、Wave、Picking、Pack、Stage、handover 與取消結果 | 不替代 Ordering 訂單終態 |
| Archone／Fulfillment | 跨模組關聯、協調與結果紀錄 | 不覆寫來源模組的權威狀態 |

流程閘道可放在產生判斷依據的 owner 泳道；圖 04 的倉儲與訂單結果分支使用來源模組已確認的事實，由 Fulfillment 記錄協調結果。跨泳道實線表示邏輯先後，不代表同一資料庫交易或直接同步呼叫。

## 3 正常核心流程

### 3-1 訂單履約（圖 01）

1. 上游送出建單請求，Ordering 驗證並建立訂單。
2. Inventory 建立出庫作業與明細、評估整單配置。
3. 庫存足夠且符合需求排序時，在一次配置交易內提交所有預留；不足則停留 CONFIRMED。
4. 已提交的配置事實使 Ordering 記錄 ALLOCATED，並推進 WMS 出庫。兩個反應不以圖面強制為固定先後，Ordering 的 ALLOCATED 更新不畫成 WMS 的同步前置呼叫。
5. WMS 建立 Shipment，執行正常倉內作業並確認交運。
6. Inventory 依交運事實完成出庫移動，扣減 onHand 與 reserved。
7. Ordering 在出庫完成後記錄 FULFILLED。此狀態不等於客戶已簽收。

非法建單、重複上游單號等同步 API 拒絕在正式訂單成立前返回；01 聚焦成功受理後的履約，不把拒絕畫成缺貨。現行重複 ownerId＋externalOrderNo 回 409，不是建立另一張訂單。

### 3-2 配貨與等待（圖 02）

- 需求依 owner、facility、SKU 隔離；同 SKU 多行先加總。
- SHIP_COMPLETE 要求整張訂單的全部需求可滿足才預留，不能先占用其中一行。
- 需求排序由貨主策略決定：FIFO 或 DISPATCH_DATE_FIRST；預設為 DISPATCH_DATE_FIRST。
- 批次挑選採 FEFO，排除不可配置的過期庫存；效期相同時再依入庫順序。
- 不符合配置條件時不建立部分預留，持久化需求等待可用量或相關條件改變。
- 收貨、預留釋放及 reconciliation 可促成重新評估；等待不是持續持有資料庫鎖，也不是把收貨交易延長到所有訂單配完。
- 等待活動是持久化需求與重評機制的分析抽象。取消確認後退出；單純送出取消請求並不足以宣告退出。
- 圖中回圈是業務重評，不是網路錯誤重試。目前未設定等待逾時自動取消。

### 3-3 倉內正常出庫（圖 03）

建立 Shipment → 規劃 Wave → Release Wave／產生 PickingWork 與 PickTask → 確認各筆揀貨 → 完成 Wave → Pack → Stage → handover。

倉內作業由模擬排程執行，波次至交運在同一交易完成：

- 服務任務表示系統自動處理；人工操作介面不在本階段範圍內。
- 中間狀態在同一交易內推進，外部不一定能逐一觀察。
- 同一張 Shipment 已被取消或已由其他執行者處理時，模擬器不應再次推進 CREATED 路徑。
- 承運商交接由 WMS 記錄；未串接承運商 API、簽收回傳或物流追蹤。
- 倉內取消條件見第 4-2 節；已取消的 Shipment 不能沿圖 03 繼續交運。

### 3-4 收貨補庫（圖 05）

1. 驗證 receiptId、貨主、設施、內部庫位、SKU、數量及日期。
2. 相同 receiptId 與相同內容成功重送：回覆成功，不增加第二次庫存。
3. 相同 receiptId 不同內容：拒絕；第一次合法請求才進入收貨。
4. 請求去重、Inbound operation／move／move line、StockQuant 增加及 availability Outbox 在同一交易。
5. 提交後才由可用量事件及獨立配置交易推進圖 02。收貨成功不保證某一張等待訂單一定取得庫存。

## 4 取消流程與決策矩陣

### 4-1 取消入口與協調（圖 04）

入口為 POST /orders/{orderId}/cancellation-requests，requestId、requestedAt、reason 為不可變的請求內容。

- 首次合法受理回 202；同請求同內容重送可回 200 ALREADY_REQUESTED。
- 同 requestId 改內容或不相容的重複請求為衝突；查無 Order 與已終止流程的入口處置依 driver 分別判定。
- 圖 04 從受理後開始，不把所有 HTTP 拒絕都當作已建立 cancellation process。
- 只有 WMS 已確認 NO_SHIPMENT 或 Shipment 已取消，才推進 Ordering 取消。
- Ordering 成功取消後發布 OrderCancelled，非同步觸發圖 06。
- Events 的 COMPLETED、Temporal 的 ORDER_CANCELLED 都不能直接證明 Inventory 已完成釋放。

| 閘道／依據 | 條件 | 結果 |
| --- | --- | --- |
| 圖 04 倉儲結果 | NO_SHIPMENT 或取消完成 | 允許取消 Order |
| 圖 04 倉儲結果 | 已確認 WMS 拒絕 | 記錄拒絕，不釋放庫存 |
| 圖 04 倉儲結果 | 已確認 multiple Shipments／矛盾 | 保留衝突供調查 |
| 圖 04 Ordering 結果 | CANCELLED／同一請求 ALREADY_CANCELLED | 記錄協調完成 |
| 圖 04 Ordering 結果 | WMS 已安全取消但 Ordering 拒絕 | 跨模組衝突，不自動恢復 Shipment |

上述閘道只在取得已確認結果後判斷。default 分支是已知結果集合的其他分支；unknown、逾時、訊息尚未到達不進入 default，仍停留等待或可重試活動。

### 4-2 倉內取消條件

| 依據 | 分支 | 處置 |
| --- | --- | --- |
| Shipment 數量 | 0 | NO_SHIPMENT |
| Shipment 數量 | 1 | 依 Shipment 狀態處理 |
| Shipment 數量 | 大於 1 | MULTIPLE_SHIPMENTS，協調端記錄衝突 |
| Shipment 狀態 | CREATED、WAVE_PLANNED、RELEASED | 可直接取消 |
| Shipment 狀態 | 尚未交運的其餘可取消作業階段 | CANCELLING，等待取消完成處理 |
| Shipment 狀態 | HANDED_OVER_TO_CARRIER | REJECTED |
| 請求重送 | 同請求、同內容 | 回報既有取消結果，不重複處理 |
| 請求重送 | 與既有不可變請求矛盾 | 明確衝突 |

取消中的出貨單由排程推進至取消終態。取消完成代表停止作業及必要復原已完成；現有範圍不包含實體回庫掃描與人工復原證據驗證。

### 4-3 非同步庫存釋放（圖 06）

| StockOperation | 處理 |
| --- | --- |
| 不存在／已 CANCELLED | 不重複異動 |
| CONFIRMED | 取消未配置需求，無預留可釋放 |
| ASSIGNED | 保存可信的外部取消確認 checkpoint，釋放預留與 move lines，再取消 operation／moves |
| DONE | NOT_CANCELLABLE，不逆轉已完成出庫 |

EXTERNAL_CONFIRMED 的現行步驟是在收到合法上游取消事實後保存本地紀錄，沒有再次同步呼叫 WMS。

釋放只降低 reserved，不降低 onHand。例如 onHand=10、reserved=3，取消後為 onHand=10、reserved=0；後續需求可再取得這 3 件。

NOT_CANCELLABLE／跨模組矛盾需要核對來源狀態。此類異常須由維運追查，系統不自動建立人工工單。

## 5 例外、超時與補償

| 情況 | 流程處理 | 現況界線 |
| --- | --- | --- |
| 缺貨／排序尚不可分配 | 保留 CONFIRMED，等待重評 | 不等於技術失敗或取消 |
| 非法輸入／重複識別衝突 | API 拒絕；業務資料不重複異動 | 詳細 HTTP schema 不在本批 BPMN 內 |
| 網路、資料庫、訊息處理失敗 | rollback、重試、必要時 DLT | 不把 timeout 當業務拒絕；圖上不畫技術重試回圈 |
| 已交運後取消 | 拒絕取消 | 未建立退貨執行分支 |
| WMS 已取消但 Ordering 拒絕 | 保留衝突／workflow failure 待調查 | 不自動把 Shipment 恢復為可出貨 |
| 取消完成但預留尚存在 | 等待並追查圖 06 與事件處理 | cancellation process 終態不等於庫存完成 |
| 取消與交運競態 | 由 owner state、交易與重複處理規則決定合法終態 | 不以「收到取消請求」提前釋放庫存 |
| 缺貨等待超時 | 沒有新增 timer 行為 | 未實作 SLA／逾時自動取消 |
| WMS 實體 recovery | 現有取消完成機制 | 不能宣稱已具備實際現場復原驗證 |

取消補償順序為：WMS 安全終態 → OrderCancelled → Inventory 釋放，由業務命令與事件推進。

Boundary Event 的使用範圍與例外對照見 [圖集第 3 節](../02_BPMN_PROCESS_MODELS.md#3-例外處理與邊界事件)。

## 6 Events 與 Temporal 的對照

兩種驅動模式在同一部署中擇一使用，共用業務規則與合法終態；圖 01、02、03、05、06 表達共同業務規則。

| 關注點 | Events | Temporal |
| --- | --- | --- |
| 正常協調 | canonical event consumer 推進 | Workflow Activities＋Signals 推進 |
| 待配貨 | StockOperation CONFIRMED，事件／reconciliation 再評估 | Workflow 等待 assignment Signal；Inventory 規則相同 |
| 待配貨取消 | 圖 04：查無 Shipment 回 NO_SHIPMENT，再取消 Order | Workflow 在尚未建立 Shipment 時可直接取消 Order，不必真的執行一次 WMS 查詢 |
| 倉內取消 | 持久化 cancellation process 等待 WMS outcome | Workflow 發出 WMS cancellation Activity 並等待 terminal Signal |
| 已交運取消 | 可先 202，協調後 REJECTED | 已完成 workflow 的入口可直接 409；進行中依事實判斷 |
| WMS 安全取消後 Ordering 拒絕 | CONFLICT | Workflow failure，不能標示成功 |
| 庫存釋放 | OrderCancelled consumer | 相同 OrderCancelled consumer |
| canonical facts | Inbox／business transaction／Outbox | 仍需要 Kafka／Outbox，不是移除事件基礎設施 |

圖 04 的具體 NO_SHIPMENT／MULTIPLE_SHIPMENTS 分支依 Events 入口整理；不能把所有節點都聲稱為 Temporal 必然執行的 Activities。
