# Karate v2 E2E

這裡是獨立於 backend Gradle source set 的黑箱 E2E。測試透過 HTTP 驗證完整履約流程，環境包含
真實 PostgreSQL、Kafka、Debezium Connect、Temporal 與 monolith：

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
```

`run.sh` 會執行以下工作：

1. 下載官方 Karate `2.1.1` standalone JAR，並核對固定的 SHA-256。
2. 建立 monolith executable JAR。
3. 以獨立 Compose project 啟動 PostgreSQL、Kafka、Debezium Connect 與 Temporal。
4. 在 dev 主檔上加入每個案例獨享的 E2E SKU／庫存 fixture，避免流程互相污染。
5. 先以 Events mode 驗證配貨、補貨、FIFO、取消、完整履約及 Debezium pause/resume 追趕。
6. 以 30 秒 WMS simulation delay 重啟 Events mode，穩定驗證 Shipment 建立後、作業前的取消窗口。
7. 重啟為 Temporal mode，驗證成功、等待補貨與取消 workflow；取消 Shipment 的案例同樣使用 30 秒窗口。
8. 測試結束後關閉 app，移除這次 E2E 的 containers 與 volumes。

HTML／JUnit 報告輸出在 `e2e/spec/build/reports/`。若失敗後需要保留基礎設施以便檢查，可執行：

```bash
KEEP_E2E_STACK=true make e2e
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
reason／requestedAt 的 HTTP 請求回 409。恢復 Connect 後再驗證取消完成。`fulfillment-process`
只 mock Workflow client 驗證結果映射，不依賴 runtime；runtime 自己保留狀態轉換、重試與 replay 測試。
