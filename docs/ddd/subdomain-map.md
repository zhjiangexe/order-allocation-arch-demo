# Subdomain Map

狀態：初稿；目前沒有產品策略要進行 Core／Supporting 取捨

## 能力地圖

| Subdomain / capability | 現行角色 | 已觀察到的設計重點 | DDD 投資分類 |
| --- | --- | --- | --- |
| Order Promising / Allocation | 配貨決策 | ship-complete、配貨順序、批次選擇、補貨喚醒與併發正確性 | 未分類 |
| Inventory Integrity / Movement | 庫存真相與搬運 | 3PL 貨主隔離、reservation、position 與實體扣帳正確性 | 未分類 |
| Warehouse Execution | 倉內執行 | Shipment、Wave、Picking、Dispatch、Receiving 與異常處理 | 未分類 |
| Order Lifecycle | 訂單生命週期 | 接收訂單並保存 allocation／fulfillment／cancellation 結果 | 未分類 |
| Outbound Fulfillment Operations | 出庫受理與營運協調 | Submission review、FulfillmentCase、SLA、取消與提升例外，不擁有下游真相 | 未分類 |
| Operational Service Policy | 貨主作業服務設定 | 服務等級、預設、SLA、核准矩陣、分批與 handling policy 的版本化 profile | 未分類 |
| Partner Integration | 外部夥伴接入 | API／EDI／file mapping、identity、deduplication、correlation、ACK 與重送 | 未分類 |
| Logistics Reference Data | 物流作業主檔 | Owner、Facility identity 與版本化 OperationalProductProfile | 未分類 |
| Control Tower | 跨 context 營運控制面 | projection、時間線、告警與工作清單；初期不是來源 truth context | 未分類 |
| Returns | 逆向物流能力 | ReturnCase、收貨關聯與處置協調；目前為獨立 capability/module | 未分類 |
| 3PL Billing | 作業計費 | BillableActivity、RateCard、Rating、Adjustment、BillingStatement | 未分類 |
| Messaging / Outbox / Inbox | 通用技術能力 | 提供可靠傳遞，不是 business subdomain | Generic technical capability |
| Temporal Runtime | 通用技術能力 | 提供 durable orchestration runtime，不是 business subdomain | Generic technical capability |

## 目前使用方式

這張圖只描述現有業務能力及其模型複雜度，不用來決定產品投資。因為目前沒有產品策略要做取捨，文件不把
Allocation、Inventory、WMS 或 Ordering 宣告為 Core／Supporting／Generic Subdomain。

即使未分類，仍應依現有風險維持下列工程要求：

- Allocation 與 Inventory 的承諾、併發及扣帳需要精確不變式與測試。
- WMS 與 Ordering 維持各自的 lifecycle 與語言，不因部署在同一 monolith 就合併模型。
- Outbound Fulfillment Operations 擁有受理、SLA、例外與協調規則，但不複製下游 domain truth。
- Control Tower 的統一畫面與查詢不構成合併 inbound、outbound、returns 模型的理由。
- Partner Integration 與 Billing 有自己的責任，不塞進 Fulfillment 或 WMS。
- Messaging 與 Temporal 保持為通用技術能力，不讓傳輸機制滲入 domain model。

## 未來何時才需要分類

只有未來需要制定產品定位、研發投資優先序、採購／自建策略或 team topology 時，才重新進行 Core、Supporting、
Generic 分類。屆時必須由商業差異化與組織決策判斷，不能只依程式碼行數或現有 module 大小分類。
