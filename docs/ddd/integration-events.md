# Integration Event Catalog

狀態：依 `backend/integration-contracts/src/main/java` 盤點的 AS-IS；另列 TO-BE 契約需求

## Lifecycle 與履約事件

| Integration Event | Producer | 主要 consumers | 業務語意 |
| --- | --- | --- | --- |
| `OrderPlacedIntegrationEvent` | Ordering | Inventory、Temporal starter | Order 已可靠成立，可開始履約 |
| `OrderAllocationCommittedIntegrationEvent` | Inventory | Ordering、WMS、Temporal bridge | 完整 Stock Operation 已承諾到具體 movement/batch |
| `StockAvailabilityIncreasedIntegrationEvent` | Inventory | Inventory Allocation | 某 owner/facility/location/SKU 的可用量增加，可喚醒 backlog |
| `StockOperationLifecycleIntegrationEvent` | Inventory | audit / interested consumers | Stock Operation lifecycle snapshot 已改變 |
| `ShipmentHandedOverIntegrationEvent` | WMS | Inventory、Temporal bridge | Shipment 已交付承運商，可完成 outbound movement |
| `OutboundMovementsCompletedIntegrationEvent` | Inventory | Ordering | 對應 Shipment 的 outbound movements 與扣帳已完成 |
| `OrderCancelledIntegrationEvent` | Ordering | Inventory | Order 已取消，Inventory 可釋放來源需求或承諾 |

## 取消協調事件

| Integration Event | Producer | Consumer | 業務語意 |
| --- | --- | --- | --- |
| `WmsCancellationRequestedIntegrationEvent` | Fulfillment | WMS | 請 WMS 對指定 Order 的 Shipment 作出取消決定 |
| `ShipmentCancelledIntegrationEvent` | WMS | Fulfillment / Temporal bridge | 具體 Shipment 已取消，包含 operation 與 request correlation |
| `WmsCancellationRequestResolvedIntegrationEvent` | WMS | Fulfillment | WMS 對沒有 Shipment、拒絕或多 Shipment 等情況作出結果 |
| `OrderingCancellationRequestedIntegrationEvent` | Fulfillment | Ordering | WMS 已安全後，請 Ordering 取消 Order |
| `OrderingCancellationRequestResolvedIntegrationEvent` | Ordering | Fulfillment | Ordering 回覆 cancelled、already cancelled 或 rejected |

## 契約治理

1. Versioned contracts 位於 `integration-contracts`；目前公開 namespace 為 `v1`。
2. Event identity、aggregate identity、correlation identity 與 occurred time 應分工清楚。
3. Consumer 必須支援至少一次傳遞下的冪等處理；重送不可重複改變 domain state。
4. Producer 只發布 canonical fact，不為 Events／Temporal mode 建立兩套事件。
5. Integration Event schema 演進需維持 consumer 相容性，並以 JSON contract tests 固定 wire shape。
6. Destination、partition key 與 Outbox/Kafka 是 delivery policy，不應改變事件的業務語意。
7. TO-BE intake 中，`OrderPlacedIntegrationEvent` 只能在 Submission 已核准、正式 Order 已建立後發布；待審不重用
   `OrderStatus.PENDING` 或 `OrderPlaced` 語意。
8. 同一完整 Application Event 需要通知 Control Tower、Partner Integration 與 Billing 時，由 Adapter 依 audience
   原子轉成對應 Integration Event，不讓 use case 重複發布多個外部表示。

## TO-BE published language 需求

下表描述需要跨邊界傳遞的業務事實，名稱尚未定稿，也不代表 `integration-contracts` 已經存在對應 class。

| 業務事實 | Producer | 主要 audience | 用途 |
| --- | --- | --- | --- |
| Submission review resolved | Outbound Fulfillment Operations | Partner Integration、Control Tower | 回覆核准、拒絕或等待狀態，必須帶 submission revision |
| Fulfillment case opened / completed | Outbound Fulfillment Operations | Control Tower、Partner Integration | 呈現正式履約責任與完成時間，不取代 Order facts |
| Fulfillment exception raised / resolved | Outbound Fulfillment Operations | Control Tower / future Work Management | 建立工作、責任時計與升級 |
| Operational service profile published | Operational Service Policy | Fulfillment、WMS、Inventory | 傳遞可執行的 version identity；consumer 依需要讀取 snapshot |
| Operational product profile published | Logistics Data | Fulfillment、WMS、Inventory | 更新倉儲硬條件與 profile version |
| Return case progressed | Returns capability | Control Tower、Partner Integration | 追蹤逆向流程，不冒充一般 inbound lifecycle |
| Billable operational fact | Ordering／Inventory／WMS／Returns | 3PL Billing | 由完整業務事實轉成 billing audience contract，產生 BillableActivity |
| Billing statement finalized | 3PL Billing | External ERP／Partner Integration | 交付對帳與開票輸入，不宣稱 invoice 已成立 |

Partner Integration 收到外部 request 時先轉為 normalized command；外部 payload 本身不是 Archone 的 domain event。
Consumer 不得從 submission contract 直接編排 OrderPlaced publication。

## 待補欄位

後續應從 destination constants、subscriber configuration 與 observability metadata 補齊：

- 每個 event 的實際 destination 與 partition key。
- 所有 subscriber ID 與 retry/DLT owner。
- schema 相容政策及 v1 deprecation 規則。
- event retention、重播與敏感資料分類。
- TO-BE fact 的正式名稱、schema owner、audience、correlation identity 與相容策略。
