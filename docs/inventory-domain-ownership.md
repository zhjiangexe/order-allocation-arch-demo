# Inventory domain ownership

Inventory 是一個 bounded context，不是五個 bounded contexts。內部用五個 domain module 分清楚
「為什麼要移動」、「如何規劃」、「承諾了什麼」、「現在有多少」與「在哪裡」。

## 五個 domain module

| Module | 擁有的事實 | 代表類型 | 不負責 |
| --- | --- | --- | --- |
| Movement | 搬運意圖、方向、Operation/Move 狀態、完成與取消 | `StockOperation`, `StockMove`, `StockOperationType` | 選批、預留明細、現有量 |
| Allocation | demand/supply 快照與非權威規劃結果 | `StockOperationDemand`, `StockAllocationSupply`, `StockAllocationProposal` | DB lock、扣量、寫 Move Line |
| Reservation | 已承諾的 Move-to-Quant 關係及其建立/釋放 | `StockMoveLine`, `StockMoveLineStore` | 決定 FEFO 規則、擁有 Stock Move |
| Position | 目前 materialized stock position 與收貨 | `StockQuant`, receipt/visibility | 訂單流程、搬運生命週期 |
| Location | 庫位身分與 usage | `StockLocation`, `LocationUsageType` | WMS 執行策略 |

## Source、Target 與過程

```text
貨主出貨訂單（source）
        │ Reservation Intake：轉成 source-neutral movement command
        ▼
StockOperation + StockMove（Movement：要從哪裡搬到哪裡）
        │
        ├─ Allocation Planning：Demand + Supply → Proposal（純計算、可丟棄）
        │
        ▼
StockMoveLine（Reservation：某 Move 已承諾哪些 StockQuant）
        │
        ├─ release：刪除明細並還原 reserved quantity
        └─ completion：消耗 Position，完成 Move/Operation
        ▼
目的庫位／出貨交接（target）
```

訂單只是發起 source；Movement 是跨流程的 canonical intent；Allocation Proposal 不是資料庫事實；
Reservation 才是承諾；Position 是目前數量。這五句是判斷新類別 ownership 的首要規則。

## Package layout

```text
inventory/
  allocation/
    application/{projection,store,valueobject}
    domain/{service,valueobject}
    entrypoint/temporal
    infrastructure/persistence/jdbc/store
  location/
    application/{store,usecase,view}
    domain/{entity,valueobject}
    entrypoint
    infrastructure/persistence/jpa/{entity,mapper,repository,store}
  movement/
    application/{command,event,exception,port,result,service,store,usecase,view}
    domain/{aggregate,entity,policy,valueobject}
    entrypoint/{consumer,rest,temporal}
    infrastructure/persistence/{jdbc/store,jpa/{entity,mapper,repository,store}}
  position/
    application/{command,event,exception,policy,port,service,store,usecase,view}
    domain/{aggregate,valueobject}
    entrypoint
    infrastructure/persistence/{jdbc/store,jpa/{entity,mapper,repository,store}}
  reservation/
    application/{command,event,exception,port,result,service,store,usecase}
    domain/{entity}
    entrypoint
    infrastructure/persistence/jpa/{entity,mapper,repository,store}
  adapter  # 暫時只保留 retry observer
```

目前採 module-first、layer-second，再依既有 Application 技術角色分包。這項決策優先追求穩定且可由
自動化工具遵守的 package grammar；除非另開架構變更，不把現有 `service`、`usecase`、`command`
改成 feature-first package。

Temporal Activity contract 依穩定業務能力切分。Allocation 的配貨請求與 Movement 的 outbound completion
分別由自己的 `entrypoint.temporal` adapter 轉成 application invocation；不建立橫跨兩個 module 的
context-wide Activity adapter。兩者目前仍可由同一 Worker 與 Task Queue 執行，contract 邊界不等同部署邊界。

Application 型別依主要責任分包：`command` 是狀態變更輸入，`projection` 是內部 selection/planning
讀取模型，`view` 是 visibility/diagnostic read model，`result` 是 use case 或 transaction 結果，`event`
是 publisher vocabulary/payload，`valueobject` 是沒有獨立 identity/lifecycle 的 workflow value，
`exception` 是 Application workflow 例外。跨外部系統的 target/decision 與所屬 `port` 共置。

Application event 使用已發生事實的過去式名稱，Publisher port 一次接收一個完整 event：

| Application event | 表達的事實 | Integration boundary |
| --- | --- | --- |
| `StockAvailabilityIncreased` | 實體庫存可用量已增加 | `StockAvailabilityIncreasedIntegrationEvent` |
| `StockOperationLifecycleChanged` | Operation 已釋放或取消；內含 action 與 before-image snapshot | `StockOperationLifecycleIntegrationEvent` |
| `StockOperationAssigned` | Move 已承諾到具體 Quant | ORDER source 轉成 `OrderAllocationCommittedIntegrationEvent` |
| `StockOperationCompleted` | Outbound Operation 與實體扣帳已完成，並保留 order／shipment correlation | `StockOperationLifecycleIntegrationEvent` + `OutboundMovementsCompletedIntegrationEvent` |

