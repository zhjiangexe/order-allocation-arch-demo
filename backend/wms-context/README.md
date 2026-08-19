# WMS context

`wms-context` 是由 `deployments:monolith` 組裝的 WMS bounded context，目前包含：

```text
promising.fulfillment-handoffs
  → subscriber wms-fulfillment-handoff
  → shared event_inbox transaction
  → CreateShipmentUsecase
  → wms_shipments / wms_shipment_lines
```

`deployments:monolith` 是目前唯一的 Spring Boot 啟動入口。未來確定要將 WMS 獨立部署時，再建立只包含
`WmsApplication`、runtime properties 與 deployment configuration 的薄 `deployments:wms`；
domain、application、JPA 與 messaging adapters 不移出本模組。
