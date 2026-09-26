# 3PL 產品與領域策略

更新日期：2026-09-26

## 定位

Archone 目前是 **warehouse-centric 3PL execution system（A）**：上游提供貨主、訂單與出貨設施，系統負責
庫存承諾、倉內執行、離倉結果與跨模組履約協調。

目標是 **A+C**：在既有執行能力上增加 Outbound Fulfillment Operations 與 Control Tower 能力，使長時間流程、
SLA、重大例外與人工處置可以被追蹤、重試與恢復。

Archone 現階段不是 OMS／DOM（B）。它不主動選倉、跨倉拆單、承諾交期或進行成本最佳化；只有未來取得這些
決策權，才考慮引入權威 `FulfillmentPlan`，由 A+C 演進為 A+B+C。

## Current：已實作範圍

- Ordering：訂單建立、配貨結果、履約完成與取消生命週期。
- Inventory：owner 隔離的 stock position、movement、allocation、reservation 與 location。
- WMS：Shipment、Wave、Picking、Dispatch 與 Inbound Receiving 的基礎流程。
- Logistics Data：Owner、Product、SKU 與 Facility 等物流參考資料。
- Fulfillment coordination：Events／Temporal 兩種互斥 driver，共用相同 bounded-context use case。
- Transactional Outbox／Inbox、Debezium、Kafka 與 versioned Integration Event contracts。

## Target：已決定、尚未全部實作

- **Outbound Fulfillment Operations**：`OrderSubmission`、不可變 revision、人工審查、`FulfillmentCase`、SLA、
  取消與提升例外。
- **Operational Service Policy**：版本化 Owner service profile、硬條件與授權政策；法律合約仍在外部系統。
- **Partner Integration／ACL**：Owner-specific API／EDI／file mapping、identity、deduplication、ACK 與重送。
- **Control Tower**：跨 context projection、時間線、告警與工作清單；初期不擁有來源事實。
- **3PL Billing**：billable activity、rate card、rating、adjustment 與 billing statement；開票與總帳留在 ERP。

## 明確不做或尚未建立

- 商品目錄、購物車、結帳與金流。
- 需求預測、安全庫存規劃與採購決策。
- 自動選倉、跨倉 routing 與商業拆單。
- 承運商選擇、運輸中追蹤與送達證明。
- 完整 RMA、退款、換貨、整新與逆物流。
- WCS／PLC 設備控制。

Inbound execution 目前留在 WMS／Inventory；只有出現 appointment、ASN coordination、vendor variance、
detention SLA 等獨立語言與規則時，才評估 `Inbound Coordination`。Returns 先作為獨立 capability，待 RMA、
退款、換貨、整新與 disposition 足夠複雜後再升格 `Returns Management`。

## 成功條件

1. 不會跨 Owner 配貨或扣用庫存。
2. 相同 business identity 的重送不會重複建立 Order、reservation、Shipment 或扣帳。
3. 配貨承諾可追溯到 `StockOperation`、`StockMove`、`StockMoveLine` 與 `StockQuant`。
4. 只有 WMS handover 後才完成 outbound movement，之後 Ordering 才記錄 fulfilled。
5. 取消與 handover 競爭時只允許合法終態；未知結果維持可調查狀態，不猜測成功或失敗。
6. Context 透過公開 application boundary 或 versioned contract 合作，不共享彼此的 domain model。
7. 未核准的 `OrderSubmission` 不建立正式 Order、庫存承諾或 WMS 工作。
8. Control Tower 提供統一視圖，但不複製 Ordering、Inventory 或 WMS 的權威狀態。

尚未定義的量化 KPI、投資分類與部署策略列在[決策與待決問題](decisions.md)。
