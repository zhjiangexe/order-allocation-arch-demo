# Eventuate Tram-aligned Messaging — Gate F FS0 Baseline

> 日期：2026-08-10
> 狀態：完成；允許進入 FS1
> 範圍：Tram API parity、現有 Kafka wire contract、retry／DLT characterization；不切換 production subscription

## 1. 結論

FS0 固定的不是 Eventuate Tram binary compatibility，而是 application-facing class model 與責任順序：

```text
IntegrationEventHandlersBuilder
  → IntegrationEventHandlers
  → IntegrationEventDispatcherFactory.make(subscriberId, handlers)
  → IntegrationEventDispatcher
  → MessageConsumer.subscribe(subscriberId, destinations, dispatcher)
```

FS1 可以建立 pure `messaging-events` API，但必須保留目前
`IntegrationEventHandler<E>`／`KafkaIntegrationEventDispatcher` compatibility path。Gate F 不移除
application `@KafkaListener`，也不建立第二個 production consumer group。

Eventuate Tram source snapshot 固定於
[`eventuate-tram-core@28a57e270f7a37bf5b0f4f30c06d8d3442b4bc35`](https://github.com/eventuate-tram/eventuate-tram-core/tree/28a57e270f7a37bf5b0f4f30c06d8d3442b4bc35)。
後續若升級參考版本，必須先更新本矩陣與 characterization tests。

## 2. Tram API parity matrix

| Tram source API | Archone FS1 target | 決策 |
|---|---|---|
| `DomainEventEnvelope<T>`：aggregate ID／type、event ID、event、generic `Message` | immutable `IntegrationEventEnvelope<E>`，保留相同五項資料 | 複製責任；使用 Archone record accessor 與 UUID identity，不複製 mutable implementation。 |
| concrete `DomainEventHandler` registration | package-private Integration Event registration | application 不 implements registration type，只透過 builder 註冊 method reference。 |
| `DomainEventHandlers` | public immutable `IntegrationEventHandlers` | 複製 collection 角色；不複製 Tram 目前可變 `List` 的內部實作。 |
| `forAggregateType(...).onEvent(...).andForAggregateType(...).build()` | `forDestination(...).onEvent(...).andForDestination(...).build()` | method chaining 形狀照搬；routing axis 改成本專案 logical destination。 |
| `DomainEventNameMapping` | `IntegrationEventNameMapping` | 複製「Java type ↔ external type」責任，額外納入 contract version。 |
| default name mapping 使用 Java FQCN | explicit stable type／version mapping | 不照搬；class rename 不得改變 wire contract。 |
| `DomainEventDispatcherFactory.make(id, handlers)` 建立並初始化 dispatcher | `IntegrationEventDispatcherFactory.make(subscriberId, handlers)` | signature 與初始化責任照搬；不得全域掃描 Spring handler beans。 |
| dispatcher 由 handler aggregate types 推導 channels，再呼叫 `MessageConsumer.subscribe(...)` | 由 handler logical destinations 推導 channels | 複製 ownership；physical Kafka topic 仍由 `ChannelMapping` 解析。 |
| unmatched handler 直接 return | shared channel 預設 `IGNORE_WITH_METRIC`，dedicated channel 可 `FAIL` | 不靜默吞掉；policy 屬後續 FS3，FS1 先固定可辨識的 unsupported outcome／exception。 |
| `MessageConsumer.subscribe(String, Set<String>, MessageHandler)` | 保留同名基本 overload | FS2 才演進 generic consumer API；獨立 group／policy 只能做 additive overload/options。 |
| consumer 本身 `getId()`／`close()` | 每個 `MessageSubscription` lifecycle handle | 不照搬 singleton consumer lifecycle；Archone 需要逐 subscription stop／readiness。 |

Tram source evidence：

- [`DomainEventEnvelope`](https://github.com/eventuate-tram/eventuate-tram-core/blob/28a57e270f7a37bf5b0f4f30c06d8d3442b4bc35/eventuate-tram-events/src/main/java/io/eventuate/tram/events/subscriber/DomainEventEnvelope.java)
- [`DomainEventHandlersBuilder`](https://github.com/eventuate-tram/eventuate-tram-core/blob/28a57e270f7a37bf5b0f4f30c06d8d3442b4bc35/eventuate-tram-events/src/main/java/io/eventuate/tram/events/subscriber/DomainEventHandlersBuilder.java)
- [`DomainEventDispatcherFactory`](https://github.com/eventuate-tram/eventuate-tram-core/blob/28a57e270f7a37bf5b0f4f30c06d8d3442b4bc35/eventuate-tram-events/src/main/java/io/eventuate/tram/events/subscriber/DomainEventDispatcherFactory.java)
- [`DomainEventDispatcher`](https://github.com/eventuate-tram/eventuate-tram-core/blob/28a57e270f7a37bf5b0f4f30c06d8d3442b4bc35/eventuate-tram-events/src/main/java/io/eventuate/tram/events/subscriber/DomainEventDispatcher.java)
- [`DomainEventNameMapping`](https://github.com/eventuate-tram/eventuate-tram-core/blob/28a57e270f7a37bf5b0f4f30c06d8d3442b4bc35/eventuate-tram-events/src/main/java/io/eventuate/tram/events/common/DomainEventNameMapping.java)
- [`MessageConsumer`](https://github.com/eventuate-tram/eventuate-tram-core/blob/28a57e270f7a37bf5b0f4f30c06d8d3442b4bc35/eventuate-tram-messaging/src/main/java/io/eventuate/tram/messaging/consumer/MessageConsumer.java)

## 3. 現有 wire contract

### 3.1 Debezium／Kafka physical facts

```text
Kafka record topic      → MessageHeaders.DESTINATION
Kafka record key        → MessageHeaders.PARTITION_ID
Kafka header id         → MessageHeaders.MESSAGE_ID
Kafka header eventType  → MessageHeaders.MESSAGE_TYPE + EventMessageHeaders.EVENT_TYPE
Kafka record timestamp  → MessageHeaders.MESSAGE_DATE
Kafka value             → Message.payload
Kafka header messageHeaders → decoded logical／protocol headers
```

`KafkaMessageMapper` 必須拒絕 serialized headers 偽造 physical message ID、type、partition、topic 或
timestamp。缺 `event-contract-version` 的 legacy record 固定視為 version 1。

Gate C 之後的新 Outbox row 會把 `event-aggregate-type`、`event-aggregate-id` 與 contract version 放在
serialized headers；FS1 target envelope 可把它們視為 required Integration Event contract。

### 3.2 歷史 aggregate metadata 相容性

V8 以前已進 Kafka 的 records 可能沒有 serialized aggregate headers。FS1 不得從 payload、record key
或 event class 猜測 aggregate identity，也不得把 partition key 當 aggregate ID。

production cutover 的安全條件是：

1. Gate F 只以 test subscriber 驗證新 API，不切 production。
2. Gate I 使用既有 consumer group／offset 原子切換，不建立會從 earliest 重播的新 group。
3. 若要 group rename、歷史 replay 或重放舊 DLT，runbook 必須先辨識缺 aggregate metadata 的 records，
   走 legacy compatibility handler 或受控 enrichment；不得讓 framework 靜默填假值。

## 4. 現有 retry／DLT contract

```text
Kafka delivery
  → allocation attempt decorator
      initial attempt + 2 optimistic-lock retries, fixed 100 ms
      every attempt re-enters transactional Inbox chain
  → exception propagates to DefaultErrorHandler
      AllocationConcurrencyExhaustedException:
        4 container retries, 1s → 2s → 4s → 8s, max interval 10s
      every other exception:
        no container retry
  → DeadLetterPublishingRecoverer
      topic = original topic + "-dlt"
      partition/key/value/original headers retained
      Spring Kafka failure metadata appended
```

`AllocationRetryMessageHandlerDecorator` 是 bounded-context concurrency policy，不得搬進 generic
messaging retry。`DeadLetterPublishingRecoverer` 是唯一允許直接使用 Kafka producer 的 recovery path；
正常 publication 仍固定為 Outbox → Debezium。

## 5. Characterization evidence

| Contract | Test evidence |
|---|---|
| physical Kafka facts、legacy version 1、serialized headers 與 collision rejection | `KafkaMessageMapperTest` |
| event ID／type payload mismatch、unsupported version、typed dispatch failure | `IntegrationEventDispatcherTest`、`KafkaIntegrationEventDispatcherTest` |
| decorator ordering、duplicate short-circuit、transaction rollback | `KafkaIntegrationEventDispatcherTest`、`AllocationTransactionalMessageChainIntegrationTest` |
| allocation retry 位於 transactional idempotency 外層 | `AllocationRetryMessageHandlerDecoratorTest` |
| retryable exception 先重送、其他 exception 直接 DLT；DLT 保留 record contract | `AllocationKafkaErrorHandlingCharacterizationTest` |
| programmatic container 可承接現有 handler、BATCH ack、concurrency 與 lifecycle | `ProgrammaticKafkaContainerFeasibilityTest` |

## 6. FS1 進入規則

FS1 必須遵守：

- 新 typed API 全部位於 pure `messaging-events`，不得 import Spring／Kafka。
- application-facing builder 不要求重複提供 destination、event type 與 event class metadata methods。
- external event type/version 由 explicit name mapping 提供；不得 reflection 讀 static field 或使用 FQCN。
- subscriber ID／processing attempt 不進 `IntegrationEventEnvelope`。
- duplicate registration、mapping collision、缺 aggregate identity、unsupported version 必須 fail fast。
- compatibility adapter 可以暫留，但新 dispatcher／factory 不得依賴全域 handler bean list。
