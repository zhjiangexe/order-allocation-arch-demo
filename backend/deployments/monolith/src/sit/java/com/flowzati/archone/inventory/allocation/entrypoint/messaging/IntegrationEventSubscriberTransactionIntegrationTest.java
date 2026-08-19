package com.flowzati.archone.inventory.allocation.entrypoint.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.inventory.v1.InventoryChannels;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingChannels;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.allocation.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockFixtures;
import com.flowzati.archone.inventory.balance.domain.repository.StockQuantRepository;
import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import com.flowzati.archone.messaging.observation.MessagingObservationNames;
import com.flowzati.archone.messaging.observation.MessagingObservationTags;
import com.flowzati.archone.messaging.producer.jdbc.MessageHeadersCodec;
import com.flowzati.archone.messaging.testsupport.ControllableMessageConsumerImplementation;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.SitDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
class IntegrationEventSubscriberTransactionIntegrationTest {

    @Autowired
    private ChannelMapping channelMapping;

    @Autowired
    private ControllableMessageConsumerImplementation transport;

    @Autowired
    private IntegrationEventSerializer eventSerializer;

    @Autowired
    private MessageHeadersCodec headersCodec;

    @Autowired
    private KafkaMessageMapper kafkaMessageMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private StockQuantRepository stockQuantRepository;

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
                .isEmpty();
        assertThat(applicationContext.getBeansOfType(IntegrationEventDispatcher.class))
                .hasSize(4);
    }

    @Test
    @DisplayName("production dispatcher path 應以穩定 subscriber 交易提交 Inbox、配貨與 Outbox")
    void shouldPreserveTheSuccessfulTransactionBoundary() {
        UUID orderId = IdGenerator.nextId();
        UUID stockQuantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant receivedAt = Instant.now().minusSeconds(1);
        orderRepository.save(OrderFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt));
        stockQuantRepository.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 10, 0));
        ConsumerRecord<String, String> record =
                record(new OrderPlacedIntegrationEvent(eventId, orderId, receivedAt), orderId);

        emit(AllocationEventSubscriptions.ORDER_LIFECYCLE, record);
        emit(AllocationEventSubscriptions.ORDER_LIFECYCLE, record);

        ResolvedMessageSubscription subscription = subscription(AllocationEventSubscriptions.ORDER_LIFECYCLE);
        assertThat(subscription.subscriberId()).isEqualTo(AllocationEventSubscriptions.ORDER_LIFECYCLE);
        assertThat(subscription.consumerGroupId()).isEqualTo(AllocationEventSubscriptions.ORDER_LIFECYCLE);
        assertThat(subscription.destinationToLogicalChannel())
                .containsEntry(OrderingChannels.ORDER_EVENTS, OrderingChannels.ORDER_EVENTS);
        assertThat(inboxCount(eventId)).isOne();
        assertThat(count("stock_pickings")).isOne();
        assertThat(count("stock_moves")).isOne();
        assertThat(count("stock_move_lines")).isOne();
        assertThat(stockQuantRepository.findById(stockQuantId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isEqualTo(3));
        assertThat(count("event_outbox")).isEqualTo(2);
    }

    @Test
    @DisplayName("production dispatcher path 業務失敗時應回滾 Inbox 與半成品")
    void shouldPreserveTheRollbackBoundary() {
        UUID orderId = IdGenerator.nextId();
        UUID stockQuantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant receivedAt = Instant.now().minusSeconds(1);
        orderRepository.save(OrderFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt));
        stockQuantRepository.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 10, 0));
        jdbcTemplate.update("DELETE FROM stock_picking_types WHERE facility_id = ?", OrderFixtures.FACILITY_ID);
        assertThatThrownBy(() -> emit(
                        AllocationEventSubscriptions.ORDER_LIFECYCLE,
                        record(new OrderPlacedIntegrationEvent(eventId, orderId, receivedAt), orderId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no outbound operation type");

        assertThat(inboxCount(eventId)).isZero();
        assertThat(count("stock_pickings")).isZero();
        assertThat(count("stock_moves")).isZero();
        assertThat(count("stock_move_lines")).isZero();
        assertThat(stockQuantRepository.findById(stockQuantId))
                .hasValueSatisfying(
                        pool -> assertThat(pool.getReservedQuantity()).isZero());
        assertThat(count("event_outbox")).isZero();
    }

    @Test
    @DisplayName("shared ordering channel 的未註冊事件應 claim Inbox 並記為 ignored_unhandled")
    void shouldObserveAndAcknowledgeAnUnhandledSharedChannelEvent() {
        UUID eventId = UUID.randomUUID();
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(OrderingChannels.ORDER_EVENTS, 0, 0, "order-ignored", "{}");
        record.headers()
                .add(KafkaMessageMapper.LEGACY_ID_HEADER, eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(
                        KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER,
                        "ordering.address-changed.v1".getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(
                        KafkaMessageMapper.SERIALIZED_HEADERS,
                        headersCodec.encode(Map.of()).getBytes(StandardCharsets.UTF_8));

        emit(AllocationEventSubscriptions.ORDER_LIFECYCLE, record);

        assertThat(inboxCount(eventId)).isOne();
        assertThat(count("stock_pickings")).isZero();
        assertThat(count("event_outbox")).isZero();
        Timer ignoredTimer = meterRegistry
                .find(MessagingObservationNames.CONSUMER)
                .tag(MessagingObservationTags.SUBSCRIBER_ID, AllocationEventSubscriptions.ORDER_LIFECYCLE)
                .tag(MessagingObservationTags.OUTCOME, "ignored_unhandled")
                .timer();
        assertThat(ignoredTimer).isNotNull();
        assertThat(ignoredTimer.count()).isPositive();
    }

    @Test
    @DisplayName("shared inventory channel 的未註冊事件應 claim Inbox 並記為 ignored_unhandled")
    void shouldObserveAndAcknowledgeAnUnhandledInventoryEvent() {
        UUID eventId = UUID.randomUUID();
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(InventoryChannels.STOCK_EVENTS, 0, 0, "inventory-scope-ignored", "{}");
        record.headers()
                .add(KafkaMessageMapper.LEGACY_ID_HEADER, eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(
                        KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER,
                        "inventory.cycle-counted.v1".getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(
                        KafkaMessageMapper.SERIALIZED_HEADERS,
                        headersCodec.encode(Map.of()).getBytes(StandardCharsets.UTF_8));

        emit(AllocationEventSubscriptions.INVENTORY_AVAILABILITY, record);

        ResolvedMessageSubscription subscription = subscription(AllocationEventSubscriptions.INVENTORY_AVAILABILITY);
        assertThat(subscription.subscriberId()).isEqualTo(AllocationEventSubscriptions.INVENTORY_AVAILABILITY);
        assertThat(subscription.consumerGroupId()).isEqualTo(AllocationEventSubscriptions.INVENTORY_AVAILABILITY);
        assertThat(inboxCount(AllocationEventSubscriptions.INVENTORY_AVAILABILITY, eventId))
                .isOne();
        assertThat(count("stock_move_lines")).isZero();
        assertThat(count("event_outbox")).isZero();
        Timer ignoredTimer = meterRegistry
                .find(MessagingObservationNames.CONSUMER)
                .tag(MessagingObservationTags.SUBSCRIBER_ID, AllocationEventSubscriptions.INVENTORY_AVAILABILITY)
                .tag(MessagingObservationTags.OUTCOME, "ignored_unhandled")
                .timer();
        assertThat(ignoredTimer).isNotNull();
        assertThat(ignoredTimer.count()).isPositive();
    }

    private ResolvedMessageSubscription subscription(String subscriberId) {
        return transport.subscriptions().stream()
                .filter(candidate -> candidate.subscriberId().equals(subscriberId))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Production subscription not registered: " + subscriberId));
    }

    private void emit(String subscriberId, ConsumerRecord<String, String> record) {
        transport.emit(subscriberId, channelMapping.transform(record.topic()), kafkaMessageMapper.map(record), 1);
    }

    private ConsumerRecord<String, String> record(OrderPlacedIntegrationEvent event, UUID orderId) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                OrderingChannels.ORDER_EVENTS, 0, 0, orderId.toString(), eventSerializer.serialize(event));
        record.headers()
                .add(
                        KafkaMessageMapper.LEGACY_ID_HEADER,
                        event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(
                        KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER,
                        event.eventType().getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(
                        KafkaMessageMapper.SERIALIZED_HEADERS,
                        headersCodec
                                .encode(Map.of(
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
}
