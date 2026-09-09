# Backend

This directory is the complete Gradle backend root. It contains the wrapper, version catalog, runtime modules,
bounded contexts, shared contracts and messaging infrastructure.

| Module | Responsibility |
| --- | --- |
| `deployments:monolith` | Modular monolith Spring Boot composition root, migrations and cross-context wiring |
| `ordering-context` | Order lifecycle bounded context |
| `inventory-context` | Inventory, allocation, movement and warehouse capabilities |
| `logistics-data-context` | Owner, product, SKU and facility reference data |
| `wms-context` | WMS bounded context，包含 application、domain 與 deployment 可組裝的 adapters |
| `fulfillment-process` | Ordering、Inventory 與 WMS 之間的共用、event-driven 與 Temporal 跨 context 流程 |
| `orchestration-temporal-contract` | Temporal Workflow／Activity interfaces 與 transport DTOs |
| `orchestration-temporal-runtime` | Long-running fulfillment Workflow runtime implementation |
| `integration-contracts` / `foundation` | Cross-boundary integration contracts and framework-neutral foundation |
| `messaging` | Transactional messaging modules |

Common commands:

```bash
./gradlew test
./gradlew :deployments:monolith:sit
./gradlew :deployments:monolith:bootRun --args='--spring.profiles.active=dev'
```

所有 Gradle 指令均從此目錄執行；repository root 不再放置 Gradle build 或 wrapper。

## Activity 演示延遲

Temporal Activity adapters 在呼叫 use case 之前執行 `SimulationUtil.sleep(3_000)`，
先等待約 3 秒，再執行業務操作並回報 Activity 完成；等待位於 use case 的業務交易之外。
正常啟動時此延遲套用於正常與取消 Activity，Events driver 不經過這些 adapters。
Gradle 的所有 `Test` 任務（含 Unit test 與 `sit`）統一設定
`archone.simulation.sleep-enabled=false`，直接略過延遲。
從 IDE 或其他腳本執行測試時，也可在 JVM options 加上 `-Darchone.simulation.sleep-enabled=false`。
`SimulationUtil.sleep` 的單位是毫秒，非正數直接返回；中斷時提前返回並恢復中斷旗標，不向外拋例外。
這是阻塞 Activity worker thread 的演示工具，不可放進 Workflow；Workflow 的計時等待使用 `Workflow.sleep`。
