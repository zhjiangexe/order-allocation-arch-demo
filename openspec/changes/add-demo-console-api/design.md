## Context

`order-promising` 目前的 HTTP 表面只有 `OrderController` 兩支端點，兩支都是為
`e2e/perf/k6/hot-sku-burst.js` 而生：下單那支用 query string 傳參數、回傳裸 UUID，
單筆查詢那支的 `OrderStatusResponse` Javadoc 明說它的用途是讓 k6 量「time to
allocation decision」。v1 決定不做前端 UI，所以這個表面從來不需要為人服務。

本次要讓系統能被互動式操作：下單、查庫存、觸發補貨、看一張訂單的事件因果鏈。這四件
事分別落在四支查詢／命令端點與兩支 dev-only 探針上。

事件因果鏈依賴 `fix-outbox-partition-key-semantics`。該 change 讓 `aggregateid` 回歸
aggregate identity 之後，`WHERE aggregatetype = 'Order' AND aggregateid = ?` 才會在
兩種分區策略下都回傳完整結果。

`allocation` 模組目前沒有任何 REST entrypoint；`common/outbox` 目前只有寫入路徑
（`OutboxAppender` → `OutboxRepo.append`），沒有讀取路徑。這兩處都要新增，是本次
唯一觸及既有模組結構的地方。

## Goals / Non-Goals

**Goals:**

- 提供足以驅動互動式操作台的 HTTP 表面：下單、最近訂單、單筆訂單含事件因果鏈、
  單一 SKU 庫存。
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
- 不在事件因果鏈中標註「這次配置是被哪一次補貨觸發的」。追溯補貨因果需要在事件間
  建立 correlation，屬於獨立議題。
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

### 事件因果鏈由 outbox 唯讀查詢供應，不新增投影表

`common/outbox` 新增唯讀查詢介面，依 aggregate type 與 aggregate id 取回該 aggregate
的全部 outbox row，按 `timestamp` 排序。

outbox 是這個系統唯一完整記錄「發出過哪些 Integration Event」的地方，而
`docs/stock-reservation-design.md` 已明訂其 row 最少保留 30 天、不得只因資料變舊而
刪除——它是有保留承諾的 append-only 紀錄，不是暫存佇列，查詢它是正當的。

考慮過新增 `order_event_log` 投影表。由於本次不追補貨因果，該表內容會與 outbox
逐筆重複、零新增資訊，卻多一條要維護的寫入路徑。

payload 原樣回傳，不攤平成固定欄位。不同事件型別的 payload 形狀本就不同，攤平會
逼後端為每個型別維護一份對映，而新增事件型別時又得再改一次；原樣回傳讓這個端點對
事件型別的增長免疫，代價是前端以 key-value 形式呈現。

### 單筆訂單查詢是壓測熱路徑，因果鏈查詢必須有 index

`GET /orders/{orderId}` 是 k6 `pollUntilDecided` 的輪詢目標，1,000 VUs 之下每張訂單
會被查詢多次。因果鏈查詢若走 `event_outbox` 全表掃描，會把壓測 baseline 一起拖走，
而那組數字是這個專案的對外成果。

因此 `event_outbox` 新增 `(aggregatetype, aggregateid, timestamp)` 複合 index，欄位
順序對應「等值篩選兩欄 + 排序一欄」，與 `orders` 既有 `idx_orders_backorder_fifo`
的設計理由相同。

未把 `events` 改成以查詢參數選擇性載入。有了 index 之後每次輪詢多的是一次回傳
少量 row 的 index lookup，相對於該路徑既有的配置寫入負載可以忽略；而選擇性載入會
讓同一個端點有兩種回應形狀。若重跑壓測後 baseline 出現可測量的退化，再以查詢參數
選擇性載入作為對策——這是有觸發條件的後備方案，不是預先實作的複雜度。

### 最近訂單排序需要 tie-breaker 與專屬 index

`placed_at DESC` 單獨使用時，同一毫秒寫入的多筆訂單在重複查詢間順序不保證穩定，
操作台每秒輪詢會看到列表無故跳動。因此排序為 `placed_at DESC, id DESC`，並新增
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
不為從未部署過的 schema 累積 migration 歷史。兩個 index 分別併入
`V3__create_orders.sql` 與 `V5__create_event_inbox_and_outbox.sql`，並沿用該處以註解
說明欄位順序理由的既有慣例。

## Implementation Contract

**Behavior:**

- `POST /orders` 接受 JSON body（SKU 與正整數數量），建立訂單並回 `200`，body 為與
  單筆查詢相同的訂單表示。`/orders` 上的非 POST 請求不建立訂單。
- `GET /orders` 回最近訂單，依 `placedAt` 遞減、`id` 遞減排序；`limit` 預設 20、
  範圍 1..100，超出回 `400`；列表項不含 `events`。
- `GET /orders/{orderId}` 回單筆訂單並新增 `events` 陣列，依發生時間排序；未知
  orderId 回 `404`（沿用既有行為）。
- `GET /stock-pool/{sku}` 回該 SKU 的 on-hand、reserved、available-to-promise；
  無 stock pool 回 `404`。
