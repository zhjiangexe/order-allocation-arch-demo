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

## Temporal 在訂單履約中的角色

本系統以 Allocation 為核心，Temporal 用來串接 Inventory、WMS 與 Ordering 的跨模組履約流程。
[`OrderFulfillmentWorkflowImpl.java`](backend/orchestration-temporal-runtime/src/main/java/com/flowzati/archone/orchestration/runtime/workflow/order/OrderFulfillmentWorkflowImpl.java)
負責決定「下一步做什麼、等待哪個結果」；**Activity** 則透過各模組的 Adapter 呼叫既有 Use Case，
實際執行配貨、建立出貨單、完成出庫與更新訂單。業務規則與資料交易仍由各模組負責，Workflow 保存的是流程進度。

正常履約主線如下；缺貨時會停在等待配貨結果，補貨後由事件通知繼續：

```text
訂單成立事件 → 啟動 Workflow
  → Activity：請求配貨 → 等待配貨完成 Signal
  → Activity：建立 WMS Shipment → 等待交運完成 Signal
  → Activity：完成出庫 movements
  → Activity：記錄訂單履約完成 → Workflow 結束
```

**Signal** 是外部結果通知：配貨與交運的 Integration Event 經 bridge 轉成 Signal，讓等待中的 Workflow 繼續。
WMS 內部揀貨、包裝與集貨仍由 WMS 自行執行；Temporal 模式也仍需要 Kafka／Outbox。
Activity 的逾時與重試由 Temporal 依設定處理；Workflow 可依執行歷史恢復進度，Activity 對應的業務操作仍須處理重複執行。

建議從 [履約活動圖（PlantUML）](backend/orchestration-temporal-runtime/src/main/java/com/flowzati/archone/orchestration/runtime/workflow/order/OrderFulfillmentWorkflowImpl.puml)
搭配 Workflow 實作閱讀，再依下表追到實際業務入口：

| 相關檔案 | 在本系統中的職責 |
| --- | --- |
| [OrderFulfillmentWorkflow](backend/orchestration-temporal-contract/src/main/java/com/flowzati/archone/orchestration/contract/workflow/order/OrderFulfillmentWorkflow.java) | 定義啟動入口、Signal、查詢進度的 Query，以及提交取消請求的 Update。 |
| [TemporalFulfillmentEventBridge](backend/fulfillment-process/src/main/java/com/flowzati/archone/orderfulfillment/entrypoint/messaging/TemporalFulfillmentEventBridge.java) | 將收單事件轉成 Workflow start，將配貨、交運與取消結果轉成 Signal。 |
| [Activity contracts](backend/orchestration-temporal-contract/src/main/java/com/flowzati/archone/orchestration/contract/activity) | 定義 Workflow 可呼叫的業務動作與輸入／輸出，實作由各 Context 提供。 |
| [TemporalInventoryAllocationActivitiesAdapter](backend/inventory-context/src/main/java/com/flowzati/archone/inventory/allocation/entrypoint/temporal/TemporalInventoryAllocationActivitiesAdapter.java) | `requestAllocation` → `AllocateOrderUsecase`，請求配貨。 |
| [TemporalShipmentActivitiesAdapter](backend/wms-context/src/main/java/com/flowzati/archone/wms/shipment/entrypoint/temporal/TemporalShipmentActivitiesAdapter.java) | `releaseToWarehouse` → `CreateShipmentUsecase`，建立出貨單；也提供請求取消出貨的 Activity。 |
| [TemporalInventoryMovementActivitiesAdapter](backend/inventory-context/src/main/java/com/flowzati/archone/inventory/movement/entrypoint/temporal/TemporalInventoryMovementActivitiesAdapter.java) | `completeOutboundMovements` → `CompleteOutboundMovementsUsecase`，在確認交運後完成出庫。 |
| [TemporalOrderActivitiesAdapter](backend/ordering-context/src/main/java/com/flowzati/archone/ordering/entrypoint/temporal/TemporalOrderActivitiesAdapter.java) | `recordOrderFulfillment` → `RecordOrderFulfillmentUsecase`，更新訂單履約結果；也提供取消訂單的 Activity。 |
| [TemporalFulfillmentWorkerConfiguration](backend/deployments/monolith/src/main/java/com/flowzati/archone/bootstrap/configuration/TemporalFulfillmentWorkerConfiguration.java) | 在 monolith 註冊 Workflow 與 Activity workers，從對應 Task Queue 接收並執行工作。 |

Workflow 另有取消協調分支，活動圖包含其等待與判斷；目前前端未提供取消操作。
Events 模式則由事件處理串接履約步驟，兩種模式沿用相同業務 Use Case 與業務狀態。

## 簡報前一鍵啟動（含前端）

