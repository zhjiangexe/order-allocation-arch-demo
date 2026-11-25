# architecture-rules

所有 bounded context 共用的 ArchUnit 規則。測試失敗時，錯誤訊息開頭的編號（例如 `[BC-08] inventory｜…`）對應下表；
先看「為什麼」，再照「正確做法」修，**不要放寬規則**。

## 規則如何被執行

| 測試 | 位置 | 作用 |
| --- | --- | --- |
| `BoundedContextArchitectureRulesTest` | 本模組 | 每條規則都有一個「故意違規」的 fixture，證明規則真的會擋；另有合規 fixture 證明不會誤擋 |
| `*SharedBoundaryArchitectureTest` | 每個 context 自己的模組 | 改該 context 時最快的回饋；延後處理的規則在這裡以 freeze 檢查 |
| `BoundedContextArchitectureTest` | `deployments/monolith` | 自動找出所有 context（有 `application` 套件的頂層套件），新 context 即使忘了加自己的測試也會被檢查 |

CI 對每個 push 與 PR 執行 `./gradlew build`，以上三組都包含在內。

## 規則對照表

套件寫法：`<base>` 是 context 根套件（例如 `com.flowzati.archone.inventory`）；`..` 表示中間可以有模組名稱，
所以 `inventory.movement.application` 與 `ordering.application` 都適用同一套規則。

| 編號 | 規則 | 為什麼 | 正確做法 |
| --- | --- | --- | --- |
| BC-01 | `domain` 不得依賴 `application`、`entrypoint`、`infrastructure` | 業務規則要能脫離框架與流程單獨測試與理解 | 需要外部資料時，由 application 先取得再傳給 domain |
| BC-02 | `application` 不得依賴 `entrypoint`、`infrastructure` | 業務流程不應知道是誰呼叫它、資料存在哪裡 | 在 `application.store`／`application.port` 定義介面，由 infrastructure 實作 |
| BC-03 | `entrypoint` 不得依賴 `infrastructure` | 入口只負責轉換輸入並呼叫 Usecase，不直接碰資料庫或外部系統 | 改呼叫 Usecase |
| BC-04 | `infrastructure` 不得依賴 `entrypoint` | 技術實作不應反過來依賴入口的請求格式 | 共用的資料放到 application（Command／Result） |
| BC-05 | `domain`、`application` 不得依賴版本化的 Integration Event contract、`IntegrationEventPublication`、`IntegrationEventPublisher` | 對外訊息格式會改版；業務程式只認自己的 Application Event | 發布 Application Event，交給 `application.port.*Publisher` |
| BC-06 | 只有 `infrastructure.messaging` 的 `*IntegrationEventAdapter` 可使用 `IntegrationEventPublisher` | 對外發布只有一個出口，才能保證格式轉換與交易一致 | 新增或擴充對應的 `*IntegrationEventAdapter` |
| BC-07 | 不得直接使用 `MessageProducer` | 直接送訊息會繞過 outbox，交易回滾時訊息照樣送出 | 透過 `*IntegrationEventAdapter` → `IntegrationEventPublisher` |
| BC-08 | `*IntegrationEventAdapter` 必須在 `infrastructure.messaging`、實作 `application.port.*Publisher`、並呼叫 `IntegrationEventPublisher` | 讓「業務事件 → 對外訊息」的轉換點固定且可找 | 依此三點補齊；不發布的類別不要用這個字尾 |
| BC-09 | `*IntegrationEventAdapter` 不得以 Outbox、Kafka、JDBC 命名 | 名稱描述業務邊界，傳輸方式可替換 | 以事件邊界命名，例如 `StockOperationIntegrationEventAdapter` |
| BC-10 | `infrastructure.messaging` 的 `*Translator` 不得發布、不得讀寫資料庫 | Translator 是純轉換，才能單獨測試、不帶副作用 | 需要查資料時，由 Usecase 先查好放進 Application Event |
| BC-11 | `entrypoint`（含 Consumer）不得發布對外事件或使用 `*Publisher` port | 收到訊息後的狀態改變與後續發布必須在同一交易內，由 Usecase 負責 | Consumer 只轉成 Command 呼叫 Usecase |
| BC-12 | `application.port.*Publisher` 的每個方法只能收一個參數 | 一次發布一個完整的 Application Event，避免事件被拆成零散欄位 | 把欄位收進一個 Application Event record |
| BC-13 | 同一模組內的 domain 子套件（`aggregate`、`entity`、`valueobject`…）不得循環依賴 | 循環代表分類錯誤，例如 value object 反過來知道 aggregate | 依 DDD 分類由外往內依賴：aggregate → entity → valueobject |
| BC-14 | 同一 context 內的模組之間不得循環依賴 | 循環讓模組無法單獨理解與修改 | 找出被雙方共用的型別，移到真正擁有它的模組，並保持單向依賴 |
| BC-15 | 每個類別都必須在 `domain`、`application`、`entrypoint`、`infrastructure`、`bootstrap` 之下，或在 context 最上層的 `api` | 不屬於任何一層的類別不受上述規則保護 | 依職責放進對應的 layer；`api` 只用於對外公開的 `*-api` 模組 |

`domain`、`entrypoint`、`infrastructure` 不存在的 context（例如 fulfillment 沒有 domain）可以通過；但 `application`
不存在時 BC-02 一定失敗，避免根套件寫錯讓所有規則空轉通過。

## 延後處理（freeze）

延後處理的規則列在 `BoundedContextArchitectureRules.DEFERRED_RULES`。該 context 自己的
`*SharedBoundaryArchitectureTest` 以 ArchUnit freeze 檢查：既有違規記在該模組的
`src/test/resources/archunit-frozen-violations`，**新增違規一律失敗**，修掉的違規會自動從凍結檔移除。
monolith 的總測試對這些項目標示為 skipped，凍結檔只存一份。

| Context | 規則 | 原因 | 何時移除 |
| --- | --- | --- | --- |
| inventory | BC-14 | allocation、balance、movement 之間既有三組雙向依賴；重整方案已定案，2026-11-25 決定延後執行 | 執行 inventory 模組重整後 |
| fulfillment | BC-15 | `fulfillment.configuration` 不屬於任何 layer | 套件統一階段 4 拆解 `configuration` 後 |

新增延後項目前必須先在上表登記原因與移除時機；凍結檔不得手動編輯，`archunit.properties` 也不得開啟
`freeze.refreeze`。

## 修改規則

- 不得為了讓程式碼通過而加入 `allowEmptyShould(true)`、排除特定類別或放寬套件條件。
- 新增或修改規則時，同時：給新編號（不重用舊編號）、補一個違規 fixture 測試、更新上表。
- Context 專屬的命名與角色規則放在該 context 自己的架構測試，不放這裡。
