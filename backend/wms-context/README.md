# WMS context

`wms-context` 是由 `deployments:monolith` 組裝的 WMS bounded context，目前包含：

```text
promising.fulfillment-handoffs
  → subscriber wms-fulfillment-handoff
  → shared event_inbox transaction
  → CreateShipmentUsecase
  → wms_shipments / wms_shipment_lines
  → 每秒掃描已等待滿 10 秒的 CREATED Shipment
  → SimulateWarehouseOperationsUsecase
  → ShipmentHandedOver
```

目前沒有串接真實 WMS，因此 dev、stage、prod 都使用同一條模擬倉內作業線路。Scheduler 不在 JVM
記憶體保存 10 秒 timer，而是使用 `Shipment.createdAt` 與持久化狀態找回到期工作；服務重啟不會遺失。
每張 Shipment 在單一 transaction 內依序執行 synthetic Wave／WarehouseWork、完整 Pick、Pack、Stage
與 carrier handover，並由 optimistic version 防止多個 instance 重複提交。

設定預設值：

```properties
archone.wms.simulation.enabled=true
archone.wms.simulation.processing-delay=10s
archone.wms.simulation.scheduler-delay-ms=1000
archone.wms.simulation.batch-limit=100
```

完整線路見 [Shipment handover 自動模擬流程](../../docs/shipment-handover-connection-gaps.png)。未來接真實
WMS 時，由外部操作入口取代 `SimulatedWarehouseOperationsScheduler`，後續 handover event、Inventory
出庫完成與 Ordering fulfillment contract 不變。

`deployments:monolith` 是目前唯一的 Spring Boot 啟動入口。未來確定要將 WMS 獨立部署時，再建立只包含
`WmsApplication`、runtime properties 與 deployment configuration 的薄 `deployments:wms`；
domain、application、JPA 與 messaging adapters 不移出本模組。
