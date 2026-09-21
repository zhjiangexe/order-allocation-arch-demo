package com.flowzati.archone.messaging.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IntegrationEventHandlersBuilderTest {

    @Test
    void keepsTheRegistrationTypeOutOfTheApplicationApi() {
        assertThat(Modifier.isPublic(IntegrationEventHandlerRegistration.class.getModifiers()))
                .isFalse();
    }

    @Test
    void chainsHandlersAcrossDestinationsAndBuildsAnImmutableSnapshot() {
        IntegrationEventHandlersBuilder builder = IntegrationEventHandlersBuilder.forDestination("order-events")
                .onEvent(OrderEvent.class, envelope -> {})
                .andForDestination("inventory-events")
                .onEvent(StockEvent.class, envelope -> {});

        IntegrationEventHandlers first = builder.build();
        builder.andForDestination("audit-events").onEvent(OrderEvent.class, envelope -> {});

        assertThat(first.destinations()).containsExactly("order-events", "inventory-events");
        assertThat(first.find("order-events", OrderEvent.class)).isPresent();
        assertThat(first.find("inventory-events", StockEvent.class)).isPresent();
        assertThat(first.find("audit-events", OrderEvent.class)).isEmpty();
        assertThatThrownBy(() -> first.destinations().add("forged-events"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void permitsTheSameEventClassOnDifferentDestinations() {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination("order-events")
                .onEvent(OrderEvent.class, envelope -> {})
                .andForDestination("audit-events")
                .onEvent(OrderEvent.class, envelope -> {})
                .build();

        assertThat(handlers.find("order-events", OrderEvent.class)).isPresent();
        assertThat(handlers.find("audit-events", OrderEvent.class)).isPresent();
    }

    @Test
    void rejectsDuplicateDestinationAndEventClassAtRegistrationTime() {
        IntegrationEventHandlersBuilder builder = IntegrationEventHandlersBuilder.forDestination("order-events")
                .onEvent(OrderEvent.class, envelope -> {});

        assertThatThrownBy(() -> builder.onEvent(OrderEvent.class, envelope -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate Integration Event handler: order-events/" + OrderEvent.class.getName());
    }

    @Test
    void rejectsAnEmptyHandlerGroup() {
        assertThatThrownBy(() -> IntegrationEventHandlersBuilder.forDestination("order-events")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("At least one Integration Event handler is required");
    }

    private static final class OrderEvent extends IntegrationEvent {
        private OrderEvent(UUID eventId) {
            super(eventId);
        }

        @Override
        public String eventType() {
            return "OrderEvent.v1";
        }
    }

    private static final class StockEvent extends IntegrationEvent {
        private StockEvent(UUID eventId) {
            super(eventId);
        }

        @Override
        public String eventType() {
            return "StockEvent.v1";
        }
    }
}
