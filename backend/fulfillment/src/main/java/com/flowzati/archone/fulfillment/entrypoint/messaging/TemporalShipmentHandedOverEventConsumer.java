package com.flowzati.archone.fulfillment.entrypoint.messaging;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentEventDestinations;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.invocation.RecordShipmentHandedOverCommand;
import com.flowzati.archone.fulfillment.application.usecase.RecordShipmentHandedOverUsecase;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal 模式：把「出貨單已交給物流商」交給履約流程。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.TEMPORAL)
public class TemporalShipmentHandedOverEventConsumer {

    private final RecordShipmentHandedOverUsecase usecase;

    public TemporalShipmentHandedOverEventConsumer(RecordShipmentHandedOverUsecase usecase) {
        this.usecase = usecase;
    }

    @Bean
    IntegrationEventDispatcher temporalShipmentHandedOverDispatcher(IntegrationEventDispatcherFactory factory) {
        return factory.make(
                FulfillmentEventSubscriptions.WORKFLOW_SHIPMENT_HANDOVER,
                IntegrationEventHandlersBuilder.forDestination(FulfillmentEventDestinations.FULFILLMENT_HANDOFFS)
                        .onEvent(
                                ShipmentHandedOverIntegrationEvent.class,
                                envelope -> onShipmentHandedOver(envelope.event()))
                        .build());
    }

    void onShipmentHandedOver(ShipmentHandedOverIntegrationEvent event) {
        usecase.execute(new RecordShipmentHandedOverCommand(
                event.getOrderId(), event.getShipmentId(), event.getHandedOverAt()));
    }
}
