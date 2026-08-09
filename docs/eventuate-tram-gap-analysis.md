# Eventuate Tram 能力盤點與 Archone Messaging Gap Analysis

狀態：已決定採用 Eventuate Tram 式 transactional messaging；typed handler／dispatcher／subscription API 盡量照 Tram class model實作，但不直接引入 Eventuate Tram dependency

盤點日期：2026-08-07
架構決策與第一階段修訂：2026-08-08
Gate E 完成與 Tram handler DSL 決策：2026-08-10
Gate F FS0～FS3 handler DSL／generic API／Spring Kafka runtime 完成：2026-08-10

## 一、文件目的

本文件回答三個問題：

1. Eventuate Tram 實際提供哪些能力，而不只是 Transactional Outbox。
2. Archone 目前已經具備哪些對應能力。
3. 如果目標是建立較精簡的 `messaging:messaging-spring-boot-starter`，真正需要補多少，而哪些 Eventuate Tram 能力應刻意不做。

本文將下列範圍分開，避免把整個 Eventuate 生態系誤算成 Tram Core：

- **Eventuate Tram Core**：generic messaging、domain events、commands、transactional outbox、consumer transaction 與 duplicate detection。
- **Eventuate Tram CDC**：讀取 Outbox 並傳送到 broker 的獨立 runtime。
- **Eventuate Tram Sagas**：建立在 Tram Command／Reply 上的 orchestration framework。
- **CQRS／Command-side Replica**：可用 Tram 實作的協作模式，不代表 Tram 會自動產生 projection。
- **Eventuate Local**：Event Sourcing 產品，並不是 Eventuate Tram Core。

## 二、評估符號與計分方式

| 符號 | 意義 |
|---|---|
| ✅ | 已具備相近且可運作的能力 |
| 🟡 | 部分具備，但仍綁定單一應用、缺少抽象或營運能力 |
| ❌ | 目前沒有 |
| ⏭️ | Eventuate Tram 有，但不是 Archone 建議目標 |

後文的百分比是架構盤點用的工程估算，不是效能 benchmark：

- 完整能力計 `1` 分。
- 部分能力計 `0.5` 分。
- 缺少能力計 `0` 分。
- 刻意不納入目標的能力不列入 Eventuate-lite 完成度分母。

## 三、兩套架構的核心路徑

### 3.1 Eventuate Tram

```text
Business transaction
  → MessageProducer／DomainEventPublisher／CommandProducer
  → MESSAGE Outbox table
  → Eventuate CDC
      ├── MySQL binlog
      ├── PostgreSQL WAL
      └── SQL polling
  → Kafka／ActiveMQ／RabbitMQ／Redis
  → MessageConsumer／Dispatcher
  → MessageHandlerDecorator chain
      ├── transaction
      ├── duplicate detection
      └── optimistic-lock retry
  → Event／Command handler
```

### 3.2 Archone Gate E／Gate F FS3 後現況

```text
Use Case transaction
  → Domain Event
  → bounded-context DomainEventPublisher port（直接同步呼叫）
  → Domain Event → Integration Event Translator
  → IntegrationEventPublisher
  → MessageProducerImpl
  → JdbcOutboxMessageProducerImplementation
  → event_outbox
  → Debezium Outbox Event Router
  → Kafka
  → application-owned @KafkaListener（Gate F／I 過渡層）
  → KafkaIntegrationEventDispatcher
  → ordered MessageHandlerDecorator chain
      ├── optional bounded-context allocation attempt retry
      └── transactional idempotency
          ├── DuplicateMessageDetector.claimIfNew()
          ├── LegacyIntegrationEventDispatcherAdapter
          ├── transitional per-event IntegrationEventHandler<E>
          └── transport-neutral usecase.execute(command)
```

對外事件主線不經過 Spring `ApplicationEventPublisher`。publisher 呼叫與 aggregate 儲存位於
同一個 application transaction；Outbox 寫入失敗時，業務異動一起 rollback。
JDBC Outbox adapter 以 caller-transaction guard 拒絕脫離 caller transaction 的獨立寫入，避免
「看似 transactional messaging，實際上只單獨提交訊息」的誤用。consumer side 則由
transactional idempotency decorator 在同一 transaction 內依序 claim Inbox、呼叫 typed handler、
執行 business behavior 與 optional Outbox append；handler exception 會讓三者一起 rollback。

`ApplicationEventPublisher` 並未被禁止，但只適合不要求跨程序可靠傳遞的 local module event，
不得成為 Outbox publication 的必要中介。

目前不是「完全沒有 messaging framework」，而是已有可靠路徑。Gate F FS1～FS3 已把 Eventuate
Tram 的 handler 與 generic consumer 結構依本專案語意一對一映射成 pure API：

