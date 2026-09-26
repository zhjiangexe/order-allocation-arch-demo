# Domain Vision

狀態：AS-IS 盤點＋第一輪 TO-BE 決策

## 願景敘述

Archone 是面向 3PL 倉配場景的執行與營運控制系統。它接收上游指定出貨倉的需求，在不同貨主的庫存嚴格隔離下，
完成受理審查、批次配貨、倉內作業與離倉扣帳，並讓長時間履約、SLA、例外與取消流程可以被追蹤、重試與恢復。

目前定位是 warehouse-centric 3PL（A），未來增加 Control Tower／process-manager 能力（C）。系統的主要價值
不是立即建立 OMS／DOM，而是使「受理決策、訂單需求、庫存承諾、實體作業、離倉結果」保持一致，並讓每個
業務邊界獨立演進。

## 主要使用者與利害關係人

| 角色 | 需要的結果 | 證據 |
| --- | --- | --- |
| 貨主／上游訂單系統 | 指定貨主與出貨倉，下達可追蹤的履約需求 | 已記錄（docs） |
| 接單／審查人員 | 處理服務等級與硬條件衝突，留下版本化核准紀錄 | TO-BE 已決策 |
| 配貨營運人員 | 看見等待配貨的需求、補貨後重新嘗試及最終承諾結果 | 已確認（code） |
| 倉內作業人員／WMS | 依已承諾的批次與數量執行 wave、pick、pack、stage、handover | 已確認（code） |
| 庫存管理人員 | 確保貨主隔離、在庫量、預留量與實際搬運一致 | 已確認（code） |
| 客服／營運人員 | 查詢履約進度並提交、追蹤取消請求 | 已確認（code） |
| Control Tower 人員 | 管理跨 context 例外、責任方、SLA 與升級 | TO-BE 已決策 |
| 財務／對帳人員 | 依不可變作業事實計算 3PL 費用並交付 ERP 開票 | TO-BE 已決策 |

## 現行業務範圍

### 包含

- 訂單建立、配貨結果記錄、履約完成與取消。
- 以貨主、設施、SKU、位置與批次為基礎的庫存可用量與移動。
- ship-complete 配貨與補貨後重試。
- WMS 的 Shipment、Wave、Picking、Dispatch 與 Inbound Receiving 基礎能力。
- 從收單到交付承運商的跨 context 履約協調。
- Events 與 Temporal 兩種互斥 driver，共用相同 bounded-context use case。

### 明確不包含或尚未建立

- 商品目錄、購物車、結帳與金流。
- 需求預測、安全庫存規劃及採購決策。
- 自動選倉與跨倉 routing；目前出貨設施由上游指定。
- 承運商選擇、運輸中追蹤與送達證明。
- 完整 RMA、退款、換貨、整新與逆物流；Return 目前只決定為獨立 capability 的目標方向。
- WCS／PLC 設備控制。

## TO-BE 已決策範圍

- Outbound Fulfillment Operations：`OrderSubmission`、版本化審查、`FulfillmentCase`、SLA、取消與提升例外。
- Partner Integration／ACL：Owner-specific API／EDI／file mapping、identity、deduplication、ACK 與重送。
- Control Tower：跨 context projection、時間線、告警與工作清單；初期不是來源事實的 bounded context。
- 3PL Billing：billable activity、rate card、rating、adjustment 與 billing statement；財務過帳留在外部 ERP。
- Operational Service／Product Profile：保存可重現決策所需的版本化作業政策與硬條件。

## 成功結果

以下是目前可由系統行為推導的成功條件，量化門檻仍待補充：

1. 不會跨貨主錯配或扣用庫存。
2. 相同 business identity 的重送不會重複建立訂單、預留、Shipment 或扣帳。
3. 配貨承諾能追溯到具體 Stock Operation、Move 與批次明細。
4. 只有實際 handover 後才完成 outbound movement，之後 Order 才能成為 fulfilled。
5. 取消與 handover 競爭時，只能有一個合法終態，且異常狀態可被查詢與調查。
6. Context 透過穩定的公開契約合作，不共享彼此的 domain model。
7. 未核准的 OrderSubmission 不會建立正式 Order、庫存承諾或 WMS 工作。
8. SLA 能區分實際經過時間與各責任方的 accountable time。
9. Control Tower 提供統一營運視圖，但不複製 Order、Inventory、Shipment 的權威狀態。

## 待補的策略資訊

- 可量化的履約、配貨與庫存正確性指標。
- 哪些能力是商業差異化，哪些只需達到產業標準。
- 系統未來偏向 modular monolith，或需按 context 獨立部署。
- Inbound coordination 與 Returns 何時具備升格 bounded context 的業務複雜度。
