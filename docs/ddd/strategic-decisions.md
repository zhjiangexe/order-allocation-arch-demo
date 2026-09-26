# Strategic Decisions

狀態：2026-09-24 第一輪討論定案

本文件記錄目標定位與業務治理決策。它不是現行功能清單；實作是否存在仍以其他文件的 AS-IS／TO-BE 標記為準。

## 產品定位

- **目前**：Warehouse-centric 3PL（A），由上游決定訂單與出貨設施，Archone 負責庫存與倉內執行。
- **目標**：A+C，在 WMS 執行能力上增加跨 context 可視性、SLA、例外與人工處置。
- **暫不走 OMS／DOM（B）**：Archone 不主動選倉、跨倉拆單或承諾交期。
- 若未來取得 sourcing、promise、跨倉拆分與成本最佳化權，再引入權威 `FulfillmentPlan`，由 A+C 演進為
  A+B+C。

## 目標邊界

### Outbound Fulfillment Operations

`backend/fulfillment` 的目標定位是正式的 **Outbound Fulfillment Operations bounded context**，不是涵蓋入庫、
出庫與退貨的通用 Operations context。

它擁有：

- `OrderSubmission`、immutable submission revision 與人工審查。
- `FulfillmentCase`、要求里程碑、SLA 與 outbound 跨 context 協調。
- 取消流程與提升到中央處理的 outbound exception case。

它不擁有：

- Ordering 的 `Order` lifecycle。
- Inventory 的 reservation、movement 與 stock truth。
- WMS 的 Shipment、Wave、Picking 與 Dispatch truth。
- 自動選倉、跨倉 sourcing 或 delivery promise。

### Inbound、Returns 與 Control Tower

- Inbound execution 留在 WMS／Inventory；只有未來出現獨立的 appointment、ASN coordination、vendor variance、
  detention SLA 等語言與規則時，才建立 `Inbound Coordination` bounded context。
- Returns 先維持清楚的獨立 capability/module；`ReturnCase` 關聯原 Order／Shipment，但不重新開啟已完成的
  `FulfillmentCase`。RMA、退款、換貨、整新與 disposition 複雜後再升格 `Returns Management` bounded context。
- Control Tower 初期是跨 context projection、時間線、告警與工作清單，不擁有來源 domain truth。
- 若跨 inbound、outbound、returns 的指派、升級與責任時計形成真正一致的業務規則，再建立小型
  `Operational Work Management` context；不建立包辦所有流程的 generic `OperationalCase`。

## 訂單受理與服務等級

### 選擇與驗證

1. 上游有指定服務等級時，以該要求為 `requestedServiceLevel`。
2. 未指定時，使用 Owner 的 `OperationalServiceProfile` 預設值。
3. Archone 驗證合約授權、冷鏈、法規與 Facility capability 等硬條件。
4. 要求與硬條件不一致時不自動覆寫並繼續履約，而是停止並進入人工確認。
5. 法規、安全或物理能力不允許的方案不可被人工強制放行；只能選擇合規方案或拒絕申請。

### 待審申請

- 待審期間只有 `OrderSubmission`／`submissionId`，尚未建立正式 `Order`。
- 不發布 `OrderPlaced`、不配置或保留庫存、不建立 WMS 工作；可以查詢可用量作為審查參考。
- Owner 未回覆時持續等待，不自動核准或拒絕。`reviewDueAt` 逾期只產生提醒、升級與 SLA 記錄。
- 同一申請使用不可變 revision；新 revision 使舊核准失效，審核決定必須指向明確版本。
- 核准時快照合約、政策與服務等級；建立 Order 前以最新資料重新驗證不可違反的硬條件。

### 分級授權

- 合約預先授權、無額外費用且不降低 SLA：3PL operations 可核准。
- 影響價格、SLA 或上游承諾：Owner 核准。
- 重大風險：依政策進行 joint approval。
- 法規、安全、溫控或 Facility 能力不允許：`NOT_OVERRIDABLE`。

## 正式 Order 與 FulfillmentCase

- 每張正式 Archone `Order` 固定一個 `Facility`。
- 跨 Facility 由上游拆成多張 warehouse-specific Order，使用 `sourceOrderId`／`externalOrderGroupId` 聚合查詢。
- 是否允許分批出貨由上游指定；未指定時採用 Owner 合約預設。Archone 不自行做商業拆單。
- 每張正式 Order 對應一個 `FulfillmentCase`；Case 可關聯多個 Shipment 與部分數量。
- 暫不建立 `FulfillmentUnit`。只有出現不能用 OrderLine／Shipment reference 表達的獨立履約分支時，才從業務
  語言重新命名與建模。
- `FulfillmentCase` 依 service policy 的 required milestones 自動結案；全部里程碑完成且無未結例外才是
  `COMPLETED`。

## SLA 與例外

- 分開 `IntakeSla`、`FulfillmentSla` 與 `ExceptionResolutionSla`。
- 同時保留 wall-clock elapsed time 與 accountable time；等待 Owner 時可暫停 3PL 責任時計，但不隱藏實際等待。
- 正常路徑依 policy 自動處理；重大例外進人工 Control Tower。
- Local exception 先由來源 context 處理。只有跨 context、需要人工決策、威脅 SLA、自動重試後仍阻塞，或等待
  外部角色時，才提升成中央 `ExceptionCase`。
- 技術重試、dead letter 與 observability incident 不會自動等同 business exception。

## 多 Owner、主檔與權限

- 頂層 tenant 是營運 Archone 的 3PL provider／legal organization；Owner 是 tenant 內受強制保護的貨主資料範圍。
- 3PL 人員可依角色存取多個 Owner；Owner portal 使用者只能看到獲授權的 Owner。
- 庫存帳務所有權必須嚴格隔離，禁止跨 Owner 自動消耗；是否物理混放由合約、SKU、批號、效期、溫層與法規決定。
- 法律合約、簽署與續約留在外部 CRM／ERP；Archone 擁有版本化 `OperationalServiceProfile`。
- Owner ERP 擁有 SKU 商業主檔；Logistics Data 擁有版本化 `OperationalProductProfile`，包含尺寸、重量、溫層、
  危險品、批號／序號／效期、包裝與 handling constraints。

## Partner Integration 與 Billing

- Partner Integration／ACL 負責 Owner-specific API、EDI、file format、外部 identity mapping、deduplication、
  correlation、ACK 與重送，再送 normalized command 給業務 context。
- Partner Integration 不決定是否可以履約。合約、硬條件、人工審查與正式受理由 Outbound Fulfillment Operations 負責。
- 建立獨立 `3PL Billing` context，擁有 `BillableActivity`、`RateCard`、`Rating`、`Adjustment` 與
  `BillingStatement`。
- 發票、收款、應收與總帳留給外部 ERP；計費更正以 adjustment 表達，不覆寫原始 billable fact。

## 未做的投資分類

本輪未決定 Core／Supporting／Generic business subdomain，也未做自建／採購與 team topology 取捨。這些判斷需等產品
策略與商業差異化目標成立後另行處理。
