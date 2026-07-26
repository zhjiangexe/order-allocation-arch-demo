## Why

v1 明確決定不做前端 UI，因此 `order-promising` 目前只有兩支 HTTP 端點
（`OrderController` 的下單與單筆查詢），且兩支都是為了 k6 壓測而存在，不是為人使用
而設計。要讓這個系統能被互動式操作與觀察——下一張單、查一次庫存、觸發一次補貨、
看一張訂單的事件因果鏈——現有 HTTP 表面不足。

同時既有的下單端點本身有三個獨立的缺陷：`@RequestMapping` 未限制 HTTP method，
因此 `GET /orders?sku=X&quantity=1` 會真的建立訂單，違反 GET 必須是 safe method；
建立資源的 command payload 走 query string；回傳 body 是裸的 UUID 字串。第一項同時
是新增列表端點的阻礙——它已佔用 `/orders` 的所有 method。

## What Changes

- **BREAKING**：`POST /orders` 合約修正——限定為 `@PostMapping`、command payload 改走
  JSON request body、回傳改為與 `GET /orders/{orderId}` 相同的訂單表示型別。HTTP
  狀態碼維持 `200`，不改為 `201`。
- **BREAKING**：`e2e/perf/k6/hot-sku-burst.js` 同步更新請求與回傳解析，並重跑一次
  壓測以更新 `e2e/perf/README.md` 的 baseline 數字，避免文件數字與腳本版本脫節。
- 新增 `GET /orders`：回傳最近下單的 N 筆訂單，依 `placedAt` 遞減排序並以 `id`
  作為 tie-breaker 確保順序穩定。`limit` 預設 20、上限 100，超出上限回 `400`，
  不靜默截斷。
- 新增查詢用 index 至 `orders` 與 `event_outbox`，直接併入
  `V3__create_orders.sql` 與 `V5__create_event_inbox_and_outbox.sql`（此 schema 尚未
  部署至任何環境，理由與 `fix-outbox-partition-key-semantics` 一致）。outbox 的
  index 是必要的——單筆訂單查詢是 k6 壓測輪詢的熱路徑，事件因果鏈若走全表掃描會
  影響既有 baseline。
- 擴充 `GET /orders/{orderId}`：新增 `events` 陣列，依發生時間回傳該訂單的
  Integration Event 因果鏈，每筆含事件識別碼、型別、發生時間與原樣的 payload。
- 新增 `GET /stock-pool/{sku}`：回傳該 SKU 的 on-hand、reserved 與
  available-to-promise。此為 allocation 模組的第一支 REST entrypoint。
- 新增 `POST /demo/replenish`（dev-only）：向 `inventory.stock-events` 發布一則真實的
  `StockReplenishedIntegrationEvent`，扮演外部 Inventory bounded context 的上游
  producer，回傳 `202`。
- 新增 `GET /demo/config`（dev-only）：回傳目前生效的
  `archone.allocation.partition-key-strategy`，讓操作台能顯示現在跑的是 v1 還是 v3。

## Capabilities

### New Capabilities

- `order-promising-http-api`: 訂單與庫存的 HTTP 命令與查詢表面——下單、最近訂單
  列表、單筆訂單含事件因果鏈、單一 SKU 的庫存狀態。這些是正式的業務能力，不受
  profile 限制。
- `demo-only-probes`: 僅在 dev profile 啟用的探針端點——模擬外部上游發布補貨事件、
  揭露目前生效的分區策略。這些不屬於任何 bounded context，存在目的是讓系統可被
  互動式操作與觀察。

### Modified Capabilities

- None.

## Impact

- 依賴 `fix-outbox-partition-key-semantics`：事件因果鏈以 aggregate identity 查詢
  outbox，該查詢條件在分區策略為 `sku` 時目前不成立。
- Production code：`OrderController`、`PlaceOrderUsecase`（回傳型別）、
  `OrderRepository` 與其實作（新增查詢）、allocation 模組新增 REST entrypoint 與
  查詢 usecase、`common/outbox` 新增唯讀查詢介面、新增 `demo` package。
- Schema：`orders` 與 `event_outbox` 各新增一個查詢 index（改
  `V3__create_orders.sql` 與 `V5__create_event_inbox_and_outbox.sql`）。
- 基礎設施：`demo` 探針使 application 首次直接作為 Kafka producer；既有對外發布
  全部走 Debezium。此處不經 outbox 是正確的——探針不變更任何本地狀態，沒有需要與
  事件發布對齊的 transaction。
- 測試：`OrderController` 的 web 層測試、新增查詢的 repository 測試、探針的
  Kafka 發布測試。
- 文件：`e2e/perf/README.md` 的 baseline 數字；`docs/stock-reservation-design.md`
  新增 HTTP 表面的說明。
- 不影響 allocation domain 規則、Integration Event 契約、Kafka topic 名稱或 consumer
  端的 inbox 冪等機制。
