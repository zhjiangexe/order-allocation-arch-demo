package com.flowzati.archone.messaging.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventEnvelope;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class IntegrationEventHandlerTestFixtureTest {

    @Test
    void invokesAHandlerDirectlyWithACompleteTransportFreeEnvelope() {
        TestEvent event = new TestEvent(UUID.randomUUID());
        AtomicReference<IntegrationEventEnvelope<TestEvent>> handled = new AtomicReference<>();
        Consumer<IntegrationEventEnvelope<TestEvent>> handler = handled::set;

        handler.accept(IntegrationEventHandlerTestFixture.envelope(event, "Order", "order-1"));

        IntegrationEventEnvelope<TestEvent> envelope = handled.get();
        assertThat(envelope.event()).isSameAs(event);
        assertThat(envelope.eventId()).isEqualTo(event.getEventId());
        assertThat(envelope.aggregateType()).isEqualTo("Order");
        assertThat(envelope.aggregateId()).isEqualTo("order-1");
        assertThat(envelope.message().partitionId()).isEqualTo("order-1");
        assertThat(envelope.message().messageDate()).isEqualTo(MessageFixtures.MESSAGE_DATE);
        assertThat(envelope.message().requiredHeader(EventMessageHeaders.EVENT_CONTRACT_VERSION))
                .isEqualTo("1");
    }

    private static final class TestEvent extends IntegrationEvent {

        private TestEvent(UUID eventId) {
            super(eventId);
        }

        @Override
        public String eventType() {
            return "TestEvent.v1";
        }
    }
}
