package com.flowzati.archone.messaging.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.api.MessageHeaders;
import com.flowzati.archone.messaging.api.MessagePublicationContext;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.ProcessingOutcome;
import com.flowzati.archone.messaging.consumer.observation.ConsumerObservationDecorator;
import com.flowzati.archone.messaging.producer.observation.ProducerObservationInterceptor;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MessagingTracePropagationTest {

    @Test
    void persistsW3cHeadersAndContinuesTheTraceAcrossOutboxAndConsumer() {
        try (TracingFixture tracing = tracing()) {
            SpanContext upstreamContext = SpanContext.create(
                    "4bf92f3577b34da6a3ce929d0e0e4736",
                    "00f067aa0ba902b7",
                    TraceFlags.getSampled(),
                    TraceState.builder().put("vendor", "value").build());
            Span upstream = Span.wrap(upstreamContext);
            Message persisted;
            try (Scope ignored = upstream.makeCurrent()) {
                persisted = tracing.publish(message());
            }

            assertThat(persisted.requiredHeader(MessageHeaders.TRACEPARENT))
                    .startsWith("00-" + upstreamContext.getTraceId() + "-");
            assertThat(persisted.requiredHeader(MessageHeaders.TRACESTATE)).isEqualTo("vendor=value");

            String consumerTraceId = tracing.consume(persisted);
            assertThat(consumerTraceId).isEqualTo(upstreamContext.getTraceId());
        }
    }

    @Test
    void startsAnIndependentConsumerTraceWhenPropagationHeadersAreAbsent() {
        try (TracingFixture tracing = tracing()) {
            String consumerTraceId = tracing.consume(message());

            assertThat(consumerTraceId)
                    .hasSize(32)
                    .doesNotContainOnlyWhitespaces()
                    .doesNotMatch("0+");
        }
    }

    private TracingFixture tracing() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        io.opentelemetry.api.trace.Tracer otelTracer = openTelemetry.getTracer("archone-messaging-test");
        OtelCurrentTraceContext currentTraceContext = new OtelCurrentTraceContext();
        Tracer tracer = new OtelTracer(otelTracer, currentTraceContext, event -> {});
        OtelPropagator propagator = new OtelPropagator(openTelemetry.getPropagators(), otelTracer);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(new ObservationHandler.FirstMatchingCompositeObservationHandler(
                        new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                        new PropagatingSenderTracingObservationHandler<>(tracer, propagator)));
        return new TracingFixture(registry, tracer, tracerProvider);
    }

    private Message message() {
        return MessageBuilder.withPayload("{}")
                .withId(UUID.randomUUID())
                .withType("ordering.order-placed.v1")
                .withPartitionId("order-501")
                .withMessageDate(Instant.parse("2026-08-10T00:00:00Z"))
                .withHeader(MessageHeaders.LOGICAL_CHANNEL, "order-events")
                .withHeader(MessageHeaders.DESTINATION, "prod.order-events")
                .build();
    }

    private static final class TracingFixture implements AutoCloseable {
        private final ObservationRegistry registry;
        private final Tracer tracer;
        private final SdkTracerProvider tracerProvider;

        private TracingFixture(ObservationRegistry registry, Tracer tracer, SdkTracerProvider tracerProvider) {
            this.registry = registry;
            this.tracer = tracer;
            this.tracerProvider = tracerProvider;
        }

        private Message publish(Message message) {
            ProducerObservationInterceptor interceptor = new ProducerObservationInterceptor(registry);
            MessagePublicationContext publication = new MessagePublicationContext("order-events", "prod.order-events");
            Message current = interceptor.preSend(message, publication);
            interceptor.postSend(current, publication, null);
            return current;
        }

        private String consume(Message message) {
            AtomicReference<String> traceId = new AtomicReference<>();
            MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(
                    List.of(new ConsumerObservationDecorator(registry)), invocation -> {
                        traceId.set(tracer.currentSpan().context().traceId());
                        return ProcessingOutcome.PROCESSED;
                    });
            chain.invokeNext(new MessageHandlerInvocation(message, new MessageContext("ordering", "order-events", 1)));
            return traceId.get();
        }

        @Override
        public void close() {
            tracerProvider.close();
        }
    }
}
