package com.flowzati.archone.inventory.adapter;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.inventory.allocation.entrypoint.ReservationAssignmentEventSubscriptions;
import com.flowzati.archone.inventory.allocation.entrypoint.ReservationIntakeEventSubscriptions;
import com.flowzati.archone.inventory.movement.entrypoint.consumer.MovementCancellationEventSubscriptions;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetryObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

/** Adds Allocation operation names, metrics and logs to the generic retry mechanism. */
public final class AllocationOptimisticLockRetryObserver implements OptimisticLockingRetryObserver {

    public static final String RETRY_ATTEMPT_METRIC = "order_allocation_retry_attempts_total";
    public static final String RETRY_EXHAUSTED_METRIC = "order_allocation_retry_exhausted_total";

    private static final Logger LOGGER = LoggerFactory.getLogger(AllocationOptimisticLockRetryObserver.class);
    private static final Set<String> ALLOCATION_SUBSCRIBERS = Set.of(
            ReservationIntakeEventSubscriptions.ORDER_PLACEMENT_DRIVER,
            MovementCancellationEventSubscriptions.ORDER_CANCELLATIONS,
            ReservationAssignmentEventSubscriptions.INVENTORY_AVAILABILITY);

    private final MeterRegistry meterRegistry;

    public AllocationOptimisticLockRetryObserver(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "Meter registry is required");
    }

    @Override
    public void onRetry(MessageHandlerInvocation invocation, int attempt) {
        if (!observes(invocation)) {
            return;
        }
        Counter.builder(RETRY_ATTEMPT_METRIC)
                .tag("operation", operation(invocation.message().type()))
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void onExhausted(
            MessageHandlerInvocation invocation, int attempts, OptimisticLockingFailureException failure) {
        if (!observes(invocation)) {
            return;
        }
        String operation = operation(invocation.message().type());
        Counter.builder(RETRY_EXHAUSTED_METRIC)
                .tag("operation", operation)
                .register(meterRegistry)
                .increment();
        LOGGER.atError()
                .addKeyValue("operation", operation)
                .addKeyValue("eventId", invocation.message().id())
                .addKeyValue("subscriberId", invocation.context().subscriberId())
                .addKeyValue("attempts", attempts)
                .addKeyValue("exceptionType", failure.getClass().getSimpleName())
                .setCause(failure)
                .log("Allocation optimistic-lock retry exhausted");
    }

    private boolean observes(MessageHandlerInvocation invocation) {
        return ALLOCATION_SUBSCRIBERS.contains(invocation.context().subscriberId());
    }

    private String operation(String messageType) {
        return switch (messageType) {
            case OrderPlacedIntegrationEvent.EVENT_TYPE -> "allocate-order";
            case OrderCancelledIntegrationEvent.EVENT_TYPE -> "release-reservation";
            case StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE ->
                "allocate-waiting-demand-after-availability-increase";
            default -> "handle-allocation-message";
        };
    }
}
