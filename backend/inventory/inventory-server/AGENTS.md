# Inventory context development rules

本檔適用於 `backend/inventory/inventory-server`。Inventory 的 living architecture 位於
`docs/architecture/inventory.md`；新增或搬移類別前，先依該文件判斷 Movement、Allocation、
Reservation、Position、Location 的唯一 owner。

## Package grammar

- 模組的 Spring Bean 組裝設定放在 `<module>.infrastructure.configuration`；Application／Domain
  不得反向依賴 Infrastructure 的組裝設定。
- 執行期技術行為依職責留在 Infrastructure，例如 AOP 例外轉換放在 `infrastructure.aop`，不放 configuration；
  監控用的 health indicator／metrics 屬於技術元件，放在 `<module>.infrastructure.observability`（2026-11-25 決定：
  不視為 entrypoint，可直接讀取該模組的 Store 做唯讀檢查）。
- 跨模組的部署組裝保留在 deployment 的 `bootstrap.configuration`。

- 維持目前 module-first、layer-second 的 package 結構；未經明確架構變更，不要自行改成 feature-first。
- Application orchestration 依既有角色收納：
  - `application.invocation`：use case 輸入的 `*Command`／`*Query` record，以及只被它們欄位使用的組成 record
    （例如 `MovementLine`）（共用規則 BC-25）。
  - `application.usecase`：對外 Application use case（`*Usecase`）與組合多個 Usecase 的 `*Interactor`（BC-37）。
    會改變狀態的 operation 只收一個 `*Command`；`Get`／`List` 開頭的查詢可以直接收識別碼但只能讀；不收參數的
    批次只由 `entrypoint.schedule` 呼叫；Interactor 只讀不寫，冪等登記這類寫入放在 Usecase 同一交易（BC-38～BC-41、BC-45）。Entrypoint 一律經過這裡，不直接用 service 或 store（BC-44）。
  - `application.service`：被多個 use case 共用的 Application 邏輯，只能命名 `*Service` 或 `*Coordinator`，且不得
    呼叫 Usecase（BC-42、BC-43）。例如 `StockOperationReleaseService` 同時被釋放與取消兩個流程使用。
  - `application.policy`：transaction、retry、lock ordering 等 Application coordination policy。
  - `application.store`：Inventory 自訂的所有 `*Store` data-access 純介面（BC-24）。
  - `application.port`：非 data-access 的外部協作 interface，包含具名 Application event Publisher，以及只屬於
    該 port contract 的 target／decision。
  - `application.result`：use case、application service 或 transaction boundary 的完成結果及其專屬明細，
    包含查詢回傳的 immutable read model（例如 `StockLocationView`）。
  - `application.state`：transaction working set（例如 `StockOperationComposite`、`MoveQuantAllocationSet`）。
  - `application.event`：Application publisher 的事件語彙與 immutable payload；即使名稱含 `Snapshot`，仍以
    主要用途判斷。
  - `application.enums`：Application 層的 enum（error code、workflow status、lifecycle action 等）。
- 型別依主要責任分包，不依 Java 形狀分包：`record` 不必然是 value object，publisher payload 優先視為 event。
  唯一例外是 enum：一律放在同層的 `enums`（BC-17），這是專案慣例而非 DDD 分類。
- 不建立通用 `dto`、`type`、`input`、`output`、`model`、`error`、`exception` 雜物 package；例外類別使用
  foundation 共用的例外型別，錯誤代碼是 enum，放 `enums`。
- Application root 不得放任何型別（BC-36）；收貨冪等用的 `receiptId` 是 `ConfirmStockReceiptCommand` 的欄位。
- Domain package 只容納 authoritative business facts、state、invariants 與純 domain policy。Workflow status、
  lifecycle publication action、caller checkpoint 等 Application vocabulary 不得放入 Domain。
- Domain 型別不得直接放在 `domain` root，只能放在下列子套件（BC-16）。判斷順序由上而下，第一個符合的即是：
  - `domain.enums`：所有 Domain enum（Status、State、Type、Policy 選項等固定選項）。
  - `domain.aggregate`：具有 identity、獨立 lifecycle 與 transaction consistency boundary 的 aggregate root。
  - `domain.entity`：具有 identity，但尚未形成或不擁有獨立 aggregate boundary 的 Domain entity。
  - `domain.valueobject`：沒有獨立 identity/lifecycle、以內容決定相等性的值；必須是 record，或 final class＋
    全部欄位 final＋覆寫 `equals`／`hashCode`（BC-18）。
  - `domain.service`：不自然屬於單一 aggregate 的 stateless pure domain calculation。介面與實作同一資料夾、
    不開 `impl`；實作名稱＝做法描述＋介面名稱（例如 `ShipCompleteFefoStockAllocationPlanner`），欄位全部
    final；只有 `infrastructure.configuration` 可以引用實作（BC-19～BC-23）。
  - `domain.policy`：具名、可替換的純業務決策規則 class（固定選項的 enum 放 `domain.enums`）。
