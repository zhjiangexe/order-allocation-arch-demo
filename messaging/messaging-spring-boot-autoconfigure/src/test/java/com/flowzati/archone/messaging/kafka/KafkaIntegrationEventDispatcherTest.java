package com.flowzati.archone.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.MessageMetadata;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecorator;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.ProcessingOutcome;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventEnvelope;
import com.flowzati.archone.messaging.events.IntegrationEventHandler;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.messaging.events.MapBasedIntegrationEventNameMapping;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    ProcessingOutcome outcome = dispatcher.dispatch(
        record(eventId, TestEvent.EVENT_TYPE), "order-events", "allocation");

    assertThat(outcome).isEqualTo(ProcessingOutcome.PROCESSED);
    assertThat(handledMetadata.get()).isEqualTo(
        new MessageMetadata(eventId, TestEvent.EVENT_TYPE, "allocation"));
  }

  @Test
  void runsOneOrderedDecoratorChainAroundTypedDispatch() {
    UUID eventId = UUID.randomUUID();
    List<String> calls = new ArrayList<>();
    IntegrationEventHandler<TestEvent> handler = handler(
        new AtomicReference<>(), () -> calls.add("handler"));
    MessageHandlerDecorator retry = around(1_200, "retry", calls);
    MessageHandlerDecorator transaction = around(2_000, "transaction", calls);
    KafkaIntegrationEventDispatcher dispatcher = new KafkaIntegrationEventDispatcher(
        deserializerReturning(new TestEvent(eventId)),
        List.of(handler),
        encoded -> Map.of(),
        List.of(transaction, retry));

    ProcessingOutcome outcome = dispatcher.dispatch(
        record(eventId, TestEvent.EVENT_TYPE), "order-events", "allocation");

    assertThat(outcome).isEqualTo(ProcessingOutcome.PROCESSED);
    assertThat(calls).containsExactly(
        "retry-before",
        "transaction-before",
        "handler",
        "transaction-after",
        "retry-after");
  }

  @Test
  void duplicateOutcomeStopsBeforeTypedDispatch() {
    UUID eventId = UUID.randomUUID();
    AtomicReference<MessageMetadata> handledMetadata = new AtomicReference<>();
    MessageHandlerDecorator duplicate = new MessageHandlerDecorator() {
      @Override
      public int order() {
        return 2_000;
      }

      @Override
      public ProcessingOutcome handle(
          MessageHandlerInvocation invocation,
          MessageHandlerDecoratorChain chain
      ) {
        return ProcessingOutcome.DUPLICATE;
      }
    };
    KafkaIntegrationEventDispatcher dispatcher = new KafkaIntegrationEventDispatcher(
        deserializerReturning(new TestEvent(eventId)),
        List.of(handler(handledMetadata)),
        encoded -> Map.of(),
        List.of(duplicate));

    ProcessingOutcome outcome = dispatcher.dispatch(
        record(eventId, TestEvent.EVENT_TYPE), "order-events", "allocation");

    assertThat(outcome).isEqualTo(ProcessingOutcome.DUPLICATE);
    assertThat(handledMetadata).hasNullValue();
  }

  @Test
  void delegatesCurrentWireEnvelopeToTheNewBrokerNeutralDispatcher() {
    UUID eventId = UUID.randomUUID();
    TestEvent event = new TestEvent(eventId);
    AtomicReference<IntegrationEventEnvelope<TestEvent>> handled = new AtomicReference<>();
    IntegrationEventDispatcher typedDispatcher = new IntegrationEventDispatcher(
        deserializerReturning(event),
        IntegrationEventHandlersBuilder.forDestination("order-events")
            .onEvent(TestEvent.class, handled::set)
            .build(),
        MapBasedIntegrationEventNameMapping.builder()
            .map(TestEvent.class, TestEvent.EVENT_TYPE, 1)
            .build());
    KafkaIntegrationEventDispatcher bridge = new KafkaIntegrationEventDispatcher(
        encoded -> Map.of(
            EventMessageHeaders.EVENT_AGGREGATE_TYPE, "Order",
            EventMessageHeaders.EVENT_AGGREGATE_ID, "order-1",
            EventMessageHeaders.EVENT_CONTRACT_VERSION, "1"),
        typedDispatcher,
        List.of());
    ConsumerRecord<String, String> record = record(eventId, TestEvent.EVENT_TYPE);
    record.headers().add(
        KafkaMessageMapper.SERIALIZED_HEADERS,
        "{encoded}".getBytes(StandardCharsets.UTF_8));

    ProcessingOutcome outcome = bridge.dispatch(
        record, "order-events", "allocation");

    assertThat(outcome).isEqualTo(ProcessingOutcome.PROCESSED);
    assertThat(handled.get().event()).isSameAs(event);
    assertThat(handled.get().aggregateType()).isEqualTo("Order");
    assertThat(handled.get().aggregateId()).isEqualTo("order-1");
    assertThat(handled.get().message().id()).isEqualTo(eventId);
  }

  @Test
  void keepsTheHistoricalMissingAggregateMetadataBoundaryVisible() {
    UUID eventId = UUID.randomUUID();
    TestEvent event = new TestEvent(eventId);
    KafkaIntegrationEventDispatcher legacyBridge = new KafkaIntegrationEventDispatcher(
        deserializerReturning(event), List.of(handler(new AtomicReference<>())));
    IntegrationEventDispatcher typedDispatcher = new IntegrationEventDispatcher(
        deserializerReturning(event),
        IntegrationEventHandlersBuilder.forDestination("order-events")
            .onEvent(TestEvent.class, envelope -> { })
            .build(),
        MapBasedIntegrationEventNameMapping.builder()
            .map(TestEvent.class, TestEvent.EVENT_TYPE, 1)
            .build());
    KafkaIntegrationEventDispatcher newBridge = new KafkaIntegrationEventDispatcher(
        encoded -> Map.of(), typedDispatcher, List.of());
    ConsumerRecord<String, String> historical = record(eventId, TestEvent.EVENT_TYPE);

    assertThat(legacyBridge.dispatch(historical, "order-events", "allocation"))
        .isEqualTo(ProcessingOutcome.PROCESSED);
    assertThatThrownBy(() -> newBridge.dispatch(historical, "order-events", "allocation"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Missing message header: event-aggregate-type");
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
    return handler(handledMetadata, () -> {
    });
  }

  private IntegrationEventHandler<TestEvent> handler(
      AtomicReference<MessageMetadata> handledMetadata,
      Runnable onHandled
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
        onHandled.run();
        handledMetadata.set(metadata);
      }
    };
  }

  private MessageHandlerDecorator around(int order, String name, List<String> calls) {
    return new MessageHandlerDecorator() {
      @Override
      public int order() {
        return order;
      }

      @Override
      public ProcessingOutcome handle(
          MessageHandlerInvocation invocation,
          MessageHandlerDecoratorChain chain
      ) {
        calls.add(name + "-before");
        ProcessingOutcome outcome = chain.invokeNext(invocation);
        calls.add(name + "-after");
        return outcome;
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
