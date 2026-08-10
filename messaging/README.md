# Messaging modules

本目錄以 Eventuate Tram 的責任切分為參考，但保留本專案的 PostgreSQL Outbox → Debezium →
Kafka producer 路徑。共用 contract 不依賴 Spring、JPA 或 Kafka；Spring artifacts 只負責 adapter
與組裝。

## 依賴方向

```text
messaging-api
├── messaging-events
├── messaging-producer-common
├── messaging-consumer-common
├── messaging-jdbc-common
└── messaging-consumer-kafka

messaging-producer-jdbc ──→ producer-common + jdbc-common
messaging-consumer-jdbc ──→ consumer-common + jdbc-common

Spring adapters
├── messaging-spring-jdbc
├── messaging-spring-producer-jdbc
├── messaging-spring-consumer-jdbc
├── messaging-spring-consumer-kafka
├── messaging-spring-*-observability
├── messaging-spring-flyway（opt-in）
└── messaging-spring-optimistic-locking（opt-in）

Spring Boot
├── messaging-spring-boot-autoconfigure
├── messaging-spring-producer-starter
├── messaging-spring-consumer-starter
└── messaging-spring-boot-starter（producer + consumer）
```

`messaging-producer-outbox`、`messaging-consumer-inbox` 與 JPA repository compatibility layer 已
移除。Physical tables 仍維持 `event_outbox`／`event_inbox`，只是由 pure JDBC implementation
透過 Spring JDBC adapter 存取。

## 如何選 starter

| Application 角色 | 依賴 |
|---|---|
| 只發布訊息 | `:messaging:messaging-spring-producer-starter` |
| 只消費訊息 | `:messaging:messaging-spring-consumer-starter` |
| 同時發布與消費 | `:messaging:messaging-spring-boot-starter` |

正常 publication 只寫入 caller transaction 的 Outbox，不直接呼叫 Kafka producer。Consumer
runtime 以 `MessageConsumer.subscribe(...)` 程式化建立 Spring Kafka containers；application 不需
撰寫 `@KafkaListener` glue code。

## Application 的事件接線

每個 bounded context 各自建立小型 `...EventConsumer`：同一個 class 明確完成
event-to-usecase mapping、handlers 宣告與唯一 subscription owner 的建立。這是刻意保留的
Tram-shaped application surface，不再額外拆成語意模糊的 `Configuration + Target`，也不採用
handler scan、全域 handler catalog 或 application `@KafkaListener`：

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
class AllocationOrderLifecycleEventConsumer {

  private final AllocateOrderUsecase allocateOrderUsecase;

  AllocationOrderLifecycleEventConsumer(AllocateOrderUsecase allocateOrderUsecase) {
    this.allocateOrderUsecase = allocateOrderUsecase;
  }

  private IntegrationEventHandlers integrationEventHandlers() {
    return IntegrationEventHandlersBuilder
        .forDestination("ordering.order-events")
        .onEvent(OrderPlacedIntegrationEvent.class,
            envelope -> allocateOrderUsecase.execute(
                new AllocateOrderCommand(envelope.event().getOrderId())))
        .build();
  }

  @Bean
  IntegrationEventDispatcher allocationOrderLifecycleDispatcher(
      IntegrationEventDispatcherFactory factory
  ) {
    return factory.make(
        "allocation-ordering-events",
        integrationEventHandlers());
  }
}
```

`IntegrationEventHandlers` 在這裡是顯式 routing definition，但不註冊成 Spring Bean。Spring context
只保存真正擁有 subscription lifecycle 的 `IntegrationEventDispatcher`，因此不需要 handler bean
名稱常數或 `@Qualifier`。

`subscriberId` 是 Inbox idempotency scope，也是 Kafka consumer group 的預設 logical identity；若部署
需要不同的實體 group，交由 runtime 的 `ConsumerGroupMapping`／properties 轉換，不在 event factory
建立第二套 options。`IntegrationEventNameMapping` 必須顯式列出允許反序列化的 stable event
type／version，不使用 Java FQCN 動態載入。`@ConditionalOnIntegrationEventConsumption` 是單一
capability condition；`spring.kafka.listener.auto-startup=false` 只讓已宣告的 subscription 不啟動，
不會把 application wiring 隱藏掉。

## Transaction 與 idempotency

```text
transaction begin
  → event_inbox INSERT ... ON CONFLICT DO NOTHING
  → typed handler / application use case
  → business writes
  → optional event_outbox append
transaction commit
```

交易由 `TransactionalIdempotencyMessageHandlerDecorator` 經 `MessagingTransactionTemplate` 建立；
`DuplicateMessageDetector` 是 chain 內的低階 detector，不是讓 application 直接 claim Inbox 的
use-case API。Handler 拋出的 exception 必須向外傳遞，才能 rollback Inbox 並交給 Spring Kafka
retry／DLT policy。

### 可選的 optimistic-lock retry

需要在同一次 broker delivery 內重新執行完整 transaction 的 application，可直接依賴
`messaging-spring-optimistic-locking`，並在 composition root 顯式 opt in：

```java
@Configuration(proxyBeanMethods = false)
@Import(OptimisticLockingDecoratorConfiguration.class)
class ApplicationOptimisticLockingConfiguration {

