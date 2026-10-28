# 業務 BDD 規格

`features/` 以標準 Gherkin 的 `Feature`、`Background`、`Scenario`、`Given`、`When`、`Then`
描述現有端到端流程，步驟內容使用繁體中文，供需求討論與驗收閱讀。

這些檔案對應 [`../spec/features/`](../spec/features/) 的 Karate 案例，保留相同檔名、Feature 名稱、
Scenario 名稱、順序與 tags。每個檔案頂端註明對照檔案；以兩邊共用的 `case-id` 定位案例。
目前共 7 個 feature、21 個 scenario。

| 業務規格 | Karate 實作 | 案例數 |
| --- | --- | ---: |
| [主檔與冪等](features/catalog-and-idempotency.feature) | [catalog-and-idempotency.feature](../spec/features/catalog-and-idempotency.feature) | 3 |
| [Events 履約](features/events-fulfillment.feature) | [events-fulfillment.feature](../spec/features/events-fulfillment.feature) | 7 |
| [Events 取消](features/events-cancellation.feature) | [events-cancellation.feature](../spec/features/events-cancellation.feature) | 2 |
| [Events Shipment 取消](features/events-shipment-cancellation.feature) | [events-shipment-cancellation.feature](../spec/features/events-shipment-cancellation.feature) | 1 |
| [事件傳遞恢復](features/connector-catch-up.feature) | [connector-catch-up.feature](../spec/features/connector-catch-up.feature) | 1 |
| [Temporal 履約](features/temporal-fulfillment.feature) | [temporal-fulfillment.feature](../spec/features/temporal-fulfillment.feature) | 5 |
| [Temporal 取消](features/temporal-cancellation.feature) | [temporal-cancellation.feature](../spec/features/temporal-cancellation.feature) | 2 |

## 雙向案例對應

兩邊每個 Scenario 上方都有相同的穩定 ID，例如：

```gherkin
  # case-id: EVT-FUL-002
  Scenario: 缺貨訂單會等待，收貨後由 availability event 喚醒並完成
```

在 repository 搜尋 `case-id: EVT-FUL-002` 即可找到業務規格與 Karate 實作。ID 使用普通註解，
不參與 Karate tag 篩選，也不改變 request、assertion、retry 或 runner 的執行邏輯。
檔名、行號或案例順序改變時仍能透過 ID 找到對應案例。

從 repository root 執行：

```bash
python3 e2e/biz-bdd/check_traceability.py

# 列出所有案例 ID、名稱，以及兩邊最新的檔案位置與行號
python3 e2e/biz-bdd/check_traceability.py --list
```

檢查只依賴 Python 3 標準函式庫，不會啟動應用程式或 Docker，也不執行或修改 feature。
遇到缺少 ID、格式錯誤、同一側重複 ID、缺少另一側案例或兩邊 Scenario 名稱不同時，回傳非零 exit code。
檢查範圍涵蓋兩個 features 目錄及其子目錄，排除 `support/` 的共用步驟；不是完整 Gherkin 語法或
業務斷言等價性檢查。目前是獨立指令，尚未接入 CI 或 `make e2e`。

維護方式：

- 新案例在兩邊各新增一個 Scenario，配置同一個尚未使用的 ID。
- ID 前綴為 `CAT`、`MSG`、`EVT-FUL`、`EVT-CAN`、`EVT-SHIP-CAN`、`TMP-FUL` 或 `TMP-CAN`，
  後接流水號，例如 `CAT-001`。前綴方便辨識來源，不由檔名動態產生。
- 已配置 ID 不因改名、搬檔或插入案例而重新編號；刪除後也不要挪給不同的業務案例。
- 同一案例改名時同步修改兩邊名稱，保留 ID；名稱不一致會由檢查提示。
- ID 放在 Scenario 前，可隔著空行、註解或 tags，不得放進步驟內容中。

## 閱讀約定

- 每個 Scenario 使用獨立的訂單、SKU 與庫存資料，不依賴上一個 Scenario。
- 除隔離案例明確指定的範圍外，下單、庫存與收貨都屬於同一貨主、同一履約倉庫。
- 「最終」表示等待非同步流程收斂；不規定輪詢頻率或固定等待秒數。
- 「受理取消」只代表開始協調，不表示訂單已取消。
- 「履約完成」指完成交運及訂單履約，不代表配送簽收。
- 庫存表中的可承諾量為 ATP；本次案例使用有效庫存，值為在手量減預留量。
- `StockOperation` 表達整組庫存作業，`StockMove` 表達單行移動需求；批次配置表達實際預留明細。

## 與自動化測試的關係

這是現有案例的業務語意版本，尚未提供 Cucumber step definitions 或執行器；實際可執行的 HTTP
驗證仍在 `e2e/spec`。此目錄不包含 Karate 的 request builder、JSON path、JavaScript、retry 或 helper call。
`support/verify-stock-isolation.feature` 與 `support/assert-stock-balance.feature` 的業務意義已展開到
對應 Scenario，因此不另外建立 support feature。

同一 Scenario 含多個操作階段時，保留多組 When／Then，以便追溯現有案例。技術失敗恢復案例仍保留
Connect 暫停／恢復這個必要前提；其餘步驟以業務動作與結果表達。

模式差異仍依現況保留：Events 的交運後取消先受理，再非同步拒絕；Temporal 已結束的 workflow
直接拒絕取消請求。主檔 feature 雖沿用來源的 `@events` tag，現有 runner 也會在 temporal-only
執行時跑這份共用案例，詳見 [`../spec/run.sh`](../spec/run.sh)。

規格範圍以既有 Karate Scenario 為準；業務敘述不代表每個語意步驟都已有獨立的底層斷言。
修改業務規則時，應同步檢視對應的 Karate Scenario 與 fixture。