```text
IntegrationEventHandlersBuilder
  → IntegrationEventHandlers
  → IntegrationEventDispatcherFactory.make(subscriberId, handlers)
  → IntegrationEventDispatcher
  → MessageConsumer.subscribe(subscriberId, destinations, dispatcher)
```

其中只保留必要差異：使用 `IntegrationEvent`／`forDestination` 命名、stable external event type +
version mapping，以及透過 additive options 分開 Kafka `consumerGroupId`。Spring Kafka
programmatic container、subscriber policy、retry／DLT 與 lifecycle runtime 亦已可獨立測試；但
production 仍刻意走上方 legacy adapter，FS4 完成 equivalence SIT、Gate I 原子切換後才移除相容層。
目前 modules 已整理成：

- `messaging:messaging-api`：framework-neutral `Message`／`MessageProducer`，以及 FS2 Tram-shaped `MessageConsumer`／subscription options。
- `messaging:messaging-events`：`IntegrationEvent`、publication metadata、serializer、publisher，
  以及 FS1 envelope／handler builder／explicit name mapping／dispatcher factory；legacy adapter 暫留至 Gate I。
- `messaging:messaging-producer-common`／`messaging-producer-jdbc`：producer orchestration 與 pure JDBC Outbox implementation。
- `messaging:messaging-consumer-common`／`messaging-consumer-jdbc`：單一 consumer implementation SPI、logical channel resolution、decorator chain、duplicate detector 與 transactional idempotency implementation。
- `messaging:messaging-consumer-kafka`：Kafka record → generic `Message` mapping；不得擁有 typed event dispatch。
- `messaging:messaging-spring-consumer-kafka`：programmatic Spring Kafka containers、stable
  subscriber/group identity、subscriber policy、failure classifier → retry／DLT adapter 與 lifecycle；
  不依賴 typed events layer。
- `messaging:messaging-spring-jdbc`／producer／consumer bridges：Spring transaction-aware JDBC wiring。
- `messaging:messaging-spring-boot-autoconfigure`：目前仍提供 temporary dispatcher bridge；Gate F 建立不做全域掃描的新 factory，Gate I production cutover 後才移除舊的 global handler-list wiring。
- `messaging:messaging-spring-boot-starter`：只負責依賴收納的薄 starter。
- `contracts`：只保留具體 Integration Event payload，依賴 `messaging-events`。
- `platform-infrastructure`：只保留 Clock 等非 messaging 的共用 Spring infrastructure。
- `order-promising`：topic、partition policy、temporary listeners、bounded-context retry／DLT 與 business use cases。
- Debezium／Kafka Connect 設定：外部 relay runtime。

Gate F 沒有動態產生 annotated methods；`messaging-spring-consumer-kafka` 仿 Tram，透過
`MessageConsumer.subscribe(...)` 程式化建立 Spring Kafka containers。application 必須明確提供每個
subscriber 的 `IntegrationEventHandlers` 與 dispatcher bean；starter 不得把 ApplicationContext 裡
所有 handler beans 全域混成一份 catalog。topic mapping、consumer group、concurrency、retry 與 DLT
仍是可覆寫的 runtime policy。

## 四、Eventuate Tram 能力盤點

### 4.1 Generic Messaging Core

| 能力 | Eventuate Tram 做法 | 主要價值 |
|---|---|---|
| Message envelope | `Message` 統一承接 ID、destination、partition、headers、payload 與日期 | transport metadata 不散落在 payload 與 broker API |
| Message producer | `MessageProducer.send(destination, message)` | 建立 transport-neutral 發送入口 |
| Message consumer | `MessageConsumer.subscribe(subscriberId, channels, handler)` | subscription 與 broker adapter 分離 |
| Logical channel | 使用 named channel，再映射到實際 broker destination | application 不必直接認識實體 topic／queue |
| Message interceptor | `preSend`、`postSend`、`preReceive`、`preHandle`、`postHandle` 等 hooks | header enrichment、tracing、audit、metrics |
| Handler decorator | 以有順序的 decorator chain 包住 handler | transaction、去重、retry 等機制可組合 |
| Header propagation | message headers 承接 correlation、reply、trace 等 metadata | 跨服務保留處理脈絡 |
| Message ID generation | application-generated 或 database-generated ID | 去重、追蹤與部分 ordering 判斷 |

### 4.2 Reliable Producer 與 CDC

