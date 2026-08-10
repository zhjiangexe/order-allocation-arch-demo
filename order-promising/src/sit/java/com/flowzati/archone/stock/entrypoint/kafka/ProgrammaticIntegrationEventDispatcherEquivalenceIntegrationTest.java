package com.flowzati.archone.stock.entrypoint.kafka;

import static com.flowzati.archone.stock.entrypoint.messaging.AllocationInventoryAvailabilityEventConfiguration.ALLOCATION_INVENTORY_AVAILABILITY_HANDLERS;
import static com.flowzati.archone.stock.entrypoint.messaging.AllocationOrderLifecycleEventConfiguration.ALLOCATION_ORDER_LIFECYCLE_HANDLERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.api.ConsumerGroupMapping;
import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHandler;
import com.flowzati.archone.messaging.api.MessageSubscription;
import com.flowzati.archone.messaging.api.MessageSubscriptionOptions;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImpl;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImplementation;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecorator;
import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherOptions;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.messaging.events.UnhandledIntegrationEventObserver;
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import com.flowzati.archone.messaging.observation.MessagingObservationNames;
import com.flowzati.archone.messaging.observation.MessagingObservationTags;
import com.flowzati.archone.messaging.producer.jdbc.MessageHeadersCodec;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.stock.domain.model.StockFixtures;
import com.flowzati.archone.stock.domain.repository.StockPoolRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.SitDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Executes the production Tram-shaped terminal path without starting a Kafka container.
 *
 * <p>This suite captures a subscription in memory, but uses the production mapping, decorator chain,
 * transaction manager, Inbox table, use case, repositories, and Outbox adapter.
 */
