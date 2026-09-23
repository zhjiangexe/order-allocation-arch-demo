package com.flowzati.archone.ordering.entrypoint.messaging;

import com.flowzati.archone.contracts.cancel.v1.CancellationEventDestinations;
import com.flowzati.archone.contracts.cancel.v1.OrderingCancellationRequestedIntegrationEvent;
import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.ordering.application.invocation.CancelOrderCommand;
import com.flowzati.archone.ordering.application.usecase.CompleteOrderCancellationRequestUsecase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.EVENTS,
        matchIfMissing = true)
public class OrderingCancellationRequestEventConsumer {
    private final CompleteOrderCancellationRequestUsecase complete;

    public OrderingCancellationRequestEventConsumer(CompleteOrderCancellationRequestUsecase complete) {
        this.complete = complete;
    }

    @Bean
    IntegrationEventDispatcher orderingCancellationRequestIntegrationEventDispatcher(
            IntegrationEventDispatcherFactory factory) {
        return factory.make(
                OrderingEventSubscriptions.CANCELLATION_REQUESTS,
                IntegrationEventHandlersBuilder.forDestination(
                                CancellationEventDestinations.ORDERING_CANCELLATION_REQUESTS)
                        .onEvent(OrderingCancellationRequestedIntegrationEvent.class, envelope -> {
                            var event = envelope.event();
                            complete.complete(new CancelOrderCommand(
                                    event.getRequestId(),
                                    event.getOrderId(),
                                    event.getRequestedAt(),
                                    event.getReason()));
                        })
                        .build());
    }
}
