# 測試與檢查

> Current · 指令皆從 repository root 執行。

[文件首頁](../README.md) · [專案首頁](../../README.md)

環境需求見[本機開發](local-development.md#環境需求)。

## 選擇測試範圍

| 範圍 | 指令（repository root） | 說明 |
| --- | --- | --- |
| 後端 unit tests | `make test` | 各模組的 unit tests |
| 格式、unit tests 與 SIT | `make check` | 需要 Docker，包含 PostgreSQL 與服務組裝測試 |
| 完整編譯、打包與檢查 | `(cd backend && ./gradlew build)` | 不包含 repository-level Karate E2E |
| 獨立 migration 關卡 | `./scripts/verify-migrations.sh` | 空 DB、重跑、V7 升級、checksum／版本不符的阻擋 |
| Monolith SIT | `(cd backend && ./gradlew :deployments:monolith:sit)` | schema、持久化、交易與跨 context 整合 |
| 五個 microservice 啟動測試 | `(cd backend && ./gradlew :deployments:microservice:sit)` | 各自建立測試 DB、migration、啟動並檢查 health／組裝 |
| 完整 Events／Temporal E2E | `make e2e` | 隔離的 PostgreSQL、Kafka、Debezium、Temporal 與 monolith |
| Microservice Events E2E | `E2E_DEPLOYMENT=microservice make e2e` | 啟動五個 JVM，重用同一份 Events 案例與 fixture |
| 前端測試 | `npm --prefix frontend test` | 元件與 hook 測試；先執行 `npm --prefix frontend ci` |
| 前端型別檢查與打包 | `npm --prefix frontend run build` | TypeScript 檢查與 Vite build |

修改 Java 後執行 `(cd backend && ./gradlew spotlessApply)`；提交前執行 `spotlessCheck`。
只驗證某個 context 可用 `(cd backend && ./gradlew :inventory:inventory-server:test)`。
上述一般 SIT 不包含需以 `ARCHONE_ALLOCATION_BENCHMARK=true` 額外開啟的效能 benchmark。

## E2E 範圍與報告

```bash
E2E_MODE=events make e2e       # 只跑 Events
E2E_MODE=temporal make e2e     # 只跑 Temporal
E2E_DEPLOYMENT=microservice make e2e # 五服務 Events，無 Temporal
KEEP_E2E_STACK=true make e2e  # 失敗後保留測試基礎設施供檢查
```

E2E 不需要先執行 `make dev-up`；runner 會依部署組合打包、準備獨立基礎設施，
先 migration／check、建立共用測試資料與 topics／connector，再啟動後端。
Monolith 預設使用 `archone-karate-e2e-monolith` project，
port 為 28390（API）、28391（PostgreSQL）、28392（Kafka）、28393（Connect）、
28394（Temporal）、28396（Temporal UI）。Microservice 使用 `archone-karate-e2e-microservice`，
五個服務在 28480～28484，PostgreSQL／Kafka／Connect 在 28491／28492／28493。
每組使用新的隔離 DB；runner 會拒絕已有容器或 volumes 的同名 project。
預設結束時移除該次測試容器與 volumes。
`KEEP_E2E_STACK=true` 保留基礎設施，app process 仍會停止；查完後可用以下指令清除測試資料：

```bash
docker compose -p archone-karate-e2e-monolith -f docker/compose.yml -f docker/compose.dev.yml \
  --profile temporal down -v --remove-orphans
```

若自訂 `COMPOSE_PROJECT_NAME`，清理時也要使用同一個名稱。
HTML／JUnit 報告在 `e2e/spec/build/<deployment>/reports/`；Gradle 報告在各 module 的 `build/reports/tests/test/`
或 `build/reports/tests/sit/`。更多參數與案例見 [E2E 說明](../../e2e/spec/README.md)，
壓測另見 [perf 說明](../../e2e/perf/README.md)。

Monolith 與 microservice 共用 Events／同步 API 案例；monolith 另跑 Temporal 案例。
測試以 `workflowQueryStatus` 與 Workflow snapshot 驗證 orchestration 狀態；Events 取消先驗證
HTTP 202 受理，再輪詢 process 的完成或拒絕結果。完整流程與業務斷言不因部署方式改變。
Microservice SIT 停用 Kafka listener，驗證各服務的獨立組裝；microservice E2E 則實際啟動五個
JVM、Kafka 與 Debezium，驗證跨服務事件與 Fulfillment 的遠端 HTTP 查詢。
