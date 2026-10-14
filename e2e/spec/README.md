# Karate v2 E2E

這裡是獨立於 backend Gradle source set 的黑箱 E2E。測試透過 HTTP 驗證完整履約流程，環境包含
真實 PostgreSQL、Kafka、Debezium Connect，以及 monolith 或五個 microservices。
兩種部署共用同一份 Events feature／fixture／業務斷言；monolith 另支援 Temporal：

```text
HTTP 下單
  → event_outbox → Debezium → Kafka → event_inbox
  → Inventory allocation → WMS shipment
  → Events 或 Temporal orchestration
  → Order FULFILLED
```

## 執行

前置條件是 Java 25、Docker、`curl`、`jq` 與 `shasum`。執行器會優先使用 Gradle toolchain
偵測到的 Java 25；必要時可用 `E2E_JAVA_BIN` 指定 executable。執行：

```bash
make e2e

# 相同 Events 案例，改由五個獨立 JVM 執行
E2E_DEPLOYMENT=microservice make e2e
```

### 執行階段

`run.sh` 會執行以下工作：

1. 下載官方 Karate `2.1.1` standalone JAR，並核對固定的 SHA-256。
2. 依 `E2E_DEPLOYMENT` 建立 monolith jar 或五個版本為 `e2e` 的 service jars。
3. 以獨立 Compose project 啟動 PostgreSQL、Kafka、Debezium Connect；需要時才啟動 Temporal。
4. 執行 migration／check，載入共用主檔／SKU／庫存 fixture，建立 topics／DLT 與 Outbox connector，再啟動所有 JVM 並等待 health。測試使用 `e2e` profile 與明確的連線參數，不依賴 monolith 的 dev seed。
5. 先以 Events mode 驗證配貨、補貨、FIFO、取消、完整履約及 Debezium pause/resume 追趕。
6. 以 30 秒 WMS simulation delay 重啟 Events mode，穩定驗證 Shipment 建立後、作業前的取消窗口。
7. Monolith 的 all／temporal mode 另驗證成功、等待補貨與取消 workflow；取消 Shipment 的案例同樣使用 30 秒窗口。
8. 測試成功或失敗後關閉本次所有 JVM，移除這次 E2E 的 containers 與 volumes。任何 feature 失敗都回傳非零 exit code。

HTML／JUnit 報告輸出在 `e2e/spec/build/<deployment>/reports/`，各 JVM 的 log 在同層 build 目錄。
失敗時 infra log 會先寫入 `infra.log`。macOS runner 執行期間會防閒置休眠，結束時自動解除。
若失敗後需要保留基礎設施以便檢查，可執行：

```bash
KEEP_E2E_STACK=true make e2e
```

### 部署選擇與路由

| 設定 | Monolith | Microservice |
| --- | --- | --- |
| `E2E_DEPLOYMENT` | `monolith`（預設） | `microservice` |
| `E2E_MODE` 預設 | `all`；也可選 `events`／`temporal` | `events`；其他值在啟動前拒絕 |
| Compose project | `archone-karate-e2e-monolith` | `archone-karate-e2e-microservice` |
| `E2E_APP_PORT` | 28390 | 28480，後續服務使用 +1～+4 |
| PostgreSQL／Kafka／Connect | 28391／28392／28393 | 28491／28492／28493 |
| Temporal／Temporal UI | 28394／28396 | 不啟動 |

Microservice 的 +0～+4 依序為 Ordering、Inventory、WMS、Fulfillment、Logistics Data。
runner 將位址傳給 Karate，並設定 Fulfillment 查詢其他服務的 HTTP URLs。
案例透過具名 URL 選擇 API 的 owner，不新增測試用 gateway：

| Karate 變數／環境變數 | API |
| --- | --- |
| `orderingUrl`／`E2E_ORDERING_URL` | 訂單建立與查詢 |
| `inventoryUrl`／`E2E_INVENTORY_URL` | 收貨、庫存與庫位 |
| `fulfillmentUrl`／`E2E_FULFILLMENT_URL` | 履約聚合查詢、取消請求與 process 查詢 |
| `logisticsDataUrl`／`E2E_LOGISTICS_DATA_URL` | 貨主、商品、SKU 與可用倉 |

Monolith 的四個 URL 都指向同一服務；直接執行 Karate 時，未提供具名 URL 會退回 `E2E_BASE_URL`。
WMS 由事件推進，再由 Fulfillment 經 HTTP 查詢結果，沿用原本的業務案例。
調整 runner 的服務連接埠請用 `E2E_APP_PORT`，基礎設施可用 `ARCHONE_POSTGRES_PORT`、
`ARCHONE_KAFKA_PORT`、`ARCHONE_CONNECT_PORT` 等既有變數覆寫。

runner 會拒絕已存在容器或 volumes 的同名 project，避免沿用已消耗庫存或清理到既有環境。
保留環境後，請另選 project／ports，或確認可刪除該組測試資料後自行清理：

```bash
docker compose -p archone-karate-e2e-microservice \
  -f docker/compose.yml -f docker/compose.dev.yml --profile temporal down -v --remove-orphans
```

## 流程案例矩陣

Karate 只從 HTTP 與 Connect 管理契約觀察系統，涵蓋下列使用者流程：

