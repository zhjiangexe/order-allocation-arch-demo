package com.flowzati.archone.fulfillment.entrypoint.messaging;

import com.flowzati.archone.contracts.cancel.v1.CancellationEventDestinations;
import com.flowzati.archone.contracts.cancel.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.invocation.RecordShipmentCancelledCommand;
import com.flowzati.archone.fulfillment.application.usecase.RecordShipmentCancelledUsecase;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal 模式：把「出貨單已取消」交給履約流程。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.TEMPORAL)
public class TemporalShipmentCancelledEventConsumer {

    private final RecordShipmentCancelledUsecase usecase;

    public TemporalShipmentCancelledEventConsumer(RecordShipmentCancelledUsecase usecase) {
        this.usecase = usecase;
    }

    @Bean
    IntegrationEventDispatcher temporalShipmentCancelledDispatcher(IntegrationEventDispatcherFactory factory) {
        return factory.make(
                FulfillmentEventSubscriptions.WORKFLOW_SHIPMENT_CANCELLATION,
                IntegrationEventHandlersBuilder.forDestination(CancellationEventDestinations.SHIPMENT_EVENTS)
                        .onEvent(
                                ShipmentCancelledIntegrationEvent.class,
                                envelope -> onShipmentCancelled(envelope.event()))
                        .build());
    }

    void onShipmentCancelled(ShipmentCancelledIntegrationEvent event) {
        usecase.execute(new RecordShipmentCancelledCommand(
                event.getOrderId(), event.getShipmentId(), event.getCancellationRequestId(), event.getCancelledAt()));
    }
}
