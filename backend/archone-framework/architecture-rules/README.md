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

### 各套件的角色與命名（BC-16～BC-35、BC-46～BC-50）

分類以 DDD 戰術分類為骨幹（aggregate／entity／valueobject／service／policy），再加上「enum 一律放
`enums`」這個專案慣例；後者不是 DDD 的要求。Domain 類別依下列順序判斷，第一個符合的就是它的位置：
enum → `enums`；有身分且為主體 → `aggregate`；隸屬主體的身分物件 → `entity`；無身分、以內容相等 →
`valueobject`；無狀態計算 → `service`；具名可替換的業務規則 class → `policy`；都不符合時先討論，不要另開套件。

| 編號 | 規則 | 為什麼 | 正確做法 |
| --- | --- | --- | --- |
| BC-16 | Domain 類別只能放在 `aggregate`、`entity`、`valueobject`、`enums`、`service`、`policy` | 固定的分類才能一眼看出角色；`type`、`error` 這類依形狀分的套件會變成雜物堆 | 依上面的判斷順序選一個 |
| BC-17 | Domain／application 的 enum 必須是獨立檔案，放在同層 `enums`；`enums` 只能放 enum | enum 是固定選項，與有內容的值不同；分層放才不會讓 domain 依賴 application | `domain.enums` 或 `application.enums`；寫在其他類別內部的 enum 要拆出來 |
| BC-18 | `domain.valueobject` 只能是 record，或 final class＋全部欄位 final＋覆寫 `equals`／`hashCode` | Value object 以內容決定相等、建立後不可改 | 優先用 record；需要隱藏內部表示時用 final class |
| BC-19 | `domain.service` 不得有子套件 | 介面與實作放一起，才看得出有哪些做法 | 不開 `impl`，實作直接放在 `domain.service` |
| BC-20 | 類別名稱不得以 `Impl` 結尾 | `Impl` 沒有說明實作的做法或技術 | 用做法命名（`PriorityCapacityWavePlanner`），store 實作用 `*StoreAdapter` |
| BC-21 | `domain.service` 的實作名稱＝做法描述＋介面名稱，且欄位全部 final | 名稱說明是哪一種做法；domain service 是無狀態計算 | 例如 `WavePlanner` → `PriorityCapacityWavePlanner` |
| BC-22 | `domain.service` 的實作只能由 `infrastructure.configuration` 引用 | 換做法時只改組裝一處；其他程式依賴介面 | 在 `infrastructure.configuration` 建立實作，其他地方注入介面 |
| BC-23 | `domain.service` 的每個介面都至少有一個實作 | 沒有實作的介面是空殼 | 補實作，或刪掉介面 |
| BC-24 | `application.store` 只能放以 `Store` 結尾的純介面 | 資料存取的契約與實作分開，application 不知道資料存在哪裡 | 實作放 `infrastructure.persistence.*.store` |
| BC-25 | `application.invocation` 只能放 `*Command`／`*Query` record，或被它們欄位使用的組成 record | 輸入型別集中且不可變；明細行（例如 `MovementLine`）屬於指令的一部分 | 不被任何 Command／Query 使用的型別，放到它真正的角色套件 |
| BC-26 | Entrypoint 類別只能放在 `rest`、`messaging`、`temporal`、`schedule`、`event` | 依觸發來源分資料夾，一眼看出程式從哪裡被呼叫 | 依觸發來源放；同一 context 內的 Application Event 入口放 `event`；不要放在 entrypoint 根或另開 `consumer` |
| BC-27 | `entrypoint.rest` 根只放 `@RestController` 的 `*Rest`；`rest.request` 只放 `*Request` record；`rest.response` 只放 `*Response` record | 控制器、請求格式、回應格式分開，HTTP 介面一目了然 | 依角色放進三個位置；HTTP 狀態碼的判斷寫在 Response 的方法裡 |
| BC-28 | `entrypoint.messaging` 只放 `*EventConsumer` 與每個模組唯一的 `*EventSubscriptions`（只能有常數） | 每個模組的 Inbox 訂閱名稱集中一處；這些**值**寫進去重紀錄，永遠不能改 | 新訂閱加進該模組既有的 `<Module>EventSubscriptions` |
| BC-29 | `entrypoint.schedule` 只放 `*Scheduler` | 資料夾表觸發來源、字尾表角色 | 排程入口命名為 `*Scheduler` |
| BC-30 | `entrypoint.temporal` 只放 `*Adapter` | 同上 | Temporal Activity 入口命名為 `*Adapter` |
| BC-47 | `entrypoint.event` 只放 `*EventListener` | 同一 context 內的 Application Event 也是觸發來源，和 HTTP、訊息一樣只負責轉成 Command 交給 Usecase | 用同步 `@EventListener`，讓 Usecase 在發布事件的交易內執行 |
| BC-31 | `@Entity`、`@Embeddable` 只能在 `jpa.model`；`jpa.model` 只放 `@Entity` 的 `*Entity` 與以 `@EmbeddedId` 使用的 `@Embeddable` `*PrimaryKey` | `entity` 會和 `domain.entity` 撞名；多欄位主鍵用 `@EmbeddedId`，主鍵欄位只寫一次 | 多欄位主鍵獨立成 `*PrimaryKey` 檔；不使用 `@IdClass` |
| BC-32 | `jpa.repository` 只放繼承 `JpaRepository` 的 `Jpa*Repository` 介面 | 名稱與種類一致：Jpa 開頭的一定是 Spring Data 介面 | 命名 `Jpa<Entity 名稱>Repository` |
| BC-33 | `jpa.store`／`jdbc.store` 的 public 類別＝所實作的 `application.store` 介面名＋`Adapter`；輔助類別 package-private | 看名字就知道它實作哪個 Store；技術看資料夾，不寫進名稱 | `StockQuantStore` → `StockQuantStoreAdapter`；不要 `Jpa`／`Jdbc` 前綴或 `Impl` |
| BC-34 | Persistence 類別只能放在 `jpa.model`、`jpa.repository`、`jpa.mapper`、`jpa.store`、`jdbc.store` | 固定位置，新類別不會各自開資料夾 | JDBC 的 row 類別放 `jdbc.store` 並設為 package-private |
| BC-35 | `jpa.mapper` 只放 `*Mapper` | 角色與命名一致 | 命名 `*Mapper` |
| BC-48 | `infrastructure.event` 只放實作 `application.port` `*Publisher` 的 `*ApplicationEventAdapter` | 發布端和 Integration Event 一樣經過 Publisher port；名稱說明它銜接的是 Application Event，不寫 Spring 等下層機制 | 例如 `WmsApplicationEventAdapter` 實作多個 Publisher port，以 Spring `ApplicationEventPublisher` 發布 |
| BC-49 | 只有 `entrypoint.event` 可以接收 Spring Application Event（`@EventListener`） | 事件反應和 HTTP、訊息一樣是觸發來源，必須從入口進來再交給 Usecase；不能在 service 或 Usecase 裡直接掛 Listener | 在 `entrypoint.event` 的 `*EventListener` 轉成 Command 交給 Usecase |
| BC-50 | 只有 `infrastructure.event` 可以使用 Spring `ApplicationEventPublisher` | 發布一律經過 `application.port` 的 `*Publisher`，業務程式不直接依賴 Spring 的發布機制 | Usecase 依賴 Publisher port，由 `*ApplicationEventAdapter` 實作 |
| BC-46 | `@RestController` 的 public method 只能回傳 `rest.response` 的 `*Response`、context 對外 `api` 套件的型別，或沒有內容；可包在 `ResponseEntity`、`List`、`Set`、`Optional` 裡 | 對外格式與內部結構分開：內部改欄位不會悄悄改到 API | 新增 `*Response` 並用 `from(...)` 轉換；實作 `*-api` 介面的 RPC 入口直接回傳 `api` 的型別 |

