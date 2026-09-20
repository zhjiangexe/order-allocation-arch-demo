# Events-mode cancellation process manager

`POST /orders/{orderId}/cancellation-requests` 先向 Ordering 查詢是否可受理；受理後，`fulfillment-process` 在同一個交易寫入 `cancellation_processes` 與 outbox 的 `WmsCancellationRequestedIntegrationEvent`。這個 HTTP 202 僅表示請求已受理，不表示訂單已取消。重送相同 `requestId` 與內容不會再發布命令；同一張訂單同時只允許一筆進行中的取消流程。

流程狀態由 `fulfillment-process` 擁有：

| 目前狀態 | 收到的事實 | 下一步 |
| --- | --- | --- |
| `WAITING_WMS` | WMS `NO_SHIPMENT` 或 `ShipmentCancelled` | 寫入 `WAITING_ORDERING`，同交易發布 `OrderingCancellationRequestedIntegrationEvent` |
| `WAITING_WMS` | WMS `REJECTED` | 寫入 `REJECTED` |
| `WAITING_WMS` | WMS `MULTIPLE_SHIPMENTS` | 寫入 `CONFLICT` |
| `WAITING_ORDERING` | Ordering `CANCELLED` 或 `ALREADY_CANCELLED` | 寫入 `COMPLETED` |
| `WAITING_ORDERING` | Ordering `REJECTED` | 寫入 `CONFLICT`，需要人工調查 WMS/Ordering 狀態 |

`GET /orders/{orderId}/cancellation-requests/{requestId}` 可查目前狀態。Ordering 不再直接訂閱 WMS 的取消結果；它只接受 process manager 的取消命令，透過自己的 Usecase 更改 Order，並發布帶 `requestId` 的結果事件。Temporal 模式維持原有 workflow。

Consumer 只將版本化事件轉為不帶傳輸細節的 outcome command。Usecase 鎖住流程列，由 `CancellationProcess` 核對原請求與狀態轉移；表內保存已處理的 WMS／Ordering outcome，完全相同的重送不再推進，不同結果則明確報錯。狀態更新與下一個 outbox 事件在同一個交易。技術錯誤會回滾並由既有 consumer retry/DLT 機制處理；流程保留在等待狀態，不把未知結果宣稱為失敗。DLT 或長時間等待需要操作人員調查與重播，尤其 WMS 已取消 Shipment 後不得自動補償為可出貨。未來獨立部署 fulfillment-process 時，須將 `cancellation_processes` migration 隨其資料庫部署。
