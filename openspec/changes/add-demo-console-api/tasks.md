## 1. 下單合約修正

- [x] 1.1 實作 **Placing an order accepts a JSON command and returns the created order**：依「`POST /orders` 的三個缺陷一次修正，狀態碼維持 200」，將 `OrderController` 的下單 mapping 限定為 POST、command payload 改由 JSON request body 取得、狀態碼維持 `200`。行為上：以 JSON body 送出 SKU 與正整數數量會建立訂單，`/orders` 上的非 POST 請求不再建立訂單。以 web 層測試驗證 POST 建立成功、且 `GET /orders` 不產生新訂單。
- [x] 1.2 實作「`PlaceOrderUsecase` 回傳 `Order`，POST 與 GET 共用同一個回應型別」：`placeOrder` 由回傳 `UUID` 改為回傳 `Order`，controller 沿用既有 `OrderStatusResponse.from(...)` 組出回應。行為上：POST 的回應 body 與單筆查詢同型別，含訂單識別碼、SKU、數量、狀態 `PENDING` 與下單時間，客戶端只需一個訂單模型。以 web 層測試斷言 POST 回應欄位與單筆查詢一致驗證。
- [x] 1.3 更新 `e2e/perf/k6/hot-sku-burst.js` 的下單請求與回傳解析，使其符合修正後的合約。行為上：壓測腳本能正確取得 orderId 並完成後續輪詢，`checks_total` 不因合約變更而失敗。以 `./e2e/perf/run.sh up` 通過既有 thresholds 驗證（baseline 數字的更新見 5.1）。

## 2. 訂單查詢端點

- [x] 2.1 實作 **Recent orders are listed in stable descending order**：新增 `GET /orders`，`OrderRepository` 新增取最近 N 筆的查詢。依「最近訂單排序需要 tie-breaker 與專屬 index」以 `placed_at DESC, id DESC` 排序，並依「查詢 index 併入既有 migration，不新增檔案」把方向一致的 index 併入 `V3__create_orders.sql`，沿用該檔以註解說明欄位順序理由的慣例。行為上：多筆訂單共用同一 `placed_at` 時，連續兩次查詢回傳相同序列。以排序穩定性測試與 repository 測試驗證。
- [x] 2.2 實作「`limit` 超出範圍回 400，不靜默截斷」：`limit` 預設 20、有效範圍 1..100，超出範圍回 `400`。行為上：呼叫方能分辨「回了 100 筆是因為只有 100 筆」與「因為被截斷」。以 web 層測試涵蓋 `limit` 為 0、101、-1 回 `400`，以及 1、100、省略時成功驗證。

## 3. 庫存查詢端點

- [x] 3.1 實作 **Stock pool state is queryable by SKU**：依「探針放 `demo` package，庫存查詢放 allocation 模組」，於 allocation 模組新增其第一支 REST entrypoint 與對應查詢 usecase，回傳該 SKU 的 on-hand、reserved 與 available-to-promise，欄位以領域語彙命名、不使用縮寫。行為上：on-hand 10／reserved 4 的 SKU 回報 available-to-promise 6；無 stock pool 的 SKU 回 `404`。以 web 層測試驗證兩種情形。

## 4. dev-only 探針

- [x] 4.1 實作 **The replenishment probe publishes a real upstream stock event**：依「補貨探針發布真實 Kafka 事件，不直呼 usecase、不經 outbox」，於新的 `demo` package 新增探針，向 `inventory.stock-events` 發布真實 `StockReplenishedIntegrationEvent`，回 `202` 與該事件識別碼、不回傳預期喚醒數量，並在決策位置以註解記錄「不變更本地狀態故不需要 outbox」的理由。行為上：發出的訊息帶事件識別碼與事件型別 header、payload 事件識別碼與 header 一致、record key 為 SKU。以整合測試斷言訊息 header、payload 與 key 驗證。
- [x] 4.2 實作 **The active partition key strategy is observable**：新增唯讀端點回報目前生效的 `archone.allocation.partition-key-strategy`，只揭露不提供切換。行為上：以 SKU 策略啟動時回報 SKU 策略為生效值。以 web 層測試在兩種設定值下各驗證一次。
- [x] 4.3 實作 **Probe endpoints exist only in the dev profile**：兩支探針端點以 dev profile 限定，與 `DevSeedDataInitializer` 的 scope 手法一致。行為上：非 dev profile 下探針路徑回 `404`，端點根本未註冊，而非註冊後拒絕。以未啟用 dev profile 的 context 測試驗證路徑回 `404`。

## 5. 端到端驗收與文件

- [x] 5.1 重建基礎設施並重跑壓測更新 baseline：先 `./e2e/perf/run.sh down` 移除既有 Postgres volume（修改既有 migration 會造成 Flyway checksum 不符，不得以 `flyway repair` 略過），再 `./e2e/perf/run.sh up`。行為上：既有 k6 thresholds 全數通過，代表合約修正與新增查詢未使壓測退化。以本次結果更新 `e2e/perf/README.md` 的 baseline 數字，使文件數字與腳本版本一致。
- [x] 5.2 端到端驗證補貨探針喚醒佇列：對一個有 backorder 佇列的 SKU 觸發探針，行為上：10 秒內 `GET /orders` 可觀察到該批訂單依 FIFO 轉為 `ALLOCATED`。以 SIT 或手動操作搭配明確時間界線驗證，不以「重新整理看得到」這類無界線描述作為通過條件。
- [x] 5.3 於 `docs/stock-reservation-design.md` 補上本次 HTTP 表面的說明：六支端點各自的用途、哪些是正式業務能力、哪些是 dev-only 探針及其不經 outbox 的理由。以文件審閱確認與實作一致驗證。
