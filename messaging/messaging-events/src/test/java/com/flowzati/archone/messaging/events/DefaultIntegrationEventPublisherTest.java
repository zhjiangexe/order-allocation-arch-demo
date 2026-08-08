package com.flowzati.archone.messaging.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageProducer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultIntegrationEventPublisherTest {

  @Test
  void convertsTheStableEventContractIntoAGenericMessage() {
    CapturingMessageProducer producer = new CapturingMessageProducer();
    IntegrationEventPublisher publisher = new DefaultIntegrationEventPublisher(
        producer,
        new JacksonIntegrationEventSerde(new ObjectMapper().findAndRegisterModules()));
    UUID eventId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-08T00:00:00Z");

    publisher.publish(
        new InternallyRenamedEvent(eventId, "order-1"),
        new AggregateReference("Order", "order-1"),
        new PublicationTarget("ordering.order-events", "order-1"),
        occurredAt);

    assertThat(producer.destination).isEqualTo("ordering.order-events");
    assertThat(producer.message).isEqualTo(new Message(
        eventId,
        "ordering.order-placed.v1",
        "Order",
        "order-1",
        "order-1",
        "{\"eventId\":\"" + eventId + "\",\"orderId\":\"order-1\"}",
        occurredAt));
  }

  private static final class CapturingMessageProducer implements MessageProducer {
    private String destination;
    private Message message;

    @Override
    public void send(String destination, Message message) {
      this.destination = destination;
      this.message = message;
    }
  }

  private static final class InternallyRenamedEvent extends IntegrationEvent {
    private final String orderId;

    private InternallyRenamedEvent(UUID eventId, String orderId) {
      super(eventId);
      this.orderId = orderId;
    }

    public String getOrderId() {
      return orderId;
    }

    @Override
    public String eventType() {
      return "ordering.order-placed.v1";
    }
  }
}
