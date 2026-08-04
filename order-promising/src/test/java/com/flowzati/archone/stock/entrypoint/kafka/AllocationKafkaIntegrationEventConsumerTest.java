package com.flowzati.archone.stock.entrypoint.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.stock.application.command.AllocateOrderCommand;
import com.flowzati.archone.stock.application.command.CancelMovementsCommand;
import com.flowzati.archone.stock.application.command.ReplenishStockCommand;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.stock.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.stock.application.retry.AllocationRetryExecutor;
import com.flowzati.archone.stock.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.stock.application.usecase.CancelMovementsUsecase;
import com.flowzati.archone.stock.application.usecase.ReplenishmentUsecase;
import com.flowzati.archone.catalog.domain.model.LocationUsage;
import com.flowzati.archone.catalog.domain.model.StockLocation;
import com.flowzati.archone.catalog.domain.repository.StockLocationRepository;
import com.flowzati.archone.stock.infrastructure.retry.SpringAllocationRetryExecutor;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventDispatcher;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AllocationKafkaIntegrationEventConsumerTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final AllocateOrderUsecase allocateOrderUsecase = mock(AllocateOrderUsecase.class);
  private final CancelMovementsUsecase releaseReservationUsecase = mock(CancelMovementsUsecase.class);
  private final ReplenishmentUsecase replenishmentUsecase = mock(ReplenishmentUsecase.class);
  private final StockLocationRepository stockLocationRepository = mock(StockLocationRepository.class);
  private final AllocationRetryExecutor retryExecutor = new SpringAllocationRetryExecutor(new RetryTemplate(RetryPolicy.builder()
      .maxRetries(2)
      .delay(Duration.ZERO)
      .build()), new SimpleMeterRegistry());
  private final AllocationKafkaIntegrationEventConsumer consumer = new AllocationKafkaIntegrationEventConsumer(
      new KafkaIntegrationEventDispatcher(
          objectMapper,
          List.of(
              new OrderPlacedIntegrationEventHandler(allocateOrderUsecase, retryExecutor),
              new OrderCancelledIntegrationEventHandler(releaseReservationUsecase, retryExecutor),
              new StockReplenishedIntegrationEventHandler(
                  replenishmentUsecase, stockLocationRepository, retryExecutor))));

  @Test
  @DisplayName("收到下單整合事件時應轉為配置訂單命令")
  void shouldMapOrderPlacedEventToInboundAllocateCommand() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(
        eventId, orderId, Instant.parse("2026-07-24T10:00:00Z"));

    consumer.consumeOrderingEvent(record(
        OrderingEventTopics.ORDER_EVENTS, event, OrderPlacedIntegrationEvent.class.getSimpleName()));

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
        OrderingEventTopics.ORDER_EVENTS, event, OrderCancelledIntegrationEvent.class.getSimpleName()));

    ArgumentCaptor<InboundCommand<CancelMovementsCommand>> inbound = inboundCaptor();
    verify(releaseReservationUsecase).handle(inbound.capture());
    assertThat(inbound.getValue().command().orderId()).isEqualTo(orderId);
    assertThat(inbound.getValue().message().eventId()).isEqualTo(eventId);
  }

  @Test
  @DisplayName("收到補貨整合事件時應轉為補貨命令")
  void shouldMapStockReplenishedEventToInboundReplenishCommand() throws Exception {
    UUID eventId = UUID.randomUUID();
    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(eventId, com.flowzati.archone.testsupport.OrderFixtures.OWNER_ID, com.flowzati.archone.testsupport.OrderFixtures.FACILITY_ID, "SKU-1", java.time.LocalDate.of(2026, 1, 5), java.time.LocalDate.of(2026, 12, 31), 8);

    // 事件說倉，命令說位置——翻譯是 entrypoint 的職責，這裡是它唯一的依據。
    when(stockLocationRepository.findInternalOf(com.flowzati.archone.testsupport.OrderFixtures.FACILITY_ID))
        .thenReturn(java.util.Optional.of(new StockLocation(
            com.flowzati.archone.testsupport.OrderFixtures.LOCATION_ID,
            com.flowzati.archone.testsupport.OrderFixtures.FACILITY_ID,
            "WH-TEST/Stock", "測試倉／庫存", LocationUsage.INTERNAL)));

    consumer.consumeInventoryEvent(record(
        InventoryEventTopics.STOCK_EVENTS, event, StockReplenishedIntegrationEvent.class.getSimpleName()));

    ArgumentCaptor<InboundCommand<ReplenishStockCommand>> inbound = inboundCaptor();
    verify(replenishmentUsecase).handle(inbound.capture());
    assertThat(inbound.getValue().command().sku()).isEqualTo("SKU-1");
    assertThat(inbound.getValue().command().quantity()).isEqualTo(8);
    assertThat(inbound.getValue().message().eventId()).isEqualTo(eventId);
    // 倉原樣帶著（續做事件對外要說倉），位置是解析出來的
    assertThat(inbound.getValue().command().facilityId())
        .isEqualTo(com.flowzati.archone.testsupport.OrderFixtures.FACILITY_ID);
    assertThat(inbound.getValue().command().locationId())
        .isEqualTo(com.flowzati.archone.testsupport.OrderFixtures.LOCATION_ID);
  }

  @Test
  @DisplayName("Kafka header 與 payload 的事件識別不一致時應拒絕")
  void shouldRejectMismatchedKafkaEventIdentity() throws Exception {
    OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(
        UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-07-24T10:00:00Z"));
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        OrderingEventTopics.ORDER_EVENTS, 0, 0, "key", objectMapper.writeValueAsString(event));
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
    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(UUID.randomUUID(), com.flowzati.archone.testsupport.OrderFixtures.OWNER_ID, com.flowzati.archone.testsupport.OrderFixtures.FACILITY_ID, "SKU-1", java.time.LocalDate.of(2026, 1, 5), java.time.LocalDate.of(2026, 12, 31), 8);

    assertThatThrownBy(() -> consumer.consumeOrderingEvent(record(
        InventoryEventTopics.STOCK_EVENTS,
        event,
        StockReplenishedIntegrationEvent.class.getSimpleName())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported Kafka integration event: "
            + OrderingEventTopics.ORDER_EVENTS + "/StockReplenishedIntegrationEvent");
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