@SpringBootTest(
    classes = ArchoneApplication.class,
    properties = "spring.kafka.listener.auto-startup=false",
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class ProgrammaticIntegrationEventDispatcherEquivalenceIntegrationTest {

  @Autowired
  private IntegrationEventDeserializer eventDeserializer;

  @Autowired
  private IntegrationEventNameMapping eventNameMapping;

  @Autowired
  private ChannelMapping channelMapping;

  @Autowired
  private ConsumerGroupMapping consumerGroupMapping;

  @Autowired
  @Qualifier(ALLOCATION_ORDER_LIFECYCLE_HANDLERS)
  private IntegrationEventHandlers orderLifecycleHandlers;

  @Autowired
  @Qualifier(ALLOCATION_INVENTORY_AVAILABILITY_HANDLERS)
  private IntegrationEventHandlers inventoryAvailabilityHandlers;

  @Autowired
  private UnhandledIntegrationEventObserver unhandledEventObserver;

  @Autowired
  private IntegrationEventSerializer eventSerializer;

  @Autowired
  private MessageHeadersCodec headersCodec;

  @Autowired
  private KafkaMessageMapper kafkaMessageMapper;

  @Autowired
  private List<MessageHandlerDecorator> decorators;

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private StockPoolRepository stockPoolRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private MeterRegistry meterRegistry;

  @BeforeEach
  void seedCatalogForOrders() {
    OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-1");
  }

  @AfterEach
  void clearDatabase() {
    SitDatabase.clear(jdbcTemplate);
  }

  @Test
  @DisplayName("停用 listener auto-startup 時仍應宣告 dispatcher，但不由 application condition 隱藏")
  void shouldDeclareDispatchersWhenContainerAutoStartupIsDisabled() {
    assertThat(applicationContext.getBeansOfType(IntegrationEventDispatcherFactory.class))
        .hasSize(1);
    assertThat(applicationContext.getBeansOfType(IntegrationEventHandlers.class))
        .hasSize(3);
    assertThat(applicationContext.getBeansOfType(IntegrationEventDispatcher.class))
        .hasSize(3);
  }

  @Test
  @DisplayName("production dispatcher path 應以穩定 subscriber 交易提交 Inbox、配貨與 Outbox")
  void shouldPreserveTheSuccessfulTransactionBoundary() {
    UUID orderId = IdGenerator.nextId();
    UUID stockPoolId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant receivedAt = Instant.now().minusSeconds(1);
    orderRepository.save(OrderFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt));
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, "SKU-1", 10, 0));
    CapturingConsumerImplementation transport = programmaticTransport();
    ConsumerRecord<String, String> record = record(
        new OrderPlacedIntegrationEvent(eventId, orderId, receivedAt), orderId);

    emit(transport, record);
    emit(transport, record);

    assertThat(transport.subscription().subscriberId())
        .isEqualTo(AllocationEventSubscriptions.ORDER_LIFECYCLE);
    assertThat(transport.subscription().consumerGroupId())
        .isEqualTo(AllocationEventSubscriptions.ORDER_LIFECYCLE_CONSUMER_GROUP);
    assertThat(transport.subscription().destinationToLogicalChannel())
        .containsEntry(OrderingEventTopics.ORDER_EVENTS, OrderingEventTopics.ORDER_EVENTS);
    assertThat(inboxCount(eventId)).isOne();
    assertThat(count("stock_pickings")).isOne();
    assertThat(count("stock_moves")).isOne();
    assertThat(count("stock_move_lines")).isOne();
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool ->
        assertThat(pool.getReservedQuantity()).isEqualTo(3));
    assertThat(count("event_outbox")).isOne();
  }

  @Test
  @DisplayName("production dispatcher path 業務失敗時應回滾 Inbox 與半成品")
  void shouldPreserveTheRollbackBoundary() {
    UUID orderId = IdGenerator.nextId();
    UUID stockPoolId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant receivedAt = Instant.now().minusSeconds(1);
    orderRepository.save(OrderFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt));
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, "SKU-1", 10, 0));
    jdbcTemplate.update(
        "DELETE FROM stock_picking_types WHERE facility_id = ?", OrderFixtures.FACILITY_ID);
    CapturingConsumerImplementation transport = programmaticTransport();

    assertThatThrownBy(() -> emit(transport, record(
        new OrderPlacedIntegrationEvent(eventId, orderId, receivedAt), orderId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has no outbound operation type");

    assertThat(inboxCount(eventId)).isZero();
    assertThat(count("stock_pickings")).isZero();
    assertThat(count("stock_moves")).isZero();
    assertThat(count("stock_move_lines")).isZero();
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool ->
        assertThat(pool.getReservedQuantity()).isZero());
    assertThat(count("event_outbox")).isZero();
  }

  @Test
  @DisplayName("shared ordering channel 的未註冊事件應 claim Inbox 並記為 ignored_unhandled")
  void shouldObserveAndAcknowledgeAnUnhandledSharedChannelEvent() {
    UUID eventId = UUID.randomUUID();
    CapturingConsumerImplementation transport = programmaticTransport();
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        OrderingEventTopics.ORDER_EVENTS,
        0,
        0,
        "order-ignored",
        "{}");
    record.headers().add(
        KafkaMessageMapper.LEGACY_ID_HEADER,
        eventId.toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER,
        "ordering.address-changed.v1".getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        KafkaMessageMapper.SERIALIZED_HEADERS,
        headersCodec.encode(Map.of()).getBytes(StandardCharsets.UTF_8));

    emit(transport, record);

    assertThat(inboxCount(eventId)).isOne();
    assertThat(count("stock_pickings")).isZero();
    assertThat(count("event_outbox")).isZero();
    Timer ignoredTimer = meterRegistry.find(MessagingObservationNames.CONSUMER)
        .tag(
            MessagingObservationTags.SUBSCRIBER_ID,
            AllocationEventSubscriptions.ORDER_LIFECYCLE)
        .tag(MessagingObservationTags.OUTCOME, "ignored_unhandled")
        .timer();
    assertThat(ignoredTimer).isNotNull();
    assertThat(ignoredTimer.count()).isPositive();
  }

  @Test
  @DisplayName("shared inventory channel 的未註冊事件應 claim Inbox 並記為 ignored_unhandled")
  void shouldObserveAndAcknowledgeAnUnhandledInventoryEvent() {
    UUID eventId = UUID.randomUUID();
    CapturingConsumerImplementation transport = inventoryProgrammaticTransport();
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        InventoryEventTopics.STOCK_EVENTS,
        0,
        0,
        "inventory-scope-ignored",
        "{}");
    record.headers().add(
        KafkaMessageMapper.LEGACY_ID_HEADER,
        eventId.toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER,
        "inventory.cycle-counted.v1".getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        KafkaMessageMapper.SERIALIZED_HEADERS,
        headersCodec.encode(Map.of()).getBytes(StandardCharsets.UTF_8));

    emit(transport, record);

    assertThat(transport.subscription().subscriberId())
        .isEqualTo(AllocationEventSubscriptions.INVENTORY_AVAILABILITY);
    assertThat(transport.subscription().consumerGroupId())
        .isEqualTo(AllocationEventSubscriptions.INVENTORY_AVAILABILITY_CONSUMER_GROUP);
    assertThat(inboxCount(AllocationEventSubscriptions.INVENTORY_AVAILABILITY, eventId)).isOne();
    assertThat(count("stock_move_lines")).isZero();
    assertThat(count("event_outbox")).isZero();
    Timer ignoredTimer = meterRegistry.find(MessagingObservationNames.CONSUMER)
        .tag(
            MessagingObservationTags.SUBSCRIBER_ID,
            AllocationEventSubscriptions.INVENTORY_AVAILABILITY)
        .tag(MessagingObservationTags.OUTCOME, "ignored_unhandled")
        .timer();
    assertThat(ignoredTimer).isNotNull();
    assertThat(ignoredTimer.count()).isPositive();
  }

  private CapturingConsumerImplementation programmaticTransport() {
    return programmaticTransport(
        AllocationEventSubscriptions.ORDER_LIFECYCLE,
        AllocationEventSubscriptions.ORDER_LIFECYCLE_CONSUMER_GROUP,
        orderLifecycleHandlers);
  }

  private CapturingConsumerImplementation inventoryProgrammaticTransport() {
    return programmaticTransport(
        AllocationEventSubscriptions.INVENTORY_AVAILABILITY,
        AllocationEventSubscriptions.INVENTORY_AVAILABILITY_CONSUMER_GROUP,
        inventoryAvailabilityHandlers);
  }

  private CapturingConsumerImplementation programmaticTransport(
      String subscriberId,
      String consumerGroupId,
      IntegrationEventHandlers handlers
  ) {
    CapturingConsumerImplementation transport = new CapturingConsumerImplementation();
    MessageConsumerImpl messageConsumer = new MessageConsumerImpl(
        transport, channelMapping, consumerGroupMapping, decorators);
    IntegrationEventDispatcherFactory factory = new IntegrationEventDispatcherFactory(
        messageConsumer,
        eventDeserializer,
        eventNameMapping);
    factory.make(
        subscriberId,
        handlers,
        IntegrationEventDispatcherOptions.builder()
            .subscriptionOptions(MessageSubscriptionOptions.withConsumerGroupId(
                consumerGroupId))
            .ignoreUnhandledEventsWith(unhandledEventObserver)
            .build());
    return transport;
  }

  private void emit(
      CapturingConsumerImplementation transport,
      ConsumerRecord<String, String> record
  ) {
    transport.emit(record.topic(), kafkaMessageMapper.map(record));
  }

  private ConsumerRecord<String, String> record(
      OrderPlacedIntegrationEvent event,
      UUID orderId
  ) {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        OrderingEventTopics.ORDER_EVENTS,
        0,
        0,
        orderId.toString(),
        eventSerializer.serialize(event));
    record.headers().add(
        KafkaMessageMapper.LEGACY_ID_HEADER,
        event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER,
        event.eventType().getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        KafkaMessageMapper.SERIALIZED_HEADERS,
        headersCodec.encode(Map.of(
                EventMessageHeaders.EVENT_AGGREGATE_TYPE, "Order",
                EventMessageHeaders.EVENT_AGGREGATE_ID, orderId.toString(),
                EventMessageHeaders.EVENT_CONTRACT_VERSION, "1"))
            .getBytes(StandardCharsets.UTF_8));
    return record;
  }

  private int inboxCount(UUID eventId) {
    return inboxCount(AllocationEventSubscriptions.ORDER_LIFECYCLE, eventId);
  }

  private int inboxCount(String subscriberId, UUID eventId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM event_inbox WHERE subscriber_id = ? AND event_id = ?",
        Integer.class,
        subscriberId,
        eventId);
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  private static final class CapturingConsumerImplementation
      implements MessageConsumerImplementation {

    private ResolvedMessageSubscription subscription;
    private MessageHandler handler;

    @Override
    public MessageSubscription subscribe(
        ResolvedMessageSubscription subscription,
        MessageHandler handler
    ) {
      if (this.subscription != null) {
        throw new IllegalStateException("FS4 test transport accepts one subscription");
      }
      this.subscription = subscription;
      this.handler = handler;
      return new MessageSubscription() {
        private boolean running = true;

        @Override
        public boolean isRunning() {
          return running;
        }

        @Override
        public void stop() {
          running = false;
        }
      };
    }

    void emit(String destination, Message message) {
      if (subscription == null || handler == null) {
        throw new IllegalStateException("FS4 test subscription has not started");
      }
      handler.handle(
          message,
          new MessageContext(
              subscription.subscriberId(),
              subscription.logicalChannelFor(destination),
              1));
    }

    ResolvedMessageSubscription subscription() {
      return subscription;
    }
  }
}
