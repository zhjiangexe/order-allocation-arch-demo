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
import com.flowzati.archone.common.messaging.IntegrationEventTopics;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
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
  @DisplayName("收到下單整合事件時應轉為配置訂單命令")
  void shouldMapOrderPlacedEventToInboundAllocateCommand() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(
        eventId, orderId, "SKU-1", 3, Instant.parse("2026-07-24T10:00:00Z"));

    consumer.consumeOrderingEvent(record(
        IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC, event, OrderPlacedIntegrationEvent.class.getSimpleName()));

    ArgumentCaptor<InboundCommand<AllocateOrderCommand>> inbound = inboundCaptor();
    verify(allocateOrderUsecase).handle(inbound.capture());
    assertThat(inbound.getValue().command().orderId()).isEqualTo(orderId);
    assertThat(inbound.getValue().message().eventId()).isEqualTo(eventId);
    assertThat(inbound.getValue().message().eventType())
        .isEqualTo(OrderPlacedIntegrationEvent.class.getSimpleName());
  }

  @Test
  @DisplayName("收到取消整合事件時應轉為釋放 Reservation 命令")
  void shouldMapOrderCancelledEventToInboundReleaseCommand() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    OrderCancelledIntegrationEvent event = new OrderCancelledIntegrationEvent(
        eventId, orderId, Instant.parse("2026-07-24T10:00:00Z"));

    consumer.consumeOrderingEvent(record(
        IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC, event, OrderCancelledIntegrationEvent.class.getSimpleName()));

    ArgumentCaptor<InboundCommand<ReleaseReservationCommand>> inbound = inboundCaptor();
    verify(releaseReservationUsecase).handle(inbound.capture());
    assertThat(inbound.getValue().command().orderId()).isEqualTo(orderId);
    assertThat(inbound.getValue().message().eventId()).isEqualTo(eventId);
  }

  @Test
  @DisplayName("收到補貨整合事件時應轉為補貨命令")
  void shouldMapStockReplenishedEventToInboundReplenishCommand() throws Exception {
    UUID eventId = UUID.randomUUID();
    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(eventId, "SKU-1", 8);

    consumer.consumeInventoryEvent(record(
        IntegrationEventTopics.INVENTORY_STOCK_EVENTS_TOPIC, event, StockReplenishedIntegrationEvent.class.getSimpleName()));

    ArgumentCaptor<InboundCommand<ReplenishStockCommand>> inbound = inboundCaptor();
    verify(replenishmentUsecase).handle(inbound.capture());
    assertThat(inbound.getValue().command().sku()).isEqualTo("SKU-1");
    assertThat(inbound.getValue().command().quantity()).isEqualTo(8);
    assertThat(inbound.getValue().message().eventId()).isEqualTo(eventId);
  }

  @Test
  @DisplayName("Kafka header 與 payload 的事件識別不一致時應拒絕")
  void shouldRejectMismatchedKafkaEventIdentity() throws Exception {
    OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(
        UUID.randomUUID(), UUID.randomUUID(), "SKU-1", 3, Instant.parse("2026-07-24T10:00:00Z"));
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC, 0, 0, "key", objectMapper.writeValueAsString(event));
    record.headers().add("id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventType", OrderPlacedIntegrationEvent.class.getSimpleName()
        .getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> consumer.consumeOrderingEvent(record))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Kafka event ID header does not match payload");
  }

  @Test
  @DisplayName("ordering topic 收到不支援事件時應拒絕")
  void shouldRejectEventNotHandledByOrderingTopic() throws Exception {
    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(
        UUID.randomUUID(), "SKU-1", 8);

    assertThatThrownBy(() -> consumer.consumeOrderingEvent(record(
        IntegrationEventTopics.INVENTORY_STOCK_EVENTS_TOPIC,
        event,
        StockReplenishedIntegrationEvent.class.getSimpleName())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported Kafka integration event: "
            + IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC + "/StockReplenishedIntegrationEvent");
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
