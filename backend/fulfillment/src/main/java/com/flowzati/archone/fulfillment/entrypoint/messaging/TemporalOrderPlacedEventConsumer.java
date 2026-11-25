package com.flowzati.archone.fulfillment.entrypoint.messaging;

import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingEventDestinations;
import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.invocation.StartOrderFulfillmentCommand;
import com.flowzati.archone.fulfillment.application.usecase.StartOrderFulfillmentUsecase;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal 模式：訂單成立後開始履約流程。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.TEMPORAL)
public class TemporalOrderPlacedEventConsumer {

    private final StartOrderFulfillmentUsecase usecase;

    public TemporalOrderPlacedEventConsumer(StartOrderFulfillmentUsecase usecase) {
        this.usecase = usecase;
    }

    @Bean
    IntegrationEventDispatcher temporalOrderPlacedDispatcher(IntegrationEventDispatcherFactory factory) {
        return factory.make(
                FulfillmentEventSubscriptions.WORKFLOW_ORDER_PLACEMENT,
                IntegrationEventHandlersBuilder.forDestination(OrderingEventDestinations.ORDER_EVENTS)
                        .onEvent(OrderPlacedIntegrationEvent.class, envelope -> onOrderPlaced(envelope.event()))
                        .build());
    }

    void onOrderPlaced(OrderPlacedIntegrationEvent event) {
        usecase.execute(new StartOrderFulfillmentCommand(event.getOrderId(), event.getReceivedAt()));
    }
}