| 能力 | Eventuate Tram 做法 | 主要價值 |
|---|---|---|
| Transactional Outbox | business data 與 `MESSAGE` row 在同一 ACID transaction 寫入 | 避免 DB commit 成功但訊息遺失 |
| CDC relay | Eventuate CDC 是獨立 Spring Boot runtime | application 不直接在 business transaction 發 broker message |
| Log tailing | MySQL binlog、PostgreSQL WAL | 避免高頻 SQL polling |
| Polling fallback | 其他 SQL database 可輪詢 Outbox | 支援更多 database |
| Multi-reader／pipeline | 一個 CDC runtime 可讀多個 Outbox | 集中管理多個 producer |
| Offset store | 保存 transaction log 或 relay 讀取位置 | crash 後可恢復 |
| Leader election | cluster 中決定 active reader | 避免多個 relay 重複競爭 |
| Multi-broker publisher | Kafka、ActiveMQ、RabbitMQ、Redis | transport 可替換 |
| CDC health／metrics | health endpoint、reader health、Prometheus metrics | 能營運 relay，而不只是寫入 Outbox |

### 4.3 Reliable Consumer

| 能力 | Eventuate Tram 做法 | 主要價值 |
|---|---|---|
| Subscriber identity | 每個 subscription 有 `subscriberId` | 同一 message 可由多個獨立 subscriber 各處理一次 |
| Duplicate detector SPI | SQL、transactional-noop、noop 等策略 | 可依 handler 是否天然 idempotent 選擇 |
| SQL Inbox | `RECEIVED_MESSAGES` 記錄已處理訊息 | broker at-least-once 下避免重複 business effect |
| Consumer transaction | duplicate claim 與 handler 在同一 transaction | handler 失敗時 claim 一起 rollback |
| Optimistic-lock retry | decorator 捕捉 optimistic locking failure 並重開 transaction | 處理 consumer concurrency |
| Handler decorators | duplicate、transaction、retry 等 cross-cutting concern 不進 business handler | handler／Use Case 保持 channel-neutral |

### 4.4 Domain Event Abstraction

| 能力 | Eventuate Tram 做法 | 主要價值 |
|---|---|---|
| DomainEventPublisher | 以 aggregate type／ID 發布一組 Domain Events | aggregate update 與 event publication 原子化 |
| DomainEventEnvelope | 消費端取得 event ID、aggregate ID/type、payload 等資訊 | handler 不直接處理 raw message |
| DomainEventDispatcher | 將 envelope 派到 typed event handler | 集中 event type resolution |
| Handler builder | 以 aggregate type 與 event class 註冊 handler | 顯式建立 subscription catalog |
| Event name mapping | Java type 與外部 event type 分離 | class rename 不必破壞 wire contract |

### 4.5 Command／Async Reply

| 能力 | Eventuate Tram 做法 | 主要價值 |
|---|---|---|
| CommandProducer | 對指定 channel 非同步送出 Command | 支援服務間 request/reply collaboration |
| CommandDispatcher | 將 Command 派到 typed handler | 集中 command routing |
| Reply channel | participant 回覆原始 caller | 非同步回應 |
| Success／failure reply | reply 明確表達執行結果 | Saga 可依結果推進或補償 |
| Correlation metadata | 將 command 與 reply 配對 | 支援多個 concurrent invocation |

### 4.6 Eventuate Tram Sagas

Eventuate Tram Sagas 是建立在 Tram Command／Reply 之上的獨立擴充：

| 能力 | 說明 |
|---|---|
| Saga orchestrator | 集中決定下一個 participant action |
| Saga instance persistence | 保存 saga data、目前步驟與結果 |
| Step DSL | 宣告 forward action、reply handler 與 compensation |
| Participant | 接收 Command 並回覆 success／failure |
| Compensation | 後續步驟失敗時反向執行補償 |
| Saga testing support | 驗證送出的 command、reply 與補償順序 |

### 4.7 CQRS 與 Command-side Replica

Eventuate Tram 可可靠傳遞 Domain Events，讓 consumer 建立：

- SQL／MongoDB／Elasticsearch read model。
- 跨服務 query projection。
- Command-side replica。
- choreography-based Saga。

Tram 提供的是可靠事件與 idempotent handler 基礎；projection schema、rebuild、版本遷移與查詢 API 仍須由 application 實作。

### 4.8 Testing、Framework 與維運支援

| 能力 | Eventuate Tram 做法 |
|---|---|
| In-memory messaging | 不啟動實際 broker 即可測試 producer／consumer |
| Event handler test support | 以 DSL 測試 Domain Event handler |
| Command handler test support | 測試 Command 與 Reply |
| Saga test support | 驗證 orchestration 與 compensation |
| Outbox／producer test support | 驗證 transaction message publication |
| Framework variants | Spring、Micronaut、Quarkus，另有相關 .NET 實作 |
| Reactive modules | 提供 reactive producer／consumer variants |
| Broker variants | Kafka、ActiveMQ、RabbitMQ、Redis |
| Database variants | MySQL、PostgreSQL 與 JDBC polling database |

## 五、Archone 與 Eventuate Tram 逐項比較

### 5.1 Messaging Core 與契約

