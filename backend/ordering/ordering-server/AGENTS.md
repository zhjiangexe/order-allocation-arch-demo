# Ordering context architecture rules

本檔適用於 `backend/ordering/ordering-server`。修改或新增 production code 前，先依此判斷類別所屬 layer、package
與依賴方向。

架構規則由
`src/test/java/com/flowzati/archone/ordering/architecture/OrderingContextArchitectureTest.java` 強制驗證；本檔用來
說明規則意圖與正確建模方式。若文件與測試不一致，不得直接刪除、忽略或放寬測試，應先確認架構決策並同步更新
兩者。

## Package root

- Project package：`com.flowzati.archone`
- Context name：`ordering`
- Context base package：`com.flowzati.archone.ordering`
- 維持 module-first、layer-second 結構：`ordering.<layer>.<role>`。

## Layer dependency direction

允許的主要依賴方向為：

`entrypoint → application → domain`

`infrastructure → application/domain`

- Domain 不得依賴 Application、Entrypoint 或 Infrastructure。
- Application 不得依賴 Entrypoint 或 Infrastructure。
- Entrypoint 與 Infrastructure 不得互相依賴。
- Domain 與 Application 不得依賴 versioned Integration Event contracts。

## Application layer

### `application.invocation`

- 只能表達 use case input。
- Top-level type 名稱只能以 `Command` 或 `Query` 結尾。
- 所有 invocation type 必須是 `record`。
- 不得依賴 Entrypoint、Infrastructure、Spring MVC、Servlet、JPA 或 versioned Integration Event contracts。
- HTTP `Request`／`Response` 不得放入此 package。

### `application.usecase`

- Top-level type 只能是 `*Usecase` 或 `*Interactor`。
- Ordering Application 中所有 `*Usecase`／`*Interactor` 都必須位於此 package。
- 每個 `*Usecase`／`*Interactor` 只能宣告一個非 static public operation；method name 使用具體業務動詞，
  不強制統一為 `execute`。
- 該 operation 必須剛好接收一個 `application.invocation` 中的 `*Command` 或 `*Query`；不得使用多個 scalar、
  Domain object 或 transport DTO 作為 public application input。
- Constructor 與 private／package-private helper 不屬於 public operation，不受上述參數形狀限制。
- `*Interactor` 必須依賴同 package 中至少一個 `*Usecase` 或另一個 `*Interactor`。
- `*Usecase` 不得反向依賴 `*Interactor`。
- Usecase／Interactor 可以依賴 `application.service` 中的 Service／Coordinator。
- 不得依賴 transport DTO、Entrypoint、Infrastructure 或 versioned Integration Event contracts。

### `application.service`

- Top-level type 只能是 `*Service` 或 `*Coordinator`。
- Ordering Application 中所有 `*Service`／`*Coordinator` 都必須位於此 package。
- `*Coordinator` 必須依賴同 package 中至少一個 `*Service` 或另一個 `*Coordinator`。
- `*Service` 不得反向依賴 `*Coordinator`。
- Service／Coordinator 不得反向依賴 `application.usecase`。
- Application service 表達可重用的 application capability；跨 use case 的流程編排才使用 Coordinator。

### Ports and events

- Persistence boundary interface 放在 `application.store`，由 Infrastructure 的 `*StoreAdapter` 實作。
- 非 persistence 的 outbound boundary interface 放在 `application.port`。
- Business state transition 產生 outbound event 時，使用：
  `Application Event → application.port.*Publisher → infrastructure.messaging.*IntegrationEventAdapter`。
- Application／Domain business code不得直接依賴 `IntegrationEventPublisher` 或 versioned Integration Event
  contracts。

## Domain layer

- Domain 不得依賴 Spring、JPA、shared messaging infrastructure 或 Integration Event contracts。
- `domain.valueobject` 中的 type 必須是 `record`。
- `domain.service` 中的 top-level type 必須以 `Service` 結尾。
- Aggregate 不得依賴 Domain Service。
- Entity 不得依賴 Aggregate 或 Domain Service。
- Value Object 不得依賴 Aggregate、Entity 或 Domain Service。
- `domain.aggregate`、`domain.entity`、`domain.valueobject`、`domain.service` 等 Domain subpackage 不得形成循環
  依賴。