先啟動 Docker（含 Compose v2），在 repository root 執行：

```bash
# 二選一；也可以同時啟動兩個互相隔離的環境
make demo-up MODE=events
make demo-up MODE=temporal
```

簡寫為 `make demo-events`、`make demo-temporal`。未指定 `MODE` 時預設 `events`。
底層腳本也可直接呼叫：`./scripts/demo.sh up temporal`。

指令會建置目前工作目錄的後端、啟動 PostgreSQL／Kafka／Kafka Connect／Kafka UI，
Temporal 模式另外啟動 Temporal server 與 UI；前端在 Node 容器內執行 `npm ci` 後啟動 Vite。
**Temporal 模式仍依賴事件驅動**：收單事件啟動 Workflow，配貨／交運事件轉成 Signal，
Ordering 的配貨狀態與庫存可用量喚醒等仍透過事件更新。因此兩種模式都會啟動 Kafka、
Kafka Connect 與 Outbox connector；Temporal 只接手指定的跨 Context 協調步驟。
等待前後端健康且 Debezium Outbox connector 為 RUNNING 後，才輸出「簡報環境已就緒」。
本機不必另外安裝 Java／Node；首次執行需要網路下載 images 與依賴，請在簡報前預先啟動。
後端使用 dev profile，沿用既有示範主檔初始化與 WMS 自動模擬作業。

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

前端自動代理到同組後端，Temporal 連結也自動指向同組 UI，不需修改 `frontend/.env.local`。
Temporal Activity 在 invoke usecase 前等待約 3 秒；WMS 模擬處理延遲為 10 秒。

```bash
make demo-ps MODE=temporal       # 查看服務狀態
make demo-logs MODE=temporal     # 持續查看所有服務日誌，Ctrl+C 離開
make demo-restart MODE=temporal  # 重建並重新啟動，套用程式修改
make demo-down MODE=temporal    # 停止該組服務，保留資料
```

兩組固定使用 `archone-demo-events`／`archone-demo-temporal` Compose project，
資料庫、Kafka 與 Temporal 資料以各自的 named volumes 保存；停止再啟動會沿用資料，
不是每次清空重建。切換展示模式請開啟另一組操作台，既有訂單不會移轉到另一個 driver。
這些環境與 `make dev-up`、先前手動啟動的 2849x／2859x 環境分開，舊示範訂單不會自動出現在新環境。

若啟動失敗，先執行 `make demo-ps MODE=...` 與 `make demo-logs MODE=...` 查看狀態；
修正後可重跑 `make demo-up MODE=...`。固定連接埠被其他程式占用時，請先停止占用者。
此入口供本機簡報使用，前端為 Vite dev server；正式部署仍使用下方流程。

## 資料庫初始化

目前尚未上線，migration 已整理成直接建立現行 schema 的 V1–V6，移除舊模型的 ALTER／資料回填歷史。
**本次整理前建立的資料庫不能直接套用新版**：Flyway 版本與 checksum 已改變，請另建空白資料庫，
或確認舊資料可捨棄後重建開發環境。不要用 `flyway repair` 假裝已完成 schema 遷移。
`make demo-down` 會保留 volumes，因此單純 down／up 不會自動轉換舊資料庫。
目前執行中的示範資料庫未被本次整理重建；重新建置啟動前請先處理其舊 schema。

各 migration 職責、seed 與驗證方式見 [資料庫說明](backend/deployments/monolith/src/main/resources/db/README.md)。

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
右上角共用貨主選擇模擬登入身分；各頁沿用所選貨主，切換時重設操作範圍。
Events／Temporal 由後端啟動設定決定，Temporal 詳情可跳轉 Workflow。
啟動方式與操作見 [frontend/README.md](frontend/README.md)，雙模式驗證見
[validation.md](docs/plans/allocation-console/validation.md)。

訂單列表 `GET /orders?limit=20&ownerId=...` 與配貨佇列都支援可選的 `ownerId`，先篩選貨主再取筆數。
這是查詢條件，尚未提供登入授權隔離。

Monolith 的相關 API：

- `GET /stock-operations?state=CONFIRMED&limit=200&ownerId=...`：列出指定貨主等待中的庫存作業；不提供精確缺口或全域佇列排名。
- `GET /demo/orders/{orderId}/fulfillment`：組合 Order、StockOperation／movement／批次、WMS Shipment，Temporal 模式另帶 Workflow state。
- `POST /orders/{orderId}/cancellation-requests`：把 immutable cancellation request 送進目前生效的 Events 或 Temporal 協調流程。

前端未提供取消操作。取消請求的 `requestId`、`requestedAt` 與 `reason` 是冪等內容；重試同一次請求時必須原樣重送。