| ID | 能力 | Eventuate Tram | Archone 目前狀態 | 判定 | 建議 |
|---|---|---|---|---:|---|
| M1 | 通用 Message envelope | 統一 ID、destination、partition、headers、payload | `messaging-api` 已有 transport-neutral `Message`；headers／correlation metadata 尚未補 | 🟡 | 保持 API 克制，等 metadata 需求確定後擴充，不先做任意 raw-message framework |
| M2 | 穩定外部 event type | Java type 與外部名稱可映射 | 每種事件已有明確 `EVENT_TYPE`；Outbox 與 handler 不再使用 class simple name | ✅ | 第一階段保留既有 wire value 以相容未消費訊息；未來更名必須走版本遷移 |
| M3 | Producer API | `MessageProducer`／`DomainEventPublisher` | 已有 `IntegrationEventPublisher → MessageProducer → OutboxMessageProducer`；application 不再依賴 `OutboxAppender` | ✅ | 保持 publisher 同步並參與 caller transaction |
| M4 | Consumer API | `MessageConsumer.subscribe(...)` | Tram 三參數 pure API、group options、Spring Kafka programmatic implementation與 lifecycle handle 已完成；production 尚未切換 | 🟡 | FS4 固定 equivalence，Gate I 原子切 subscriber；transport policy不塞入 generic API |
| M5 | Typed dispatcher | `DomainEventHandlersBuilder` + dispatcher factory | immutable envelope／handler collection、explicit name mapping、factory 已完成；另有強制 observer 的 `FAIL`／`IGNORE_WITH_METRIC` policy，production 仍走 deprecated compatibility adapter | 🟡 | Gate I 遷移 application handler targets並替 shared subscriber 明確選 policy |
| M6 | Duplicate handler 檢查 | handler collection／dispatcher 建立時 fail fast | builder 拒絕重複 `(destination, event class)`；name mapping 拒絕 class／external type collision，dispatcher 啟動前驗證雙向 mapping | ✅ | 保持 fail-fast，不把 validation 延後到第一筆 Kafka delivery |
| M7 | Logical channel mapping | logical channel 映射 broker destination | producer／consumer common 共用 `ChannelMapping`；consumer 在唯一 transport SPI 前完成 reverse mapping，architecture test 禁止 Kafka adapter 維護第二份 | ✅ | 保持 logical ownership 與 physical binding 分離 |
| M8 | Message interceptor | 有完整 send／receive lifecycle hooks | 已有 framework-neutral `MessageInterceptor` contract；consumer receive wiring／observation 尚待 Gate G | 🟡 | 保留單一 lifecycle，不另建平行 hooks |
| M9 | Handler decorator chain | transaction、duplicate、optimistic retry 可組合 | 已有 immutable ordered chain、transactional idempotency decorator 與 application-attempt insertion range，production Kafka path已啟用 | ✅ | 保持單一 chain；Tram handler DSL 只取代 terminal typed dispatch，不重做 transaction pipeline |

### 5.2 Producer、Outbox 與 Relay

| ID | 能力 | Eventuate Tram | Archone 目前狀態 | 判定 | 建議 |
|---|---|---|---|---:|---|
| P1 | Transactional Outbox | business update 與 message row 同 transaction | Use Case 明確同步呼叫 bounded-context publisher，最後由 `OutboxMessageProducer` 寫入；SIT 驗證 commit／rollback | ✅ | 保留，不在 producer side 直接呼叫 Kafka |
| P2 | Aggregate 與 transport metadata 分離 | envelope／destination 分欄 | `aggregateType`／`aggregateId` 與 `route`／`partitionKey` 已分欄 | ✅ | 目前模型清楚，不要合併欄位 |
| P3 | Outbox relay | 自有 Eventuate CDC | Debezium Outbox Event Router | ✅ | 不另寫 poller 或 CDC service |
| P4 | Log tailing | MySQL binlog／PostgreSQL WAL | Debezium 讀 PostgreSQL transaction log | ✅ | 保留 Debezium 的營運責任 |
| P5 | Partition／ordering policy | partition metadata 與 broker adapter | `StockContentionKey` 實作 `(owner, facility)` single-writer；Outbox 有 `partition_key` | ✅ | 這是 Archone 業務政策，不移入 generic starter |
| P6 | Multi-reader／pipeline | Eventuate CDC 可集中讀多個 Outbox | 目前 connector 針對單一 `event_outbox` | ⏭️ | 出現第二個獨立 database／runtime 後再評估 |
| P7 | Multi-broker relay | Kafka、ActiveMQ、RabbitMQ、Redis | Kafka only | ⏭️ | 不建立沒有需求的 broker abstraction |
| P8 | Schema ownership | Eventuate artifact／runtime 定義 MESSAGE schema | JPA Entity 已移到 `messaging-producer-outbox`，V5 migration 仍在 `order-promising` | 🟡 | starter 提供 namespaced migration，runtime 顯式啟用 |

### 5.3 Consumer、Inbox 與交易

