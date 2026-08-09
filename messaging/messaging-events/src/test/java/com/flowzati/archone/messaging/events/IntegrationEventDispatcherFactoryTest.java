package com.flowzati.archone.messaging.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageConsumer;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHandler;
import com.flowzati.archone.messaging.api.MessageSubscription;
import com.flowzati.archone.messaging.api.MessageSubscriptionOptions;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class IntegrationEventDispatcherFactoryTest {

  @Test
  void makesAndSubscribesOneExplicitHandlerGroupUsingTheTramFactoryShape() {
    UUID eventId = UUID.randomUUID();
    AtomicBoolean handled = new AtomicBoolean();
    CapturingMessageConsumer consumer = new CapturingMessageConsumer();
    IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder
        .forDestination("order-events")
        .onEvent(TestEvent.class, envelope -> handled.set(true))
        .andForDestination("audit-events")
        .onEvent(TestEvent.class, envelope -> { })
        .build();
    IntegrationEventDispatcherFactory factory = new IntegrationEventDispatcherFactory(
        consumer,
        deserializerReturning(new TestEvent(eventId)),
        mapping());

    IntegrationEventDispatcher dispatcher = factory.make("allocation", handlers);

    assertThat(dispatcher).isNotNull();
    assertThat(consumer.subscriberId).isEqualTo("allocation");
    assertThat(consumer.basicOverloadUsed).isTrue();
    assertThat(consumer.options.consumerGroupId()).isEmpty();
    assertThat(consumer.options.resolveConsumerGroupId("allocation")).isEqualTo("allocation");
    assertThat(consumer.channels)
        .containsExactly("order-events", "audit-events");

    consumer.emit(message(eventId), new MessageContext("allocation", "order-events", 1));
    assertThat(handled).isTrue();
  }

  @Test
  void failsBeforeStartingASubscriptionWhenAHandlerClassIsNotExplicitlyMapped() {
    CapturingMessageConsumer consumer = new CapturingMessageConsumer();
    IntegrationEventDispatcherFactory factory = new IntegrationEventDispatcherFactory(
        consumer,
        deserializerReturning(new TestEvent(UUID.randomUUID())),
        MapBasedIntegrationEventNameMapping.builder().build());
    IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder
        .forDestination("order-events")
        .onEvent(TestEvent.class, envelope -> { })
        .build();

    assertThatThrownBy(() -> factory.make("allocation", handlers))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unmapped Integration Event class: " + TestEvent.class.getName());
    assertThat(consumer.subscriberId).isNull();
  }

  @Test
  void keepsAnExplicitConsumerGroupSeparateFromTheSubscriberId() {
    CapturingMessageConsumer consumer = new CapturingMessageConsumer();
    IntegrationEventDispatcherFactory factory = new IntegrationEventDispatcherFactory(
        consumer,
        deserializerReturning(new TestEvent(UUID.randomUUID())),
        mapping());
    IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder
        .forDestination("order-events")
        .onEvent(TestEvent.class, envelope -> { })
        .build();

    factory.make(
        "allocation-inbox-scope",
        handlers,
        MessageSubscriptionOptions.withConsumerGroupId("allocation-kafka-group"));

    assertThat(consumer.subscriberId).isEqualTo("allocation-inbox-scope");
    assertThat(consumer.basicOverloadUsed).isFalse();
    assertThat(consumer.options.resolveConsumerGroupId(consumer.subscriberId))
        .isEqualTo("allocation-kafka-group");
  }

  @Test
  void passesTypedDispatchPolicyAndSubscriptionIdentityWithoutChangingTheTramBasicOverload() {
    CapturingMessageConsumer consumer = new CapturingMessageConsumer();
    IntegrationEventDispatcherFactory factory = new IntegrationEventDispatcherFactory(
        consumer,
        deserializerReturning(new TestEvent(UUID.randomUUID())),
        mapping());
    IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder
        .forDestination("order-events")
        .onEvent(TestEvent.class, envelope -> { })
        .build();
    AtomicBoolean observed = new AtomicBoolean();

    IntegrationEventDispatcher dispatcher = factory.make(
        "allocation-inbox-scope",
        handlers,
        IntegrationEventDispatcherOptions.builder()
            .subscriptionOptions(
                MessageSubscriptionOptions.withConsumerGroupId("allocation-kafka-group"))
            .ignoreUnhandledEventsWith(event -> observed.set(true))
            .build());
    dispatcher.dispatch(
        message(UUID.randomUUID()).withHeader(EventMessageHeaders.EVENT_CONTRACT_VERSION, "2"),
        "order-events");

    assertThat(consumer.basicOverloadUsed).isFalse();
    assertThat(consumer.options.resolveConsumerGroupId(consumer.subscriberId))
        .isEqualTo("allocation-kafka-group");
    assertThat(observed).isTrue();
  }

  private IntegrationEventNameMapping mapping() {
    return MapBasedIntegrationEventNameMapping.builder()
        .map(TestEvent.class, TestEvent.EVENT_TYPE, 1)
        .build();
  }

  private IntegrationEventDeserializer deserializerReturning(TestEvent event) {
    return new IntegrationEventDeserializer() {
      @Override
      public <E extends IntegrationEvent> E deserialize(String payload, Class<E> eventClass) {
        return eventClass.cast(event);
      }
    };
  }

  private Message message(UUID eventId) {
    return MessageBuilder.withPayload("{}")
        .withId(eventId)
        .withType(TestEvent.EVENT_TYPE)
        .withPartitionId("order-1")
        .withHeader(EventMessageHeaders.EVENT_TYPE, TestEvent.EVENT_TYPE)
        .withHeader(EventMessageHeaders.EVENT_AGGREGATE_TYPE, "Order")
        .withHeader(EventMessageHeaders.EVENT_AGGREGATE_ID, "order-1")
        .withHeader(EventMessageHeaders.EVENT_CONTRACT_VERSION, "1")
        .build();
  }

  private static final class CapturingMessageConsumer implements MessageConsumer {
    private String subscriberId;
    private Set<String> channels;
    private MessageSubscriptionOptions options;
    private MessageHandler handler;
    private boolean basicOverloadUsed;

    @Override
    public MessageSubscription subscribe(
        String subscriberId,
        Set<String> channels,
        MessageHandler handler
    ) {
      basicOverloadUsed = true;
      return capture(
          subscriberId,
          channels,
          handler,
          MessageSubscriptionOptions.defaults());
    }

    @Override
    public MessageSubscription subscribe(
        String subscriberId,
        Set<String> channels,
        MessageHandler handler,
        MessageSubscriptionOptions options
    ) {
      basicOverloadUsed = false;
      return capture(subscriberId, channels, handler, options);
    }

    private MessageSubscription capture(
        String subscriberId,
        Set<String> channels,
        MessageHandler handler,
        MessageSubscriptionOptions options
    ) {
      this.subscriberId = subscriberId;
      this.channels = channels;
      this.options = options;
      this.handler = handler;
      return new MessageSubscription() {
        @Override
        public boolean isRunning() {
          return true;
        }

        @Override
        public void stop() {
        }
      };
    }

    void emit(Message message, MessageContext context) {
      handler.handle(message, context);
    }
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
