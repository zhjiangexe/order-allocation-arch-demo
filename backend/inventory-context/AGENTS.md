# Inventory context development rules

本檔適用於 `backend/inventory-context`。Inventory 的 living architecture 位於
`docs/inventory-domain-ownership.md`；新增或搬移類別前，先依該文件判斷 Movement、Allocation、
Reservation、Position、Location 的唯一 owner。

## Package grammar

- 維持目前 module-first、layer-second 的 package 結構；未經明確架構變更，不要自行改成 feature-first。
- Application orchestration 依既有角色收納：
  - `application.command`：改變狀態的 command 與其專屬輸入明細。
  - `application.usecase`：對外 Application use case。
  - `application.service`：由 use case 使用的 Application component 或 transaction helper。
  - `application.policy`：transaction、retry、lock ordering 等 Application coordination policy。
  - `application.store`：Inventory 自訂的所有 `*Store` data-access interface。
  - `application.port`：非 data-access 的外部協作 interface，包含具名 Application event Publisher，以及只屬於
    該 port contract 的 target／decision。
  - `application.projection`：只供內部 selection／planning 使用的 immutable persistence projection。
  - `application.view`：visibility query、operator query 或 diagnostic query 回傳的 immutable read model。
  - `application.result`：use case、application service 或 transaction boundary 的完成結果及其專屬明細。
  - `application.event`：Application publisher 的事件語彙與 immutable payload；即使名稱含 `Snapshot`，仍以
    主要用途判斷。
  - `application.valueobject`：具 value equality 與不變式、沒有獨立 identity/lifecycle 的 workflow-scoped
    value。
  - `application.exception`：由 Application workflow 判斷並拋出的例外。
- 型別依主要責任分包，不依 Java 形狀分包：`record` 不必然是 value object，`enum` 不建立 `type` package，
  publisher payload 優先視為 event，query projection 優先視為 view／projection。
- 無法單一判定角色的 application working model 暫留 owning module 的 `application` root，並記錄原因；
  不建立通用 `dto`、`type`、`input`、`output` 或 `model` 雜物 package。
- 目前允許留在 Application root 的只有 `StockOperationComposite`、`StockReceiptRequest`、
  `MoveQuantAllocationSet`；新增 root type 前必須先證明它無法歸入既有角色 package，並同步更新 living
  architecture。
- Domain package 只容納 authoritative business facts、state、invariants 與純 domain policy。Workflow status、
  lifecycle publication action、caller checkpoint 等 Application vocabulary 不得放入 Domain。
- Domain 型別不得直接放在 `domain` root：
  - `domain.aggregate`：具有 identity、獨立 lifecycle 與 transaction consistency boundary 的 aggregate root。
  - `domain.entity`：具有 identity，但尚未形成或不擁有獨立 aggregate boundary 的 Domain entity。
  - `domain.valueobject`：沒有獨立 identity/lifecycle、以值表達穩定 Domain 概念；Domain enum 也歸此類。
  - `domain.policy`：具名且可重用的純業務決策規則或 policy vocabulary。
  - `domain.service`：不自然屬於單一 aggregate 的 stateless pure domain calculation contract/implementation。
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

## Persistence package rules

- Persistence adapter 統一放在 owning module 的 `infrastructure.persistence.<technology>.<role>`；不得再新增
  `infrastructure.repo`、`infrastructure.entity` 或 `infrastructure.mapper`。
- JDBC 目前使用 `infrastructure.persistence.jdbc.store`，收納 Application `*Store` 的 JDBC 實作，以及必須
  與實作共用 package-private 存取權限的 row-folding collaborator。
- JPA 使用下列技術角色：
  - `infrastructure.persistence.jpa.entity`：`@Entity`、composite key 等 JPA persistence model。
  - `infrastructure.persistence.jpa.repository`：Spring Data `Jpa*Repository` interface。
  - `infrastructure.persistence.jpa.mapper`：Domain 與 JPA entity 的轉換。
  - `infrastructure.persistence.jpa.store`：實作 Application `*Store` 並協調 repository/mapper 的 adapter。
- 不為未出現的角色預建空 package。可重用 SQL、converter、codec 或 JDBC support 真正形成獨立責任時，才建立
  `query`、`converter`、`codec` 或 `support`。
- JDBC 不建立假的 `entity` package；SQL row 若只服務單一 Store，保留為 private/nested type 或同一
  `jdbc.store` 的 package-private collaborator。
- Domain 與 Infrastructure 不鏡像分包：`domain.entity` 表達 Domain identity/lifecycle，
  `persistence.jpa.entity` 表達 ORM mapping，兩者語意不同。

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
- 以下情況可以保留 nested type：
  - private local key、row、queue 或單一演算法的 implementation detail；
  - sealed interface 的封閉 variants；
  - 只由父 response/snapshot 對外呈現、沒有被其他 production class 直接 import 的組成明細。
- JDBC row folding 已形成獨立責任時，抽成同一 `persistence.jdbc.store` package 的 package-private
  collaborator；
  collaborator 自己的 private row buffers 可以繼續 nested。

## Adapter ownership

- Temporal Activity adapter 依穩定業務能力歸入 owner 的 `entrypoint.temporal`；Allocation 與 Movement 使用
  獨立 contract／adapter，不建立 context-wide `TemporalInventoryActivitiesAdapter`。
- `inventory.adapter` 暫時只保留 `AllocationOptimisticLockRetryObserver`。不得把新的 Domain 或 Application
  business type 放入 `inventory.adapter`；新增 adapter 時仍應先尋找明確 owner，除非另有架構決策。

## Verification

- 修改 Java 後執行 `cd backend && ./gradlew spotlessApply`。
- 至少執行 `cd backend && ./gradlew :inventory-context:test`。
- 變更 Inventory 對外 package 或 monolith caller 時，再執行 monolith compile/SIT 或專案 E2E。
- 提交前執行 `cd backend && ./gradlew spotlessCheck`。
