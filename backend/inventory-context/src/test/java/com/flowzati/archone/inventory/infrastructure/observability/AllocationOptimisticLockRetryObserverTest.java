package com.flowzati.archone.inventory.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.inventory.adapter.AllocationOptimisticLockRetryObserver;
import com.flowzati.archone.inventory.allocation.entrypoint.ReservationAssignmentEventSubscriptions;
import com.flowzati.archone.inventory.allocation.entrypoint.ReservationIntakeEventSubscriptions;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class AllocationOptimisticLockRetryObserverTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AllocationOptimisticLockRetryObserver observer =
            new AllocationOptimisticLockRetryObserver(meterRegistry);

    @Test
    void recordsAllocationRetryAndExhaustionWithBusinessOperationNames() {
        MessageHandlerInvocation invocation = invocation(
                ReservationIntakeEventSubscriptions.ORDER_PLACEMENT_DRIVER, OrderPlacedIntegrationEvent.EVENT_TYPE);

        observer.onRetry(invocation, 2);
        observer.onRetry(invocation, 3);
        observer.onExhausted(invocation, 3, new OptimisticLockingFailureException("forced conflict"));

        assertThat(meterRegistry
                        .get(AllocationOptimisticLockRetryObserver.RETRY_ATTEMPT_METRIC)
                        .tag("operation", "allocate-order")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(meterRegistry
                        .get(AllocationOptimisticLockRetryObserver.RETRY_EXHAUSTED_METRIC)
                        .tag("operation", "allocate-order")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void keepsInventoryAvailabilityOperationClassification() {
        observer.onRetry(
                invocation(
                        ReservationAssignmentEventSubscriptions.INVENTORY_AVAILABILITY,
                        StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE),
                2);

        assertThat(meterRegistry
                        .get(AllocationOptimisticLockRetryObserver.RETRY_ATTEMPT_METRIC)
                        .tag("operation", "allocate-waiting-demand-after-availability-increase")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void ignoresNonAllocationSubscribers() {
        MessageHandlerInvocation invocation =
                invocation("other-context-subscriber", OrderPlacedIntegrationEvent.EVENT_TYPE);

        observer.onRetry(invocation, 2);
        observer.onExhausted(invocation, 3, new OptimisticLockingFailureException("forced conflict"));

        assertThat(meterRegistry
                        .find(AllocationOptimisticLockRetryObserver.RETRY_ATTEMPT_METRIC)
                        .counter())
                .isNull();
        assertThat(meterRegistry
                        .find(AllocationOptimisticLockRetryObserver.RETRY_EXHAUSTED_METRIC)
                        .counter())
                .isNull();
    }

    private MessageHandlerInvocation invocation(String subscriberId, String messageType) {
        return new MessageHandlerInvocation(
                MessageBuilder.withPayload("{}")
                        .withId(UUID.randomUUID())
                        .withType(messageType)
                        .withPartitionId("order-1")
                        .build(),
                new MessageContext(subscriberId, "test.events", 1));
    }
}
