# Bounded Context Catalog

狀態：AS-IS catalog＋第一輪 TO-BE 邊界決策

## 總覽

| Bounded Context | 使命 | 擁有的主要事實 | 對應實作 |
| --- | --- | --- | --- |
| Ordering | 接收訂單並管理訂單生命週期 | `Order`、`OrderLine`、`OrderStatus` | `backend/ordering` |
| Inventory | 管理搬運需求、配貨承諾、庫存位置與數量 | `StockOperation`、`StockMove`、`StockMoveLine`、`StockQuant`、`StockLocation` | `backend/inventory` |
| WMS | 管理倉內執行直到交付承運商 | `Shipment`、`Wave`、`PickingWork`、`ShipmentDispatch`、`InboundOperation` | `backend/wms` |
| Logistics Data | 管理物流主檔與參考資料 | `Owner`、`Product`、`Sku`、`Facility` | `backend/logistics-data-context` |
| Fulfillment Coordination（現行） | 管理跨 context 履約查詢與取消協調 | `CancellationProcess` 與 workflow-facing application state | `backend/fulfillment`、`backend/orchestration-temporal-*` |

`integration-contracts`、`archone-framework` 與 `deployments:monolith` 是契約、技術能力與部署組裝，不是 bounded
context。Inventory 與 WMS 內的 package 是內部 domain module；除非出現獨立語言、模型所有權與團隊生命週期，
不因 package 分割就提升成 bounded context。

## TO-BE 邊界狀態

| 能力／邊界 | 決策 | 說明 |
| --- | --- | --- |
| Outbound Fulfillment Operations | 正式 bounded context | 由現行 Fulfillment Coordination 演進，擁有受理、Case、SLA、取消與提升例外 |
| Operational Service Policy | 目標 bounded context | 擁有版本化 Owner service profile；法律合約仍在外部 CRM／ERP |
| Partner Integration | 先做獨立 ACL/module | Partner onboarding、mapping lifecycle 與回覆治理成熟後再升格 context |
| 3PL Billing | 正式目標 bounded context | 擁有作業計費，不擁有 invoice、AR 或 general ledger |
| Returns | 獨立 capability/module | 複雜 RMA、退款、換貨、整新、disposition 後再升格 Returns Management |
| Control Tower | 初期不是 bounded context | 是跨 context projection、告警與工作入口；共用畫面不代表共用 domain model |
| Inbound Coordination | 尚不建立 | Inbound execution 留在 WMS／Inventory；有獨立協調語言與規則後再評估 |

## Ordering

**使命**：接受具備貨主、指定設施與明細的訂單，保存 allocation、fulfillment 與 cancellation 對 Order 的結果。

**擁有**：Order identity、Order lifecycle、OrderLine、DeliveryTerms，以及 Order 是否已 allocated、fulfilled、cancelled。

**不擁有**：可用庫存計算、批次承諾、倉內作業狀態、跨 context 取消流程進度。

**公開合作**：發布 order placed/cancelled；接收 allocation committed、outbound movements completed 與協調後的取消命令。

## Inventory

**使命**：把外部需求轉成 source-neutral movement intent，規劃並提交庫存承諾，維持位置數量與搬運生命週期。

**擁有**：

- Movement：operation/move 的意圖、方向與生命週期。
- Allocation：需求、供給、proposal 與 assignment policy。
- Reservation：Move-to-Quant 的持久承諾；目前部分型別仍位於 `allocation` package。
- Balance/Position：`StockQuant` 的 on-hand 與 reserved 數量。
- Location：位置身分與 usage。

**不擁有**：Order lifecycle、Shipment/Wave/Pick lifecycle、Owner/Product/SKU 主檔生命週期。

**已知漂移**：`docs/inventory-domain-ownership.md` 使用 Position／Reservation 五模組語言；現行 package 使用
`balance`，而 `StockMoveLine` 位於 `allocation`。策略概念仍可成立，但 package TO-BE 尚未確認。

## WMS

**使命**：將已承諾的出庫需求轉成可執行的倉內工作，直到貨物交付承運商；也包含 inbound receiving 基礎流程。

