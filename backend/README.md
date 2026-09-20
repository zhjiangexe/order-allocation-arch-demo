# Backend

`backend/` 是 Archone 的完整 Gradle multi-project build，包含 Gradle wrapper、version catalog、bounded
contexts、共用 contracts、messaging infrastructure，以及目前唯一的 Spring Boot 啟動組裝：
`deployments:monolith`。

所有 Gradle 指令均從此目錄執行；repository root 不放置 Gradle build 或 wrapper。

## 模組

| 模組 | 職責 |
| --- | --- |
| `deployments:monolith` | Modular monolith Spring Boot composition root、Flyway migrations 與跨 context wiring |
| `ordering/ordering-api`、`ordering/ordering-server` | Order RPC 契約與 lifecycle bounded context |
| `inventory/inventory-api`、`inventory/inventory-server` | Inventory API 模組與 allocation、movement、warehouse 實作 |
| `logistics-data-context` | Owner、product、SKU 與 facility reference data |
| `wms/wms-server` | WMS bounded context；取消命令由 Integration Event 或 Temporal Activity 接收 |
| `fulfillment-process` | Ordering、Inventory、WMS 之間的共用 event-driven 與 Temporal 跨 context 流程 |
| `orchestration-temporal-contract` | Temporal Workflow／Activity interfaces 與 transport DTOs |
| `orchestration-temporal-runtime` | Long-running fulfillment Workflow runtime implementation |
| `integration-contracts` | 跨 bounded context 使用的 versioned integration event contracts |
| `domain-contract` | 共用的 domain-level contracts |
| `foundation` | Framework-neutral foundation utilities |
| `spring-framework-support` | 共用 Spring framework support |
| `messaging` | Transactional Outbox／Inbox、Kafka 與 Spring adapters；細節見 [`messaging/README.md`](messaging/README.md) |

Ordering 的 `ordering-api` 定義帶 `@PostExchange` 的內部取消 RPC 契約，server
以 `@RestController` 實作。Monolith 直接注入 server bean；API 模組的測試以 `RestClient` 和
`HttpServiceProxyFactory` 驗證遠端 HTTP 呼叫。遠端部署可設定
`archone.fulfillment.rpc.transport=http` 與 `ordering-base-url` 建立 Ordering
HTTP proxy。WMS 取消命令透過 Integration Event 或 Temporal Activity 接收，無同步 RPC API。
Inventory 目前無同步 RPC 需求，因此 `inventory-api` 暫無 Java 契約。
取消流程專用的 Integration Event 與 destination 定義在 `contracts.cancel.v1`；
`OrderCancelledIntegrationEvent` 是 Ordering 的訂單生命週期事實，仍位於 `contracts.ordering.v1`。
未來若拆成微服務，需另行處理跨 HTTP 的交易、逾時、重試與服務部署。

Events 模式的取消入口先經 Ordering API 檢查是否已取消／已完成，再於本地交易
寫出 `WmsCancellationRequestedIntegrationEvent`。WMS 消費請求並處理 Shipment；
有 Shipment 時以 `ShipmentCancelledIntegrationEvent` 推進 Ordering，查無 Shipment
時以 `WmsCancellationRequestResolvedIntegrationEvent` 推進 Ordering。HTTP 202
僅代表請求已寫入 outbox，並非跨 context 取消已完成。Temporal 模式仍由 Workflow
執行取消流程。

## 常用指令

```bash
# 執行全部 unit tests
./gradlew test

# 執行 monolith 的 system/integration tests（需要測試所需的外部服務）
./gradlew :deployments:monolith:sit

# 啟動本機 dev profile
./gradlew :deployments:monolith:bootRun --args='--spring.profiles.active=dev'

# 套用／檢查 Palantir Java Format
./gradlew spotlessApply
./gradlew spotlessCheck
```

`check` 會依賴 `spotlessCheck`。若只想驗證特定模組，可將 `test` 替換為該模組，例如
`./gradlew :inventory:inventory-server:test`；完整的 PostgreSQL、Flyway、messaging 與跨 context 驗證則使用
`./gradlew :deployments:monolith:sit`。

`deployments:monolith` 是目前唯一的 Spring Boot 啟動入口；各 bounded context 本身不是獨立可啟動的
application。Temporal Workflow／Activity 的 contract 與 runtime 分別位於
`orchestration-temporal-contract` 與 `orchestration-temporal-runtime`，跨 context 的流程接線由
`fulfillment-process` 提供。

## Activity 演示延遲

Temporal Activity adapters 在呼叫 use case 之前執行 `SimulationUtil.sleep(3_000)`，
先等待約 3 秒，再執行業務操作並回報 Activity 完成；等待位於 use case 的業務交易之外。
正常啟動時此延遲套用於正常與取消 Activity，Events driver 不經過這些 adapters。
Gradle 的所有 `Test` 任務（含 Unit test 與 `sit`）統一設定
`archone.simulation.sleep-enabled=false`，直接略過延遲。
從 IDE 或其他腳本執行測試時，也可在 JVM options 加上 `-Darchone.simulation.sleep-enabled=false`。
`SimulationUtil.sleep` 的單位是毫秒，非正數直接返回；中斷時提前返回並恢復中斷旗標，不向外拋例外。
這是阻塞 Activity worker thread 的演示工具，不可放進 Workflow；Workflow 的計時等待使用 `Workflow.sleep`。