`StockOperationAssignmentResult` 仍只代表 transaction/use case 回傳結果；新 commit 另外建立
`StockOperationAssigned` 才能發布。`StockOperationLifecycleSnapshot` 與 `StockOperationLifecycleAction` 是完整
lifecycle event 的組成內容，不再分開傳給 Publisher。

Publisher 與防腐層維持下列方向：

```text
Application Event
  → event-specific Publisher Port
  → Infrastructure Integration Event Adapter
  → pure Integration Event Translator（僅在 mapping 複雜時）
  → IntegrationEventPublisher
```

`StockOperationLifecycleTranslator` 與 `OrderAllocationCommittedTranslator` 負責跨 bounded context 的純語意
轉換，不查 DB、不修改 Domain，也不直接發布。Availability 目前是一對一 mapping，因此留在
`StockAvailabilityIncreasedIntegrationEventAdapter`，不為形式增加 Translator。Adapter 名稱描述目前直接銜接的
Integration Event boundary，不以 Outbox、Kafka 或 JDBC 等可替換的下層機制命名。

Application Event 與 Integration Event 不要求一對一。當同一個已提交的 business fact 面向不同 audience 時，
由一個 Adapter 在原 transaction 內同步建立全部 publication；不要讓 Usecase 為 audit 與跨 context notification
各發布一次語意重複的 Application Event，也不要用非同步 Application listener 隱藏 fan-out。

`ShipmentHandoverEventConsumer` 只把 V1 contract 與 shipment correlation 映射為
`CompleteOutboundMovementsCommand`；Temporal Activity 也轉成同一個 command。`CompleteOutboundMovementsUsecase`
在單一 transaction 內驗證完整 movement proof、完成 Stock Operation，並只發布一個完整的
`StockOperationCompleted` Application Event。`StockOperationCompletedIntegrationEventAdapter` 從同一事實原子產生
版本化 `StockOperationLifecycleIntegrationEvent` audit 與 `OutboundMovementsCompletedIntegrationEvent` fulfillment
notification。因此沒有 Consumer-side Result-to-response orchestration，也不需要特殊的 Responder abstraction。

Application Event 與 Integration Event 可以使用不同 bounded context 的語言，但外部語意必須能由內部事實
完整推導，不得把 proposal、partial assignment 或 soft hold 升級成 committed。現行 ORDER 規則為：完整且
持久的 `StockOperationAssigned` 才能轉成 `OrderAllocationCommittedIntegrationEvent`。

不要以 Java 形狀猜角色：`record` 不自動等於 value object，名稱含 `Snapshot` 但只作為 publisher payload
時仍歸 `event`，diagnostic `Report` 若代表只讀查詢結果則歸 `view`。無法單一判定角色的 working model
暫留 owner 的 `application` root，不建立 `dto`、`type`、`input`、`output` 或通用 `model` package。
Domain 只保留 authoritative business state 與 invariants；aggregate 必須真的具有一致性邊界與
identity/lifecycle，不能只因為會被 Store 載入或具有行為就放入 `domain.aggregate`。

### Domain tactical package

| Package | 判準 | 目前代表型別 |
| --- | --- | --- |
| `domain.aggregate` | 有 identity、獨立 lifecycle、version/transaction consistency boundary | `StockOperation`, `StockMove`, `StockOperationCancellation`, `StockQuant` |
| `domain.entity` | 有 identity，但沒有證明自己擁有獨立 aggregate boundary | `StockOperationType`, `StockLocation`, `StockMoveLine` |
| `domain.valueobject` | 沒有獨立 identity/lifecycle，以值表達穩定 Domain 概念或 pure planning input/output | `StockOperationSource`, state/direction/source enums, `SkuQuantities`, `ReceivingBatchIdentity`, `LocationUsageType`, Allocation Demand/Supply/Proposal types |
| `domain.policy` | 純業務規則或 policy vocabulary | `MovementAssignmentPolicy` |
| `domain.service` | 不自然屬於單一 aggregate 的 stateless pure calculation | `StockAllocationPlanner`, `MovementAssignmentPlanner` |

Domain type 不直接放在 `domain` root。`Demand`、`Supply`、`Proposal` 是類別的 Domain 名詞，不是 package 的
技術角色；它們沒有獨立 identity/lifecycle，內容完整決定其意義與相等性，因此放在
`allocation.domain.valueobject`。`Demand → Supply → Proposal` 的流程語意由類別名稱與
`StockAllocationPlanner` signature 表達，不再複製成 package 層級。

Domain 不建立 `dto`、`view`、`command`、`result`、`projection` 或 `event` package。DTO／View 是邊界或查詢
模型，應位於 Application／Entrypoint；Domain 只使用 `aggregate`、`entity`、`valueobject`、`policy`、
`service` 這些 tactical technical roles。

### Infrastructure persistence package

