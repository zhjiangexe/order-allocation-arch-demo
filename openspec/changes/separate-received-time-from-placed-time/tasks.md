## 1. Schema

- [ ] 1.1 依 `order-intake` 的 **An order records when we received it and, when supplied, when it was placed**，改寫 `V2__create_ordering_tables.sql`：`orders.placed_at` 更名 `received_at`（仍 NOT NULL），新增可空的 `placed_at`，`idx (placed_at DESC, id DESC)` 隨之改為 `(received_at DESC, id DESC)`。行為上：空資料庫一次套用即得最終 schema。以 `./e2e/perf/run.sh down` 後重建、Flyway 套用成功驗證。

  沿用 R1～R3 的判準改寫既有 migration 而非新增——schema 未部署，新開一支只會在歷史上留下「建了又改名」的假歷史。

  **index 一定要跟著改名**：它現在依收單時刻排序，而那正是最近訂單列表的排序鍵。漏改會讓 index 指向一個不存在的欄位，migration 直接失敗——這一項不會安靜地錯。

## 2. Domain

- [ ] 2.1 `Order` 的 `placedAt` 更名 `receivedAt`，新增可空的 `placedAt`。行為上：兩個時刻各自獨立。以領域測試驗證。

- [ ] 2.2 依同一個 requirement，加上容忍窗驗證：`placedAt` 晚於 `receivedAt` 超過可設定的容忍窗即拋錯，未超過則接受，不設下限。行為上：填成明天的下單時間被擋下，快兩秒的不會。以領域測試涵蓋「三個月前、一分鐘前、晚兩秒、晚一天、沒給」五種情形驗證。

  **不做嚴格比較**：上游時鐘與我們的不同步，快幾秒是常態，嚴格比較會把正常的單擋在門外。容忍窗預設 5 分鐘——它擋的是「填成明天」這種等級的錯誤，不是精確的時鐘校正，註解要寫明這件事，免得日後有人以為它是量出來的。

  不設下限的理由：三個月前的下單時間可能是歷史資料匯入，那是合法的。

- [ ] 2.3 既有的 `requireNotBefore(backorderedSince, placedAt, ...)` 等時序驗證改為對 `receivedAt` 比較。行為上：缺貨、配貨、取消時間仍不得早於收單時刻。以既有測試改寫後通過驗證。

  **這一項容易漏**：那些驗證原本比較的欄位改名了，若改成比較新的 `placedAt`，會在上游沒給時（NULL）安靜地失效。

## 3. 應用層與持久化

- [ ] 3.1 `PlaceOrderCommand` 與 `PlaceOrderUsecase` 接受可選的下單時間；`receivedAt` 仍由 usecase 以 `Instant.now()` 寫入，**不接受呼叫端提供**。行為上：收單時刻永遠是我們的事實。以單元測試驗證。

- [ ] 3.2 `OrderEntity`、`OrderMapper`、`OrderRepositoryImpl` 兩個欄位；最近訂單查詢的排序改為 `received_at DESC, id DESC`。行為上：列表順序與改動前完全一致。以 SIT 驗證。

## 4. HTTP 契約

- [ ] 4.1 依 `order-promising-http-api` 的 **Placing an order accepts a JSON command and returns the created order**，`PlaceOrderRequest` 新增可選的下單時間欄位；`OrderStatusResponse` 的 `placedAt` 更名 `receivedAt` 並新增可空的 `placedAt`。行為上：回應同時帶兩個時間戳。以 web 層測試涵蓋「有給、沒給、超出容忍窗」三種情形驗證。

  **上游沒給時回傳 null，不填入收單時刻**——填了就與「上游真的給了同一個時間」在畫面上長得一樣。

- [ ] 4.2 依 **Recent orders are listed in stable descending order**，列表排序改用收單時刻，並新增一支「上游下單最早但最後才收到的單排在最前」的測試。行為上：排序基準是我們收到的順序。以測試驗證。

## 5. 前端

- [ ] 5.1 `api/types.ts` 的訂單型別：`placedAt` 更名 `receivedAt`，新增可空的 `placedAt`。行為上：型別檢查會抓出所有未更新的使用處。以 `tsc` 驗證。

- [ ] 5.2 `OrderTable` 顯示兩個時間戳，欄位標題分別是「收單時間」與「上游下單時間」；上游沒給時顯示為空而非重複收單時刻。行為上：兩者的差值一眼看得出上游延遲了多久。以前端測試涵蓋有值與空值兩種情形驗證。

  **下單表單不加時間欄位**：表單模擬的是上游系統送單，上游給不給下單時間是上游的事。兩種情形由種子資料展示。

## 6. 種子與收尾

- [ ] 6.1 `DevSeedDataInitializer` 種一張帶上游下單時間、一張不帶的訂單。行為上：畫面上一眼看得到兩種情形。以 seed SIT 驗證。

- [ ] 6.2 全 repo 掃過殘留：搜尋 `placedAt`／`placed_at` 的每一處，確認它現在指的是上游下單時間而不是收單時刻。行為上：沒有第二個地方還把舊語意當新語意用。

  **這是本 change 唯一無法靠型別擋住的風險**：欄位名沒變、意義變了。只改名不改語意的讀取端會安靜地讀到不同的東西。


- [ ] 6.3 更新 `openspec/changes/decouple-allocation-from-ordering/` 的文件：其中提到 `placed_at` 之處改為 `received_at`。行為上：R4 的文件從一開始就用新名字。

- [ ] 6.4 後端 `test` 與 `sit`、前端 `vitest` 與 `tsc` 全綠，`spectra validate --strict` 通過。
