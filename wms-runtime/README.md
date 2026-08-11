# WMS runtime

`wms-runtime` 是 `wms` pure Java application/domain 的 deployable adapter。它目前只承接第一條
fulfillment handoff vertical slice：

```text
promising.fulfillment-handoffs
  → subscriber wms-fulfillment-handoff
  → WMS event_inbox transaction
  → CreateShipmentUsecase
  → wms_shipments / wms_shipment_lines
```

依賴方向固定為：

```text
wms-runtime → contracts + messaging consumer starter + foundation + wms
wms         → 不依賴 Spring / Kafka / JPA / wms-runtime
```

本機啟動：

```bash
./gradlew :wms-runtime:bootRun --args='--spring.profiles.active=dev'
```

可用 `WMS_DB_URL`、`WMS_DB_USERNAME`、`WMS_DB_PASSWORD` 與
`WMS_KAFKA_BOOTSTRAP_SERVERS` 覆寫 dev defaults。Flyway migration 使用專屬
`classpath:db/wms/migration`，避免與其他 deployable 的 `V1` 發生 classpath 衝突。

目前不啟用 producer starter：沒有具體跨邊界 reader 的 Pick／Pack／Stage domain events 留在 WMS
內部。整單取消及跨 topic 亂序保護追蹤於
[`messaging-post-refactoring-roadmap.md`](../docs/messaging-post-refactoring-roadmap.md) 的 D4b。
