# 打包與部署

> Current · 指令皆從 repository root 執行。

[文件首頁](../README.md) · [專案首頁](../../README.md)

## 部署方式

| 組裝 | 履約模式 | 現況 |
| --- | --- | --- |
| Monolith | Events、Temporal | 單一 executable jar；提供 dev／stage／prod Compose 組裝 |
| Microservice | Events | 五個 executable jars；目前共用 PostgreSQL schema |

兩種組裝共用 `database/migrations`，啟動 app 前須完成獨立 migration／check。
SQL 不在 application jar 內；版本相容性與 CI/CD 關卡見[資料庫流程](../../database/README.md)。

原 V1–V32 history 與目前整理後的 SQL 不相容；請另建空白資料庫，或在確認資料可捨棄後重建開發環境。
`make demo-down` 會保留資料，down／up 不會轉換舊 history；不要用 `flyway repair` 取代 schema 遷移。

## Monolith

從 repository root 打包 executable jar：

```bash
(cd backend && ./gradlew :deployments:monolith:bootJar)
```

產物是 `backend/deployments/monolith/build/libs/archone-monolith.jar`。
`bootJar` 只打包；完成[本機開發](../development/local-development.md)的基礎設施、migration、topics／connector 與環境變數設定後，
可用以下指令替代 `bootRun`：

```bash
java -jar backend/deployments/monolith/build/libs/archone-monolith.jar --spring.profiles.active=dev
```

根目錄的 `Makefile` 是統一入口。開發環境會一併啟動 monolith、PostgreSQL、Kafka、
Kafka Connect，並註冊 Debezium Outbox connector：

```bash
make dev-up
make logs ENV=dev
make dev-down
```

stage／prod 使用同一份 image，但只啟動 monolith，資料庫、Kafka 與 Debezium 視為外部平台服務。
先從範本建立不進版控的環境檔並填入設定，再打包或部署 immutable image。
部署前須由獨立步驟完成 migration；`stage-deploy`／`prod-deploy` 只檢查 schema，成功才啟動 app。
CI 測試、環境核准與 migration 的順序見 [資料庫流程](../../database/README.md#部署關卡)。

```bash
cp docker/env/stage.env.example docker/env/stage.env
make package ENV=stage
make push ENV=stage
make stage-deploy
```

prod 對應 `docker/env/prod.env.example` 與 `make prod-deploy`。完整變數與部署責任請見
[`docker/README.md`](../../docker/README.md)。

## Microservice

### 打包

```bash
# 全部五個 executable jars
(cd backend && ./gradlew :deployments:microservice:bootJars)

# 只打包 Ordering，並指定此次產物版本
(cd backend && ./gradlew :deployments:microservice:ordering-service:bootJar -PserviceVersion=1.1.0)
```

各服務產物在 `backend/deployments/microservice/<service>/build/libs/`。
單獨打包時只編譯該服務與其 library dependencies，不需要打包其他服務。

| Service | 預設 port | 預設 jar |
| --- | --- | --- |
| `ordering-service` | 28880 | `archone-ordering-0.0.1-SNAPSHOT.jar` |
| `inventory-service` | 28881 | `archone-inventory-0.0.1-SNAPSHOT.jar` |
| `wms-service` | 28882 | `archone-wms-0.0.1-SNAPSHOT.jar` |
| `fulfillment-service` | 28883 | `archone-fulfillment-0.0.1-SNAPSHOT.jar` |
| `logistics-data-service` | 28884 | `archone-logistics-data-0.0.1-SNAPSHOT.jar` |

### 手動啟動

目前提供獨立 jar 與測試用 E2E 啟動；demo 的五服務 Docker／Compose 一鍵啟動、示範資料初始化
與前端 API 路由仍待下一階段。以下示範五個 JVM 沿用[本機開發](../development/local-development.md)第 1～3 步準備好的 dev 基礎設施。
請先停止使用同一資料庫／Kafka 的 monolith，讓這組環境只執行一種部署組合。
schema migration 對共用資料庫執行一次即可。

在每個服務 terminal，從 repository root 設定以下環境變數；這裡明確覆寫 microservice
原本的 DB 28891／Kafka 28892 預設值，改接本機開發的 dev ports：

```bash
export ARCHONE_DB_URL=jdbc:postgresql://localhost:28291/order_promising
export ARCHONE_DB_USERNAME=order_promising
export ARCHONE_DB_PASSWORD=archone-local-dev-only
export ARCHONE_KAFKA_BOOTSTRAP_SERVERS=localhost:28292
```

接著每個 terminal 選一條啟動，共五個 terminal；使用自訂版本時替換 jar 名稱：

```bash
java -jar backend/deployments/microservice/ordering-service/build/libs/archone-ordering-0.0.1-SNAPSHOT.jar
java -jar backend/deployments/microservice/inventory-service/build/libs/archone-inventory-0.0.1-SNAPSHOT.jar
java -jar backend/deployments/microservice/wms-service/build/libs/archone-wms-0.0.1-SNAPSHOT.jar
java -jar backend/deployments/microservice/fulfillment-service/build/libs/archone-fulfillment-0.0.1-SNAPSHOT.jar
java -jar backend/deployments/microservice/logistics-data-service/build/libs/archone-logistics-data-0.0.1-SNAPSHOT.jar
```

各服務的健康檢查是 `http://localhost:<port>/actuator/health`。例如：

```bash
curl -fsS http://localhost:28880/actuator/health
```

Fulfillment 預設向 `localhost:28880`、`28881`、`28882` 查詢 Ordering、Inventory、WMS；
若更換服務位址，需同步設定 `archone.services.ordering`、`archone.services.inventory`、
`archone.services.wms`。`ARCHONE_PORT` 可覆寫單一服務的 HTTP port。
Microservice 僅支援 Events，不需要 Temporal；不會載入 monolith 的 dev seed。
健康檢查通過代表服務啟動，完整履約還需要主檔／庫存資料與事件流程驗證。

停止時在各 terminal 按 `Ctrl+C`，再以 `make dev-down` 停止共用基礎設施並保留 DB volume。
更多設定與組裝責任見 [Microservice 說明](../../backend/deployments/microservice/README.md)。
