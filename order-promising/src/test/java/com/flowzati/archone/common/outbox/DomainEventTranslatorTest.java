package com.flowzati.archone.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.allocation.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.translator.AllocationDomainEventTranslator;
import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.common.messaging.IntegrationEventTopics;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.application.event.translator.OrderingDomainEventTranslator;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DomainEventTranslatorTest {

  private final Instant occurredAt = Instant.parse("2026-07-24T10:00:00Z");

  @Test
  @DisplayName("下單 Domain Event 應翻譯並附加至 Outbox")
  void shouldTranslateOrderPlacedAndAppendOutboxRow() throws Exception {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new OrderingDomainEventTranslator(appender)
        .translate(new OrderPlaced(orderId, "SKU-1", 3, occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue()).satisfies(row -> {
      assertThat(row.aggregateType()).isEqualTo(OutboxAggregateTypes.ORDER);
      assertThat(row.aggregateId()).isEqualTo(orderId.toString());
      assertThat(row.eventType()).isEqualTo(OrderPlacedIntegrationEvent.class.getSimpleName());
      assertThat(row.route()).isEqualTo(IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC);
      assertThat(row.occurredAt()).isEqualTo(occurredAt);
      assertThat(row.payload()).contains("\"orderId\":\"" + orderId + "\"");
    });
  }

  @Test
  @DisplayName("完成配置事件應帶著 Reservation 明細翻譯為 Outbox 訊息")
  void shouldTranslateCompletedAllocationWithReservationDetails() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();

    new AllocationDomainEventTranslator(appender).translate(new OrderAllocationCompleted(
        orderId, reservationId, "SKU-1", 3, occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().eventType())
        .isEqualTo(OrderAllocatedIntegrationEvent.class.getSimpleName());
    assertThat(outbox.getValue().route()).isEqualTo(IntegrationEventTopics.PROMISING_ALLOCATION_EVENTS_TOPIC);
    assertThat(outbox.getValue().payload())
        .contains("\"reservationId\":\"" + reservationId + "\"");
  }
}
