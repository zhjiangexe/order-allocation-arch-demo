package com.flowzati.archone.messaging.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHandlingOutcome;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IntegrationEventDispatcherTest {

    @Test
    void dispatchesATramShapedEnvelopeWithoutSubscriberMetadata() {
        UUID eventId = UUID.randomUUID();
        TestEvent event = new TestEvent(eventId);
        AtomicReference<IntegrationEventEnvelope<TestEvent>> handled = new AtomicReference<>();
        IntegrationEventDispatcher dispatcher = dispatcher(deserializerReturning(event), handlers(handled), mapping());
        Message message = message(eventId, TestEvent.EVENT_TYPE, 1);

        dispatcher.handle(message, new MessageContext("allocation", "order-events", 3));

        assertThat(handled.get().message()).isSameAs(message);
        assertThat(handled.get().aggregateType()).isEqualTo("Order");
        assertThat(handled.get().aggregateId()).isEqualTo("order-1");
        assertThat(handled.get().eventId()).isEqualTo(eventId);
        assertThat(handled.get().event()).isSameAs(event);
    }

    @Test
    void routesTheSameEventClassIndependentlyForEachLogicalDestination() {
        UUID eventId = UUID.randomUUID();
        AtomicBoolean orderHandled = new AtomicBoolean();
        AtomicBoolean auditHandled = new AtomicBoolean();
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination("order-events")
                .onEvent(TestEvent.class, envelope -> orderHandled.set(true))
                .andForDestination("audit-events")
                .onEvent(TestEvent.class, envelope -> auditHandled.set(true))
                .build();
        IntegrationEventDispatcher dispatcher =
                dispatcher(deserializerReturning(new TestEvent(eventId)), handlers, mapping());

        dispatcher.dispatch(message(eventId, TestEvent.EVENT_TYPE, 1), "audit-events");

        assertThat(orderHandled).isFalse();
        assertThat(auditHandled).isTrue();
    }

    @Test
    void ignoresAndObservesAnUnsupportedTypeOrVersionBeforeDeserialization() {
        AtomicBoolean deserialized = new AtomicBoolean();
        AtomicReference<UnhandledIntegrationEvent> unhandled = new AtomicReference<>();
        IntegrationEventDispatcher dispatcher = new IntegrationEventDispatcher(
                trackingDeserializer(deserialized), handlers(new AtomicReference<>()), mapping(), unhandled::set);
        Message message = message(UUID.randomUUID(), TestEvent.EVENT_TYPE, 2);

        MessageHandlingOutcome outcome = dispatcher.dispatchWithOutcome(message, "order-events");

        assertThat(deserialized).isFalse();
        assertThat(outcome).isEqualTo(MessageHandlingOutcome.IGNORED_UNHANDLED);
        assertThat(unhandled.get())
                .isEqualTo(new UnhandledIntegrationEvent(
                        message,
                        "order-events",
                        TestEvent.EVENT_TYPE,
                        2,
                        UnhandledIntegrationEventReason.UNKNOWN_TYPE_VERSION));
    }

    @Test
    void ignoresAndObservesAMappedEventWithoutAHandlerForThisDestination() {
        AtomicBoolean deserialized = new AtomicBoolean();
        AtomicReference<UnhandledIntegrationEvent> unhandled = new AtomicReference<>();
        IntegrationEventNameMapping mapping = MapBasedIntegrationEventNameMapping.builder()
                .map(TestEvent.class, TestEvent.EVENT_TYPE, 1)
                .map(OtherEvent.class, OtherEvent.EVENT_TYPE, 1)
                .build();
        IntegrationEventDispatcher dispatcher = new IntegrationEventDispatcher(
                trackingDeserializer(deserialized), handlers(new AtomicReference<>()), mapping, unhandled::set);
        Message message = message(UUID.randomUUID(), OtherEvent.EVENT_TYPE, 1);

        MessageHandlingOutcome outcome = dispatcher.dispatchWithOutcome(message, "order-events");

        assertThat(deserialized).isFalse();
        assertThat(outcome).isEqualTo(MessageHandlingOutcome.IGNORED_UNHANDLED);
        assertThat(unhandled.get().reason()).isEqualTo(UnhandledIntegrationEventReason.NO_HANDLER_FOR_DESTINATION);
    }

    @Test
    void propagatesObserverFailureInsteadOfSilentlyAcknowledgingAnUnhandledEvent() {
        IllegalStateException failure = new IllegalStateException("metric unavailable");
        IntegrationEventDispatcher dispatcher = new IntegrationEventDispatcher(
                trackingDeserializer(new AtomicBoolean()), handlers(new AtomicReference<>()), mapping(), event -> {
                    throw failure;
                });

        assertThatThrownBy(() -> dispatcher.dispatch(message(UUID.randomUUID(), "FutureEvent.v1", 1), "order-events"))
                .isSameAs(failure);
    }

    @Test
    void requiresAggregateIdentityInsteadOfGuessingFromTheRecordKeyOrPayload() {
        UUID eventId = UUID.randomUUID();
        AtomicBoolean deserialized = new AtomicBoolean();
        IntegrationEventDispatcher dispatcher =
                dispatcher(trackingDeserializer(deserialized), handlers(new AtomicReference<>()), mapping());
        Message complete = message(eventId, TestEvent.EVENT_TYPE, 1);
        Map<String, String> headers = new LinkedHashMap<>(complete.headers());
        headers.remove(EventMessageHeaders.EVENT_AGGREGATE_ID);
        Message missingAggregateId = new Message(complete.payload(), headers);

        assertThatThrownBy(() -> dispatcher.dispatch(missingAggregateId, "order-events"))
                .isInstanceOf(IntegrationEventContractException.class)
                .hasMessage("Missing message header: event-aggregate-id");
        assertThat(deserialized).isFalse();
    }

    @Test
    void rejectsAMessageIdThatDoesNotMatchThePayload() {
        IntegrationEventDispatcher dispatcher = dispatcher(
                deserializerReturning(new TestEvent(UUID.randomUUID())), handlers(new AtomicReference<>()), mapping());

        assertThatThrownBy(
                        () -> dispatcher.dispatch(message(UUID.randomUUID(), TestEvent.EVENT_TYPE, 1), "order-events"))
                .isInstanceOf(IntegrationEventContractException.class)
                .hasMessage("Integration Event ID header does not match payload");
    }

    @Test
    void rejectsAnEventTypeThatDoesNotMatchThePayloadContract() {
        UUID eventId = UUID.randomUUID();
        IntegrationEventDispatcher dispatcher = dispatcher(
                deserializerReturning(new TestEvent(eventId, "UnexpectedEvent.v1")),
                handlers(new AtomicReference<>()),
                mapping());

        assertThatThrownBy(() -> dispatcher.dispatch(message(eventId, TestEvent.EVENT_TYPE, 1), "order-events"))
                .isInstanceOf(IntegrationEventContractException.class)
                .hasMessage("Integration Event type header does not match payload contract");
    }

    @Test
    void marksDeserializerInputFailuresAsContractFailures() {
        UUID eventId = UUID.randomUUID();
        IntegrationEventDispatcher dispatcher = dispatcher(
                failingDeserializer(new IllegalArgumentException("invalid JSON")),
                handlers(new AtomicReference<>()),
                mapping());

        assertThatThrownBy(() -> dispatcher.dispatch(message(eventId, TestEvent.EVENT_TYPE, 1), "order-events"))
                .isInstanceOf(IntegrationEventContractException.class)
                .hasMessage("invalid JSON")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void propagatesTheOriginalHandlerException() {
        UUID eventId = UUID.randomUUID();
        IllegalArgumentException failure = new IllegalArgumentException("business rejection");
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination("order-events")
                .onEvent(TestEvent.class, envelope -> {
                    throw failure;
                })
                .build();
        IntegrationEventDispatcher dispatcher =
                dispatcher(deserializerReturning(new TestEvent(eventId)), handlers, mapping());

        assertThatThrownBy(() -> dispatcher.dispatch(message(eventId, TestEvent.EVENT_TYPE, 1), "order-events"))
                .isSameAs(failure);
    }

    private IntegrationEventDispatcher dispatcher(
            IntegrationEventDeserializer deserializer,
            IntegrationEventHandlers handlers,
            IntegrationEventNameMapping mapping) {
        return new IntegrationEventDispatcher(deserializer, handlers, mapping, event -> {});
    }

    private IntegrationEventHandlers handlers(AtomicReference<IntegrationEventEnvelope<TestEvent>> handled) {
        return IntegrationEventHandlersBuilder.forDestination("order-events")
                .onEvent(TestEvent.class, handled::set)
                .build();
    }

    private IntegrationEventNameMapping mapping() {
        return MapBasedIntegrationEventNameMapping.builder()
                .map(TestEvent.class, TestEvent.EVENT_TYPE, 1)
                .build();
    }

    private IntegrationEventDeserializer deserializerReturning(IntegrationEvent event) {
        return new IntegrationEventDeserializer() {
            @Override
            public <E extends IntegrationEvent> E deserialize(String payload, Class<E> eventClass) {
                return eventClass.cast(event);
            }
        };
    }

    private IntegrationEventDeserializer trackingDeserializer(AtomicBoolean invoked) {
        return new IntegrationEventDeserializer() {
            @Override
            public <E extends IntegrationEvent> E deserialize(String payload, Class<E> eventClass) {
                invoked.set(true);
                throw new AssertionError("Deserializer should not be invoked");
            }
        };
    }

    private IntegrationEventDeserializer failingDeserializer(IllegalArgumentException failure) {
        return new IntegrationEventDeserializer() {
            @Override
            public <E extends IntegrationEvent> E deserialize(String payload, Class<E> eventClass) {
                throw failure;
            }
        };
    }

    private Message message(UUID id, String eventType, int contractVersion) {
        return MessageBuilder.withPayload("{}")
                .withId(id)
                .withType(eventType)
                .withPartitionId("order-1")
                .withHeader(EventMessageHeaders.EVENT_TYPE, eventType)
                .withHeader(EventMessageHeaders.EVENT_AGGREGATE_TYPE, "Order")
                .withHeader(EventMessageHeaders.EVENT_AGGREGATE_ID, "order-1")
                .withHeader(EventMessageHeaders.EVENT_CONTRACT_VERSION, Integer.toString(contractVersion))
                .build();
    }

    private static class TestEvent extends IntegrationEvent {
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

    private static final class OtherEvent extends IntegrationEvent {
        private static final String EVENT_TYPE = "OtherEvent.v1";

        private OtherEvent(UUID eventId) {
            super(eventId);
        }

        @Override
        public String eventType() {
            return EVENT_TYPE;
        }
    }
}
