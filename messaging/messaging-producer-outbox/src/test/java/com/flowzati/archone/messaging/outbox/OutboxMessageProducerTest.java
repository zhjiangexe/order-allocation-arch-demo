package com.flowzati.archone.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.messaging.api.Message;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OutboxMessageProducerTest {

  @Test
  void persistsTheGenericMessageAsAnOutboxRow() {
    OutboxRepo repository = mock(OutboxRepo.class);
    OutboxMessageProducer producer = new OutboxMessageProducer(repository);
    UUID eventId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-08T00:00:00Z");

    producer.send("ordering.order-events", new Message(
        eventId,
        "OrderPlacedIntegrationEvent",
        "Order",
        "order-1",
        "warehouse-1",
        "{\"eventId\":\"" + eventId + "\"}",
        occurredAt));

    ArgumentCaptor<Outbox> row = ArgumentCaptor.forClass(Outbox.class);
    verify(repository).append(row.capture());
    assertThat(row.getValue()).isEqualTo(new Outbox(
        eventId,
        "Order",
        "order-1",
        "OrderPlacedIntegrationEvent",
        "ordering.order-events",
        "warehouse-1",
        "{\"eventId\":\"" + eventId + "\"}",
        occurredAt));
  }
}
