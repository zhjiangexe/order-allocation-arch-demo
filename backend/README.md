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
| `fulfillment-workflow-contract` | Temporal Workflow／Activity interfaces 與 transport DTOs |
| `fulfillment-workflow-runtime` | Long-running fulfillment Workflow runtime implementation |
| `integration-contracts` / `foundation` | Cross-boundary integration contracts and framework-neutral foundation |
| `messaging` | Transactional messaging modules |

Common commands:

```bash
./gradlew test
./gradlew :deployments:monolith:sit
./gradlew :deployments:monolith:bootRun --args='--spring.profiles.active=dev'
```

所有 Gradle 指令均從此目錄執行；repository root 不再放置 Gradle build 或 wrapper。
