package com.flowzati.archone.stock.entrypoint.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.stock.application.command.AllocateOrderCommand;
import com.flowzati.archone.stock.application.command.CancelMovementsCommand;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.stock.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.stock.application.usecase.CancelMovementsUsecase;
import com.flowzati.archone.stock.application.usecase.AllocateWaitingDemandUsecase;
import com.flowzati.archone.messaging.events.JacksonIntegrationEventSerde;
import com.flowzati.archone.messaging.kafka.KafkaIntegrationEventDispatcher;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
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
  private final CancelMovementsUsecase releaseReservationUsecase = mock(CancelMovementsUsecase.class);
  private final AllocateWaitingDemandUsecase allocateWaitingDemandUsecase =
      mock(AllocateWaitingDemandUsecase.class);
  private final AllocationKafkaIntegrationEventConsumer consumer = new AllocationKafkaIntegrationEventConsumer(
      new KafkaIntegrationEventDispatcher(
          new JacksonIntegrationEventSerde(objectMapper),
          List.of(
              new OrderPlacedIntegrationEventHandler(allocateOrderUsecase),
              new OrderCancelledIntegrationEventHandler(releaseReservationUsecase),
              new StockAvailabilityIncreasedIntegrationEventHandler(
                  allocateWaitingDemandUsecase))));

  @Test
  @DisplayName("收到下單整合事件時應轉為配置訂單命令")
  void shouldMapOrderPlacedEventToAllocateCommand() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(
        eventId, orderId, Instant.parse("2026-07-24T10:00:00Z"));

    consumer.consumeOrderingEvent(record(
        OrderingEventTopics.ORDER_EVENTS, event, OrderPlacedIntegrationEvent.EVENT_TYPE));

    ArgumentCaptor<AllocateOrderCommand> command =
        ArgumentCaptor.forClass(AllocateOrderCommand.class);
    verify(allocateOrderUsecase).execute(command.capture());
    assertThat(command.getValue().orderId()).isEqualTo(orderId);
  }

  @Test
  @DisplayName("收到取消整合事件時應轉為釋放 Reservation 命令")
  void shouldMapOrderCancelledEventToCancelMovementsCommand() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    OrderCancelledIntegrationEvent event = new OrderCancelledIntegrationEvent(
        eventId, orderId, Instant.parse("2026-07-24T10:00:00Z"));

    consumer.consumeOrderingEvent(record(
        OrderingEventTopics.ORDER_EVENTS, event, OrderCancelledIntegrationEvent.EVENT_TYPE));

    ArgumentCaptor<CancelMovementsCommand> command =
        ArgumentCaptor.forClass(CancelMovementsCommand.class);
    verify(releaseReservationUsecase).execute(command.capture());
    assertThat(command.getValue().orderId()).isEqualTo(orderId);
  }

  @Test
  @DisplayName("收到可用庫存事件時應直接使用事件中的 location 執行配貨")
  void shouldMapAvailabilityIncreaseToAllocateWaitingDemandUsecase() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID ownerId = com.flowzati.archone.testsupport.OrderFixtures.OWNER_ID;
    UUID facilityId = com.flowzati.archone.testsupport.OrderFixtures.FACILITY_ID;
    UUID locationId = com.flowzati.archone.testsupport.OrderFixtures.LOCATION_ID;
    StockAvailabilityIncreasedIntegrationEvent event =
        new StockAvailabilityIncreasedIntegrationEvent(
            eventId, ownerId, facilityId, locationId, "SKU-1", 8);

    consumer.consumeInventoryEvent(record(
        InventoryEventTopics.STOCK_EVENTS,
        event,
        StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE));

    ArgumentCaptor<AllocateWaitingDemandCommand> command =
        ArgumentCaptor.forClass(AllocateWaitingDemandCommand.class);
    verify(allocateWaitingDemandUsecase).execute(command.capture());
    assertThat(command.getValue()).isEqualTo(
        new AllocateWaitingDemandCommand(ownerId, facilityId, locationId, "SKU-1"));
  }

  @Test
  @DisplayName("Kafka header 與 payload 的事件識別不一致時應拒絕")
  void shouldRejectMismatchedKafkaEventIdentity() throws Exception {
    OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(
        UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-07-24T10:00:00Z"));
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        OrderingEventTopics.ORDER_EVENTS, 0, 0, "key", objectMapper.writeValueAsString(event));
    record.headers().add("id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventType", OrderPlacedIntegrationEvent.EVENT_TYPE
        .getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> consumer.consumeOrderingEvent(record))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Kafka event ID header does not match payload");
  }

  private ConsumerRecord<String, String> record(String topic, Object event, String eventType) throws Exception {
    UUID eventId = ((com.flowzati.archone.messaging.events.IntegrationEvent) event).getEventId();
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        topic, 0, 0, "key", objectMapper.writeValueAsString(event));
    record.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
    return record;
  }
}
