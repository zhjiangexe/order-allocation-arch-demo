package com.flowzati.archone.messaging.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHandler;
import com.flowzati.archone.messaging.api.MessageSubscription;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImpl;
import com.flowzati.archone.messaging.consumer.common.MessageConsumerImplementation;
import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import com.flowzati.archone.messaging.consumer.observation.ConsumerObservationDecorator;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.messaging.events.MapBasedIntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.UnhandledIntegrationEvent;
import com.flowzati.archone.messaging.observation.ConsumerMessageObservationContext;
import com.flowzati.archone.messaging.observation.MessagingObservationTags;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TypedIgnoredOutcomeObservationContractTest {

  @Test
  void carriesATypedIgnoredOutcomeThroughTheGenericConsumerObservationChain() {
    RecordingObservationHandler observations = new RecordingObservationHandler();
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(observations);
    CapturingConsumerImplementation transport = new CapturingConsumerImplementation();
    MessageConsumerImpl consumer = new MessageConsumerImpl(
        transport,
        logicalChannel -> logicalChannel,
        List.of(new ConsumerObservationDecorator(registry)));
    AtomicReference<UnhandledIntegrationEvent> ignored = new AtomicReference<>();
    IntegrationEventDispatcherFactory factory = new IntegrationEventDispatcherFactory(
        consumer,
        new IntegrationEventDeserializer() {
          @Override
          public <E extends IntegrationEvent> E deserialize(
              String payload,
              Class<E> eventClass
          ) {
            throw new AssertionError("An unknown event must not be deserialized");
          }
        },
        MapBasedIntegrationEventNameMapping.builder()
            .map(KnownEvent.class, KnownEvent.EVENT_TYPE, 1)
            .build(),
        ignored::set);

    factory.make(
        "allocation-ordering-events",
        IntegrationEventHandlersBuilder.forDestination("ordering.order-events")
            .onEvent(KnownEvent.class, envelope -> { })
            .build());
    Message message = MessageBuilder.withPayload("{}")
        .withId(UUID.randomUUID())
        .withType("ordering.address-changed.v1")
        .withPartitionId("order-1")
        .withHeader(EventMessageHeaders.EVENT_TYPE, "ordering.address-changed.v1")
        .withHeader(EventMessageHeaders.EVENT_CONTRACT_VERSION, "1")
        .build();

    transport.emit("ordering.order-events", message);

    assertThat(ignored.get()).isNotNull();
    assertThat(tags(observations.stopped.getLowCardinalityKeyValues()))
        .containsEntry(MessagingObservationTags.OUTCOME, "ignored_unhandled")
        .containsEntry(
            MessagingObservationTags.SUBSCRIBER_ID,
            "allocation-ordering-events");
  }

  private static Map<String, String> tags(KeyValues keyValues) {
    Map<String, String> tags = new LinkedHashMap<>();
    keyValues.forEach(keyValue -> tags.put(keyValue.getKey(), keyValue.getValue()));
    return tags;
  }

  private static final class KnownEvent extends IntegrationEvent {

    private static final String EVENT_TYPE = "ordering.order-placed.v1";

    private KnownEvent(UUID eventId) {
      super(eventId);
    }

    @Override
    public String eventType() {
      return EVENT_TYPE;
    }
  }

  private static final class RecordingObservationHandler
      implements ObservationHandler<ConsumerMessageObservationContext> {

    private ConsumerMessageObservationContext stopped;

    @Override
    public void onStop(ConsumerMessageObservationContext context) {
      stopped = context;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
      return context instanceof ConsumerMessageObservationContext;
    }
  }

  private static final class CapturingConsumerImplementation
      implements MessageConsumerImplementation {

    private ResolvedMessageSubscription subscription;
    private MessageHandler handler;

    @Override
    public MessageSubscription subscribe(
        ResolvedMessageSubscription subscription,
        MessageHandler handler
    ) {
      this.subscription = subscription;
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

    private void emit(String destination, Message message) {
      handler.handle(
          message,
          new MessageContext(
              subscription.subscriberId(),
              subscription.logicalChannelFor(destination),
              1));
    }
  }
}