## Entrypoint layer

Entrypoint 只負責 transport/protocol boundary、輸入轉換與呼叫 Application，不承擔 persistence 或 outbound
publication。

### `entrypoint.rest`

- Top-level type 只能是 `*Rest`、`*Request` 或 `*Response`。
- `*Request`／`*Response` 只屬於 HTTP boundary，而且必須是 `record`。
- `@RestController` 必須以 `Rest` 結尾；`*Rest` 必須標註 `@RestController`。
- REST adapter 必須將 Request 轉成 Application `Command`／`Query`。
- 不得直接依賴 persistence implementation、Application Store 或 outbound Publisher port。

### `entrypoint.messaging`

- Top-level type 必須以 `EventConsumer` 結尾。
- Consumer 只把 inbound versioned contract 與必要 correlation identity 轉成 normalized `Command`／`Query`，
  再呼叫 Usecase／Interactor。
- 不得直接依賴 persistence implementation、Application Store、outbound Publisher port 或 messaging
  infrastructure。
- 不得自行組合或發布 outbound Integration Event。

### `entrypoint.temporal`

- Top-level type 必須以 `Adapter` 結尾。
- Adapter 必須轉成 Application `Command`／`Query`，再呼叫 Usecase／Interactor。
- 不得繞過 Application boundary 依賴 Infrastructure、Application Store 或 outbound Publisher port。

REST、Messaging 與 Temporal entrypoint package 不得互相依賴。

## Infrastructure layer

### `infrastructure.messaging`

- Top-level type 只能是 `*IntegrationEventAdapter`、`*Translator` 或 `*Resolver`。
- `*IntegrationEventAdapter` 必須實作 `application.port` 中的 `*Publisher`，並負責呼叫
  `IntegrationEventPublisher`。
- Translator／Resolver 等 helper 不得直接呼叫 `IntegrationEventPublisher`。
- Ordering outbound versioned Integration Event contracts 只能由此 package 使用。
- 複雜語意轉換可放在純 `*Translator`；Translator 不查資料庫、不修改 Domain、不直接發布。
- Adapter 名稱描述直接銜接的 Integration Event boundary，不使用 Outbox、Kafka、JDBC 等下層傳輸機制命名。

### `infrastructure.persistence.jpa`

- `model`：top-level type 必須以 `Entity` 結尾並標註 `@Entity`。
- `repository`：top-level type 必須是以 `Repository` 結尾的 interface。
- `mapper`：top-level type 必須以 `Mapper` 結尾。
- `store`：top-level type 必須以 `StoreAdapter` 結尾並實作 `application.store` interface。
- Model 不得依賴 Repository、Mapper 或 Store。
- Repository 不得依賴 Mapper 或 Store。
- Mapper 不得依賴 Repository 或 Store。
- Persistence infrastructure 不得依賴 messaging infrastructure、shared messaging API 或 Integration Event
  contracts。

Messaging infrastructure 與 Persistence infrastructure 不得互相依賴。

## Changing architecture rules

- 不得只為了讓新程式碼通過測試而加入 `allowEmptyShould(true)`、排除特定類別或放寬 package predicate。
- 不得刪除失敗規則、停用 Architecture Test，或以 synthetic／generated class 為由排除真正的 production type。
- 編譯器產生的 synthetic class 可從「角色必須依賴某類型」的規則中排除，但規則仍須覆蓋所有 top-level
  production type。
- 若架構決策確實改變，先說明舊規則不再成立的原因，再同步修改本檔與
  `OrderingContextArchitectureTest`。
- 新增 layer role 或 package 時，應同時補上命名、package ownership 與依賴方向的 ArchUnit gate。

## Verification

- Ordering scoped verification 統一執行 repository root 的 `./scripts/agent/verify-ordering.sh`；agent、hook 與
  CI 不得各自複製另一套指令。
- 修改 Java 後執行 `cd backend && ./gradlew spotlessApply`。
- 至少執行 `cd backend && ./gradlew :ordering:ordering-server:test`。
- 提交前執行 `cd backend && ./gradlew spotlessCheck`。
