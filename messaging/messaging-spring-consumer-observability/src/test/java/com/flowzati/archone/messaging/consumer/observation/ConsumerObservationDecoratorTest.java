package com.flowzati.archone.messaging.consumer.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHeaders;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.ProcessingOutcome;
import com.flowzati.archone.messaging.observation.ConsumerMessageObservationContext;
import com.flowzati.archone.messaging.observation.MessagingObservationNames;
import com.flowzati.archone.messaging.observation.MessagingObservationTags;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConsumerObservationDecoratorTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void observesEveryStableOutcomeWithoutAddingASecondHandlerPipeline() {
        for (ProcessingOutcome outcome : ProcessingOutcome.values()) {
            RecordingHandler handler = new RecordingHandler();
            ObservationRegistry registry = registry(handler);
            AtomicInteger handlerCalls = new AtomicInteger();
            MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(
                    List.of(new ConsumerObservationDecorator(registry)), ignored -> {
                        handlerCalls.incrementAndGet();
                        assertThat(registry.getCurrentObservation()).isNotNull();
                        return outcome;
                    });

            assertThat(chain.invokeNext(invocation())).isEqualTo(outcome);
            assertThat(handlerCalls).hasValue(1);
            assertThat(handler.stopped)
                    .singleElement()
                    .satisfies(context -> assertThat(tags(context.getLowCardinalityKeyValues()))
                            .containsEntry(MessagingObservationTags.OUTCOME, expectedTag(outcome)));
        }
    }

    @Test
    void recordsFailureAndRetainsOnlyBoundedValuesAsMetricTags() {
        RecordingHandler handler = new RecordingHandler();
        ObservationRegistry registry = registry(handler);
        IllegalStateException failure = new IllegalStateException("handler unavailable");
        MessageHandlerDecoratorChain chain =
                MessageHandlerDecoratorChain.create(List.of(new ConsumerObservationDecorator(registry)), ignored -> {
                    throw failure;
                });

        assertThatThrownBy(() -> chain.invokeNext(invocation())).isSameAs(failure);

        assertThat(handler.stopped).singleElement().satisfies(context -> {
            assertThat(context.getName()).isEqualTo(MessagingObservationNames.CONSUMER);
            assertThat(context.getError()).isSameAs(failure);
            assertThat(tags(context.getLowCardinalityKeyValues()))
                    .containsEntry(MessagingObservationTags.SUBSCRIBER_ID, "ordering")
                    .containsEntry(MessagingObservationTags.LOGICAL_DESTINATION, "order-events")
                    .containsEntry(MessagingObservationTags.MESSAGE_TYPE, "ordering.order-placed.v1")
                    .containsEntry(MessagingObservationTags.OUTCOME, "failed")
                    .containsEntry(MessagingObservationTags.EXCEPTION_TYPE, IllegalStateException.class.getName())
                    .doesNotContainKeys(
                            MessagingObservationTags.MESSAGE_ID,
                            MessagingObservationTags.PARTITION_ID,
                            MessagingObservationTags.CORRELATION_ID);
            assertThat(tags(context.getHighCardinalityKeyValues()))
                    .containsEntry(MessagingObservationTags.MESSAGE_ID, "00000000-0000-0000-0000-000000000301")
                    .containsEntry(MessagingObservationTags.PARTITION_ID, "order-301")
                    .containsEntry(MessagingObservationTags.CORRELATION_ID, "checkout-301");
        });
    }

    @Test
    void exposesInboundTraceCarrierAndCreatesADurationTimer() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry registry = ObservationRegistry.create();
        TraceReadingHandler traceReader = new TraceReadingHandler();
        registry.observationConfig()
                .observationHandler(traceReader)
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(
                List.of(new ConsumerObservationDecorator(registry)), ignored -> ProcessingOutcome.PROCESSED);

        chain.invokeNext(invocation());

        assertThat(traceReader.extractedTraceparent).isEqualTo(TRACEPARENT);
        Timer duration = meterRegistry
                .get(MessagingObservationNames.CONSUMER)
                .tag(MessagingObservationTags.OUTCOME, "processed")
                .timer();
        assertThat(duration.count()).isEqualTo(1);
    }

    private ObservationRegistry registry(RecordingHandler handler) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(handler);
        return registry;
    }

    private MessageHandlerInvocation invocation() {
        Message message = MessageBuilder.withPayload("{}")
                .withId(UUID.fromString("00000000-0000-0000-0000-000000000301"))
                .withType("ordering.order-placed.v1")
                .withPartitionId("order-301")
                .withMessageDate(Instant.parse("2026-08-10T00:00:00Z"))
                .withHeader(MessageHeaders.CORRELATION_ID, "checkout-301")
                .withHeader(MessageHeaders.TRACEPARENT, TRACEPARENT)
                .build();
        return new MessageHandlerInvocation(message, new MessageContext("ordering", "order-events", 1));
    }

    private String expectedTag(ProcessingOutcome outcome) {
        return switch (outcome) {
            case PROCESSED -> "processed";
            case DUPLICATE -> "duplicate";
            case IGNORED_UNHANDLED -> "ignored_unhandled";
        };
    }

    private static Map<String, String> tags(KeyValues keyValues) {
        Map<String, String> tags = new LinkedHashMap<>();
        keyValues.forEach(keyValue -> tags.put(keyValue.getKey(), keyValue.getValue()));
        return tags;
    }

    private static final class RecordingHandler implements ObservationHandler<Observation.Context> {
        private final List<Observation.Context> stopped = new ArrayList<>();

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(context);
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof ConsumerMessageObservationContext;
        }
    }

    private static final class TraceReadingHandler implements ObservationHandler<ConsumerMessageObservationContext> {
        private String extractedTraceparent;

        @Override
        public void onStart(ConsumerMessageObservationContext context) {
            extractedTraceparent = context.getGetter().get(context.getCarrier(), MessageHeaders.TRACEPARENT);
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof ConsumerMessageObservationContext;
        }
    }
}
