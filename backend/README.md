# Backend

`backend/` 是 Archone 的完整 Gradle multi-project build，包含 Gradle wrapper、version catalog、bounded
contexts、共用 contracts、messaging infrastructure，以及 monolith 與 microservice 兩種 Spring Boot 部署組裝。

所有 Gradle 指令均從此目錄執行；repository root 不放置 Gradle build 或 wrapper。

## 模組

| 模組 | 職責 |
| --- | --- |
| `deployments:monolith` | Modular monolith Spring Boot composition root 與跨 context wiring |
| `deployments:deployment-support` | 共用部署 policies、時鐘與 Integration Event allow-list |
| `deployments:microservice:*` | 五個 Events 服務的獨立 composition roots；[打包方式與模組責任](deployments/microservice/README.md) |
| `ordering/ordering-api`、`ordering/ordering-server` | Order RPC 契約與 lifecycle bounded context |
| `inventory/inventory-api`、`inventory/inventory-server` | Inventory API 模組與 allocation、movement、warehouse 實作 |
| `logistics-data-context` | Owner、product、SKU 與 facility reference data |
| `wms/wms-api`、`wms/wms-server` | WMS shipment query RPC 契約與 bounded context 實作；取消命令由事件或 Activity 接收 |
| `fulfillment` | Ordering、Inventory、WMS 之間的共用 event-driven 與 Temporal 跨 context 流程 |
| `orchestration-temporal-contract` | Temporal Workflow／Activity interfaces 與 transport DTOs |
| `orchestration-temporal-runtime` | Long-running fulfillment Workflow runtime implementation |
| `integration-contracts` | 跨 bounded context 使用的 versioned integration event contracts |
| `domain-contract` | 共用的 domain-level contracts |
| `foundation` | Framework-neutral foundation utilities |
| `spring-framework-support` | 共用 Spring framework support |
| `archone-framework/messaging` | Transactional Outbox／Inbox、Kafka 與 Spring adapters；細節見 [`messaging/README.md`](archone-framework/messaging/README.md) |

Ordering、Inventory、WMS 的 `*-api` 模組定義帶 `@GetExchange` 的 query contracts，server
以 `@RestController` 實作。Monolith 直接注入本地 bean；microservice 的 Fulfillment 透過
`HttpServiceProxyFactory` 呼叫相同 HTTP 契約，連線 timeout 為 2 秒、讀取 timeout 為 5 秒。
流程命令沿用 Integration Event；同步 API 只提供 query。

Events 模式的取消入口在 Fulfillment 的本地交易保存 cancellation process 與 Outbox，WMS、Ordering
透過 Kafka 完成後續處理並回報結果。HTTP 202 代表請求已持久化，不代表取消已完成。
Temporal 模式目前由 monolith 部署組裝 workers；microservice 第一版只接受 Events。

## 常用指令

```bash
# 打包所有 microservice executable jars（不啟動服務）
./gradlew :deployments:microservice:bootJars

# 執行全部 unit tests
./gradlew test

# 執行 monolith 的 system/integration tests（需要測試所需的外部服務）
./gradlew :deployments:monolith:sit

# 啟動本機 dev profile（先依 database/README.md 完成獨立 migration）
./gradlew :deployments:monolith:bootRun --args='--spring.profiles.active=dev'

# 套用／檢查 Palantir Java Format
./gradlew spotlessApply
./gradlew spotlessCheck
```

`check` 會依賴 `spotlessCheck`。若只想驗證特定模組，可將 `test` 替換為該模組，例如
`./gradlew :inventory:inventory-server:test`；完整的 PostgreSQL、Flyway、messaging 與跨 context 驗證則使用
`./gradlew :deployments:monolith:sit`。

monolith 與 microservice 不在啟動時修改 schema。SQL 與獨立執行入口位於 repository root 的
[`database`](../database/README.md)，Gradle 僅在資料庫整合測試中載入同一份 SQL。

`deployments:monolith` 與 `deployments:microservice:*` 提供 Spring Boot 啟動入口；bounded context
模組本身維持 library，由 deployment 決定本地或遠端接線。Temporal Workflow／Activity 的 contract 與 runtime 分別位於
`orchestration-temporal-contract` 與 `orchestration-temporal-runtime`，跨 context 的流程接線由
`fulfillment` 提供。

## Activity 演示延遲

Temporal Activity adapters 在呼叫 use case 之前執行 `SimulationUtil.sleep(3_000)`，
先等待約 3 秒，再執行業務操作並回報 Activity 完成；等待位於 use case 的業務交易之外。
正常啟動時此延遲套用於正常與取消 Activity，Events driver 不經過這些 adapters。
Gradle 的所有 `Test` 任務（含 Unit test 與 `sit`）統一設定
`archone.simulation.sleep-enabled=false`，直接略過延遲。
從 IDE 或其他腳本執行測試時，也可在 JVM options 加上 `-Darchone.simulation.sleep-enabled=false`。
`SimulationUtil.sleep` 的單位是毫秒，非正數直接返回；中斷時提前返回並恢復中斷旗標，不向外拋例外。
這是阻塞 Activity worker thread 的演示工具，不可放進 Workflow；Workflow 的計時等待使用 `Workflow.sleep`。
