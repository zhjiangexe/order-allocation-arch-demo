# WMS context

`wms-context` 是由 `deployments:monolith` 組裝的 WMS bounded context。package 依同層業務能力切分，
而不是以 `inbound`、`outbound` 當成兩個大型容器：

| Capability | 責任與 aggregate |
| --- | --- |
| `receiving` | 到貨、驗收、上架；`InboundOperation` |
| `shipment` | 履約需求與整體進度 checkpoint；`Shipment` |
| `wave` | 出庫批次規劃與釋放；`Wave`、`WavePlanner` |
| `picking` | 現場揀貨與短揀；`PickingWork`、`PickTask` |
| `dispatch` | 包裝、集貨、承運商交接；`ShipmentDispatch` |
| `process` | 跨 aggregate 的 application orchestration、排程與 Temporal adapter；不是 domain |

`inbound` / `outbound` 仍可作為業務方向用語，例如 `InboundOperation`，但不再主導 package
與 aggregate ownership。

## Shipment 前後流程

```text
promising.fulfillment-handoffs
  → subscriber wms-fulfillment-handoff
  → shared event_inbox transaction
  → CreateShipmentUsecase
  → wms_shipments / wms_shipment_lines
  → 每秒掃描已等待滿 10 秒的 CREATED Shipment
  → SimulateWarehouseOperationsUsecase
  → PlanWaveUsecase
  → ReleaseWaveUsecase
      └─ WaveReleased（同步 domain event）
          ├─ 建立 PickingWork / PickTask
          └─ 更新 Shipment release checkpoint
  → ConfirmPickUsecase
      └─ PickingWorkStatusChanged（同步 domain event）
          └─ 更新 Shipment picking checkpoint
  → Pack / Stage / HandOver use cases
      └─ ShipmentDispatchStatusChanged（同步 domain event）
          └─ 更新 ShipmentDispatch checkpoint
              └─ ShipmentHandedOver（integration event / outbox）
```

`Wave`、`PickingWork`、`ShipmentDispatch` 各自擁有狀態與資料表；`Shipment` 不再內嵌現場揀貨、包裝細節，
只保留跨流程查詢和取消判斷所需的 checkpoint。WMS 內部事件使用 Spring 同步發布，handler
與發布者共享同一個 transaction，任何一步失敗都整筆 rollback。跨 bounded context 的
`ShipmentHandedOver`、`ShipmentCancelled` 仍使用 durable outbox；若未來拆成不同服務，再把需要跨服務的
內部事件升級為 outbox / inbox integration event。

目前沒有串接真實 WMS，因此 dev、stage、prod 都使用同一條模擬倉內作業線路。Scheduler 不在 JVM
記憶體保存 10 秒 timer，而是使用 `Shipment.createdAt` 與持久化狀態找回到期工作；服務重啟不會遺失。
每張 Shipment 在單一 transaction 內依序執行 synthetic Wave／PickingWork、完整 Pick、Pack、Stage
與 carrier handover，並由 optimistic version 防止多個 instance 重複提交。

設定預設值：

```properties
archone.wms.simulation.enabled=true
archone.wms.simulation.processing-delay=10s
archone.wms.simulation.scheduler-delay-ms=1000
archone.wms.simulation.batch-limit=100
archone.wms.simulation.cancellation-batch-limit=100
```

完整線路見 [Shipment handover 自動模擬流程](../../docs/shipment-handover-connection-gaps.png)。未來接真實
WMS 時，由外部操作入口取代 `SimulatedWarehouseOperationsScheduler`，後續 handover event、Inventory
出庫完成與 Ordering fulfillment contract 不變。

`deployments:monolith` 是目前唯一的 Spring Boot 啟動入口。未來確定要將 WMS 獨立部署時，再建立只包含
`WmsApplication`、runtime properties 與 deployment configuration 的薄 `deployments:wms`；
domain、application、JPA 與 messaging adapters 不移出本模組。
