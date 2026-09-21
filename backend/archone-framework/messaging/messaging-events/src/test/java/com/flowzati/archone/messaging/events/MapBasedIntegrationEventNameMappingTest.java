package com.flowzati.archone.messaging.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MapBasedIntegrationEventNameMappingTest {

    @Test
    void mapsExplicitStableTypeAndVersionInBothDirections() {
        MapBasedIntegrationEventNameMapping mapping = MapBasedIntegrationEventNameMapping.builder()
                .map(TestEvent.class, "public-order-placed", 3)
                .build();

        assertThat(mapping.externalTypeFor(TestEvent.class))
                .isEqualTo(new IntegrationEventDescriptor("public-order-placed", 3));
        assertThat(mapping.eventClassFor("public-order-placed", 3)).contains(TestEvent.class);
        assertThat(mapping.eventClassFor("public-order-placed", 2)).isEmpty();
    }

    @Test
    void doesNotDeriveAnUnmappedTypeFromAJavaClassName() {
        MapBasedIntegrationEventNameMapping mapping =
                MapBasedIntegrationEventNameMapping.builder().build();

        assertThatThrownBy(() -> mapping.externalTypeFor(TestEvent.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unmapped Integration Event class: " + TestEvent.class.getName());
    }

    @Test
    void rejectsDuplicateClassAndExternalTypeMappings() {
        MapBasedIntegrationEventNameMapping.Builder duplicateClass =
                MapBasedIntegrationEventNameMapping.builder().map(TestEvent.class, "event-a", 1);
        MapBasedIntegrationEventNameMapping.Builder duplicateType =
                MapBasedIntegrationEventNameMapping.builder().map(TestEvent.class, "shared-event", 1);

        assertThatThrownBy(() -> duplicateClass.map(TestEvent.class, "event-b", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate Integration Event class mapping: " + TestEvent.class.getName());
        assertThatThrownBy(() -> duplicateType.map(OtherEvent.class, "shared-event", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate Integration Event type mapping: shared-event/1");
    }

    private static final class TestEvent extends IntegrationEvent {
        private TestEvent(UUID eventId) {
            super(eventId);
        }

        @Override
        public String eventType() {
            return "not-used-by-the-mapping";
        }
    }

    private static final class OtherEvent extends IntegrationEvent {
        private OtherEvent(UUID eventId) {
            super(eventId);
        }

        @Override
        public String eventType() {
            return "also-not-used-by-the-mapping";
        }
    }
}
