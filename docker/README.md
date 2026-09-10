# Monolith deployment

這個目錄只負責「如何部署」，不放 bounded context 或 application code。所有環境都使用根目錄
`Dockerfile` 產生的同一份 `archone-monolith` image；差異只來自 Compose overlay 與環境變數。

| Environment | Compose files | Runtime dependencies |
| --- | --- | --- |
| `dev` | `compose.yml` + `compose.dev.yml` | 由 Compose 啟動 PostgreSQL、Kafka、Kafka Connect、Kafbat UI |
| `stage` | `compose.yml` + `compose.stage.yml` | 外部 PostgreSQL、Kafka 與 Debezium；Spring profiles 為 `prod,staging` |
| `prod` | `compose.yml` + `compose.prod.yml` | 外部 PostgreSQL、Kafka 與 Debezium；Spring profile 為 `prod` |

## 簡報操作台

需要前端與完整雙模式服務時，使用根目錄的 `make demo-up MODE=events|temporal`。
此入口額外套用 `compose.demo.yml`，使用獨立 project／ports，並保留 Kafka 與 Temporal 資料。
Temporal 模式仍會啟動 Kafka、Kafka Connect 與 Outbox connector。
啟動、停止與連接埠對照請見 [根目錄 README](../README.md#簡報前一鍵啟動含前端)。

## Commands

一律從 repository root 使用 `Makefile`：

```bash
make dev-up
make dev-up-temporal
make package ENV=stage
make push ENV=stage
make deploy ENV=stage
make logs ENV=stage
make down ENV=stage
```

`make dev-up` 會 build image、等待 application 與基礎設施健康，再冪等建立 Debezium Outbox connector。
Kafbat UI 預設在 [http://localhost:28297](http://localhost:28297)，可查看 topic、partition、message key、
consumer group 與 Kafka Connect；它只存在於 dev overlay。stage／prod 的 `deploy` 則會先 pull 環境檔
指定的 image tag，再啟動 monolith。

## Fulfillment orchestration modes

同一份 image 支援兩個互斥的流程 driver，且共用相同的 application use cases：

- `events`（預設）：Kafka consumers 依序推進 allocation、WMS、Inventory 與 Ordering。
- `temporal`：Order event 啟動 Temporal Workflow；allocation 與 shipment facts 只轉成 Workflow Signal。

本機可用 `make dev-up-temporal` 一併啟動 Temporal CLI development server，gRPC 預設在
`localhost:28294`、Web UI 在 `http://localhost:28296`。這個 development server 使用記憶體儲存，
不得用於 stage/prod；非開發環境應以 `ORDER_PROMISING_TEMPORAL_TARGET` 指向平台管理的 Temporal
frontend。`ORDER_PROMISING_FULFILLMENT_ORCHESTRATION_MODE` 若不是 `events` 或 `temporal`，application
會在啟動時直接失敗，避免兩個 driver 都未啟用。

這個設定是整個 deployment 的 cutover，不是逐筆訂單或可混跑的 feature flag。所有 replicas
必須使用同一模式；切換時先停止接收新履約命令，讓舊模式完成既有 fulfillment，再停止舊 driver
並啟動新模式。若無法等待完成，須另行設計並執行 in-flight 流程遷移，不能直接由新模式接手。不可用同時存在 `events`／`temporal` pods 的一般 rolling update，否則
同一組 stable Kafka subscriber 可能把不同 partitions 分給不同 driver。

## 搭配前端

Vite 預設 28295 與 dev management port 相同。搭配前端時，使用
`ARCHONE_MANAGEMENT_PORT=28298 make dev-up` 或
`ARCHONE_MANAGEMENT_PORT=28298 make dev-up-temporal`，再啟動前端。
API 仍使用 28290；後續重建容器時沿用相同覆寫值。
也可保留後端設定，改用前端 `npm run dev -- --port 其他埠`。

Temporal 跳轉預設 UI `http://localhost:28296`、namespace `default`；隔離環境透過前端
`VITE_TEMPORAL_UI_URL`／`VITE_TEMPORAL_NAMESPACE` 調整，這不會切換後端模式。
詳見 [前端操作說明](../frontend/README.md) 與 [雙模式驗收紀錄](../docs/plans/allocation-console/validation.md)。

## Environment files

`dev.env` 只有本機開發用預設值，可以進版控；資料庫密碼也明確標示為本機專用。stage／prod 請從 `.example` 建立實際檔案：

```bash
cp docker/env/stage.env.example docker/env/stage.env
cp docker/env/prod.env.example docker/env/prod.env
```

實際的 `stage.env`、`prod.env` 已被 `.gitignore` 排除。正式平台應由 CI/CD 或 secret manager 在部署時
產生它們，不應把資料庫密碼提交到 Git。`ARCHONE_IMAGE_TAG` 應使用 commit SHA 或 release version，
不要使用會漂移的 `latest`。

## Boundary responsibilities

- image 內含 monolith 與 Flyway migrations；application 啟動時驗證並執行 migration。
- dev 的 connector 由 `scripts/register-outbox-connector.sh` 建立。
- stage／prod 的 Kafka topics、Debezium connector、database backup／restore、TLS 與 credentials rotation
  屬於部署平台責任，不由 application container 動態建立。
- management port 預設只綁定 `127.0.0.1`；若 Prometheus 位於其他主機，應透過 private network 或
  reverse proxy 暴露，而不是直接公開到 Internet。
