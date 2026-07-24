package com.flowzati.archone.allocation.entrypoint.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.command.ReplenishStockCommand;
import com.flowzati.archone.allocation.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.allocation.application.usecase.ReleaseReservationUsecase;
import com.flowzati.archone.allocation.application.usecase.ReplenishmentUsecase;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventDispatcher;
import com.flowzati.archone.common.outbox.OutboxRoutes;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AllocationKafkaIntegrationEventConsumerTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final AllocateOrderUsecase allocateOrderUsecase = mock(AllocateOrderUsecase.class);
  private final ReleaseReservationUsecase releaseReservationUsecase = mock(ReleaseReservationUsecase.class);
  private final ReplenishmentUsecase replenishmentUsecase = mock(ReplenishmentUsecase.class);
  private final AllocationKafkaIntegrationEventConsumer consumer = new AllocationKafkaIntegrationEventConsumer(
      new KafkaIntegrationEventDispatcher(
          objectMapper,
          List.of(
              new OrderPlacedIntegrationEventHandler(allocateOrderUsecase),
              new OrderCancelledIntegrationEventHandler(releaseReservationUsecase),
              new StockReplenishedIntegrationEventHandler(replenishmentUsecase))));

  @Test
  void shouldMapOrderPlacedEventToInboundAllocateCommand() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(
        eventId, orderId, "SKU-1", 3, Instant.parse("2026-07-24T10:00:00Z"));

    consumer.consumeOrderingEvent(record(
        OutboxRoutes.ORDERING_ORDER_EVENTS, event, OrderPlacedIntegrationEvent.class.getSimpleName()));

    ArgumentCaptor<InboundCommand<AllocateOrderCommand>> inbound = inboundCaptor();
    verify(allocateOrderUsecase).handle(inbound.capture());
    assertThat(inbound.getValue().command().orderId()).isEqualTo(orderId);
    assertThat(inbound.getValue().message().eventId()).isEqualTo(eventId);
    assertThat(inbound.getValue().message().eventType())
        .isEqualTo(OrderPlacedIntegrationEvent.class.getSimpleName());
  }

  @Test
  void shouldMapOrderCancelledEventToInboundReleaseCommand() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    OrderCancelledIntegrationEvent event = new OrderCancelledIntegrationEvent(
        eventId, orderId, Instant.parse("2026-07-24T10:00:00Z"));

    consumer.consumeOrderingEvent(record(
        OutboxRoutes.ORDERING_ORDER_EVENTS, event, OrderCancelledIntegrationEvent.class.getSimpleName()));

    ArgumentCaptor<InboundCommand<ReleaseReservationCommand>> inbound = inboundCaptor();
    verify(releaseReservationUsecase).handle(inbound.capture());
    assertThat(inbound.getValue().command().orderId()).isEqualTo(orderId);
    assertThat(inbound.getValue().message().eventId()).isEqualTo(eventId);
  }

  @Test
  void shouldMapStockReplenishedEventToInboundReplenishCommand() throws Exception {
    UUID eventId = UUID.randomUUID();
    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(eventId, "SKU-1", 8);

    consumer.consumeInventoryEvent(record(
        OutboxRoutes.INVENTORY_STOCK_EVENTS, event, StockReplenishedIntegrationEvent.class.getSimpleName()));

    ArgumentCaptor<InboundCommand<ReplenishStockCommand>> inbound = inboundCaptor();
    verify(replenishmentUsecase).handle(inbound.capture());
    assertThat(inbound.getValue().command().sku()).isEqualTo("SKU-1");
    assertThat(inbound.getValue().command().quantity()).isEqualTo(8);
    assertThat(inbound.getValue().message().eventId()).isEqualTo(eventId);
  }

  @Test
  void shouldRejectMismatchedKafkaEventIdentity() throws Exception {
    OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(
        UUID.randomUUID(), UUID.randomUUID(), "SKU-1", 3, Instant.parse("2026-07-24T10:00:00Z"));
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        OutboxRoutes.ORDERING_ORDER_EVENTS, 0, 0, "key", objectMapper.writeValueAsString(event));
    record.headers().add("id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventType", OrderPlacedIntegrationEvent.class.getSimpleName()
        .getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> consumer.consumeOrderingEvent(record))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Kafka event ID header does not match payload");
  }

  @Test
  void shouldRejectRecordFromUnexpectedTopic() throws Exception {
    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(
        UUID.randomUUID(), "SKU-1", 8);

    assertThatThrownBy(() -> consumer.consumeOrderingEvent(record(
        OutboxRoutes.INVENTORY_STOCK_EVENTS,
        event,
        StockReplenishedIntegrationEvent.class.getSimpleName())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unexpected Kafka topic: " + OutboxRoutes.INVENTORY_STOCK_EVENTS);
  }

  private ConsumerRecord<String, String> record(String topic, Object event, String eventType) throws Exception {
    UUID eventId = ((com.flowzati.archone.common.integration.IntegrationEvent) event).getEventId();
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        topic, 0, 0, "key", objectMapper.writeValueAsString(event));
    record.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
    return record;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private <C> ArgumentCaptor<InboundCommand<C>> inboundCaptor() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(InboundCommand.class);
  }
}