- `Demand`、`Supply`、`Proposal` 是類別的 Domain 名詞，不是技術 package；沒有獨立 identity/lifecycle 且
  以內容決定相等性的 pure planning input/output 放在 `domain.valueobject`。
- Domain 不建立 `dto`、`view`、`command`、`result`、`projection` 或 `event` package；這些是 Application、
  Entrypoint 或 integration boundary 的技術角色。
- 不得因型別會被 Store 保存、具有方法或使用 `record` 就自動歸類為 Aggregate／Entity／Value Object。
- Application 與 Domain 的 layer ownership 優先於 package role：跨 use case 穩定成立的業務身分與不變式
  歸 Domain；command/result/view/event、外部 port vocabulary、transaction working set 與 DB coordination
  policy 歸 Application。
- `Repository` suffix 只供 Infrastructure 的 Spring Data `Jpa*Repository` 使用。
- Application event 使用已發生事實的過去式名稱；位於 `application.event` 時不必重複加 `Event` suffix。
- Publisher port 必須接收一個完整 Application event。不得直接發布 use case `Result`，也不得把 event 拆成
  `Snapshot + Action` 多個參數；snapshot/detail 可以是 event 內部 immutable payload。
- 跨 bounded context 的 event 語意轉換放在 `infrastructure.messaging.*Translator`。Translator 必須是純轉換：
  不查資料庫、不修改 Domain、不執行發布，只接收完整 Application event 並產生版本化 Integration Event
  publication；`*IntegrationEventAdapter` 才負責交給 `IntegrationEventPublisher`。Adapter 不得以 Outbox、
  Kafka 或 JDBC 等可替換的下層機制命名。
- 只有一對一且沒有獨立轉換規則的 Integration Event mapping 可以留在 Integration Event Adapter；不要為對稱
  而建立空 Translator interface 或 router。
- 一個完整 Application Event 若同時衍生 audit 與跨 context notification，可由同一個 Integration Event Adapter
  建立多筆 publication 並在原 transaction 內同步發布；Usecase 不應為不同 audience 重複宣告同一 business
  fact，也不得用非同步 Application listener 隱藏這段 fan-out。
- Integration Event consumer 只負責把 versioned contract 與必要的 `shipmentId` 等 correlation identity 轉成
  normalized command。業務狀態改變與對應 outbound publication 必須由同一個 transactional Usecase 透過完整
  Application Event、Publisher port 與 Integration Event Adapter 完成；Consumer 不得發布 Usecase Result。
- Application Event 與 Integration Event 可以採用不同 bounded context 的語言，但外部語意必須能由內部事實
  完整推導；不得把 proposal、partial assignment 或 soft hold 翻譯成 committed。
- 同一 context 內以 Spring Application Event 串接模組時：發布端是 `infrastructure.event.*ApplicationEventAdapter`，
  實作 `application.port` 的 `*Publisher`，只有它可以使用 `ApplicationEventPublisher`（BC-48、BC-50）；接收端是
  `entrypoint.event.*EventListener`，只把事件轉成 Command 交給 Usecase，只有它可以掛 `@EventListener`
  （BC-47、BC-49）。Listener 使用同步 `@EventListener`，Usecase 在發布者的交易內執行。實例見 wms 的 `process` 模組。

## Persistence package rules

- Persistence adapter 統一放在 owning module 的 `infrastructure.persistence.<technology>.<role>`；不得再新增
  `infrastructure.repo`、`infrastructure.entity` 或 `infrastructure.mapper`。
- JDBC 目前使用 `infrastructure.persistence.jdbc.store`，收納 Application `*Store` 的 JDBC 實作，以及必須
  與實作共用 package-private 存取權限的 row-folding collaborator。
- JPA 使用下列技術角色：
  - `infrastructure.persistence.jpa.model`：`@Entity` 的 `*Entity`，以及多欄位主鍵 `*PrimaryKey`
    （`@Embeddable`，由 entity 以 `@EmbeddedId` 使用，主鍵欄位只定義一次；不使用 `@IdClass`）（BC-31）。
  - `infrastructure.persistence.jpa.repository`：Spring Data `Jpa*Repository` interface。
  - `infrastructure.persistence.jpa.mapper`：Domain 與 JPA entity 的轉換。
  - `infrastructure.persistence.jpa.store`：實作 Application `*Store` 並協調 repository/mapper 的 adapter。
