package com.flowzati.archone.ordering.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.ordering.application.event.OrderCancelled;
import com.flowzati.archone.ordering.application.event.OrderPlaced;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderingIntegrationEventAdaptersTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID FACILITY_ID = UUID.randomUUID();
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-30T03:00:00Z");

    private final List<IntegrationEventPublication> publications = new ArrayList<>();
    private final OrderingPartitionKeyResolver partitionKeyResolver = new OrderingPartitionKeyResolver("order-id");

    @Test
    void adaptsOrderPlacedToItsIntegrationContract() {
        new OrderPlacedIntegrationEventAdapter(publications::add, partitionKeyResolver)
                .publish(new OrderPlaced(ORDER_ID, OWNER_ID, FACILITY_ID, OCCURRED_AT));

        assertThat(publications).singleElement().satisfies(publication -> {
            assertThat(publication.event()).isInstanceOf(OrderPlacedIntegrationEvent.class);
            assertThat(((OrderPlacedIntegrationEvent) publication.event()).getOrderId())
                    .isEqualTo(ORDER_ID);
            assertThat(publication.target().partitionKey()).isEqualTo(ORDER_ID.toString());
            assertThat(publication.occurredAt()).isEqualTo(OCCURRED_AT);
        });
    }

    @Test
    void adaptsOrderCancelledToItsIntegrationContract() {
        new OrderCancelledIntegrationEventAdapter(publications::add, partitionKeyResolver)
                .publish(new OrderCancelled(ORDER_ID, OWNER_ID, FACILITY_ID, OCCURRED_AT));

        assertThat(publications).singleElement().satisfies(publication -> {
            assertThat(publication.event()).isInstanceOf(OrderCancelledIntegrationEvent.class);
            assertThat(((OrderCancelledIntegrationEvent) publication.event()).getOrderId())
                    .isEqualTo(ORDER_ID);
            assertThat(publication.target().partitionKey()).isEqualTo(ORDER_ID.toString());
            assertThat(publication.occurredAt()).isEqualTo(OCCURRED_AT);
        });
    }
}