- `POST /demo/replenish`（dev-only）發布真實 `StockReplenishedIntegrationEvent`
  並回 `202` 與該事件識別碼。
- `GET /demo/config`（dev-only）回目前生效的分區策略值。
- 非 dev profile 下，兩支探針路徑回 `404`。

**Interface / data shape:**

- 訂單表示沿用既有 `OrderStatusResponse` 的欄位，新增 `events` 陣列（僅單筆查詢
  回傳）。每個 event 項含事件識別碼、事件型別名稱、發生時間、以及原樣的 payload
  JSON 物件。
- 庫存回應欄位以領域語彙命名：on-hand、reserved、available-to-promise，不使用縮寫。
- `OrderRepository` 新增「取最近 N 筆」查詢。`common/outbox` 新增唯讀查詢介面，
  依 aggregate type 與 aggregate id 回傳按發生時間排序的 row。
- `PlaceOrderUsecase.placeOrder` 回傳型別由 `UUID` 改為 `Order`。
- 不新增、不修改任何 Integration Event 型別、Kafka topic 名稱或 payload 欄位。

**Schema:** `orders` 新增 `(placed_at DESC, id DESC)` index；`event_outbox` 新增
`(aggregatetype, aggregateid, timestamp)` index。兩者併入既有 migration 檔。

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
3. 事件因果鏈以 SIT 驗證：一張經歷 placed → backordered → allocated 的訂單，在
   `partition-key-strategy=sku` 下查詢回傳三筆事件、順序正確、payload 與發布內容
   一致；剛下單的訂單回傳僅含一筆的陣列而非錯誤。
4. 庫存查詢以「on-hand 10／reserved 4 回 available-to-promise 6」與「未知 SKU 回
   `404`」驗證。
5. 補貨探針以整合測試驗證發出的訊息含事件識別碼與事件型別 header、payload 事件
   識別碼與 header 一致、record key 為 SKU。
6. 端到端驗證：對一個有 backorder 佇列的 SKU 觸發探針後，10 秒內 `GET /orders`
   可觀察到該批訂單依 FIFO 轉為 `ALLOCATED`。
7. 非 dev profile 下探針路徑回 `404`。
8. `e2e/perf/k6/hot-sku-burst.js` 更新後，`./e2e/perf/run.sh up` 通過既有 thresholds；
   `e2e/perf/README.md` 的 baseline 數字以本次重跑結果更新。
9. `./gradlew :order-promising:test :order-promising:sit` 全數通過。

**Scope boundaries:** 涵蓋上述六支端點、其支撐的 repository 與 usecase 變更、兩個
查詢 index、k6 腳本與 README baseline 同步，以及相關測試。不涵蓋前端、取消訂單
端點、訂單篩選與分頁、補貨因果的關聯追溯、分區策略的執行期切換，以及
`fix-outbox-partition-key-semantics` 已涵蓋的 outbox 語意修正。

## Risks / Trade-offs

- [因果鏈查詢拖慢 k6 輪詢熱路徑，使壓測 baseline 失真] → 新增
  `(aggregatetype, aggregateid, timestamp)` index；驗收第 8 項要求重跑壓測並確認
  thresholds 仍通過。若仍出現可測量退化，改以查詢參數選擇性載入 `events`。
- [k6 腳本與 README 數字脫節] → 三項合約修正一次完成，並把「重跑壓測並更新 README
  baseline」列為驗收條件，而非後續清理。
- [探針成為第一個直接 Kafka producer，日後被誤認為可繞過 outbox 的先例] → 在探針
  的決策位置以註解記錄「不變更本地狀態故不需要 outbox」的理由，並以 dev profile
  限定，讓它無法出現在非 dev 環境。
- [`allocation` 首次出現 REST entrypoint，模組邊界可能被逐步侵蝕] → 本次僅新增
  唯讀的單一 SKU 查詢；命令類操作仍只從 Kafka entrypoint 進入。
- [`PlaceOrderUsecase` 回傳型別變更影響既有呼叫端] → 呼叫端僅 `OrderController`
  一處，編譯期即可暴露。
- [payload 原樣回傳使前端需處理不同形狀] → 這是刻意取捨：換得端點對事件型別增長
  免疫；前端以 key-value 表格呈現，不為每個型別寫版面。

## Migration Plan

1. 先完成 `fix-outbox-partition-key-semantics`——事件因果鏈的查詢條件依賴它。
2. 修改 `V3__create_orders.sql` 與 `V5__create_event_inbox_and_outbox.sql` 加入兩個
   index。
3. 執行 `./e2e/perf/run.sh down` 移除既有 Postgres volume 後重建；與前一個 change
   同理，修改既有 migration 會造成 Flyway checksum 不符，不得以 `flyway repair`
   略過。
4. 實作端點、usecase、repository 查詢與探針。
5. 更新 `e2e/perf/k6/hot-sku-burst.js` 的請求與回傳解析。
6. `./gradlew :order-promising:test :order-promising:sit` 全綠。
7. `./e2e/perf/run.sh up` 重跑壓測，以結果更新 `e2e/perf/README.md` 的 baseline。

回滾：本次無正式環境部署，回滾即為 revert commit 後重複步驟 3。

## Open Questions

None.
