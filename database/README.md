# 獨立資料庫 migration

`database/migrations` 是 monolith 與目前共用 schema 的 microservices 唯一的 SQL 來源。
SQL 不打包進 application jar；一般應用程式不依賴 Flyway，也不在啟動時修改 schema。
本次只搬移 V1–V8，不變更 SQL 內容或 Flyway checksum；已套用目前 V1–V8 的資料庫可以直接驗證。

## 本機與 CI/CD 共用入口

需要 Docker Compose v2。runner 固定為 `flyway/flyway:11.14.1`，與目前 Spring Boot BOM 的測試版本一致。
連線資料由環境變數提供；密碼使用本機環境或 CI secret，不寫入 script／SQL。
例如連接 host 上的本機 PostgreSQL（runner 在 container 內，不能用 `localhost` 指向 host）：

```bash
export FLYWAY_URL=jdbc:postgresql://host.docker.internal:28291/order_promising
export FLYWAY_USER=order_promising
export FLYWAY_PASSWORD=order_promising # 僅供本機 demo
./scripts/migrate.sh info
./scripts/migrate.sh validate
./scripts/migrate.sh migrate
./scripts/migrate.sh check
```

| 指令 | 行為 |
| --- | --- |
| `info` | 顯示已套用與待執行版本 |
| `validate` | 執行前檢查已套用的 checksum／history，允許 pending migrations |
| `migrate` | 驗證後只執行尚未套用的 SQL；失敗回傳非零 exit code |
| `check` | 執行後／部署前檢查；pending、checksum 不符或超出這份 SQL 的版本都會失敗 |

入口不提供 `clean`、`repair` 或自動 baseline。`check` 只檢查 history，不會修改 schema，
也不能證明新舊程式的業務相容性；相容性須由 review 與整合測試確認。
Flyway 的預設 future-version 忽略規則已清除，參見
[官方 ignoreMigrationPatterns 說明](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-ignore-migration-patterns-setting)。

既有 Compose 環境可把 Compose options 傳給同一入口，使用該環境的 network 與連線設定：

```bash
./scripts/migrate.sh migrate --env-file docker/env/dev.env \
  -f docker/compose.yml -f docker/compose.dev.yml
```

`make dev-up`、`make demo-up`、E2E 與 perf 啟動腳本會先等待 DB，執行 migration／check，
成功後才啟動應用程式。直接 `bootRun` 或 `java -jar` 時，請先自行完成這個步驟。
microservice 使用同一入口，把連線改成其共用資料庫即可；整個 schema 只需執行一次。

## 部署關卡

```text
CI：隔離 DB migration 驗證 → build／unit／SIT → monolith 與 microservice E2E
CD：CI 通過的相同 revision → 環境核准／相容性確認 → migrate → check → deploy → health／smoke
```

`.github/workflows/database-and-deployments.yml` 實作 CI；E2E 沿用現有完整測試，不忽略失敗。
CI matrix 分別啟動 monolith（Events／Temporal）與五個 microservices（Events），
共用 Events 案例與 fixture；每組各自執行 migration，使用獨立測試 DB。

CD 平台與目標環境核准尚未設定，因此這裡不新增可操作正式資料庫的 workflow。
平台需將上述 CI jobs 設為必要檢查，通過後才提供 migration job 的目標 DB 憑證。
每個目標 DB 的 migration／deployment job 應序列執行，避免不同 release 交錯。
SQL、runner 設定與 application image 必須來自同一個已驗證的 revision；不要搭配任意工作目錄。

stage／prod 的 `scripts/deploy.sh up|deploy|restart` 只執行 `check` 後啟動服務，
不代替 CD 執行 migration。此檢查使用該環境的 application DB 連線，帳號需能讀取
`flyway_schema_history`；實際 migration 使用獨立 DDL 帳號，由 CI secret 提供 `FLYWAY_*`。
若 DB 已比目前 SQL 新，應取該環境的正確 release bundle，再確認服務相容性，不略過檢查。

共用 schema 的變更由所有受影響 context 一起 review。先新增可相容欄位／表格，部署新程式與完成
必要回填，再另一次移除舊欄位；破壞性變更另外安排核准、備份與恢復方案。
application rollback 不等於 schema rollback；本入口不自動回退 SQL。

