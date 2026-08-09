package com.flowzati.archone.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.MessageMetadata;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class KafkaIntegrationEventDispatcherTest {

  @Test
  void dispatchesByDestinationAndStableEventType() {
    UUID eventId = UUID.randomUUID();
    TestEvent event = new TestEvent(eventId);
    AtomicReference<MessageMetadata> handledMetadata = new AtomicReference<>();
    IntegrationEventHandler<TestEvent> handler = handler(handledMetadata);
    KafkaIntegrationEventDispatcher dispatcher = new KafkaIntegrationEventDispatcher(
        deserializerReturning(event), List.of(handler));

    dispatcher.dispatch(record(eventId, TestEvent.EVENT_TYPE), "order-events", "allocation");

    assertThat(handledMetadata.get()).isEqualTo(
        new MessageMetadata(eventId, TestEvent.EVENT_TYPE, "allocation"));
  }

  @Test
  void rejectsDuplicateDestinationAndEventTypeRegistrations() {
    IntegrationEventHandler<TestEvent> first = handler(new AtomicReference<>());
    IntegrationEventHandler<TestEvent> duplicate = handler(new AtomicReference<>());

    assertThatThrownBy(() -> new KafkaIntegrationEventDispatcher(
        deserializerReturning(new TestEvent(UUID.randomUUID())), List.of(first, duplicate)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Duplicate Integration Event handler: order-events/TestEvent.v1");
  }

  @Test
  void rejectsAnUnregisteredWireContract() {
    UUID eventId = UUID.randomUUID();
    KafkaIntegrationEventDispatcher dispatcher = new KafkaIntegrationEventDispatcher(
        deserializerReturning(new TestEvent(eventId)), List.of());

    assertThatThrownBy(() -> dispatcher.dispatch(
        record(eventId, TestEvent.EVENT_TYPE), "order-events", "allocation"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported Kafka integration event: order-events/TestEvent.v1");
  }

  @Test
  void rejectsAMissingMessageIdHeader() {
    UUID eventId = UUID.randomUUID();
    KafkaIntegrationEventDispatcher dispatcher = new KafkaIntegrationEventDispatcher(
        deserializerReturning(new TestEvent(eventId)), List.of(handler(new AtomicReference<>())));
    ConsumerRecord<String, String> record = record(eventId, TestEvent.EVENT_TYPE);
    record.headers().remove("id");

    assertThatThrownBy(() -> dispatcher.dispatch(record, "order-events", "allocation"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Missing Kafka header: id");
  }

  @Test
  void rejectsAMissingEventTypeHeader() {
    UUID eventId = UUID.randomUUID();
    KafkaIntegrationEventDispatcher dispatcher = new KafkaIntegrationEventDispatcher(
        deserializerReturning(new TestEvent(eventId)), List.of(handler(new AtomicReference<>())));
    ConsumerRecord<String, String> record = record(eventId, TestEvent.EVENT_TYPE);
    record.headers().remove("eventType");

    assertThatThrownBy(() -> dispatcher.dispatch(record, "order-events", "allocation"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Missing Kafka header: eventType");
  }

  @Test
  void rejectsAMessageIdThatDoesNotMatchThePayload() {
    UUID payloadEventId = UUID.randomUUID();
    KafkaIntegrationEventDispatcher dispatcher = new KafkaIntegrationEventDispatcher(
        deserializerReturning(new TestEvent(payloadEventId)),
        List.of(handler(new AtomicReference<>())));

    assertThatThrownBy(() -> dispatcher.dispatch(
        record(UUID.randomUUID(), TestEvent.EVENT_TYPE), "order-events", "allocation"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Kafka event ID header does not match payload");
  }

  @Test
  void rejectsAnEventTypeThatDoesNotMatchThePayloadContract() {
    UUID eventId = UUID.randomUUID();
    KafkaIntegrationEventDispatcher dispatcher = new KafkaIntegrationEventDispatcher(
        deserializerReturning(new TestEvent(eventId, "UnexpectedEvent.v1")),
        List.of(handler(new AtomicReference<>())));

    assertThatThrownBy(() -> dispatcher.dispatch(
        record(eventId, TestEvent.EVENT_TYPE), "order-events", "allocation"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Kafka event type header does not match payload contract");
  }

  private IntegrationEventHandler<TestEvent> handler(
      AtomicReference<MessageMetadata> handledMetadata
  ) {
    return new IntegrationEventHandler<>() {
      @Override
      public String destination() {
        return "order-events";
      }

      @Override
      public String eventType() {
        return TestEvent.EVENT_TYPE;
      }

      @Override
      public Class<TestEvent> eventClass() {
        return TestEvent.class;
      }

      @Override
      public void handleTyped(TestEvent event, MessageMetadata metadata) {
        handledMetadata.set(metadata);
      }
    };
  }

  private IntegrationEventDeserializer deserializerReturning(TestEvent event) {
    return new IntegrationEventDeserializer() {
      @Override
      public <E extends IntegrationEvent> E deserialize(String payload, Class<E> eventClass) {
        return eventClass.cast(event);
      }
    };
  }

  private ConsumerRecord<String, String> record(UUID eventId, String eventType) {
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("order-events", 0, 0L, "order-1", "{}");
    record.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
    return record;
  }

  private static final class TestEvent extends IntegrationEvent {
    private static final String EVENT_TYPE = "TestEvent.v1";
    private final String eventType;

    private TestEvent(UUID eventId) {
      this(eventId, EVENT_TYPE);
    }

    private TestEvent(UUID eventId, String eventType) {
      super(eventId);
      this.eventType = eventType;
    }

    @Override
    public String eventType() {
      return eventType;
    }
  }
}
