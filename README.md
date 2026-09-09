# Archone

專案目錄：

- `backend/`：Java／Spring Boot multi-project build，包含 Gradle wrapper 與所有後端模組。
- `frontend/`：前端應用程式。
- `e2e/`：repository-level correctness 與 performance tests。
- `docs/`：架構與操作文件。
- `docker/`：monolith 的 dev／stage／prod Compose 組裝與環境範本。
- `scripts/`：環境啟動與 Debezium connector 初始化腳本。

後端指令一律從 `backend/` 執行：

```bash
cd backend
./gradlew test
./gradlew :deployments:monolith:bootRun --args='--spring.profiles.active=dev'
```

後端模組與職責請見 [`backend/README.md`](backend/README.md)。

## Monolith 打包與啟動

根目錄的 `Makefile` 是統一入口。開發環境會一併啟動 monolith、PostgreSQL、Kafka、
Kafka Connect，並註冊 Debezium Outbox connector：

```bash
make dev-up
make logs ENV=dev
make dev-down
```

stage／prod 使用同一份 image，但只啟動 monolith，資料庫、Kafka 與 Debezium 視為外部平台服務。
先從範本建立不進版控的環境檔，再打包或部署 immutable image：

```bash
cp docker/env/stage.env.example docker/env/stage.env
make package ENV=stage
make push ENV=stage
make stage-deploy
```

prod 對應 `docker/env/prod.env.example` 與 `make prod-deploy`。完整變數與部署責任請見
[`docker/README.md`](docker/README.md)。

使用 IntelliJ IDEA 開啟 repository root 時，專案設定會自動將 Gradle project 連結至 `backend/`；
首次開啟或更新後請執行 Reload All Gradle Projects。

## Allocation 操作台與履約 API

前端提供訂單、配貨佇列、庫存與主檔四頁，可從建單、補貨追蹤到履約完成。
Events／Temporal 由後端啟動設定決定，Temporal 詳情可跳轉 Workflow。
啟動方式與操作見 [frontend/README.md](frontend/README.md)，雙模式驗證見
[validation.md](docs/plans/allocation-console/validation.md)。

Monolith 的相關 API：

- `GET /stock-operations?state=CONFIRMED&limit=200`：列出等待中的庫存作業；不提供精確缺口或全域佇列排名。
- `GET /demo/orders/{orderId}/fulfillment`：組合 Order、StockOperation／movement／批次、WMS Shipment，Temporal 模式另帶 Workflow state。
- `POST /orders/{orderId}/cancellation-requests`：把 immutable cancellation request 送進目前生效的 Events 或 Temporal 協調流程。

前端未提供取消操作。取消請求的 `requestId`、`requestedAt` 與 `reason` 是冪等內容；重試同一次請求時必須原樣重送。
