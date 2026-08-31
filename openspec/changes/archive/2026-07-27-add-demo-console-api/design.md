## Context

`order-promising` 目前的 HTTP 表面只有 `OrderRest` 兩支端點，兩支都是為
`e2e/perf/k6/hot-sku-burst.js` 而生：下單那支用 query string 傳參數、回傳裸 UUID，
單筆查詢那支的 `OrderStatusResponse` Javadoc 明說它的用途是讓 k6 量「time to
allocation decision」。v1 決定不做前端 UI，所以這個表面從來不需要為人服務。

本次要讓系統能被互動式操作：下單、查庫存、觸發補貨、看訂單狀態如何隨之改變。這四件
事分別落在四支查詢／命令端點與兩支 dev-only 探針上。

`allocation` 模組目前沒有任何 REST entrypoint，本次會新增其第一支（且僅唯讀），是這次
唯一觸及既有模組結構的地方。

## Goals / Non-Goals

**Goals:**

- 提供足以驅動互動式操作台的 HTTP 表面：下單、最近訂單、單筆訂單、單一 SKU 庫存。
- 修正 `POST /orders` 的三個獨立缺陷（method 未限制、payload 走 query string、
  回傳裸 UUID），並讓 k6 與 README baseline 同步到修正後的合約。
- 讓補貨可以被觸發，且走完整的真實路徑（Kafka → consumer → inbox → 重試／DLT），
  不是繞過訊息基礎設施的捷徑。
- 讓目前生效的分區策略在操作台上可見。
- 不因新增查詢而讓 k6 壓測 baseline 失真。

**Non-Goals:**

- 不做多頁路由、登入或權限。
- 不做訂單歷史的篩選與真分頁；`limit` 之外沒有其他查詢參數。
- 不新增取消訂單端點；`CancelOrderUsecase` 屬於 v1 核心範圍，與操作台無關。
- 不提供訂單的事件歷史或因果鏈。`event_outbox` 記錄的是「本服務對外發布了什麼」，
  不是「這張訂單發生了什麼」——它不含外部傳入的事件、內容由發布需求而非領域決定、
  且受 30 天保留政策約束，因此不是訂單歷史的正確來源。若日後真的需要領域層的事件
  歷史（含補貨觸發的因果），那是另一個 change，要建專屬的投影而不是查 outbox。
- 不改變 allocation domain 規則、Integration Event 契約或 Kafka topic 名稱。
- 不實作前端（屬於 `add-demo-console-frontend`）。
- 不提供切換分區策略的能力；它在啟動時解析，只揭露、不修改。

## Decisions

### `POST /orders` 的三個缺陷一次修正，狀態碼維持 200

現有 `@RequestMapping` 未指定 method、`@RequestParam` 取值、回傳裸 UUID 是三個獨立
問題，可以分開修，但分開修要付兩次壓測重跑的成本——每次改動 k6 腳本或回傳形狀都要
重跑一輪並更新 `e2e/perf/README.md` 的數字，否則文件數字與腳本版本會脫節。

三項一起改：限定 `@PostMapping`、payload 移入 JSON request body、回傳完整訂單表示。

狀態碼刻意維持 `200` 而非改為 `201`。`201` 在語意上更精確，但 k6 的 `check` 寫死
`r.status === 200`，改它會多破壞一處而換不到這個 demo 需要的東西。這是明確記錄的
取捨，不是疏漏。

限定 method 這一項同時是 `GET /orders` 的前置條件：現有 mapping 佔用 `/orders` 的
所有 method，兩支 handler 會在同一路徑上競爭。

### `PlaceOrderUsecase` 回傳 `Order`，POST 與 GET 共用同一個回應型別

`placeOrder` 目前只回 `UUID`，controller 因此無法在不重查一次的情況下組出含
`placedAt` 與 `status` 的回應。

改為回傳 `Order`。這與 `GetOrderUsecase.getOrder` 回傳 `Order` 的既有做法一致，
且讓 POST 能直接沿用 `OrderStatusResponse.from(...)`——POST 與 GET 回同一個型別，
前端只需要一個訂單模型，而不是「建立後拿到 A 型別、查詢時拿到 B 型別」。

考慮過讓 usecase 回傳一個新的 `PlaceOrderResult(orderId, placedAt)` record。它避免
把 domain object 交給 controller，但會製造第二個訂單表示型別，把成本轉嫁到每個
consumer 身上；而 domain object 交給 controller 這件事在本 codebase 已是既有做法。

