# SR-08 Database Foundation 實作紀錄

狀態：已完成

完成日期：2026-07-23

## 實作範圍

SR-08 只建立 database migration 與 PostgreSQL integration test 基礎，不建立任何 reservation 業務資料表。

- 導入 Spring Boot Flyway starter 與 PostgreSQL database module。
- 導入 Spring Boot Testcontainers 整合與 PostgreSQL container。
- 建立 Flyway `V1` baseline migration。
- 設定 production-safe 的 schema 管理預設值。
- 提供 dev datasource 基礎設定。
- 使用真實 PostgreSQL 驗證 migration 啟動與 transaction failure rollback。

## 新增檔案

### `../../order-promising/src/main/resources/db/migration/V1__baseline.sql`

- 建立 Flyway schema history 的第一個版本。
- migration 目前只有註解，不建立業務 table。
- `stock_pools`、`orders`、`stock_reservations`、Inbox 與 Outbox table 分別留給 SR-09～SR-12。

### `../../order-promising/src/sit/resources/application-test.properties`

- 測試環境明確使用 `spring.jpa.hibernate.ddl-auto=none`。
- SIT profile 明確啟用 Flyway。
- 測試 datasource 由 Testcontainers service connection 提供，不保存固定 port 或認證資訊。

### `../../order-promising/src/sit/java/com/flowzati/archone/testsupport/PostgreSQLTestConfiguration.java`

- 提供可由後續 persistence tests 重用的 PostgreSQL container bean。
- 使用 Spring Boot `@ServiceConnection` 自動建立 datasource connection details。
- 固定使用 `postgres:16-alpine`，避免浮動的 `latest` image。
- 測試 database、username 與 password 均使用 `order_promising` 專用名稱。

### `../../order-promising/src/sit/java/com/flowzati/archone/DatabaseFoundationIntegrationTest.java`

- 使用 JDBC test slice，避免載入尚未完成的 application／Inbox／Outbox adapters。
- 驗證 Flyway 已套用 `V1` 且 validation 成功。
- 在 transaction 內建立 probe table、寫入資料並主動拋出錯誤。
- transaction 結束後確認 probe table 不存在，證明 PostgreSQL DDL 與資料變更都已 rollback。

## 變更檔案

### `../../gradle/libs.versions.toml`

新增 dependency aliases：

- `spring-boot-starter-flyway`
- `flyway-database-postgresql`
- `spring-boot-testcontainers`
- `testcontainers-postgresql`

### `../../order-promising/build.gradle`

- production runtime 加入 Flyway starter 與 PostgreSQL support。
- 保留 Gradle `test` source set 與 task 給 unit tests。
- 新增獨立的 `sit` source set 與 task，Testcontainers dependencies 只放在 `sitImplementation`。
- `check` 依賴 `sit`，完整 verification lifecycle 會同時執行 unit tests 與 SIT。

### `../../order-promising/src/main/resources/application.properties`

- 預設關閉 Flyway，避免 production application startup 自動執行 DDL。
- 明確關閉 `baseline-on-migrate`；新資料庫必須透過 versioned migrations 建立。
- 將 Hibernate `ddl-auto` 設為 `none`，避免 production 自動建立或修改 schema。

### `../../order-promising/src/main/resources/application-dev.properties`

新增 dev datasource 設定，並允許透過以下環境變數覆寫：

- `ORDER_PROMISING_DB_URL`
- `ORDER_PROMISING_DB_USERNAME`
- `ORDER_PROMISING_DB_PASSWORD`

未提供環境變數時，預設連線至 `jdbc:postgresql://localhost:5432/order_promising`。

Dev profile 明確啟用 Flyway；production 若要 migration，必須由部署流程另行執行或明確開啟。

### 測試目錄拆分

- 原本的空白 full-context smoke test 已由聚焦 database foundation 的 `DatabaseFoundationIntegrationTest` 取代。
- 原測試會因尚未完成的 raw `InboxStore extends JpaRepository` 而在 repository discovery 階段失敗；SR-08 不越界修改該 SR-12 項目。
- 純 domain／application unit tests 保留在 `../../order-promising/src/test/java`。
- PostgreSQL integration tests、共用 Testcontainers configuration 與 SIT resources 放在 `../../order-promising/src/sit`。

### `../stock-reservation-design.md`

- SR-08 checkbox 更新為完成。
- 整體進度更新為 `1 / 17`。

## 驗證結果

執行 unit tests：

```bash
./gradlew :order-promising:test
```

結果：`11 tests completed`。

執行 SIT：

```bash
./gradlew :order-promising:sit
```

結果：`2 tests completed`。

兩組測試結果：

```text
BUILD SUCCESSFUL
11 unit tests completed
2 SIT tests completed
```

完整 verification lifecycle：

```bash
./gradlew :order-promising:check
```

`check` 已確認同時依賴 `test` 與 `sit`，執行結果為 `BUILD SUCCESSFUL`。

驗證內容：

- Java production 與 test source 均成功編譯。
- PostgreSQL Testcontainer 成功啟動。
- Flyway `V1` migration 成功套用及驗證。
- transaction failure 後沒有留下 probe table。
- 原有 domain／application unit tests 全部通過。

## 開發環境注意事項

- Testcontainers 必須能連線至 Docker daemon。
- 本機固定使用 OrbStack，並在使用者層級的 `~/.zshrc` 設定 `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`；專案不負責偵測本機 Docker runtime。
- 一般 Docker Desktop／CI 若提供標準 `/var/run/docker.sock`，不需要使用 OrbStack 專用設定。
- 目前沒有 dev seed、production datasource credential 或業務 table；這些仍依原 tasklist 由後續任務負責。
