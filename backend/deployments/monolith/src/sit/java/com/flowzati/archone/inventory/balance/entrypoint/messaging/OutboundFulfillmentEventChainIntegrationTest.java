package com.flowzati.archone.inventory.balance.entrypoint.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentAggregateTypes;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.fulfillment.v1.OutboundMovementsCompletedForFulfillmentIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverForFulfillmentIntegrationEvent;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockFixtures;
import com.flowzati.archone.inventory.balance.domain.repository.StockQuantRepository;
import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventMessageMapper;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.messaging.events.PublicationTarget;
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import com.flowzati.archone.messaging.testsupport.ControllableMessageConsumerImplementation;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.testsupport.MovementFixtures;
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

/** 驗證 event-driven 模式從 WMS custody handover 一路推進到 Ordering 終態。 */
@SpringBootTest(
        classes = ArchoneApplication.class,
        properties = "spring.kafka.listener.auto-startup=false",
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class OutboundFulfillmentEventChainIntegrationTest {

    @Autowired
    private IntegrationEventSerializer eventSerializer;

    @Autowired
    private KafkaMessageMapper kafkaMessageMapper;

    @Autowired
    private ChannelMapping channelMapping;

    @Autowired
    private ControllableMessageConsumerImplementation transport;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private StockQuantRepository stockQuantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedCatalog() {
        OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-OUTBOUND");
    }

    @AfterEach
    void clearDatabase() {
        SitDatabase.clear(jdbcTemplate);
    }

    @Test
    @DisplayName("WMS 交運事件應完成庫存出庫，再由完成事件將訂單推進到 FULFILLED")
    void shouldCompleteTheEventDrivenOutboundChainExactlyOnce() {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID stockQuantId = UUID.randomUUID();
        Instant allocatedAt = Instant.now().minusSeconds(2);
        Instant handedOverAt = allocatedAt.plusSeconds(1);
        var order = OrderFixtures.allocatedOrder(orderId, "SKU-OUTBOUND", 3, allocatedAt.minusSeconds(1), allocatedAt);
        orderRepository.save(order);
        stockQuantRepository.save(StockFixtures.unexpiredBatch(stockQuantId, "SKU-OUTBOUND", 10, 3));
        UUID movementId = MovementFixtures.seedAssignedPicking(jdbcTemplate, order, stockQuantId, 3);
        // fulfillment v1 對外沿用 allocationId 這個名稱，但值是本次出庫唯一的 picking ID，
        // 不是 allocation_demand_id。fixture 必須忠實反映 production adapter 的 mapping。
        UUID allocationId =
                jdbcTemplate.queryForObject("SELECT picking_id FROM stock_moves WHERE id = ?", UUID.class, movementId);

        var handover = new ShipmentHandedOverForFulfillmentIntegrationEvent(
                UUID.randomUUID(), shipmentId, allocationId, orderId, List.of(movementId), handedOverAt);

        // 同一 handover 重送兩次：Inbox 必須讓出庫扣帳與 completion outbox 都只發生一次。
        consumeHandover(handover);
        consumeHandover(handover);

        assertThat(inboxClaimExists(OutboundFulfillmentEventSubscriptions.SHIPMENT_HANDOVER, handover.getEventId()))
                .isTrue();
        assertThat(stockQuantRepository.findById(stockQuantId)).hasValueSatisfying(stockQuant -> {
            assertThat(stockQuant.getOnHandQuantity()).isEqualTo(7);
            assertThat(stockQuant.getReservedQuantity()).isZero();
        });
        assertThat(MovementFixtures.moveStatesOf(jdbcTemplate, orderId)).containsExactly("DONE");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT state FROM stock_pickings WHERE order_id = ?", String.class, orderId))
                .isEqualTo("DONE");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event_outbox WHERE type = ?",
                        Integer.class,
                        OutboundMovementsCompletedForFulfillmentIntegrationEvent.EVENT_TYPE))
                .isOne();

        UUID completionEventId = drainOutboundCompletion();
        // Ordering subscriber 自己也必須能承受 Kafka redelivery。
        drainOutboundCompletion();

        assertThat(inboxClaimExists(OrderingEventSubscriptions.FULFILLMENT_COMPLETION, completionEventId))
                .isTrue();
        assertThat(orderRepository.findById(orderId)).hasValueSatisfying(fulfilled -> {
            assertThat(fulfilled.getStatus()).isEqualTo(OrderStatus.FULFILLED);
            assertThat(fulfilled.getFulfilledAt()).isEqualTo(handedOverAt);
        });
    }

    private void consumeHandover(ShipmentHandedOverForFulfillmentIntegrationEvent event) {
        IntegrationEventPublication publication = new IntegrationEventPublication(
                event,
                new AggregateReference(
                        FulfillmentAggregateTypes.WMS_SHIPMENT,
                        event.getShipmentId().toString()),
                new PublicationTarget(
                        FulfillmentChannels.FULFILLMENT_HANDOFFS,
                        event.getOrderId().toString()),
                event.getHandedOverAt());
        transport.emit(
                OutboundFulfillmentEventSubscriptions.SHIPMENT_HANDOVER,
                channelMapping.transform(FulfillmentChannels.FULFILLMENT_HANDOFFS),
                new IntegrationEventMessageMapper(eventSerializer).toMessage(publication),
                1);
    }

    private UUID drainOutboundCompletion() {
        Map<String, Object> row =
                jdbcTemplate.queryForMap("""
        SELECT id, type, partition_key, payload, headers FROM event_outbox
         WHERE type = ?
        """, OutboundMovementsCompletedForFulfillmentIntegrationEvent.EVENT_TYPE);
        UUID eventId = UUID.fromString(row.get("id").toString());
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                FulfillmentChannels.FULFILLMENT_HANDOFFS,
                0,
                0,
                row.get("partition_key").toString(),
                row.get("payload").toString());
        record.headers()
                .add(KafkaMessageMapper.LEGACY_ID_HEADER, eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(
                        KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER,
                        row.get("type").toString().getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(
                        KafkaMessageMapper.SERIALIZED_HEADERS,
                        row.get("headers").toString().getBytes(StandardCharsets.UTF_8));
        transport.emit(
                OrderingEventSubscriptions.FULFILLMENT_COMPLETION,
                channelMapping.transform(FulfillmentChannels.FULFILLMENT_HANDOFFS),
                kafkaMessageMapper.map(record),
                1);
        return eventId;
    }

    private boolean inboxClaimExists(String subscriberId, UUID eventId) {
        return jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event_inbox WHERE subscriber_id = ? AND event_id = ?",
                        Integer.class,
                        subscriberId,
                        eventId)
                == 1;
    }
}