| ID | 能力 | Eventuate Tram | Archone 目前狀態 | 判定 | 建議 |
|---|---|---|---|---:|---|
| C1 | Subscriber identity | duplicate key 包含 subscriber 概念 | `MessageContext`／subscription 提供 stable subscriber；Inbox PK 為 `(subscriber_id, event_id)` | ✅ | Gate F 顯式綁定 dispatcher ID、Inbox scope 與可分開的 Kafka group |
| C2 | Duplicate detector SPI | SQL／transactional-noop／noop 可替換 | 已有 framework-neutral `DuplicateMessageDetector` 與 JDBC implementation；目前只實作專案需要的 SQL strategy | ✅ | 不為了形式建立無需求 strategies，但保留 SPI |
| C3 | Atomic Inbox transaction | claim 與 handler 同 transaction | transactional idempotency decorator 在同一 transaction claim → handler → business／Outbox；PostgreSQL SIT 驗證 rollback | ✅ | 保留原子性與 handler exception propagation |
| C4 | Channel-neutral Use Case | decorator 在 handler 外管理 transaction／Inbox | 六個 consumer-side use cases 已改收純 Command，移除 `InboundCommand`／`InboxRepo` | ✅ | Gate F envelope 停在 integration handler，不再往 application usecase 傳遞 |
| C5 | Header／payload validation | message abstraction集中處理 | target dispatcher 在 deserialize 前驗證 type／version／aggregate identity，再驗 payload event ID／type；mapping／contract／handler／infrastructure taxonomy 已接 retry／DLT | ✅ | source metadata 可後續增補 |
| C6 | Optimistic-lock retry | generic handler decorator | `SpringAllocationRetryExecutor` 只服務 allocation contention | 🟡 | 保留 business-specific retry；不要過早泛化所有 handler retry |
| C7 | Broker retry／DLT | broker adapter與 handler error policy | shared factory 已組裝 `DefaultErrorHandler`／classifier backoff／DLT；allocation 仍保有 4 次指數退避與 direct-DLT characterization | 🟡 | Gate H 抽可覆寫 starter default；business classification 留在 application |
| C8 | Exception propagation | handler 失敗交給 transaction／delivery policy | dispatcher 不吞 handler exception，Kafka error handler 接手 | ✅ | 保留 |

### 5.4 上層協作模型

| ID | 能力 | Eventuate Tram | Archone 目前狀態 | 判定 | 是否應補 |
|---|---|---|---|---:|---:|
| H1 | Generic raw messaging | named-channel Message API | generic `Message`／producer、Tram-shaped consumer 與 Spring Kafka programmatic runtime 均已完成 | ✅ | ✅，直接支撐 Events 與 Gate J Command／Reply |
| H2 | Transactional DomainEventPublisher | 可直接跨服務發布 Domain Event | Use Case 已直接呼叫 bounded-context publisher；publisher 先把內部 Domain Event 轉為 Integration Event，再進 transactional messaging | ✅ | 保留 Domain／Integration Event 分離，不複製 Tram 的同型別做法 |
| H3 | Command／Async Reply | CommandProducer／Dispatcher／Reply | 尚未實作，Roadmap Gate J 已規劃建立在 generic messaging 上 | ❌ | ✅，不與 Saga 綁定 |
| H4 | Saga orchestration | Tram Sagas step DSL 與 compensation | 無；Temporal 評估後刻意移除 fulfillment workflow modules | ❌ | ⏭️，需要時優先評估 Temporal |
| H5 | CQRS support | 以可靠 event handler 建 projection | 有 application-specific read view／projection，無 framework | 🟡 | ⏭️，projection 應由 bounded context 擁有 |
| H6 | Command-side replica | 以事件維護其他服務資料副本 | `demand_lines` 是同 database view，不是跨服務 replica | ❌ | ⏭️，真正拆服務後才需要 |
| H7 | Event Sourcing | 屬於 Eventuate Local，不是 Tram Core | 無 | ⏭️ | ⏭️ |

### 5.5 Auto-configuration、測試與維運