  @Bean
  OptimisticLockingRetrySettings optimisticLockingRetrySettings() {
    return new OptimisticLockingRetrySettings(2, Duration.ofMillis(100));
  }
}
```

這個 artifact 仿 Tram 將 `OptimisticLockingDecorator` 做成通用 handler decorator，但不由 consumer
starter 自動帶入。shared module 只認識 `OptimisticLockingFailureException`、message invocation 與 retry
chain；subscriber 範圍、operation 名稱、metrics／logs 可由 application 實作
`OptimisticLockingRetryObserver`。Decorator 固定在 transactional idempotency 外層，因此每次 local
retry 都會建立新 transaction；若耗盡，`OptimisticLockingRetryExhaustedException` 再交給 Kafka
redelivery／DLT policy。

## 設定與維運

`archone.messaging.*` 只管理 messaging capability、channel／consumer-group mapping、dispatcher、
observation 與 Flyway 是否組裝。Kafka container 的全域 operational baseline 直接使用 Spring Boot
配置好的 `ConcurrentKafkaListenerContainerFactory`，不要再維護第二套同義 properties：

```properties
spring.kafka.listener.concurrency=4
spring.kafka.listener.ack-mode=record
spring.kafka.listener.missing-topics-fatal=false
spring.kafka.listener.observation-enabled=true
spring.kafka.listener.auto-startup=true
```

`archone.messaging.consumer.kafka.enabled` 仍是 programmatic Kafka runtime 的 capability 開關；
`archone.messaging.consumer.kafka.concurrency`、`ack-mode`、`missing-topics-fatal`、
`observation-enabled` 與 `shutdown-timeout` 不再是有效設定。Factory 的設定是 baseline，application
提供的 `KafkaSubscriptionPolicyResolver` 只回傳明確的 subscriber-specific overrides：

```java
@Bean
KafkaSubscriptionPolicyResolver kafkaSubscriptionPolicyResolver() {
  return subscription -> "slow-export".equals(subscription.subscriberId())
      ? KafkaSubscriptionPolicy.builder()
          .concurrency(1)
          .shutdownTimeout(Duration.ofSeconds(30))
          .build()
      : KafkaSubscriptionPolicy.defaults(); // 沒有 override，完整繼承 shared factory
}
```

precedence 固定如下：

1. Spring Boot `spring.kafka.listener.*` 與 application 的 Kafka factory／container customizer
   建立全域 baseline；
2. `KafkaSubscriptionPolicyResolver` 的非空欄位只覆寫該 subscriber；
3. runtime 最後補上 subscription 的 group、listener、DLT error handler 與 lifecycle ownership。

Spring Boot 沒有直接提供的 container setting（例如本專案使用的 shutdown timeout），全域值應由
Spring Kafka `ContainerCustomizer` 設定；只有單一 subscriber 特例才放在
`KafkaSubscriptionPolicy`。Default policy 不攜帶任何 operational default，因此不會蓋掉 Boot 或
custom factory 設定。

責任切分如下：

| Application 明確擁有 | Messaging runtime／starter 擁有 |
|---|---|
| stable event mapping、handler routing | programmatic Kafka container 與 lifecycle |
| stable `subscriberId` | consumer-group mapping、unhandled-event observation |
| 可重試 exception 與特殊 backoff | 依 `ResolvedMessageSubscription` 建立 error handler |
| 必要的 per-subscriber policy override | 繼承 shared factory、精確 DLT headers、failure observation 與 Micrometer 接線 |

Application 若有特殊失敗語意，只提供 `KafkaConsumerFailurePolicyResolver`；不需要自行建立
`CommonErrorHandler`、`KafkaOperations`、DLT headers provider 或 Micrometer observer。同一 physical
topic 即使有多個 subscriber，runtime 仍以實際 subscription identity 建立各自的 DLT metadata。

Transport record 無法轉成 generic Message 時，runtime 會拋出 `MessageMappingException`；typed
Integration Event 的 header／payload 不符合 contract 時會拋出
`IntegrationEventContractException`。兩者都不包住 application handler exception，讓 application
policy 能分開判斷 `MAPPING`、`CONTRACT`、`HANDLER` 與 `INFRASTRUCTURE`。failure observation 會帶
低基數 `messaging.failure.category`、`messaging.failure.retryable`，DLT 另帶
`messaging.dlt.disposition=direct|retry_exhausted`。

Rolling deployment、subscriber/group rename、DLT replay、header failure 與 retention 請見
[`docs/messaging-operations-runbook.md`](../docs/messaging-operations-runbook.md)。完整演進決策請見
[`docs/eventuate-tram-aligned-messaging-roadmap.md`](../docs/eventuate-tram-aligned-messaging-roadmap.md)。
