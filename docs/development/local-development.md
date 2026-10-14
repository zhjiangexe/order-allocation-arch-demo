# 本機開發

> Current · 指令皆從 repository root 執行。

[文件首頁](../README.md) · [專案首頁](../../README.md)

## 環境需求

| 工具 | 用途 |
| --- | --- |
| Docker、Docker Compose v2 | PostgreSQL、Kafka、migration 與整合測試 |
| JDK 25 | 後端 Gradle wrapper、IDE 與 `java -jar` |
| Node.js 22、npm | 前端開發 |
| Bash、`curl`、`jq`、`shasum` | E2E runner |

只要展示系統，可改用[一鍵 demo](../operations/demo.md)，不需在主機安裝 JDK 或 Node.js。
後端模組職責見 [backend/README.md](../../backend/README.md)。

## 開發方式

開發時可讓基礎設施在 Docker 執行，後端在主機或 IDE 啟動。
本節沿用 `docker/env/dev.env` 的 `archone-dev` 環境；若之前以 `make dev-up` 啟動後端，
先執行 `make dev-down` 停止該組容器並保留資料，再依下列步驟啟動，避免同時運行兩份 consumer。

## 1. 啟動基礎設施

```bash
docker compose --env-file docker/env/dev.env \
  -f docker/compose.yml -f docker/compose.dev.yml \
  up -d --wait postgres kafka kafka-connect kafka-ui
```

## 2. 執行獨立 migration

```bash
./scripts/migrate.sh migrate --env-file docker/env/dev.env \
  -f docker/compose.yml -f docker/compose.dev.yml
./scripts/migrate.sh check --env-file docker/env/dev.env \
  -f docker/compose.yml -f docker/compose.dev.yml
```

`migrate` 會先驗證 history／checksum，再執行尚未套用的 SQL；`check` 確認沒有待執行或不符的版本。
失敗時先處理資料庫狀態，成功後才繼續啟動。SQL 來源與舊資料庫處理方式見 [資料庫流程](../../database/README.md)。

## 3. 準備 Kafka topics 與 Outbox connector

```bash
# 建立事件 topics 與各自的 DLT
docker compose --env-file docker/env/dev.env \
  -f docker/compose.yml -f docker/compose.dev.yml \
  run --rm --no-deps kafka-dlt-init

# schema 已存在，此時即可註冊 connector；不啟動容器版 monolith
docker compose --env-file docker/env/dev.env \
  -f docker/compose.yml -f docker/compose.dev.yml \
  --profile tools run --rm --no-deps connector-init
```

等到 connector 回報就緒後再啟動後端。只有 PostgreSQL／Kafka 就緒，尚不足以推進 Outbox 事件流程。

## 4. 啟動後端

```bash
export ORDER_PROMISING_DB_URL=jdbc:postgresql://localhost:28291/order_promising
export ORDER_PROMISING_DB_USERNAME=order_promising
export ORDER_PROMISING_DB_PASSWORD=archone-local-dev-only
export ORDER_PROMISING_KAFKA_BOOTSTRAP_SERVERS=localhost:28292
export ORDER_PROMISING_FULFILLMENT_ORCHESTRATION_MODE=events

(cd backend && ./gradlew :deployments:monolith:bootRun --args='--spring.profiles.active=dev')
```

以上密碼是 `docker/env/dev.env` 的本機預設值；若修改該檔案，主機連線設定也要同步調整。
容器內使用 `postgres`／`kafka` hostname，主機上的 JVM 使用 `localhost` 與對外 port，
因此不要直接把整份容器 env 檔載入主機 shell。
dev profile 會建立示範資料並啟用 WMS 模擬作業；schema 已由前一步建立。

另開 terminal 檢查：

```bash
curl -fsS http://localhost:28290/actuator/health
```

使用 IntelliJ IDEA 時，Reload `backend/` 的 Gradle project，再執行
`com.flowzati.archone.ArchoneApplication`；Active profiles 設為 `dev`，環境變數沿用上例。

若要在主機開發 Temporal 模式，先額外啟動 Temporal，再以新的環境變數重新啟動後端：

```bash
docker compose --env-file docker/env/dev.env \
  -f docker/compose.yml -f docker/compose.dev.yml \
  --profile temporal up -d --wait temporal
export ORDER_PROMISING_FULFILLMENT_ORCHESTRATION_MODE=temporal
export ORDER_PROMISING_TEMPORAL_TARGET=localhost:28294
```

Events／Temporal 模式切換前先讓既有流程完成；需要同時展示兩種模式時，使用[隔離的 demo 環境](../operations/demo.md)。

## 5. 啟動前端與停止環境

另開 terminal，從 repository root 執行：

```bash
npm --prefix frontend ci
npm --prefix frontend run dev
```

開啟 http://localhost:28295；Vite 預設代理到後端 `http://localhost:28290`。
若後端使用容器版 `make dev-up`，請改用 `ARCHONE_MANAGEMENT_PORT=28298 make dev-up`，
讓 management port 與 Vite 分開。其他前端設定見 [frontend/README.md](../../frontend/README.md)。

開發完成後，在前後端 terminal 各按 `Ctrl+C`，再執行 `make dev-down` 停止基礎設施。
若曾啟動 Temporal，使用以下指令一併停止，PostgreSQL volume 仍保留：

```bash
docker compose --env-file docker/env/dev.env \
  -f docker/compose.yml -f docker/compose.dev.yml \
  --profile temporal down --remove-orphans
```
