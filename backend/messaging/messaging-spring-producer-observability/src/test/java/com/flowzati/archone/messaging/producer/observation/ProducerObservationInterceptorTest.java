package com.flowzati.archone.messaging.producer.observation;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageHeaders;
import com.flowzati.archone.messaging.api.MessagePublicationContext;
import com.flowzati.archone.messaging.observation.MessagingObservationNames;
import com.flowzati.archone.messaging.observation.MessagingObservationTags;
import com.flowzati.archone.messaging.observation.ProducerMessageObservationContext;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProducerObservationInterceptorTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void observesOutboxAppendAndMakesInjectedTraceHeadersPersistable() {
        RecordingHandler handler = new RecordingHandler(true);
        ObservationRegistry registry = registry(handler);
        ProducerObservationInterceptor interceptor = new ProducerObservationInterceptor(registry);
        MessagePublicationContext publication = new MessagePublicationContext("order-events", "prod.order-events");

        Message traced = interceptor.preSend(message(), publication);
        assertThat(registry.getCurrentObservation()).isNotNull();
        interceptor.postSend(traced, publication, null);

        assertThat(registry.getCurrentObservation()).isNull();
        assertThat(traced.header(MessageHeaders.TRACEPARENT)).contains(TRACEPARENT);
        assertThat(handler.stopped).singleElement().satisfies(context -> {
            assertThat(context.getName()).isEqualTo(MessagingObservationNames.PRODUCER);
            assertThat(tags(context.getLowCardinalityKeyValues()))
                    .containsEntry(MessagingObservationTags.LOGICAL_DESTINATION, "order-events")
                    .containsEntry(MessagingObservationTags.MESSAGE_TYPE, "ordering.order-placed.v1")
                    .containsEntry(MessagingObservationTags.OUTCOME, "appended")
                    .containsEntry(MessagingObservationTags.EXCEPTION_TYPE, "none")
                    .doesNotContainKeys(
                            MessagingObservationTags.MESSAGE_ID,
                            MessagingObservationTags.PARTITION_ID,
                            MessagingObservationTags.CORRELATION_ID);
            assertThat(tags(context.getHighCardinalityKeyValues()))
                    .containsEntry(MessagingObservationTags.MESSAGE_ID, "00000000-0000-0000-0000-000000000201")
                    .containsEntry(MessagingObservationTags.PARTITION_ID, "order-201");
        });
    }

    @Test
    void recordsTheOriginalAppendFailure() {
        RecordingHandler handler = new RecordingHandler(false);
        ObservationRegistry registry = registry(handler);
        ProducerObservationInterceptor interceptor = new ProducerObservationInterceptor(registry);
        MessagePublicationContext publication = new MessagePublicationContext("order-events", "prod.order-events");
        IllegalStateException failure = new IllegalStateException("outbox unavailable");

        Message current = interceptor.preSend(message(), publication);
        interceptor.postSend(current, publication, failure);

        assertThat(handler.stopped).singleElement().satisfies(context -> {
            assertThat(context.getError()).isSameAs(failure);
            assertThat(tags(context.getLowCardinalityKeyValues()))
                    .containsEntry(MessagingObservationTags.OUTCOME, "failed")
                    .containsEntry(MessagingObservationTags.EXCEPTION_TYPE, IllegalStateException.class.getName());
        });
    }

    private ObservationRegistry registry(RecordingHandler handler) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(handler);
        return registry;
    }

    private Message message() {
        return MessageBuilder.withPayload("{}")
                .withId(UUID.fromString("00000000-0000-0000-0000-000000000201"))
                .withType("ordering.order-placed.v1")
                .withPartitionId("order-201")
                .withMessageDate(Instant.parse("2026-08-10T00:00:00Z"))
                .withHeader(MessageHeaders.LOGICAL_CHANNEL, "order-events")
                .withHeader(MessageHeaders.DESTINATION, "prod.order-events")
                .build();
    }

    private static Map<String, String> tags(KeyValues keyValues) {
        Map<String, String> tags = new LinkedHashMap<>();
        keyValues.forEach(keyValue -> tags.put(keyValue.getKey(), keyValue.getValue()));
        return tags;
    }

    private static final class RecordingHandler implements ObservationHandler<Observation.Context> {
        private final boolean injectTrace;
        private final List<Observation.Context> stopped = new ArrayList<>();

        private RecordingHandler(boolean injectTrace) {
            this.injectTrace = injectTrace;
        }

        @Override
        public void onStart(Observation.Context context) {
            if (injectTrace && context instanceof ProducerMessageObservationContext producerContext) {
                producerContext.getSetter().set(producerContext.getCarrier(), MessageHeaders.TRACEPARENT, TRACEPARENT);
            }
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(context);
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof ProducerMessageObservationContext;
        }
    }
}