### 最近訂單排序需要 tie-breaker 與專屬 index

`placed_at DESC` 單獨使用時，同一毫秒寫入的多筆訂單在重複查詢間順序不保證穩定，
操作台重新整理時會看到列表無故跳動。因此排序為 `placed_at DESC, id DESC`，並新增
方向一致的 `(placed_at DESC, id DESC)` index，讓 PostgreSQL 直接取前 N 筆而不排序。

`orders` 現有唯一 index 是 `(sku, status, backordered_since, id)`，對此查詢無效。
壓測後該表會累積數千至上萬筆，這不是「量小先不管」的情境。

同一個 tie-breaker 理由已記錄在 `V3__create_orders.sql` 對 FIFO index 的註解中。

### `limit` 超出範圍回 400，不靜默截斷

`limit` 預設 20、有效範圍 1..100，超出即 `400`。

靜默把 101 改成 100 會讓呼叫方無法分辨「回了 100 筆是因為只有 100 筆」還是「因為
被截斷」。這與補貨探針不回傳預期喚醒數量是同一個判斷：不製造會說謊的回應。

### 補貨探針發布真實 Kafka 事件，不直呼 usecase、不經 outbox

探針向 `inventory.stock-events` 發布真實的 `StockReplenishedIntegrationEvent`。

不直接呼叫 `ReplenishmentUsecase.handle(...)` 的三個具體理由：該方法收
`InboundCommand`，其中的 `MessageMetadata` 正是 inbox 冪等所依據的憑證，直接呼叫等於
自行偽造；繞過 Kafka 也繞過 `AllocationRetryExecutor` 與退避／DLT 這整套本專案投入
最多的機制；而既有 SIT 已經是繞過 Kafka 的 in-process 捷徑，若操作台也繞過，就沒有
任何地方在示範真實路徑。

不經 outbox 也是正確的，理由必須記錄以免看似自相矛盾：outbox 解決的是「本地狀態
變更」與「事件發布」的原子性；探針扮演外部 Inventory context，不變更任何本地狀態，
沒有需要對齊的 transaction。這會是本 application 首次直接作為 Kafka producer。

### 探針放 `demo` package，庫存查詢放 allocation 模組

補貨探針與設定揭露端點放在新的 `demo` package 並以 dev profile 限定，與
`DevSeedDataInitializer` 的 scope 手法一致。它們不屬於任何 bounded context——探針
假扮的是本 repo 並不擁有的上游，設定揭露端點描述的是執行期組態。

庫存查詢則放 `allocation` 模組的 REST entrypoint，不放 `demo`。查詢 SKU 的
available-to-promise 是正當的業務查詢能力，與探針性質不同；把它放進 `demo` 會讓
「哪些能力是正式的」這個判斷變得模糊。這是 allocation 模組的第一支 REST entrypoint。

### 查詢 index 併入既有 migration，不新增檔案

與 `fix-outbox-partition-key-semantics` 同一理由：schema 尚未部署至任何環境
（`spring.flyway.enabled` 僅 dev profile 開啟，`application-uat.properties` 為空檔），
不為從未部署過的 schema 累積 migration 歷史。index 併入 `V3__create_orders.sql`，
並沿用該檔以註解說明欄位順序理由的既有慣例。

## Implementation Contract

**Behavior:**

- `POST /orders` 接受 JSON body（SKU 與正整數數量），建立訂單並回 `200`，body 為與
  單筆查詢相同的訂單表示。`/orders` 上的非 POST 請求不建立訂單。
- `GET /orders` 回最近訂單，依 `placedAt` 遞減、`id` 遞減排序；`limit` 預設 20、
  範圍 1..100，超出回 `400`。
- `GET /orders/{orderId}` 回單筆訂單；未知 orderId 回 `404`（沿用既有行為）。
- `GET /stock-pool/{sku}` 回該 SKU 的 on-hand、reserved、available-to-promise；
  無 stock pool 回 `404`。
- `POST /demo/replenish`（dev-only）發布真實 `StockReplenishedIntegrationEvent`
  並回 `202` 與該事件識別碼。
- `GET /demo/config`（dev-only）回目前生效的分區策略值。
- 非 dev profile 下，兩支探針路徑回 `404`。

