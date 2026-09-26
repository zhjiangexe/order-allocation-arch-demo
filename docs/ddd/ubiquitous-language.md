# Ubiquitous Language

狀態：初稿；同一詞在不同 context 的意思刻意分開

| 詞彙 | Context | 定義 | 容易混淆的詞 |
| --- | --- | --- | --- |
| Tenant | Platform / Identity | 營運 Archone 的 3PL provider／legal organization | 不等於 Owner |
| Owner / 貨主 | Logistics Data / 全域 identity | Tenant 內的 3PL 委託方；ACL、庫存、政策、訂單與計費隔離的必要維度 | 不是 tenant、倉庫或承運商 |
| Facility / 設施 | Logistics Data | 上游指定的履約節點／倉庫 identity | 不等於最細 Stock Location |
| Operational Product Profile | Logistics Data | SKU 的版本化倉儲作業屬性與硬限制 | 不等於 Owner ERP 的商業 Product Master |
| Operational Service Profile | Operational Service Policy | Owner 的服務等級、SLA、核准、分批、混放等版本化作業設定 | 不等於已簽署的法律合約或 Billing RateCard |
| Order Submission | Outbound Fulfillment Operations | 尚待驗證／核准的上游履約申請；不形成庫存承諾 | 不等於正式 Order |
| Submission Revision | Outbound Fulfillment Operations | 同一 submission 的不可變內容版本；新版本使舊核准失效 | 不得原地覆寫已審內容 |
| Requested Service Level | Outbound Fulfillment Operations | 上游指定或 Owner default 解析出的要求 | 不等於最終可執行服務 |
| Effective Service Level | Outbound Fulfillment Operations | 經合約、硬條件與必要人工核准後採用的服務 | 必須保留來源、理由與 policy version |
| Review Case | Outbound Fulfillment Operations | 服務要求與合約／硬條件不一致時的人工決策案件 | 待審期間不存在正式 Order |
| Order | Ordering | 上游提交、由 Ordering 管理生命週期的履約需求 | 不等於 Stock Operation 或 Shipment |
| Order Line | Ordering | Order 中針對 SKU 與數量的需求明細 | 不等於 Stock Move |
| Allocation / 配貨 | Inventory | 將需求完整承諾到具體可用庫存的決策與提交 | 不等於 Picking |
| Proposal | Inventory Allocation | 可丟棄、尚未提交的規劃結果 | 不得對外宣稱 committed |
| Stock Operation | Inventory Movement | 同一來源與路線下的一組搬運意圖 | 不等於 WMS Shipment |
| Stock Move | Inventory Movement | 某 SKU 從 source 到 destination 的權威搬運意圖 | 不等於實際 Move Line |
| Stock Move Line | Inventory Reservation | Move 對具體 Quant／批次的承諾與執行明細 | 不等於 Order Line |
| Stock Quant | Inventory Balance/Position | 特定 owner/location/SKU/batch 的 materialized stock position | 不是交易歷史 |
| On hand | Inventory | 該位置實際在庫數量 | 不等於 ATP |
| Reserved | Inventory | 已由 Move Line 承諾、不可再分配的數量 | 不表示已揀貨 |
| Available / ATP | Inventory | 在限制條件下可再承諾的數量，通常由 position 聚合計算 | 不應另存成第三套真相 |
| Shipment | WMS | 將已承諾需求帶入倉內出庫執行的工作單元 | 不等於配送中的 parcel |
| Wave | WMS | 將候選倉內工作依容量與優先序分組／釋放 | `release` 不代表釋放庫存 |
| Picking Work | WMS | 一組可執行的揀貨工作，包含 Pick Tasks | 不等於 Allocation |
| Handover | WMS | 貨物已由倉庫交付承運商的 terminal fact | 不宣稱 Delivered |
| Fulfilled | Ordering | Order 的履約責任已完成 | 不與 WMS Handover 共用狀態詞 |
| Fulfillment Case | Outbound Fulfillment Operations | 一張正式 Order 的端到端 outbound 營運案件，擁有里程碑、SLA、例外與結案判定 | 不取代 Order、Shipment 或 StockOperation；不使用 `FulfillmentUnit` |
| Required Milestone | Outbound Fulfillment Operations | Service policy 要求 Case 完成前必須觀察到的業務事實 | 不是來源 aggregate 的第二套狀態 |
| Intake SLA | Outbound Fulfillment Operations | Submission 受理與審查的責任時計 | 不等於正式接單後 Fulfillment SLA |
| Fulfillment SLA | Outbound Fulfillment Operations | 正式 Order／Case 開啟至 required milestones 完成的責任時計 | 應區分 accountable time 與 wall-clock time |
| Exception Resolution SLA | Outbound Fulfillment Operations / Work Management | 每個提升例外從建立到解決的責任時計 | 技術 retry latency 不自動等同此 SLA |
| Exception Case | Outbound Fulfillment Operations / future Work Management | 跨 context、需人工決策、威脅 SLA 或重試後仍阻塞的業務案件 | 不等於 local domain exception 或 technical incident |
| Cancellation Request | Fulfillment | 具 immutable identity、時間與原因的跨 context 協調命令 | HTTP 202 不等於已取消 |
| Cancellation Process | Fulfillment | Events mode 中持久化的取消協調狀態 | 不取代 Order/Shipment 的狀態 |
| Workflow Phase | Fulfillment/Temporal | 跨 context checkpoint 的可觀測進度 | 不是 domain aggregate status |
| Return Case | Returns capability | Handover 後逆向流程的獨立案件，連結原 Order／Shipment | 不重新開啟原 FulfillmentCase，也不等同一般 Inbound Receipt |
| Billable Activity | 3PL Billing | 由已發生作業事實衍生、可依 RateCard 計價的不可變活動 | 不等於 invoice line；更正使用 Adjustment |
| Control Tower | Cross-context application surface | 聚合 projection、時間線、告警與工作清單的營運入口 | 初期不是來源 truth bounded context |

## 命名原則

- 過去式名稱表示已發生事實，例如 `OrderPlaced`、`ShipmentHandedOver`。
- Command 使用祈使／意圖語言，例如 `CancelShipmentCommand`；command accepted 不等於 terminal fact。
- `Released` 必須帶 context：Wave released、reservation released 與貨物離倉是不同事實。
- `Delivered` 保留給未來 TMS／last-mile，不用來描述離倉。
- Context 外只承諾 published contract 的語意，不暴露內部 Aggregate 或 persistence model。
- `FulfillmentUnit` 暫不列入共同語言；業界無統一語意，現階段以 OrderLine、Shipment reference 與 milestone 表達。
- `Operations Control` 不作為包辦 inbound、outbound、returns 的通用 context 名稱；共用畫面不等於共用模型。