Persistence 先依技術、再依技術角色分包，不與 Domain package 鏡像：

| Package | 責任 |
| --- | --- |
| `persistence.jdbc.store` | Application `*Store` 的 JDBC 實作，以及需要 package-private 共置的 row folder |
| `persistence.jpa.entity` | `@Entity` 與 composite key 等 ORM persistence model |
| `persistence.jpa.repository` | Spring Data `Jpa*Repository` interface |
| `persistence.jpa.mapper` | Domain model 與 JPA entity 的轉換 |
| `persistence.jpa.store` | 實作 Application `*Store`，協調 JPA repository 與 mapper |

JDBC row 不是 Domain Entity，也不因對應資料表就建立 `jdbc.entity`。只有當 reusable SQL、converter、codec 或
JDBC support 真正形成獨立責任時，才新增相應技術 package；不預建空 package，也不再使用模糊的
`infrastructure.repo`。

### Layer ownership corrections

| 型別 | 判定 | 原因 |
| --- | --- | --- |
| `ReceivingBatchIdentity` | Application → `position.domain.valueobject` | 入庫日與效期共同定義 StockQuant 批次身分，跨 receipt use case 仍成立 |
| `StockWriteOrder` | Domain → `position.application.policy` | 它統一 DB lock/write order 以避免 deadlock，是 transaction coordination，不是倉儲業務事實 |
| `AssignmentQueueKey` | 保留 `allocation.application.valueobject` | 它是 backlog wake/selection 的 coordination partition，不是權威庫存事實 |
| `StockOperationAssignmentCandidate`、`StockOperationPredecessor` | 保留 `allocation.application.projection` | 它們是 Store 選出的 immutable planning projection，Domain input 是其中的 `StockOperationDemand` |

目前只有下列型別允許留在 Application root；這不是預設收納位置：

| 型別 | 暫留原因 |
| --- | --- |
| `movement.application.StockOperationComposite` | 同時組合 Movement aggregate 與 Reservation detail，是 transaction working model，不是 view、result 或 aggregate |
| `position.application.StockReceiptRequest` | 同時代表 caller idempotency identity 與完整 command binding，尚未決定是 command envelope 或 application entity |
| `reservation.application.MoveQuantAllocationSet` | 同時服務 commit、release、completion 的跨流程 working set，不具 value equality，也不是 authoritative aggregate |

新增 Application root type 必須先證明無法歸入既有角色 package，並把判斷與原因補進本表。

## Data-access port vocabulary

Inventory 的自訂資料存取 port 統一使用 `*Store`，並放在所屬 module 的
`application.store`。Command/query 仍是 use case 的分類，但不再決定 data port 的 package 或 suffix；
讀、鎖與寫的效果由 `find...`、`inspect...`、`lock...`、`save...` 等方法名稱直接表達。

| Store 類型 | 語意 | 規則 |
| --- | --- | --- |
| canonical-model Store | 同一模型的普通讀取、明確 lock 與 mutation | 同一 persistence lifecycle 不再拆成成對介面 |
| planning-input Store | Demand、predecessor、supply、backlog projection | 只回傳 immutable planning input，不併入 canonical-model Store |
| visibility Store | Operator／diagnostic read model | 回傳 immutable View／projection，保持用途隔離 |

- `StockMoveStore` 同時負責 ordinary loading、依全域順序鎖定與保存 canonical `StockMove`。
- `StockMoveLineStore` 依 Move 載入明細，並負責 assignment 整批建立與 release 整批刪除。
- `StockQuantStore` 同時提供完整身分讀取、global lock order 與 counter 寫入。
- `StockOperationAssignmentCandidateStore` 與 `StockAllocationSupplyStore` 是獨立的 immutable planning-input
  Store，不因都讀資料庫就併入 `StockOperationStore` 或 `StockQuantStore`。
- `StockLocationViewStore`、`StockOperationViewStore` 與 `StockQuantViewStore` 是 focused visibility Store。
- Domain 不宣告任何 persistence port；Application 載入 Domain object 後再呼叫其行為。
- `Repository` 只出現在 Infrastructure 的 Spring Data `Jpa*Repository`，保留 framework vocabulary。

## Nested type policy

- 其他 production class 若必須 import `Parent.Child`，`Child` 原則上外提為獨立 top-level type。
- Controller request/response、Store result、Application port model 與跨 service/use case 的 working value
  不由某個 implementation holder 擁有。
- private local key、row、queue、sealed variants，以及只由父 response/snapshot 呈現的組成明細可以保留
  nested。
- JDBC row folding 成為獨立 mapping responsibility 時，使用同 package 的 package-private collaborator，
  不把 JDBC shape 洩漏到 Application 或 Domain。

## 未來擴充

Posting、Traceability、Inventory Control、Availability 目前只是 seam，不建立空介面或空 package。
等到出現「實際執行與原承諾不同」、serial/lot custody、調整盤點或獨立 ATP 政策時，再以已存在的
行為與資料生命週期決定是否成立新 module。
