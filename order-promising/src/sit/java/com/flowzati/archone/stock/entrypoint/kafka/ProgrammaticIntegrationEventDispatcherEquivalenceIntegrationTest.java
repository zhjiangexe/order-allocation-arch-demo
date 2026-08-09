package com.flowzati.archone.stock.entrypoint.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.messaging.api.IdentityChannelMapping;
import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHandler;
import com.flowzati.archone.messaging.api.MessageSubscription;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImpl;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImplementation;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecorator;
import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.messaging.events.MapBasedIntegrationEventNameMapping;
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import com.flowzati.archone.messaging.producer.jdbc.MessageHeadersCodec;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.stock.application.command.AllocateOrderCommand;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.stock.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.stock.domain.model.StockFixtures;
import com.flowzati.archone.stock.domain.repository.StockPoolRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.SitDatabase;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Executes the future Tram-shaped terminal path without starting a second Kafka consumer.
 *
 * <p>The existing legacy listener tests remain the other side of the FS4 equivalence proof. This
 * suite captures a subscription in memory, but uses the production mapper, decorator chain,
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
  private AllocateOrderUsecase allocateOrderUsecase;

  @Autowired
  private IntegrationEventDeserializer eventDeserializer;

  @Autowired
  private IntegrationEventSerializer eventSerializer;

  @Autowired
  private MessageHeadersCodec headersCodec;

  @Autowired
  private List<MessageHandlerDecorator> decorators;

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private StockPoolRepository stockPoolRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void seedCatalogForOrders() {
    OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-1");
  }

  @AfterEach
  void clearDatabase() {
    SitDatabase.clear(jdbcTemplate);
  }

  @Test
  @DisplayName("新 dispatcher path 應以相同 subscriber 交易提交 Inbox、配貨與 Outbox")
  void shouldMatchTheLegacySuccessfulTransactionBoundary() {
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
        .isEqualTo(AllocationEventSubscriptions.ORDER_LIFECYCLE);
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
  @DisplayName("新 dispatcher path 業務失敗時應與 legacy path 一樣回滾 Inbox 與半成品")
  void shouldMatchTheLegacyRollbackBoundary() {
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

  private CapturingConsumerImplementation programmaticTransport() {
    CapturingConsumerImplementation transport = new CapturingConsumerImplementation();
    MessageConsumerImpl messageConsumer = new MessageConsumerImpl(
        transport, IdentityChannelMapping.INSTANCE, decorators);
    IntegrationEventDispatcherFactory factory = new IntegrationEventDispatcherFactory(
        messageConsumer,
        eventDeserializer,
        MapBasedIntegrationEventNameMapping.builder()
            .map(OrderPlacedIntegrationEvent.class, OrderPlacedIntegrationEvent.EVENT_TYPE, 1)
            .build());
    factory.make(
        AllocationEventSubscriptions.ORDER_LIFECYCLE,
        IntegrationEventHandlersBuilder.forDestination(OrderingEventTopics.ORDER_EVENTS)
            .onEvent(OrderPlacedIntegrationEvent.class, envelope ->
                allocateOrderUsecase.execute(
                    new AllocateOrderCommand(envelope.event().getOrderId())))
            .build());
    return transport;
  }

  private void emit(
      CapturingConsumerImplementation transport,
      ConsumerRecord<String, String> record
  ) {
    transport.emit(record.topic(), new KafkaMessageMapper(headersCodec).map(record));
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
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM event_inbox WHERE subscriber_id = ? AND event_id = ?",
        Integer.class,
        AllocationEventSubscriptions.ORDER_LIFECYCLE,
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
