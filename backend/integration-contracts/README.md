# Integration Event catalog

**Current · 2026-09-30.** `integration-contracts` 的 Java 類別、destination 常數與測試是本專案的 Integration Event 契約來源。下表協助閱讀事件流向；新增或修改事件時，須以發布 Adapter、訂閱 Dispatcher 與 Golden JSON 測試核對。此目錄不是另一份獨立的 schema 定義。

| Topic | Event type（均為 v1） | 發布 context | 本專案訂閱 context | 用途 |
| --- | --- | --- | --- | --- |
| `ordering.order-events` | `OrderPlacedIntegrationEvent` | Ordering | Inventory（events）；Fulfillment／Temporal（temporal） | 訂單已收到，啟動配貨或 workflow。 |
| `ordering.order-events` | `OrderCancelledIntegrationEvent` | Ordering | Inventory（events） | 訂單取消後處理配貨／庫存側狀態。 |
| `promising.allocation-events` | `OrderAllocationCommittedIntegrationEvent` | Inventory | Ordering、WMS（events）；Fulfillment／Temporal（temporal） | 配貨結果與出貨交接所需資料。 |
| `inventory.stock-events` | `StockAvailabilityIncreasedIntegrationEvent` | Inventory | Inventory Allocation | 庫存增加後重新評估配貨。 |
| `inventory.stock-operation-events` | `StockOperationLifecycleIntegrationEvent` | Inventory | 無本專案訂閱者 | 庫存操作生命週期稽核事實。 |
| `promising.fulfillment-handoffs` | `ShipmentHandedOverIntegrationEvent` | WMS | Inventory（events）；Fulfillment／Temporal（temporal） | 出貨已交接給承運商。 |
| `promising.fulfillment-handoffs` | `OutboundMovementsCompletedIntegrationEvent` | Inventory | Ordering（events） | 出庫搬運完成，推進訂單履約。 |
| `fulfillment.cancellation-requests` | `WmsCancellationRequestedIntegrationEvent` | Fulfillment | WMS（events） | 請 WMS 處理出貨取消。 |
| `wms.shipment-events` | `WmsCancellationRequestResolvedIntegrationEvent` | WMS | Fulfillment（events） | WMS 回報未產生 `ShipmentCancelledIntegrationEvent` 的取消結果。 |
| `wms.shipment-events` | `ShipmentCancelledIntegrationEvent` | WMS | Fulfillment（events）；Fulfillment／Temporal（temporal） | 出貨已取消。 |
| `fulfillment.ordering-cancellation-requests` | `OrderingCancellationRequestedIntegrationEvent` | Fulfillment | Ordering（events） | 請 Ordering 處理訂單取消。 |
| `ordering.cancellation-results` | `OrderingCancellationRequestResolvedIntegrationEvent` | Ordering | Fulfillment（events） | Ordering 回報訂單取消結果。 |

表中的 `events`／`temporal` 指 `ORDER_PROMISING_FULFILLMENT_ORCHESTRATION_MODE`；是否啟用訂閱仍受 `@ConditionalOnIntegrationEventConsumption` 控制。同一 topic 可承載多種 event type，消費端依註冊的型別處理。

Payload 範例位於 [`src/test/resources/contracts`](src/test/resources/contracts)，由 [`IntegrationEventJsonContractTest`](src/test/java/com/flowzati/archone/contracts/IntegrationEventJsonContractTest.java) 驗證 Java 輸出與已保存 JSON 的相符性，並驗證保存的 JSON 仍能讀回。Wire type／version 的唯一性由 [`IntegrationEventContractTest`](src/test/java/com/flowzati/archone/contracts/IntegrationEventContractTest.java) 檢查；實際允許的型別另由 monolith 的 `IntegrationEventNameMapping` 明確列出。

[`OrderPlaced` AsyncAPI demo](src/test/resources/asyncapi/order-placed-v1.json) 只描述一個 v1 事件，方便在 Registry 中試用；它不改變 Java 契約或 Kafka 訊息格式。測試會核對它的 topic、event type 與 payload 範例。此檔不代表 `ordering.order-events` 上的所有事件。

Topic、partition key、headers 與 replay 的維運規則見 [`Messaging operations`](../../docs/operations/messaging.md)。
