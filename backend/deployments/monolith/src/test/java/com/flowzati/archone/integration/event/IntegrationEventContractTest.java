package com.flowzati.archone.integration.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntegrationEventContractTest {

    private final UUID eventId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final Instant occurredAt = Instant.parse("2026-07-23T00:00:00Z");

    @Test
    @DisplayName("Integration Event 應擁有不可變的事件識別")
    void integrationEventShouldOwnImmutableEventId() throws NoSuchFieldException {
        assertThat(Modifier.isFinal(
                        IntegrationEvent.class.getDeclaredField("eventId").getModifiers()))
                .isTrue();
        assertThat(IntegrationEvent.class.getMethods())
                .noneMatch(method -> method.getName().equals("setEventId"));
        assertThatThrownBy(() -> new OrderPlacedIntegrationEvent(null, orderId, occurredAt))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Ordering Integration Event 應提供完整契約欄位")
    void shouldExposeCompleteOrderingIntegrationEventContracts() {
        OrderPlacedIntegrationEvent placed = new OrderPlacedIntegrationEvent(eventId, orderId, occurredAt);
        OrderCancelledIntegrationEvent cancelled = new OrderCancelledIntegrationEvent(eventId, orderId, occurredAt);

        assertThat(placed.getEventId()).isEqualTo(eventId);
        assertThat(placed.getOrderId()).isEqualTo(orderId);
        assertThat(placed.getReceivedAt()).isEqualTo(occurredAt);
        assertThat(cancelled.getOrderId()).isEqualTo(orderId);
        assertThat(cancelled.getCancelledAt()).isEqualTo(occurredAt);
    }

    @Test
    @DisplayName("Allocation Integration Event 應提供完整契約欄位")
    void shouldExposeCompleteAllocationIntegrationEventContracts() {
        OrderAllocationCommittedIntegrationEvent allocated = allocationCommitted(occurredAt);

        assertThat(allocated.getOrderId()).isEqualTo(orderId);
        assertThat(allocated.getCommittedAt()).isEqualTo(occurredAt);
    }

    @Test
    @DisplayName("Integration Event 應拒絕不合法 payload")
    void shouldRejectInvalidIntegrationEventPayloads() {
        assertThatThrownBy(() -> new OrderPlacedIntegrationEvent(eventId, null, occurredAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allocationCommitted(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private OrderAllocationCommittedIntegrationEvent allocationCommitted(Instant committedAt) {
        return new OrderAllocationCommittedIntegrationEvent(
                eventId,
                UUID.randomUUID(),
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new OrderAllocationCommittedIntegrationEvent.AllocationLine(
                        UUID.randomUUID(), UUID.randomUUID(), "SKU-1", UUID.randomUUID(), 1)),
                occurredAt.plusSeconds(3600),
                50,
                committedAt);
    }
}