| ID | 能力 | Eventuate Tram | Archone 目前狀態 | 判定 | 建議 |
|---|---|---|---|---:|---|
| O1 | Spring Boot Starter | 多個 starter／auto-configuration artifacts | 已有獨立 auto-configuration、`AutoConfiguration.imports` 與 dependency-only starter；不再由 `platform-infrastructure` wiring | ✅ | 維持薄 starter，不放 application listener policy |
| O2 | Conditional configuration | 依 broker／database／bean 選擇 implementation | bean 可用 `@ConditionalOnMissingBean` 覆寫；`archone.messaging.jpa.enabled` 可整組關閉，package registration 亦可設定 | 🟡 | 真有只用 Inbox 或只用 Outbox 的應用後，再拆 feature toggle |
| O3 | Dedicated serializer | message serialization abstraction | `JacksonIntegrationEventSerde` 使用 event-scoped Jackson 2 mapper；會複製唯一的 app mapper，否則自行建立，且不發布全域 mapper bean | ✅ | 維持 REST／Jackson 3 與 messaging wire policy 隔離 |
| O4 | Reusable test kit | in-memory producer／consumer 與 handler test support | unit／SIT 很完整，但 fixture 綁在 `order-promising` | 🟡 | 建立 captured publisher、inbound driver 與 duplicate fixture |
| O5 | Contract compatibility test | external name mapping與 serializer可獨立測試 | 五種事件已有 JSON golden files、round-trip、type uniqueness 與固定 wire name 測試 | ✅ | 後續新增事件必須同步加入同一組 contract tests |
| O6 | Messaging observability | interceptor、CDC health／metrics | 有 OpenTelemetry、allocation retry metrics、DLT 與 E2E tests，但無統一 message metrics | 🟡 | 統一 `messageId`、type、subscriber、result、duplicate 與 duration metrics |
| O7 | Relay health／offset | CDC health、reader／offset visibility | Kafka Connect／Debezium 在外部 runtime，尚未由 starter 統一呈現 | 🟡 | 留在 deployment／observability，不塞入 application starter |
| O8 | Multi-framework／reactive | Spring、Micronaut、Quarkus、reactive variants | Spring imperative only | ⏭️ | 沒有實際 consumer 前不做 |

## 六、落差到底有多少

### 6.1 對「完整 Eventuate Tram 生態」

若把 generic messaging、Command／Reply、Saga、CQRS support、多 broker、自有 CDC、多 framework 與 testing ecosystem 全部算入，Archone 約只覆蓋 **35%～40%**。

這個數字不代表目前架構只有四成可用，因為大量差距是刻意不需要：

- 不需要自有 CDC，已有 Debezium。
- 不需要多 broker，現在只有 Kafka。
- 不需要 Command／Reply bus。
- 不需要再造 Saga framework。
- 不需要 generic CQRS framework。
- 不需要 Event Sourcing。

因此「追到 Eventuate Tram 100%」不是合理目標。

### 6.2 對建議的「Eventuate-lite Messaging Starter」

建議目標只包含可靠 Integration Event：

| 評估面向 | 權重 | 目前成熟度 | 加權得分 | 主要缺口 |
|---|---:|---:|---:|---|
| Event contract／metadata | 20 | 3.5 / 5 | 14.0 | 尚缺 schema version、correlation／causation 與 source metadata |
| Producer／Outbox／Relay | 25 | 4.5 / 5 | 22.5 | publisher API 與 migration ownership |
| Consumer／Inbox／Transaction | 25 | 4.75 / 5 | 23.75 | transaction／duplicate chain、Tram handler DSL 與 programmatic runtime 已完成；尚缺 production migration |
| Starter／Auto-configuration | 20 | 4.0 / 5 | 16.0 | 尚缺 migration ownership 與更完整的 feature toggles |
| Observability／Test support | 10 | 2.5 / 5 | 5.0 | reusable test kit 與統一 messaging metrics |
| **2026-08-10 Gate F FS3 後合計** | **100** |  | **81.25 / 100** | **programmatic runtime／policy／DLT／lifecycle 已落地；production migration 與 observability 仍缺** |

本文件初次盤點為 `57 / 100`；完成穩定 event type、subscriber-aware Inbox 與 contract tests 後提升為
`65.5 / 100`。2026-08-08 完成 producer／consumer 子模組、broker-neutral handler、auto-configuration
與薄 starter後估為 `77.5 / 100`；2026-08-10 完成 Gate E decorator transaction ownership、pure use case
cutover 與 REST idempotency boundary 後估為 `80.0 / 100`。FS1～FS3 新增 pure handler DSL、generic
consumer API 與 Spring Kafka programmatic runtime，因此提升為 `81.25 / 100`；production 尚未切換，
不把 runtime readiness 誤算成 migration 完成。這仍是架構盤點，不是產品成熟度 SLA，
必須配合兩個判讀：

1. **可靠傳送主線約已有 80%～90%。** Transactional Outbox、Debezium、Kafka dispatcher、subscriber-aware Inbox transaction、retry／DLT 與 partition ordering 都已存在並有 SIT。
2. **可重用 starter 產品化約 75%～80%。** 模組、bean composition、transaction chain、Tram-style handler DSL 與 programmatic subscription 已獨立；schema ownership、production auto-wiring、test kit 與 metrics 仍未完成。

換句話說，剩餘工作主要不是重新實作 Kafka 或 Outbox，而是把既有可靠機制整理成穩定 API 與可插拔 runtime。

## 七、真正需要補的 Eventuate-lite 範圍

### P0：先固定 API 與 wire contract