## Schema 內容與歷史

此專案尚未上線，原 V1–V32 已整理為六份直接建立最終模型的 migration。
歷史版本可由 Git 查閱；不再建立已淘汰的表格或執行舊資料轉換。

| Migration | 職責 |
| --- | --- |
| V1 | 貨主、商品／SKU、設施、庫位與貨主設施關係 |
| V2 | 訂單與訂單行，包含履約與取消關聯 |
| V3 | 庫存批次、配貨政策、StockOperation／Move、取消與收貨冪等記錄 |
| V4 | Subscriber-aware Inbox 與含 headers 的 Outbox |
| V5 | WMS Shipment、波次、揀貨、交運與收貨 |
| V6 | Inventory 的 deferred constraint triggers、functions 與 Ordering 來源 View |
| V7 | Events cancellation process |
| V8 | Cancellation outcome 記錄 |

欄位、PRIMARY KEY、UNIQUE、CHECK 與 FOREIGN KEY 都在各自的 CREATE TABLE 內定義。
索引緊接其表格；跨表的 deferred guards 在所有表建立後定義，保留交易結束時的完整性檢查。
沒有為了整理 schema 改變既有 nullable、default、索引、外鍵刪除策略或業務約束。

## Seed

Migration 只建立 schema；空白資料庫套用原 V1–V32 後同樣沒有業務 seed rows。
舊 UPDATE／INSERT SELECT 是歷史資料轉換，不是新環境必需的示範資料，已移除。

Monolith dev profile 的 `DevSeedDataInitializer` 直接建立現行主檔、庫存批次、訂單與 StockOperation／Move，
不依賴 allocation demand、stock picking 或 WMS 舊欄位回填。主檔先於參照它的庫存與訂單建立，
整批 seed 在同一個 transaction 內完成，讓 deferred guards 驗證最終一致性；重跑不重複新增。
`owner_allocation_policies` 是可選的貨主覆寫，未設定時使用 application 預設 `DISPATCH_DATE_FIRST`，
不需要將舊版對既有 owners 的回填改成 dev seed。

## 既有開發資料庫

先前將 V1–V32 整理為 V1–V6 並不是舊 schema 的升級路徑；若 DB 仍使用原 V1–V32 history，
會與目前版本不相容，
不能以 `flyway repair` 解決。請使用空白資料庫，或備份／確認資料可捨棄後另行重建。
簡報環境的 `make demo-down` 保留所有資料，不會自動清空舊 schema；本次程式修改也沒有刪除其資料。
未來有需要保留資料的正式環境後，新增版本 migration，不再改寫已套用的檔案。

## 驗證

整理時於獨立 PostgreSQL 16 資料庫分別套用原 V1–V32 與新版 V1–V6；
`pg_dump --schema-only --no-owner --no-privileges` 與 data-only dump 排除隨機的 restrict token 後完全一致。
比對涵蓋 27 張表、24 個額外索引（不含 PK／UNIQUE 自帶索引）、1 個 View、4 個 function 與 6 個 trigger。

若本機曾編譯舊 migration，先執行 `./gradlew :deployments:monolith:clean :deployments:deployment-support:clean` 清除舊的 build resources，
避免舊 SQL 殘留在輸出目錄；此命令只清編譯產物，不會清資料庫。

從 repository root 執行獨立 runner 的驗證（空 DB、重跑、checksum 失敗、版本不符與 V7 → V8 升級）：

```bash
./scripts/verify-migrations.sh
```

測試使用獨立 Compose project 與 tmpfs PostgreSQL，結束後清除本次容器，不使用開發資料庫或 volume。
Gradle 測試只將相同 SQL 放進 SIT／test fixture resources；monolith SIT 可透過測試專用 Flyway
auto-configuration 初始化 DB，microservice fixture 在啟動 application 前明確執行 Flyway。

從 backend 執行：

```bash
./gradlew :deployments:monolith:test :deployments:monolith:sit
```

`DatabaseFoundationIntegrationTest` 驗證新版 migration 套用、Flyway 重跑無變更與 canonical metadata。
各 schema／persistence／transaction SIT 繼續驗證最終業務約束；`DevSeedDataIntegrationTest` 驗證 seed
資料一致性與重跑。只服務歷史升級的 checksum guard 與分階段回填測試已移除。
