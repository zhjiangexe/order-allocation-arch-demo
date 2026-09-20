package com.flowzati.archone.orderfulfillment.configuration;

import com.flowzati.archone.contracts.cancel.v1.CancellationEventDestinations;
import com.flowzati.archone.contracts.cancel.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentEventDestinations;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingEventDestinations;
import com.flowzati.archone.contracts.promising.v1.AllocationEventDestinations;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.orderfulfillment.entrypoint.messaging.TemporalFulfillmentEventBridge;
import io.temporal.client.WorkflowClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 組裝 Temporal fulfillment 的 integration-event subscriptions。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "temporal")
public class TemporalFulfillmentMessagingConfiguration {

    static final String SHIPMENT_CANCELLATION_SUBSCRIPTION = "temporal-shipment-cancellation";
    private static final String ORDER_PLACEMENT_SUBSCRIPTION = "allocation-ordering-events";
    private static final String FULFILLMENT_HANDOFF_SUBSCRIPTION = "wms-fulfillment-handoff";
    private static final String SHIPMENT_HANDOVER_SUBSCRIPTION = "inventory-shipment-handover";

    @Bean
    TemporalFulfillmentEventBridge temporalFulfillmentEventBridge(WorkflowClient workflowClient) {
        return new TemporalFulfillmentEventBridge(workflowClient);
    }

    @Bean
    IntegrationEventDispatcher temporalFulfillmentOrderStartDispatcher(
            IntegrationEventDispatcherFactory factory, TemporalFulfillmentEventBridge bridge) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        OrderingEventDestinations.ORDER_EVENTS)
                .onEvent(OrderPlacedIntegrationEvent.class, envelope -> bridge.startWorkflow(envelope.event()))
                .build();
        return factory.make(ORDER_PLACEMENT_SUBSCRIPTION, handlers);
    }

    @Bean
    IntegrationEventDispatcher temporalAllocationFactDispatcher(
            IntegrationEventDispatcherFactory factory, TemporalFulfillmentEventBridge bridge) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        AllocationEventDestinations.ALLOCATION_EVENTS)
                .onEvent(
                        OrderAllocationCommittedIntegrationEvent.class,
                        envelope -> bridge.signalStockOperationAssigned(envelope.event()))
                .build();
        return factory.make(FULFILLMENT_HANDOFF_SUBSCRIPTION, handlers);
    }

    @Bean
    IntegrationEventDispatcher temporalShipmentHandoverFactDispatcher(
            IntegrationEventDispatcherFactory factory, TemporalFulfillmentEventBridge bridge) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        FulfillmentEventDestinations.FULFILLMENT_HANDOFFS)
                .onEvent(
                        ShipmentHandedOverIntegrationEvent.class,
                        envelope -> bridge.signalShipmentHandedOver(envelope.event()))
                .build();
        return factory.make(SHIPMENT_HANDOVER_SUBSCRIPTION, handlers);
    }

    @Bean
    IntegrationEventDispatcher temporalShipmentCancellationFactDispatcher(
            IntegrationEventDispatcherFactory factory, TemporalFulfillmentEventBridge bridge) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        CancellationEventDestinations.SHIPMENT_EVENTS)
                .onEvent(
                        ShipmentCancelledIntegrationEvent.class,
                        envelope -> bridge.signalShipmentCancelled(envelope.event()))
                .build();
        return factory.make(SHIPMENT_CANCELLATION_SUBSCRIPTION, handlers);
    }
}
