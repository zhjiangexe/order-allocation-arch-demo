## 1. 前端的策略標示

- [x] 1.1 依 `demo-console-frontend` 的 **The console presents orders and stock as two navigable pages**，把 `AppHeader.tsx` 的比對由行內字面 `'sku'` 改為具名常數 `STOCK_STRATEGY = 'stock'`，與後端 `OrderingDomainEventTranslator.STOCK_STRATEGY` 一致。行為上：跑 `stock` 策略時 header 標示「v3 single-writer」而非「v1 樂觀鎖」。以下一項的測試驗證。

  **這是缺陷修正，不是用詞整理。** R3 把設定值改名之後這個比對永遠失敗，header 因此把 single-writer 策略說成樂觀鎖策略——而「現在跑的是哪個策略」正是操作台要展示的東西，說錯等於整個 demo 的結論是錯的。字串比對失敗不拋錯、不留 log，所以它只能靠測試抓。

- [x] 1.2 新增 `AppHeader.test.tsx`（原本完全沒有測試檔，這正是 1.1 漏改的原因），涵蓋四種情形：`stock` 標成 v3 且原樣顯示後端給的值、`order-id` 標成 v1、**未知值一律當 v1**、讀取失敗時不給任何策略結論。行為上：下一次改名會在測試層失敗，而不是在畫面上安靜地說錯。**已以植入原 bug 驗證**——把常數改回 `'sku'`，恰好一支失敗（`stock` 那支），其餘三支仍過。

  未知值往保守方向倒的理由：說成 v1 只是少報一個能力，說成 v3 則是宣稱一個並不成立的保證。兩個方向不對稱。

  讀取失敗不給結論的理由：顯示「v1」會與「後端真的設成 order-id」在畫面上長得一樣。

## 2. 既有 requirement 的策略名

- [x] 2.1 依 `outbox-event-delivery` 的 **Allocation outcome events key by order identity**，更新該 requirement 的策略名（`sku` → `stock`），並把 scenario 標題與 `GIVEN` 一併改掉。行為上：規格不再引用一個不存在的設定值。以 `spectra validate --strict` 與文件審閱驗證。

  這個 requirement 是 R3 **應該包含卻沒有包含**的 MODIFIED：R3 的 delta 只改了它直接改寫的 requirement，改名的連帶影響留在別處。archive 之後它就以權威的姿態說著舊值。

- [x] 2.2 同一份 requirement 補上 R3 之後才存在的例外：`AllocationDomainEventTranslator` 現在發兩類事件——配置結果事件一律 `orderId`，**續做喚醒事件一律爭用群組**。原 requirement 以 topic 界定範圍，字面上沒涵蓋後者，但標題「Allocation outcome events key by order identity」很容易被讀成「那個 translator 發的都以 orderId 為 key」。行為上：讀規格的人不會為了「一致性」把策略套到續做事件上。

  **這一項無須新測試，因為保證是結構性的**：`AllocationDomainEventTranslator` 的建構子根本不收 `partition-key-strategy`，所以套不上去。已查證 `DomainEventTranslatorTest.shouldAlwaysKeyWakeContinuationByContentionGroup` 正是以無策略參數的建構子驗證。規格因此寫成「發它的元件 SHALL NOT 收到那個設定」——讀不到比讀到而選擇忽略更強，後者會被一次「讓它跟旁邊一致」的修改推翻。

- [x] 2.3 依 `demo-only-probes` 的 **The active partition key strategy is observable** 更新策略名，並明寫端點回報的是**設定值原文**而非衍生的標籤。行為上：operator 看到的字串就是他會去 grep 設定檔的那個字串。以文件審閱驗證（端點本來就是原文轉出，無程式改動）。

- [x] 2.4 依 `demo-console-frontend` 的同一個 requirement 更新 header 對照表（`sku` → `stock`），並補「未知值一律非 single-writer」與「讀取失敗不給結論」兩個 scenario——它們對應 1.2 的測試。行為上：1.2 的每一支測試都有規格出處。

- [x] 2.5 **（archive 前的最後一輪清查才發現）**依 `outbox-event-delivery` 的 **Outbox rows separate aggregate identity from delivery metadata**，更新「同一則事件在兩種策略下」對照表：策略名 `sku` → `stock`，`partition_key` 欄由 `sku` 改為 `<owner>/<node>`。行為上：規格舉的例子是系統真的產得出來的那一種。以文件審閱驗證（無程式改動）。

  這一列同時錯兩件事：策略名是舊的，而 key 的組成從 R3 起就不含 SKU 了。原本只盯著「策略名」去掃，因此漏掉同一格裡的第二個錯。requirement 一併加上「`partition_key` SHALL NOT 放裸 SKU 代碼」——**過時的例子比沒有例子更糟**，讀的人會把它當成格式的規定。

- [x] 2.6 依 `outbox-event-delivery` 的 **Debezium derives the Kafka message key from partition_key**，把示例 `partition_key = HOT-SKU` 改為 `<owner>/<node>`，標題「v3 ordering event」改為以策略名指稱。行為上：示例的 key 是實際會出現在 outbox 裡的形狀。以文件審閱驗證（無程式改動）。

  這一項容易被判成無害而略過：Debezium 只是把欄位原樣複製，不解讀內容，所以**任何值都同樣能說明機制**。正因如此才必須改——既然值本身對機制沒有影響，那它唯一的作用就是教讀者格式，而它教的是一個不會存在的格式。requirement 明寫這條，免得下次又被當成「只是個佔位字串」。

## 3. 程式註解

- [x] 3.1 `AllocationDomainEventTranslator` 的 Javadoc 還寫著「使 allocation consumer 成為**該 SKU** 的 single writer」，同一次改名的殘留。改為「成為那些庫存列的 single writer」，並把「per-SKU 的順序保證」改為「庫存維度的順序保證」。行為上：註解不再描述一個已經改掉的 key 組成。以文件審閱驗證。

## 4. 收尾

- [x] 4.1 全 repo 掃過殘留的策略字面：後端常數本來就是 `"stock"`，其餘 `"sku"` 全是補貨事件的 JSON 欄位名（`StockReplenishedIntegrationEvent`、`BackorderWakeRequestedIntegrationEvent` 的 payload），與設定值無關，不動。行為上：確認只有前端那一處漏改，沒有第二個。
- [x] 4.2 後端 `test` 與 `sit`、前端 `vitest` 與 `tsc` 全綠，`spectra validate --strict` 通過。

  **archive 途中機器當機，重開後重跑過一輪**：後端 `test` 248 支全過（43 個 test class，0 failure／0 error）、前端 `tsc --noEmit` 乾淨、`vitest` 36 支全過（含本 change 新增的 `AppHeader.test.tsx` 4 支）、`spectra validate --strict` 通過。**未重跑 `sit`**——當機後補的 2.5 與 2.6 是純文件改動，中斷點也在 archive 而不在實作，`sit` 的輸入沒有變。