**擁有**：Shipment、Wave、PickingWork/PickTask、ShipmentDispatch、InboundOperation 及其內部狀態。

**不擁有**：Order 是否 fulfilled、StockQuant 真實數量、跨 context 取消流程的整體終態。

**內部 module**：`shipment`、`wave`、`picking`、`dispatch`、`receiving`；`process` 目前負責模擬 actor 與內部推進。

## Logistics Data

**使命**：提供物流流程共同參考的 owner、product、SKU、facility identity 與倉儲作業 profile。

**擁有**：Owner／Facility identity、owner-to-facility 關係，以及版本化 `OperationalProductProfile`，包含尺寸、
重量、溫層、危險品、批號／序號／效期、包裝與 handling constraints。

**不擁有**：SKU 商業名稱、售價與採購主檔、貨主配貨政策、庫存、訂單或倉內 operation。商業 Product Master
由 Owner ERP 擁有；貨主專屬 allocation policy 由 Inventory 擁有。

**待確認**：目前下游主要以 ID/snapshot 使用主檔，尚未看到正式跨 context published contract；需確認正式整合方式。

## Outbound Fulfillment Operations

**現行起點**：`backend/fulfillment` 目前以組合式履約查詢與持久化取消流程為主。

**目標使命**：在正式 Order 之前管理 outbound submission、服務政策驗證與人工核准；正式接單後管理端到端
FulfillmentCase、SLA、里程碑、取消與提升例外。

**目標擁有**：

- `OrderSubmission`、immutable revision 與 review decision。
- `requestedServiceLevel`、`effectiveServiceLevel` 及適用 policy snapshot。
- `FulfillmentCase`、required milestones、responsibility clocks 與 completion decision。
- Events mode 的 `CancellationProcess`、請求冪等身分、跨 context outcome 與協調狀態。
- 符合提升條件的 outbound `ExceptionCase`。

**不擁有**：Order、StockOperation、Shipment 的業務狀態；workflow phase 不是第二套 domain truth。

**邊界決策**：正式定位為 Outbound Fulfillment Operations bounded context。Temporal contract/runtime 是其中一種
執行機制，不是另一個 context；也不因使用 durable workflow 就自動取得選倉、拆單或 promise 權力。

## Operational Service Policy（TO-BE）

**使命**：把外部法律合約轉成 Archone 可執行、可版本化、可追溯的 Owner 作業服務設定。

**擁有**：`OperationalServiceProfile`、允許／預設服務等級、SLA 與暫停規則、approval matrix、partial shipment、
inventory commingling 與有效期間。

**不擁有**：法律條款、簽署文件、報價與續約；這些仍由外部 CRM／ERP／CLM 擁有。Billing 的 `RateCard` 由
3PL Billing 擁有，避免合約與計價模型互相滲透。

## 3PL Billing（TO-BE）

**使命**：將已發生的倉儲與履約事實轉成可稽核的費用及對帳明細。

**擁有**：`BillableActivity`、`RateCard`、`Rating`、`Adjustment` 與 `BillingStatement`。

**不擁有**：來源作業事實、發票、收款、應收帳款與總帳。來源更正以新的 adjustment 表達，不回寫或覆蓋原始
business fact。

## Partner Integration／ACL（TO-BE 邊界）

**使命**：隔離 Owner-specific protocol 與 published language，將外部訊息轉為 normalized application command。

**擁有**：外部 identity mapping、message correlation、deduplication、mapping version、ACK／retry state。

**不擁有**：履約可行性、合約授權、服務等級選擇與人工核准。初期可為獨立 integration module；若 partner
onboarding 與 mapping lifecycle 成為成熟業務能力，再提升為 bounded context。

## Control Tower 與 Returns 的邊界

- Control Tower 初期只擁有 projection 與 UI workflow，不宣告來源 aggregate 的 terminal state。
- 若跨流程的工作指派、升級與責任時計形成獨立語言，可抽成 `Operational Work Management` context。
- Returns 先以獨立 capability/module 管理 `ReturnCase`；它不屬於 outbound `FulfillmentCase` lifecycle，也不因共用
  receiving execution 就等同一般 inbound。
