# Microservice 打包

本目錄將既有 bounded contexts 各自組裝成 executable jar，與 `deployments:monolith` 長期並存。
第一版支援 Events orchestration，共用 PostgreSQL schema。各 context 本身仍是 library。

## 打包

從 `backend/` 執行，需要 JDK 25；Gradle wrapper 與 dependency versions 沿用根目錄設定。

```bash
# 單一服務
./gradlew :deployments:microservice:ordering-service:bootJar

# 全部五個服務
./gradlew :deployments:microservice:bootJars

# Monolith
./gradlew :deployments:monolith:bootJar
```

`bootJar`／`bootJars` 只編譯與打包，不執行測試、不啟動服務，也不連接資料庫或 Kafka。
`build`／`check` 另會執行驗證，包含需要 Docker 的啟動組裝測試。

| Service | Business dependency | 預設 jar（各服務的 `build/libs/`） |
| --- | --- | --- |
| `ordering-service` | `ordering:ordering-server` | `archone-ordering-0.0.1-SNAPSHOT.jar` |
| `inventory-service` | `inventory:inventory-server` | `archone-inventory-0.0.1-SNAPSHOT.jar` |
| `wms-service` | `wms:wms-server` | `archone-wms-0.0.1-SNAPSHOT.jar` |
| `fulfillment-service` | `fulfillment` 與三個 query API contracts | `archone-fulfillment-0.0.1-SNAPSHOT.jar` |
| `logistics-data-service` | `logistics-data-context` | `archone-logistics-data-0.0.1-SNAPSHOT.jar` |

Monolith 的產物仍為 `deployments/monolith/build/libs/archone-monolith.jar`。
單獨 build 一個服務時，Gradle 會編譯其 library dependencies；不需要打包其餘服務。

## 服務版本

可針對這次打包指定服務版本：

```bash
./gradlew :deployments:microservice:ordering-service:bootJar -PserviceVersion=1.1.0
```

產物為 `ordering-service/build/libs/archone-ordering-1.1.0.jar`，內含
`META-INF/build-info.properties`，記錄 service artifact 與版本。
`serviceVersion` 套用到同一次 Gradle invocation 選中的所有 microservice 打包任務；
不同服務要使用不同版本時，分別執行各自的 `bootJar`。

此入口提供獨立產物識別。單獨升版仍須維持 HTTP、事件與共用 schema 的相容性；
新舊版本混跑的相容性驗證與 demo 版本組合管理會在啟動層處理。

## 組裝責任

- 各 `*-service` 負責 Application 入口、所屬 context 的依賴與服務設定。
- `common` 提供 Events mode 檢查、共同 properties 與啟動測試 fixture。
- [`deployment-support`](../deployment-support/README.md) 與 monolith 共用時鐘、事件 mapping、
  Kafka failure policy 與 consumer retry，不依賴任何 business context。
- 各 Application 明確 `@Import` 所需設定。Logistics Data 只使用共用時鐘與 Events mode 檢查。
- Inventory retry observer 由 `inventory-service` 與 monolith 各自組裝；其實作沿用 Inventory library。
- Fulfillment 透過 `ordering-api`、`inventory-api`、`wms-api` 的 `@GetExchange` contracts 建立 HTTP clients。
  連線 timeout 為 2 秒、讀取 timeout 為 5 秒；流程命令與事件沿用 transactional Outbox／Inbox。

各 context 負責人可單獨修改與驗證對應 service。共用設定或 contract 變更時，需驗證所有受影響的
service 與 monolith；schema 仍共同維護。

## 執行期設定

目前提供應用程式設定與測試用五 JVM 啟動；demo 的 Docker image、Compose、一鍵啟動與示範資料初始化留到下一階段。
打包成功不代表外部基礎設施已準備完成。執行事件流程仍需 PostgreSQL、Kafka topics 與 Outbox CDC connector。

| 設定 | 預設值／用途 |
| --- | --- |
| `ARCHONE_PORT` | Ordering 28880、Inventory 28881、WMS 28882、Fulfillment 28883、Logistics Data 28884 |
| `ARCHONE_DB_URL` | `jdbc:postgresql://localhost:28891/order_promising` |
| `ARCHONE_DB_USERNAME`／`ARCHONE_DB_PASSWORD` | 本機 demo 預設均為 `order_promising` |
| `ARCHONE_KAFKA_BOOTSTRAP_SERVERS` | `localhost:28892` |
| `archone.services.ordering` | Fulfillment query URL，預設 `http://localhost:28880` |
| `archone.services.inventory` | Fulfillment query URL，預設 `http://localhost:28881` |
| `archone.services.wms` | Fulfillment query URL，預設 `http://localhost:28882` |

Microservice 只接受 `archone.fulfillment.orchestration-mode=events`；monolith 保留既有 Events／Temporal 支援。

schema migration 是啟動前的獨立步驟，使用 [`database`](../../../database/README.md) 的共用 SQL
與 `scripts/migrate.sh`。五個服務的 jar 都不含 SQL／Flyway runtime，也不提供 `ARCHONE_MIGRATE` 開關。

## 驗證

```bash
# Deployment unit tests：mode guard、HTTP query clients、共用 retry 與 monolith wiring
./gradlew :deployments:deployment-support:test :deployments:microservice:test :deployments:monolith:test

# 五個服務各自的啟動組裝測試，需要 Docker
./gradlew :deployments:microservice:sit

# 只驗證某個服務
./gradlew :deployments:microservice:inventory-service:sit
```

啟動測試使用各服務自己的 runtime classpath 與獨立 PostgreSQL Testcontainer，驗證 migrations、HTTP health、
entity／bean 隔離與 consumer retry／observer 組裝。Kafka listener 停用自動啟動，不連接 broker；
這組測試不涵蓋完整 Kafka 事件流程。Fulfillment unit tests 另驗證遠端 query 路徑、DTO 與錯誤傳遞。

從 repository root 執行完整五服務 Events E2E：

```bash
E2E_DEPLOYMENT=microservice make e2e
```

沿用 monolith 的同一份 Events feature 與測試資料，runner 負責五服務打包、migration、
Kafka topics／Debezium、HTTP 路由、health 與清理。詳見 [E2E 操作說明](../../../e2e/spec/README.md)。