- [x] 建立 framework-neutral `messaging-api`（generic `Message`／`MessageProducer`）。
- [x] 將 `IntegrationEvent` 移到 `messaging-events`，不要放進 `contracts` 或 Spring Boot module。
- [x] `IntegrationEvent` 提供穩定 `eventType()`，不再使用 class simple name。
- [x] 定義 `IntegrationEventPublisher`、`AggregateReference`、`IntegrationEventPublication` 與
  `PublicationTarget`。
- [x] 將既有 Kafka handler 抽成 broker-neutral `IntegrationEventHandler<E>`；Kafka record 僅存在 consumer adapter。這是 Gate E compatibility 形狀，不是最終 API。
- [x] 補 JSON golden contract、round-trip 與 event type uniqueness tests。
- [x] Gate F FS1 已建立 Tram 式 `IntegrationEventEnvelope`、`IntegrationEventHandlers`／builder、`IntegrationEventNameMapping` 與 dispatcher factory；per-event handler interface 只保留至 Gate I production migration。
- [x] Gate F FS2 已建立 Tram 三參數 `MessageConsumer.subscribe(...)`、additive consumer-group options，並固定唯一 generic consumer SPI 與 consumer-side `ChannelMapping` 邊界。
- [x] Gate F FS3 已建立 Spring Kafka programmatic container、subscriber policy、stable
  subscriber/group collision validation、failure taxonomy → retry／DLT adapter、readiness／graceful
  shutdown，以及強制 observer 的 unhandled-event policy；allocation production listener 尚未切換。

### P1：整理 producer／consumer transaction boundary

- [x] 移除 application-facing `OutboxAppender`，改成
  `IntegrationEventPublisher → MessageProducerImpl → JdbcOutboxMessageProducerImplementation`。
- [x] 對外事件 publication 不再經過 `ApplicationEventPublisher`／`@EventListener`。
- [x] 以唯一 ordered decorator chain 集中處理 transaction、Inbox claim 與 handler invocation；沒有建立第二條 `InboundMessageProcessor` pipeline。
- [x] Use Case 改收純 Command，不再直接依賴 `InboundCommand`／`InboxRepo`。
- [x] 將 Inbox key 擴充為 `(subscriber_id, event_id)`，並提供既有資料 migration。
- [x] 保留 exception propagation，由 Spring Kafka retry／DLT 接手。

### P1：Spring Boot Starter 產品化

- [x] 建立 `messaging:messaging-spring-boot-autoconfigure`。
- [x] 建立幾乎只有 dependencies 的 `messaging:messaging-spring-boot-starter`。
- [x] 以 `AutoConfiguration.imports` 載入，不使用 component scan。
- [x] 依 Spring Boot lifecycle 分為基礎 auto-config 與 JPA auto-config；提供整組 JPA enable／disable 與 bean override。
- [x] 使用 event-scoped Jackson 2 codec，不發布未具名全域 `ObjectMapper`。
- [ ] 提供 namespaced Flyway migration，由 runtime 顯式啟用。

### P2：營運與開發體驗

- [ ] 統一 message lifecycle metrics 與 structured log fields。
- [ ] 增加 correlation／causation／source／schema version metadata。
- [ ] 建立 in-memory publisher、captured events、inbound driver 與 duplicate fixture。
- [x] 使用 `ApplicationContextRunner` 驗證 auto-configuration 的 enable、disable、override 與無 application mapper fallback。

## 八、刻意不做的能力

| 能力 | 不做原因 |
|---|---|
| Eventuate wire／binary 相容層 | 保留 Tram API shape，但不承諾可直接交換其 MESSAGE schema、headers 或序列化格式 |
| Saga framework | Temporal 更適合真正的長時間跨系統 orchestration |
| CQRS framework | projection schema、更新與 rebuild 應由 bounded context 擁有 |
| 自有 CDC／poller | Debezium 已成熟處理 transaction-log relay |
| 多 broker adapter | 沒有 RabbitMQ／ActiveMQ／Redis 需求 |
| Runtime 產生 annotated Kafka listener methods | 不做 annotation／proxy code generation；改由 Tram 式 `MessageConsumer.subscribe(...)` 程式化建立 containers，policy 仍可覆寫 |
| Event Sourcing | 不屬於目前需求，也不是 Tram Core 必需能力 |

## 九、建議的最終依賴方向

以下所有箭頭都表示「左側 module 依賴右側 module」。這是 Gate F／I 完成後的目標；legacy
`messaging-producer-outbox`／`messaging-consumer-inbox` 只保留到 migration cleanup：