- Store 實作（JPA 與 JDBC 皆同）命名為「介面名稱＋`Adapter`」，例如 `StockQuantStore` → `StockQuantStoreAdapter`；
  不加 `Jpa`／`Jdbc` 前綴（技術看資料夾），不用 `*Impl`（BC-20、BC-33）。只供它使用的輔助類別必須是
  package-private。
- 不為未出現的角色預建空 package。可重用 SQL、converter、codec 或 JDBC support 真正形成獨立責任時，才建立
  `query`、`converter`、`codec` 或 `support`。
- JDBC 不建立假的 `entity` package；SQL row 若只服務單一 Store，保留為 private/nested type 或同一
  `jdbc.store` 的 package-private collaborator。
- Domain 與 Infrastructure 不鏡像分包：`domain.entity` 表達 Domain identity/lifecycle，
  `persistence.jpa.model` 表達 ORM mapping，兩者語意不同；這也是 JPA 資料夾不叫 `entity` 的原因。

## Store rules

- 每個 `*Store` interface 必須放在其 owner 的 `application.store`；不得放在 `application.repo`、
  `application.repository` 或 Domain。
- Store 可以同時包含同一 persistence lifecycle 所需的 read、lock 與 write；以完整方法名稱表達效果。
- Store dependency field 必須使用完整類型語意，例如 `stockOperationStore`，不得縮成 `store` 或 `repository`。
- JDBC/JPA implementation 留在 owning module 的上述 technology-specific persistence package，並實作
  Application Store。

## Nested type rules

- 其他 top-level production class 若需要 import `Parent.Child`，原則上應將 `Child` 外提成獨立 top-level file。
- 跨 class method signature、Application port、Store result 或 controller request/response 的型別必須是可辨識
  owner 的 top-level type。
- Domain／Application 的 enum 一律是 top-level（BC-17），不得寫在其他類別內部。
- 以下情況可以保留 nested type：
  - private local key、row、queue 或單一演算法的 implementation detail；
  - sealed interface 的封閉 variants；
  - 只由父 response/snapshot 對外呈現、沒有被其他 production class 直接 import 的組成明細。
- JDBC row folding 已形成獨立責任時，抽成同一 `persistence.jdbc.store` package 的 package-private
  collaborator；
  collaborator 自己的 private row buffers 可以繼續 nested。

## Entrypoint package rules

- Entrypoint 依觸發來源分資料夾：`rest`、`messaging`、`temporal`、`schedule`、`event`（同一 context 內的 Application Event，`*EventListener`，BC-47），不得放在 entrypoint 根（BC-26）。
- `entrypoint.rest` 根只放 `@RestController` 的 `*Rest`；`rest.request` 放 `*Request` record；`rest.response`
  放 `*Response` record（BC-27）。
- `entrypoint.messaging` 只放 `*EventConsumer`，以及每個模組唯一一個 `<Module>EventSubscriptions`
  （例如 `AllocationEventSubscriptions`、`MovementEventSubscriptions`）（BC-28）。Subscriptions 的常數**值**
  已寫入 Inbox 去重紀錄，永遠不可修改。
- `entrypoint.schedule` 只放 `*Scheduler`（BC-29）；`entrypoint.temporal` 只放 `*Adapter`（BC-30）。

## Adapter ownership

- Temporal Activity adapter 依穩定業務能力歸入 owner 的 `entrypoint.temporal`；Allocation 與 Movement 使用
  獨立 contract／adapter，不建立 context-wide `TemporalInventoryActivitiesAdapter`。
- `AllocationOptimisticLockRetryObserver` 放在 `inventory.bootstrap.observability`，負責跨 Allocation／
  Movement 的重試監測。新增 adapter 仍應先尋找明確 owner，不建立通用 `inventory.adapter`。

## Architecture gate

- `InventorySharedBoundaryArchitectureTest` 套用 `:archone-framework:architecture-rules` 的
  `BoundedContextArchitectureRules`，驗證 layer 依賴方向、Integration Event 邊界、模組無循環，以及上述各
  package 的角色與命名（本檔標註的 `BC-xx`）。規則編號、理由與正確做法見
  `backend/archone-framework/architecture-rules/README.md`。
- 模組間循環（BC-14）目前以 freeze 延後處理：既有循環記在 `src/test/resources/archunit-frozen-violations`，
  **新增任何跨模組雙向依賴都會失敗**。不得編輯凍結檔或開啟 refreeze。
- 不得以排除類別或放寬 package predicate 讓新程式碼通過；架構決策改變時先更新本檔與共用規則。

## Verification

- 修改 Java 後執行 `cd backend && ./gradlew spotlessApply`。
- 至少執行 `cd backend && ./gradlew :inventory:inventory-server:test`。
- 變更 Inventory 對外 package 或 monolith caller 時，再執行 monolith compile/SIT 或專案 E2E。
- 提交前執行 `cd backend && ./gradlew spotlessCheck`。
