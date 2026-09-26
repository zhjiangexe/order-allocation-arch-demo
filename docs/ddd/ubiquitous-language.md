# Ubiquitous Language

更新日期：2026-09-26

同一個字在不同 context 可以有不同意思；跨 boundary 時必須使用 published contract 的語言，不直接傳遞內部
aggregate 或 persistence model。

## Current vocabulary

| 詞彙 | Context | 定義 | 不等於 |
| --- | --- | --- | --- |
| Tenant | Platform／Identity | 營運 Archone 的 3PL provider／legal organization | Owner |
| Owner／貨主 | Logistics Data／全域 identity | tenant 內委託 3PL 保管與履約的一方；ACL 與庫存的強制隔離維度 | tenant、Facility |
| Facility／設施 | Logistics Data | 上游指定的履約節點／倉庫 identity | 最細 Stock Location |
| Order | Ordering | 由 Ordering 管理生命週期的正式履約需求 | StockOperation、Shipment |
| Order Line | Ordering | Order 中一個 SKU 與數量的需求明細 | StockMove |
| Allocation／配貨 | Inventory | 將完整需求承諾到具體可用庫存的決策與提交 | Picking |
| Proposal | Inventory Allocation | 可丟棄、尚未提交的規劃結果 | committed reservation |
| Stock Operation | Inventory Movement | 同一 source unit、路線與 assignment policy 下的一組 moves | WMS warehouse task |
| Stock Move | Inventory Movement | 某 SKU 從 source location 到 destination location 的搬運意圖 | 實際批次明細 |
| Stock Move Line | Inventory Allocation | Move 對特定 Quant／批次的承諾與執行明細 | Order Line |
| Stock Quant | Inventory Balance | owner、location、SKU、入庫日與效期下的物化庫存餘額 | movement history、SKU 總池 |
| On hand | Inventory | Quant 目前實際在庫數量 | ATP |
| Reserved | Inventory | 已被 Move Line 承諾、不可再次配置的數量 | 已揀貨 |
| Available／ATP | Inventory | `onHand - reserved`，再套用效期等 allocation 條件後的可承諾量 | 另一個持久化餘額 |
| Shipment | WMS | 將已承諾需求帶入倉內出庫執行的工作單元 | 運送中的 parcel |
| Wave | WMS | 依容量與優先序分組／釋放候選倉內工作 | 釋放 inventory reservation |
| Picking Work | WMS | 一組可執行的揀貨工作與 Pick Tasks | Allocation |
| Handover | WMS | 貨物已由倉庫交付承運商的 terminal fact | Delivered |
| Fulfilled | Ordering | Ordering 已記錄 Order 履約責任完成 | WMS Handover 狀態 |
| Cancellation Request | Fulfillment | 具 immutable identity、時間與原因的跨 context 協調請求 | 已取消 |
| Cancellation Process | Fulfillment／Events | 持久化的跨 context 取消協調狀態 | Order／Shipment 狀態 |
| Workflow Phase | Fulfillment／Temporal | durable workflow 的跨 context checkpoint | domain aggregate status |

## Target vocabulary

以下詞彙已作策略決策，但能力尚未全部實作：

| 詞彙 | Context | 定義 |
| --- | --- | --- |
| Operational Product Profile | Logistics Data | SKU 的版本化倉儲作業屬性與硬限制，不取代 Owner ERP 的商業主檔 |
| Operational Service Profile | Service Policy | Owner 的服務等級、SLA、核准、分批與混放等版本化作業設定 |
| Order Submission | Outbound Fulfillment Operations | 尚待驗證／核准的履約申請；不形成正式 Order 或庫存承諾 |
| Submission Revision | Outbound Fulfillment Operations | 同一 submission 的不可變內容版本；新版本使舊核准失效 |
| Fulfillment Case | Outbound Fulfillment Operations | 一張正式 Order 的端到端營運案件，擁有 milestones、SLA、exceptions 與結案判定 |
| Required Milestone | Outbound Fulfillment Operations | policy 要求 Case 結案前必須觀察到的 owner-context fact |
| Exception Case | Fulfillment／future Work Management | 跨 context、需人工決策、威脅 SLA 或重試後仍阻塞的業務案件 |
| Return Case | Returns | handover 後逆向流程的獨立案件；連結原 Order／Shipment，但不重開 FulfillmentCase |
| Billable Activity | 3PL Billing | 由已發生作業事實衍生、可依 RateCard 計價的不可變活動 |
| Control Tower | Cross-context surface | projection、時間線、告警與工作入口；不是來源 truth context |

`FulfillmentUnit` 不是目前共同語言。業界沒有一致定義，現階段以 OrderLine、Shipment reference、StockOperation
與 milestone 表達；只有出現真正獨立的 identity 與 lifecycle 才重新建模。

## 命名規則

- 過去式 event 表示已發生事實，例如 `OrderPlaced`、`ShipmentHandedOver`。
- Command 使用意圖語言；command accepted 不等於 terminal fact。
- `Released` 必須帶 context；Wave released、reservation released 與貨物離倉是不同事實。
- `Delivered` 保留給未來 TMS／last-mile，不用來描述離倉。
- `Proposal`、partial assignment 或 soft hold 不得翻譯成 committed。
- 外部 contract 可以使用接收方理解的語言，但必須能由 owner context 的完整事實推導。
