package com.flowzati.archone.messaging.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageMetadata;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IntegrationEventDispatcherTest {

  @Test
  void dispatchesWithoutSeeingAKafkaRecord() {
    UUID eventId = UUID.randomUUID();
    AtomicReference<MessageMetadata> handled = new AtomicReference<>();
    IntegrationEventDispatcher dispatcher = new IntegrationEventDispatcher(
        deserializerReturning(new TestEvent(eventId)), List.of(handler(handled)));

    dispatcher.dispatch(message(eventId, TestEvent.EVENT_TYPE), "order-events", "allocation");

    assertThat(handled.get()).isEqualTo(
        new MessageMetadata(eventId, TestEvent.EVENT_TYPE, "allocation"));
  }

  @Test
  void rejectsAnUnsupportedContractVersion() {
    UUID eventId = UUID.randomUUID();
    IntegrationEventDispatcher dispatcher = new IntegrationEventDispatcher(
        deserializerReturning(new TestEvent(eventId)), List.of(handler(new AtomicReference<>())));
    Message unsupported = MessageBuilder.from(message(eventId, TestEvent.EVENT_TYPE))
        .withHeader(EventMessageHeaders.EVENT_CONTRACT_VERSION, "2")
        .build();

    assertThatThrownBy(() -> dispatcher.dispatch(
        unsupported, "order-events", "allocation"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported Integration Event contract version: TestEvent.v1/2");
  }

  private IntegrationEventHandler<TestEvent> handler(AtomicReference<MessageMetadata> handled) {
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
        handled.set(metadata);
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

  private Message message(UUID id, String eventType) {
    return MessageBuilder.withPayload("{}")
        .withId(id)
        .withType(eventType)
        .withPartitionId("order-1")
        .withHeader(EventMessageHeaders.EVENT_TYPE, eventType)
        .withHeader(EventMessageHeaders.EVENT_CONTRACT_VERSION, "1")
        .build();
  }

  private static final class TestEvent extends IntegrationEvent {
    private static final String EVENT_TYPE = "TestEvent.v1";

    private TestEvent(UUID eventId) {
      super(eventId);
    }

    @Override
    public String eventType() {
      return EVENT_TYPE;
    }
  }
}
