# Domain Narrative

狀態：AS-IS 履約主線＋TO-BE 受理與營運控制

## 受理與審查（TO-BE 已決策）

1. Partner Integration 將 Owner-specific API／EDI／file request 轉成 normalized submission command。
2. Outbound Fulfillment Operations 建立 `OrderSubmission` revision，解析上游指定的 service level；未指定時使用
   Owner `OperationalServiceProfile` 的預設值。
3. 系統檢查合約授權、冷鏈、法規、OperationalProductProfile 與 Facility capability。
4. 條件一致時可核准 submission；不一致時建立人工 review case，並依授權政策交給 3PL、Owner 或雙方核准。
5. 待審期間不建立 Order、不保留庫存、不啟動 WMS。Owner 未回覆時持續等待，逾期只產生提醒、升級與 SLA 記錄。
6. 核准時快照 service、contract 與 policy version；建立正式 Order 前重新驗證最新的不可違反硬條件。
7. 驗證通過後才由 Ordering 建立正式 `Order`，並發布「Order 已可靠成立」的跨 context 事實。

```text
Partner request
  -> OrderSubmission revision
  -> policy and hard-constraint validation
  -> approved ───────────────> formal Order
  -> inconsistent -> review -> approved / rejected / waiting
```

## 正常履約（現行事件主線）

1. Ordering 已建立包含 `ownerId`、單一 `facilityId`、訂單明細與交付條件的正式 `Order`。
2. Ordering 將「訂單已成立」發布為跨 context 事實。
3. Inventory 以訂單作為需求來源，建立 `StockOperation` 與 `StockMove`。
4. Allocation 依貨主策略、可用 `StockQuant`、批次條件與 ship-complete 規則產生 proposal。
5. Inventory 在同一交易內鎖定 position、建立 Move-to-Quant 承諾，並將 operation 標為 assigned。
6. Inventory 發布完整的配貨承諾；Ordering 記錄 allocation，WMS 以同一 snapshot 建立 Shipment。
7. WMS 規劃並釋放 Wave，完成 Picking、Packing、Staging 與 carrier handover。
8. WMS 發布 handover 事實；Inventory 以 movement proof 完成 outbound moves 並扣減實體 position。
9. Inventory 發布 outbound movements completed；Ordering 將 Order 記為 fulfilled。

```text
Order placed
  -> stock demand registered
  -> allocation committed
  -> shipment created
  -> wave / pick / pack / stage
  -> shipment handed over
  -> outbound movements completed
  -> order fulfilled
```

## Facility 與分批出貨（TO-BE 已決策）

- 每張正式 Archone Order 只屬於一個 Facility。
- 跨 Facility 由上游拆成多張 warehouse-specific Order，透過 `sourceOrderId`／`externalOrderGroupId` 提供總覽。
- 是否允許分批出貨由上游指定；未指定時由 Owner profile 決定。
- 一張 Order 對應一個 `FulfillmentCase`，可關聯同倉產生的多個 Shipment 與部分數量。
- 暫不建立額外 `FulfillmentUnit`；Control Tower 直接使用 OrderLine、Shipment reference 與 milestone projection。

## 缺貨與補配

1. 若完整訂單無法滿足，Inventory 保留 confirmed operation，不建立不完整承諾。
2. 入庫或其他異動使可用量增加時，Inventory 發布 availability increased。
3. Allocation 依 queue 與貨主策略重新嘗試等待中的 operation。
4. 只有完整持久化的 assignment 才能對外宣告 allocation committed。

## 取消

取消請求不是「立即把所有資料改為 cancelled」，而是一個跨 Ordering、WMS 與 Inventory 的協調流程。

- Shipment 尚不存在時，可直接取消 Order，之後由 `OrderCancelled` 觸發 Inventory 釋放需求／承諾。
- Shipment 存在但尚未 handover 時，先要求 WMS 取得安全終態，再取消 Order。
- handover 已成立時，正常履約勝出；後續若需退回，屬於尚未納入範圍的 reverse logistics。
- Events mode 由 Fulfillment 的持久化 `CancellationProcess` 協調；Temporal mode 由既有 Order workflow 協調。

## SLA、例外與結案（TO-BE 已決策）

- `IntakeSla`、`FulfillmentSla`、`ExceptionResolutionSla` 分開計時。
- `FulfillmentCase` 建立時快照 required milestones 與 completion policy。
- 全部 required milestones 完成且沒有未結例外時，Case 才自動成為 `COMPLETED`。
- WMS／Inventory 的 local exception 先在來源 context 處理；需要人工決策、跨 context、威脅 SLA、重試後仍阻塞，
  或等待外部角色時，才提升成中央 `ExceptionCase`。
- Control Tower 聚合案件、里程碑、責任時計與告警，但不改寫來源 aggregate 狀態。

## 退貨與計費（TO-BE 方向）

- Handover 後的逆向需求建立獨立 `ReturnCase`，連結原 Order／Shipment，不重新開啟原 `FulfillmentCase`。
- WMS／Inventory 仍擁有實際收貨、檢驗、隔離、上架與報廢事實。
- 各 context 發生的可計費事實送入 3PL Billing，形成不可變 `BillableActivity`；更正使用 Adjustment，發票由外部
  ERP 產生。

## Context 內外的事實

- Ordering 的 `OrderStatus`、Inventory 的 operation/move/quant、WMS 的 shipment/work/dispatch 各自是權威事實。
- Fulfillment case／workflow phase 只表示協調進度、里程碑與責任，不鏡像上述聚合狀態。
- Integration Event 是跨邊界的 published language，不是任一 consumer 的內部 domain object。
