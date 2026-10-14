# 啟動示範系統

> Current · 指令皆從 repository root 執行。

[文件首頁](../README.md) · [專案首頁](../../README.md)

## 一鍵啟動

先啟動 Docker（含 Compose v2），選擇一種履約模式：

```bash
make demo-up MODE=events
# 或
make demo-up MODE=temporal
```

簡寫為 `make demo-events`、`make demo-temporal`；未指定 `MODE` 時預設 `events`。
兩組環境可同時執行，使用不同的 ports 與資料 volumes。

指令會在容器中建置目前工作目錄的後端，並以 Node.js 執行前端；主機不需安裝 JDK 或 Node.js。
首次執行需要下載 images 與依賴。後端使用 dev profile，會初始化示範主檔並啟用 WMS 自動模擬作業。

啟動順序：

1. 啟動 PostgreSQL、Kafka、Kafka Connect、Kafka UI 與 Schema Registry；Temporal 模式另外啟動 Temporal。
2. 執行獨立 migration 與 schema check。
3. 建置並啟動後端、前端，等待健康檢查通過。
4. 初始化 Debezium Outbox connector 與示範 event schema，確認 connector 為 `RUNNING`。

看到「簡報環境已就緒」後，開啟下表的操作台。

## 服務位址

| 服務 | Events | Temporal |
| --- | --- | --- |
| **ALLOCATION! 操作台** | http://localhost:28695 | http://localhost:28795 |
| 後端 API | http://localhost:28690 | http://localhost:28790 |
| Kafka UI | http://localhost:28697 | http://localhost:28797 |
| Temporal UI | 不啟動 | http://localhost:28796 |
| PostgreSQL | localhost:28691 | localhost:28791 |
| Kafka | localhost:28692 | localhost:28792 |
| Kafka Connect | http://localhost:28693 | http://localhost:28793 |
| Temporal gRPC | 不啟動 | localhost:28794 |
| Management | http://localhost:28698 | http://localhost:28798 |
| Schema Registry API | http://localhost:28699/apis/ccompat/v7 | http://localhost:28799/apis/ccompat/v7 |

前端會代理到同組後端，Temporal 連結也指向同組 UI，不需修改 `frontend/.env.local`。
示範環境的 Temporal Activity 會在呼叫 use case 前等待約 3 秒；WMS 模擬處理延遲為 10 秒。

## 操作台能做什麼

| 頁面 | 用途 |
| --- | --- |
| 訂單 | 建立多 SKU 訂單、查看最近訂單、開啟履約詳情 |
| 配貨佇列 | 查看等待中的庫存作業、依條件篩選並前往補貨 |
| 庫存 | 查詢在手、預留、可承諾與過期數量，展開批次並收貨 |
| 主檔瀏覽 | 查看貨主、倉別、庫位、商品與 SKU |
| DLT 訊息 | 依 topic／consumer group 查詢失敗訊息與 payload |

可先查看庫存，再建立訂單，從「查看履約」追蹤配貨、交運與完成出庫。
右上角貨主選單是操作範圍選擇，目前尚未實作登入授權隔離；DLT 頁面是跨貨主的技術查詢。
前端尚未提供取消訂單或手動 WMS 操作。欄位、重試與自動追蹤行為見[前端操作說明](../../frontend/README.md)。

## 停止、重建與資料保存

```bash
make demo-ps MODE=events       # 查看狀態
make demo-logs MODE=events     # 查看日誌，Ctrl+C 離開
make demo-restart MODE=events  # 重建並啟動目前的程式碼
make demo-down MODE=events    # 停止服務，保留資料
```

Temporal 環境使用相同指令，將 `MODE` 改成 `temporal`。
Compose project 固定為 `archone-demo-events` 與 `archone-demo-temporal`，兩組的資料庫、Kafka、Temporal 資料各自保存。
重新啟動會沿用資料；切換展示模式時開啟另一組操作台，訂單不會移轉到另一個 driver。
兩組 demo 也與 `make dev-up` 的開發環境分開。

## 啟動失敗時

先用 `make demo-ps`、`make demo-logs` 找出未就緒的服務；修正後重跑 `make demo-up`。
固定連接埠被占用時，先確認並停止占用程式，或使用另一組隔離環境。
若 migration 回報版本或 checksum 不符，依[資料庫流程](../../database/README.md)處理既有資料庫；停止再啟動不會重置 history。

此入口使用 Vite dev server，供本機展示。正式部署見[打包與部署](deployment.md)。
Temporal 模式仍透過 Kafka／Outbox 傳遞業務事件；兩種 driver 的責任與切換限制見[履約架構](../architecture/fulfillment-orchestration.md)。

## 首頁截圖

專案首頁使用 `docs/images/` 下的實際操作台截圖，擷取自本機 Events demo。
更新畫面時先啟動系統，待頁面資料載入完成後擷取；使用示範資料並保留原始畫面。