**Interface / data shape:**

- 訂單表示沿用既有 `OrderStatusResponse` 的欄位，由下單、列表與單筆查詢三處共用。
- 庫存回應欄位以領域語彙命名：on-hand、reserved、available-to-promise，不使用縮寫。
- `OrderStore` 新增「取最近 N 筆」查詢。
- `PlaceOrderUsecase.placeOrder` 回傳型別由 `UUID` 改為 `Order`。
- 不新增、不修改任何 Integration Event 型別、Kafka topic 名稱或 payload 欄位。

**Schema:** `orders` 新增 `(placed_at DESC, id DESC)` index，併入既有 migration 檔。

**Failure modes:**

- `limit` 超出 1..100 回 `400`，不截斷。
- 未知 orderId 或未知 SKU 回 `404`。
- 補貨探針發布失敗（Kafka 不可用）回 `5xx`，不吞掉錯誤——探針的價值在於誠實反映
  訊息路徑的狀態。
- 探針回應是 `202`，不代表配置已完成；配置結果只能由後續查詢觀察。

**Acceptance criteria:**

1. Web 層測試涵蓋：POST 以 JSON body 建立訂單並回完整表示；`GET /orders` 不建立
   訂單；`limit` 為 0、101、-1 時回 `400`，為 1、100、省略時成功。
2. 排序穩定性以「多筆同 `placed_at` 的訂單、連續兩次查詢結果序列一致」驗證。
3. 庫存查詢以「on-hand 10／reserved 4 回 available-to-promise 6」與「未知 SKU 回
   `404`」驗證。
4. 補貨探針以整合測試驗證發出的訊息含事件識別碼與事件型別 header、payload 事件
   識別碼與 header 一致、record key 為 SKU。
5. 端到端驗證：對一個有 backorder 佇列的 SKU 觸發探針後，10 秒內 `GET /orders`
   可觀察到該批訂單依 FIFO 轉為 `ALLOCATED`。
6. 非 dev profile 下探針路徑回 `404`。
7. `e2e/perf/k6/hot-sku-burst.js` 更新後，`./e2e/perf/run.sh up` 通過既有 thresholds；
   `e2e/perf/README.md` 的 baseline 數字以本次重跑結果更新。
8. `./gradlew :order-promising:test :order-promising:sit` 全數通過。

**Scope boundaries:** 涵蓋上述六支端點、其支撐的 repository 與 usecase 變更、`orders`
的查詢 index、k6 腳本與 README baseline 同步，以及相關測試。不涵蓋前端、取消訂單
端點、訂單篩選與分頁、任何形式的訂單事件歷史或因果鏈、分區策略的執行期切換。

## Risks / Trade-offs

- [k6 腳本與 README 數字脫節] → 三項合約修正一次完成，並把「重跑壓測並更新 README
  baseline」列為驗收條件，而非後續清理。
- [探針成為第一個直接 Kafka producer，日後被誤認為可繞過 outbox 的先例] → 在探針
  的決策位置以註解記錄「不變更本地狀態故不需要 outbox」的理由，並以 dev profile
  限定，讓它無法出現在非 dev 環境。
- [`allocation` 首次出現 REST entrypoint，模組邊界可能被逐步侵蝕] → 本次僅新增
  唯讀的單一 SKU 查詢；命令類操作仍只從 Kafka entrypoint 進入。
- [`PlaceOrderUsecase` 回傳型別變更影響既有呼叫端] → 呼叫端僅 `OrderRest`
  一處，編譯期即可暴露。

## Migration Plan

1. 修改 `V3__create_orders.sql` 加入查詢 index。
2. 執行 `./e2e/perf/run.sh down` 移除既有 Postgres volume 後重建；與前一個 change
   同理，修改既有 migration 會造成 Flyway checksum 不符，不得以 `flyway repair`
   略過。
3. 實作端點、usecase、repository 查詢與探針。
4. 更新 `e2e/perf/k6/hot-sku-burst.js` 的請求與回傳解析。
5. `./gradlew :order-promising:test :order-promising:sit` 全綠。
6. `./e2e/perf/run.sh up` 重跑壓測，以結果更新 `e2e/perf/README.md` 的 baseline。

回滾：本次無正式環境部署，回滾即為 revert commit 後重複步驟 2。

## Open Questions

None.