| 分類 | 案例 |
| --- | --- |
| 下單前置 | 貨主 → 商品 → SKU、可用倉、倉內庫位查詢 |
| 命令安全 | 重複上游單號拒絕、收貨 retry 冪等、同 receiptId 不同內容拒絕 |
| Events 配貨 | 立即配貨、缺貨等待與補貨喚醒、ship-complete、同 SKU 多行加總、嚴格 FIFO |
| Events 取消 | 待配貨取消、取消後不再被喚醒、Shipment 建立後取消、交運後拒絕取消 |
| Messaging | Connect 暫停時提交 outbox，恢復後從 WAL 追趕 |
| Temporal | 成功履約、缺貨等待後恢復、無 Shipment 取消、Shipment 建立後取消、取消請求內容衝突回 409 與原請求冪等重送、交運後拒絕取消 |

庫存隔離在 Events／Temporal 各有兩個案例：同倉不同貨主、同貨主不同倉，兩邊都使用相同 SKU。
目標訂單要 4 件但只有 2 件；另一範圍有 7 件仍不得配置。另一範圍的控制訂單要 9 件，補 5 件後
必須完成並剩 3 件，此時目標仍須等待且保留原本 2 件。最後只向目標補 2 件，原訂單才可完成，
目標庫存歸零、另一範圍仍剩 3 件。控制訂單提供非同步處理完成的觀察點，避免只靠固定 sleep 判斷隔離。
這些案例使用 `features/support/verify-stock-isolation.feature`，驗證業務庫存範圍，未涵蓋尚未實作的 API 授權。

一般成功履約案例會在下單前與交運完成後查詢 `/stock-pool`，核對在手量／預留量／ATP：
Events 出貨 3 件為 `20/0/20 → 17/0/17`，Temporal 出貨 4 件為 `20/0/20 → 16/0/16`。
這些斷言確認完成狀態與實際扣帳一致，且沒有殘留預留量或重複扣帳。

Shipment 取消案例會先用 10 件庫存預留第一張的 3 件，再確認需要 8 件的第二張訂單等待。
取消第一張後，第二張須在不補貨、不重送訂單的情況下自行完成；沿途透過 `/stock-pool`
驗證在手量／預留量／ATP 為 `10/0/10 → 10/3/7 → 10/8/2 → 2/0/2`，並確認原單維持取消。
取消後的等待訂單由既有 backlog reconciliation 恢復；runner 只在取消階段將掃描間隔設為 1 秒，
初次掃描仍使用原本的 30 秒延遲，避免測試依賴恰好趕上第一次掃描。一般測試階段保留原本間隔。
Events 與 Temporal 都執行這些斷言；共用的單批庫存檢查放在 `features/support/assert-stock-balance.feature`。

Feature 與 Scenario 都使用中文名稱，重要步驟旁也說明該斷言保護的業務規則。

`karate-config.js` 只放環境 URL、fixture IDs、timeout 與 UUID／時間等全域工具。Order 與 Stock
Receipt 的 request builder 放在 `features/support/`；`externalOrderNo` 與 `receiptId` 則由 Scenario
明確建立，讓重送與冪等案例能直接看出使用的是不是同一個業務鍵。

## E2E 與較低層測試的邊界

舊 correctnessE2E 透過 Java/Testcontainers 取得 Spring bean、repository 與 Kafka record，並注入
consumer failure。這些不是外部使用者流程，因此不搬進 Karate 黑箱案例：

- consumer retry exhaustion、transaction rollback、DLT headers／replay 由 messaging integration test 驗證。
- optimistic locking、同 eventId 的 Inbox claim 與併發不超賣由 SIT 驗證。
- HTTP 欄位 validation 與例外到 status code 的完整排列由 controller test 驗證。

這樣的邊界讓 E2E 專注回答「一個真實業務流程能不能從入口走到可觀察結果」，而不為了注入內部
故障，替 production application 增加測試專用 API。

Temporal 取消請求衝突案例會暫停 Connect，讓 Workflow 等待 Shipment terminal event，再驗證修改
reason／requestedAt 的 HTTP 請求回 409。恢復 Connect 後再驗證取消完成。`fulfillment`
只 mock Workflow client 驗證結果映射，不依賴 runtime；runtime 自己保留狀態轉換、重試與 replay 測試。

## Allocation console 驗收資料

`fixtures/e2e-catalog.sql` 另備有 `UI-EVT-*` 與 `UI-TMP-*` 各五個 SKU，分別供有貨、
缺貨補貨、多 SKU 整單與 FEFO 前端驗收；既有 Karate scenarios 不使用這些 SKU。
FEFO 每個 SKU 初始四批，在手 17、有效 ATP 10、過期 7。
完整資料以 [`fixtures/e2e-catalog.sql`](fixtures/e2e-catalog.sql) 與 executable feature scenarios 為準。

fixtures 僅適用新的隔離資料庫；`ON CONFLICT DO NOTHING` 不還原已消耗數量。
重跑請建立新的隔離環境，不要把同一資料庫當成初始庫存。`KEEP_E2E_STACK=true` 只保留容器，
runner 結束仍會停止其啟動的 app；瀏覽器驗收須另啟動對應模式的 app 與前端。


### 分模式執行

`E2E_MODE=events ./e2e/spec/run.sh` 僅啟動 Events 所需基礎設施並執行共用契約與
Events 案例（包含 cancellation 與 Connect catch-up）；不啟動 Temporal。
`E2E_MODE=temporal ./e2e/spec/run.sh` 執行共用契約與 Temporal 案例。
Monolith 預設 `E2E_MODE=all`；microservice 預設且只接受 `events`。

各次驗收請設定獨立 `COMPOSE_PROJECT_NAME` 與連接埠，使用新的資料庫。
可用 `E2E_BUILD_DIR` 指定獨立的絕對輸出目錄，避免覆寫前一模式的 log 與報告。
`KEEP_E2E_STACK=true` 保留基礎設施供後續瀏覽器驗證，runner 結束仍停止 app。
