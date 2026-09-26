# Fulfillment orchestration current architecture

更新日期：2026-09-26

Fulfillment orchestration 協調 Ordering、Inventory 與 WMS 的長時間流程。它決定下一步呼叫誰、等待哪個結果及如何
處理取消，但不接管各 bounded context 的 business truth。

## Ownership boundary

| Owner context | 權威事實 |
| --- | --- |
| Ordering | Order 是否已配置、fulfilled、cancelled |
| Inventory | StockOperation／Move、reservation、quant 與 outbound movement completion |
| WMS | Shipment、warehouse work 與 handover／cancellation outcome |
| Fulfillment | 跨 context correlation、Events cancellation process、Temporal workflow checkpoint |

Workflow phase、process-manager state 與組合查詢不是 Order、StockOperation 或 Shipment 的替代狀態。

## Runtime modes

系統以設定選擇互斥的 `events` 或 `temporal` driver。兩者使用相同 Ordering、Inventory、WMS use cases 與 terminal
facts；差別只在跨 context coordination 的保存與推進方式。

### Events

- Integration Event consumer 接收前一步的 terminal fact，轉成 normalized command 後呼叫下一個 use case。
- Inbox、business state 與 follow-up Outbox publication 在同一 transaction。
- Cancellation 使用 `fulfillment` 自己的持久化 process manager。

### Temporal

- 收單事件啟動 `OrderFulfillmentWorkflow`。
- Workflow 透過 Activities 呼叫各 context 的既有 application boundary。
- Allocation、handover 與 cancellation outcome 經 event bridge 轉成 Signals。
- Workflow history 保存 coordination checkpoint；Activity 仍必須冪等，business transaction 仍由 owner context
  控制。
- Temporal 模式仍依賴 Kafka／Outbox 傳遞 canonical business facts。

程式入口與活動圖位於 `backend/orchestration-temporal-runtime`；公開 Workflow／Activity contract 位於
`backend/orchestration-temporal-contract`。

## Normal fulfillment

```text
Order placed
  → Inventory registers StockOperation and requests allocation
  → wait for committed assignment
  → WMS creates and executes Shipment
  → wait for Shipment handover
  → Inventory completes outbound movements
  → Ordering records fulfillment
```

- 缺貨時 operation 維持 confirmed，等待可用量增加後重新配置。
- WMS 內部 wave、pick、pack、stage 由 WMS 自行推進，orchestrator 不鏡像其細部狀態。
- Handover 是 WMS terminal fact，不是 Delivered；Inventory 完成出庫後，Ordering 才記錄 fulfilled。

## Cancellation

`POST /orders/{orderId}/cancellation-requests` 接收具 immutable `requestId`、`requestedAt` 與 `reason` 的請求。
HTTP `202` 只表示已受理協調，不表示 Order、Shipment 或 StockOperation 已取消。

Events mode 的 current state machine：

| State | Outcome | Transition／action |
| --- | --- | --- |
| `WAITING_WMS` | `NO_SHIPMENT` 或 Shipment cancelled | 進入 `WAITING_ORDERING`，發布 Ordering cancellation command |
| `WAITING_WMS` | WMS rejected | `REJECTED` |
| `WAITING_WMS` | multiple Shipments | `CONFLICT` |
| `WAITING_ORDERING` | cancelled／already cancelled | `COMPLETED` |
| `WAITING_ORDERING` | Ordering rejected | `CONFLICT`，交由人工調查 |

相同 request 與內容重送不重複發布；同一 Order 同時只允許一筆進行中的 cancellation process。每個 outcome 都由
process row lock 與既有結果驗證：完全相同的重送不再推進，不同結果則是明確衝突。

Temporal mode 由 Workflow Update 接收請求並協調對應 Activities／Signals，但仍遵守相同 owner-context 終態。

## Failure semantics

- Unknown、timeout 或 technical failure 不得被翻譯成 business rejection；流程留在等待／可重試狀態。
- Consumer failure 必須 rollback Inbox、business change 與 follow-up Outbox，交由 retry／DLT 處理。
- WMS 已取消 Shipment 後，不得用自動補償把它恢復成可出貨；跨 context 不一致進人工調查。
- Activity／message 可能重送，因此每個業務入口都要以穩定 business identity 保證冪等。
- Workflow replay 不得依 wall clock、random、I/O 或 mutable external state；這些工作只能在 Activity。
- Canonical terminal event 必須由 owner context 在自己的 business transaction 內發布。

## Target evolution

現行 module 將演進成 Outbound Fulfillment Operations：在 coordination 之外增加 `OrderSubmission`、review、
`FulfillmentCase`、milestones、SLA 與提升例外。Target 能力必須遵守下列限制：

- 待審 submission 不建立 Order、Inventory commitment 或 WMS work。
- `FulfillmentCase` 只組合 owner-context facts，不覆寫來源 aggregate state。
- 正常 retry 與 local exception 留在來源 context；只有跨 context、需人工決策或威脅 SLA 的問題才提升。
- 不以 generic `FulfillmentUnit` 預先抽象；先用實際出現的 OrderLine、Shipment、StockOperation 與 milestone。
- Events／Temporal 的長期取捨仍是 open decision，不能讓兩套 runtime 演進出不同業務語意。
