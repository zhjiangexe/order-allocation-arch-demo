package com.flowzati.archone.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.allocation.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.translator.AllocationDomainEventTranslator;
import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.application.event.translator.OrderingDomainEventTranslator;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DomainEventTranslatorTest {

  private final Instant occurredAt = Instant.parse("2026-07-24T10:00:00Z");

  @Test
  void shouldTranslateOrderPlacedAndAppendOutboxRow() throws Exception {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new OrderingDomainEventTranslator(appender)
        .translate(new OrderPlaced(orderId, "SKU-1", 3, occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue()).satisfies(row -> {
      assertThat(row.aggregateType()).isEqualTo("Order");
      assertThat(row.aggregateId()).isEqualTo(orderId.toString());
      assertThat(row.eventType()).isEqualTo(OrderPlacedIntegrationEvent.class.getSimpleName());
      assertThat(row.occurredAt()).isEqualTo(occurredAt);
      assertThat(row.payload()).contains("\"orderId\":\"" + orderId + "\"");
    });
  }

  @Test
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
    assertThat(outbox.getValue().payload())
        .contains("\"reservationId\":\"" + reservationId + "\"");
  }
}