### Application 的角色與呼叫方向（BC-36～BC-45）

一個請求從外面進來的路徑固定是：**entrypoint → Usecase／Interactor → Service／Coordinator → store／port**。

- **Usecase**：一件完整的業務動作，自己開交易。會改變狀態的只收一個 `*Command`。
- **Interactor**：把 Usecase 串起來的流程，自己只讀不寫。例如「排程撈出一批，逐筆交給 Usecase，某筆失敗不影響其他筆」。
- **Service**：被多個 Usecase 共用的一段 application 邏輯。它沒有自己的入口，只被 Usecase 或 Coordinator 呼叫。
- **Coordinator**：把幾個 Service 串起來。

查詢 Usecase 以 `Get`／`List` 開頭，可以直接收識別碼，但只能讀。

| 編號 | 規則 | 為什麼 | 正確做法 |
| --- | --- | --- | --- |
| BC-36 | Application 類別只能放在 `enums`、`event`、`invocation`、`policy`、`port`、`result`、`service`、`state`、`store`、`usecase` | 固定位置才看得出角色；直接放在 `application` 根的類別沒有角色 | 依角色選一個；例如 `StockReceiptRequest` 這種「識別碼＋Command」的包裝，應把識別碼併進 Command |
| BC-37 | `application.usecase` 只放 `*Usecase`、`*Interactor`；這兩種類別也只能放在這裡 | 找業務動作只要看一個資料夾 | 不要用 `*Facade` 等其他名稱包裝 Usecase |
| BC-38 | `*Usecase`／`*Interactor` 只有一個 public operation。會改變狀態的只收一個 `application.invocation` 的 `*Command`，或在排程批次時不收參數；`Get`／`List` 開頭的查詢可以直接收識別碼 | 一個類別一件事；Command 是輸入的唯一契約，加欄位不必改簽名 | 兩個入口就拆成兩個 Usecase；共用的核心邏輯抽成 `application.service` 的 `*Service` |
| BC-39 | `Get`／`List` 查詢 Usecase 只能讀：不得依賴 `*Publisher` 或 Application Event；呼叫 store 時只能用 `find`／`get`／`list`／`count`／`exists`／`load`／`search` 開頭的 method | 名稱是查詢，就保證沒有副作用，可以放心重試或快取 | 需要寫入就不要用 `Get`／`List` 命名，改收 Command |
| BC-40 | 不收參數的 Usecase／Interactor operation 只能由 `entrypoint.schedule` 呼叫 | 不收參數代表「自己去撈該處理的資料」，這只該由排程觸發 | 其他入口要觸發時，定義 Command 傳入要處理的對象 |
| BC-41 | `*Interactor` 必須組合至少一個 Usecase 或 Interactor；`*Usecase` 不得依賴 Interactor | Interactor 是 Usecase 之上的流程，方向只能往下 | 沒有組合 Usecase 的 Interactor 改名 `*Usecase` |
| BC-42 | `application.service` 只放 `*Service`、`*Coordinator`；這兩種類別也只能放在這裡 | 名稱即角色；`*Registrar`、`*Completer`、`*Transactions` 這類名稱說不出它在哪一層 | 改名 `*Service`（例如 `InboundReceiptRegistrar` → `InboundReceiptRegistrationService`） |
| BC-43 | `*Coordinator` 必須組合至少一個 Service 或 Coordinator；`*Service` 不得依賴 Coordinator；`application.service` 不得依賴 `usecase` | Service 在 Usecase 之下；Service 呼叫 Usecase 會讓交易與事件發布的責任混在一起 | 需要 Usecase 裡的一段邏輯時，把那段抽成 Service，讓 Usecase 與原呼叫端都呼叫它 |
| BC-44 | Entrypoint 只能透過 Usecase／Interactor 進入 application；不得直接使用 `application.service` 或 `application.store` | 入口不繞過 Usecase，交易邊界與檢查才只有一處 | 查詢也建一個 `Get*`／`List*` Usecase |
| BC-45 | `*Interactor` 只能讀：不得依賴 `*Publisher` 或 Application Event；呼叫 store 時只能用讀取動詞開頭的 method（同 BC-39） | Interactor 只負責撈清單、逐筆轉交、隔離失敗；寫入若分散在 Interactor 與 Usecase 兩處，直接呼叫 Usecase 的人就會漏掉那段寫入 | 寫入（包含冪等登記）移進 Usecase；例如收貨編號的登記在 `ConfirmStockReceiptUsecase` 同一交易內完成 |

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

新增延後項目前必須先在上表登記原因與移除時機；凍結檔不得手動編輯，`archunit.properties` 也不得開啟
`freeze.refreeze`。

凍結檔以違規的文字（含類別名稱）比對。搬移或改名凍結範圍內的類別時，既有違規會被當成新違規而失敗。這時：

1. 確認違規的「內容」沒有變：模組間的循環組合與跨模組依賴（類別對類別）扣掉改名後完全相同。
2. 刪除該模組的 `archunit-frozen-violations`，暫時把 `allowStoreCreation` 設為 `true` 跑一次該模組測試產生新檔，
   再改回 `false`。
3. 在 commit 說明裡寫明重新產生的原因與比對結果。

## 修改規則

- 不得為了讓程式碼通過而加入 `allowEmptyShould(true)`、排除特定類別或放寬套件條件。
- 新增或修改規則時，同時：給新編號（不重用舊編號）、補一個違規 fixture 測試、更新上表。
- Context 專屬的命名與角色規則放在該 context 自己的架構測試，不放這裡。