```text
messaging:messaging-events → messaging:messaging-api
contracts → messaging:messaging-events
messaging:messaging-producer-common → messaging:messaging-api
messaging:messaging-consumer-common → messaging:messaging-api
messaging:messaging-jdbc-common → framework-neutral JDBC／transaction ports
messaging:messaging-producer-jdbc
  → messaging:messaging-producer-common
  → messaging:messaging-jdbc-common
messaging:messaging-consumer-jdbc
  → messaging:messaging-consumer-common
  → messaging:messaging-jdbc-common
messaging:messaging-consumer-kafka → messaging:messaging-api
messaging:messaging-spring-jdbc → messaging:messaging-jdbc-common
messaging:messaging-spring-consumer-kafka
  → messaging:messaging-consumer-common
  → messaging:messaging-consumer-kafka

messaging:messaging-spring-boot-autoconfigure
  → messaging:messaging-api
  → messaging:messaging-events
  → messaging:messaging-spring-producer-jdbc
  → messaging:messaging-spring-consumer-jdbc
  → messaging:messaging-spring-consumer-kafka

messaging:messaging-spring-boot-starter
  → messaging:messaging-spring-boot-autoconfigure

order-promising
  → contracts
  → messaging:messaging-api
  → messaging:messaging-events
  → messaging:messaging-spring-consumer-kafka（FS3 compatibility policy；Gate H 後可由 starter 收納）
  → messaging:messaging-spring-boot-starter
  → platform-infrastructure

platform-infrastructure → spring-context（Clock 等非 messaging wiring）

wms-runtime（未來）
  → wms
  → contracts
  → messaging:messaging-spring-boot-starter

order-promising → foundation
wms → foundation
```

`platform-infrastructure` 已不再是 messaging composition root。bounded context 的 domain 不依賴
messaging；application use cases 只接收純 Command。Integration Event metadata 停在 publisher／
handler target；consumer transaction／Inbox 由單一 decorator chain 擁有。Gate F／I 仍需把
temporary per-event handler interface／global bean list／application `@KafkaListener` 收斂成 Tram 式
handler group + dispatcher factory + `MessageConsumer.subscribe(...)`。

## 十、結論

Archone 與 Eventuate Tram 的最大差距，不是可靠消息主線，而是 framework productization：

- Producer reliability 已由 Transactional Outbox + Debezium 解決。
- Consumer reliability 已有 Inbox transaction、typed dispatcher、retry 與 DLT。
- 穩定 event identity、subscriber-aware Inbox、ordered transaction chain、pure use cases、consumer 子模組、auto-configuration、pure Tram handler DSL、generic consumer API 與 Spring Kafka programmatic runtime 已完成；下一個主要缺口是 production compatibility cutover、schema ownership、observability 與 reusable test support。
- 完整 Eventuate Tram 約只有 35%～40% 覆蓋，但大多數缺口不是需求。
- `65.5 / 100` 是 2026-08-07 基線；2026-08-08 完成 producer／consumer module 與 starter 後估為 `77.5 / 100`；2026-08-10 Gate E／Gate F FS2 後為 `80.0 / 100`，FS3 programmatic runtime／policy／lifecycle 完成後估為 `81.25 / 100`；production cutover 尚未完成。

本專案現在採取更明確的原則：**能直接沿用的 Tram messaging class model 就盡量照搬，只有與
既有 bounded-context 語意或 Kafka operational requirements 衝突時才偏離。**具體包含：

1. Producer／consumer API 分離。
2. Transactional Outbox／Inbox。
3. Subscriber-aware duplicate detection。
4. 單一 ordered handler decorator chain。
5. Envelope + handler collection／builder + dispatcher factory。
6. `MessageConsumer.subscribe(subscriberId, channels, handler)` programmatic subscription。

不照搬的部分是 `DomainEvent` 命名、FQCN event names、自有 CDC、多 broker／framework、Saga 與
Kafka policy defaults；這些差異必須在 roadmap 明確記錄，不得因「仿 Tram」而悄悄混入 core API。

## 十一、主要參考資料

- [About Eventuate Tram](https://eventuate.io/docs/manual/eventuate-tram/latest/about-eventuate-tram.html)
- [Getting started with Eventuate Tram](https://eventuate.io/docs/manual/eventuate-tram/latest/getting-started-eventuate-tram.html)
- [Eventuate Tram `DomainEventHandlersBuilder` Javadoc](https://eventuate.io/docs/javadoc/eventuate-tram/0.25.1.RELEASE/io/eventuate/tram/events/subscriber/DomainEventHandlersBuilder.html)
- [Eventuate Tram Core source repository](https://github.com/eventuate-tram/eventuate-tram-core)
- [Eventuate Tram CDC configuration](https://eventuate.io/docs/manual/eventuate-tram/latest/cdc-configuration.html)
- [Eventuate Tram Sagas](https://eventuate.io/docs/manual/eventuate-tram/latest/getting-started-eventuate-tram-sagas.html)
- [Eventuate Tram basic examples](https://github.com/eventuate-tram/eventuate-tram-core-examples-basic)
- [Debezium Outbox Event Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)
