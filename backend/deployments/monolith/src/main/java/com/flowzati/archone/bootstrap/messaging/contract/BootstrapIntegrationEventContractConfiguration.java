package com.flowzati.archone.bootstrap.messaging.contract;

import com.flowzati.archone.contracts.fulfillment.v1.OutboundMovementsCompletedIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.inventory.v1.StockPickingLifecycleIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.MapBasedIntegrationEventNameMapping;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared producer/consumer allow-list for stable Integration Event wire types. */
@Configuration(proxyBeanMethods = false)
public class BootstrapIntegrationEventContractConfiguration {

    @Bean
    IntegrationEventNameMapping bootstrapIntegrationEventNameMapping() {
        return MapBasedIntegrationEventNameMapping.builder()
                .map(
                        OrderAllocationCommittedIntegrationEvent.class,
                        OrderAllocationCommittedIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION)
                .map(
                        com.flowzati.archone.contracts.promising.v2.OrderAllocationCommittedIntegrationEvent.class,
                        com.flowzati.archone.contracts.promising.v2.OrderAllocationCommittedIntegrationEvent.EVENT_TYPE,
                        com.flowzati.archone.contracts.promising.v2.OrderAllocationCommittedIntegrationEvent
                                .CONTRACT_VERSION)
                .map(
                        com.flowzati.archone.contracts.promising.v3.OrderAllocationCommittedIntegrationEvent.class,
                        com.flowzati.archone.contracts.promising.v3.OrderAllocationCommittedIntegrationEvent.EVENT_TYPE,
                        com.flowzati.archone.contracts.promising.v3.OrderAllocationCommittedIntegrationEvent
                                .CONTRACT_VERSION)
                .map(
                        OrderPlacedIntegrationEvent.class,
                        OrderPlacedIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION)
                .map(
                        OrderCancelledIntegrationEvent.class,
                        OrderCancelledIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION)
                .map(
                        StockAvailabilityIncreasedIntegrationEvent.class,
                        StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION)
                .map(
                        StockPickingLifecycleIntegrationEvent.class,
                        StockPickingLifecycleIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION)
                .map(
                        com.flowzati.archone.contracts.inventory.v2.StockOperationLifecycleIntegrationEvent.class,
                        com.flowzati.archone.contracts.inventory.v2.StockOperationLifecycleIntegrationEvent.EVENT_TYPE,
                        com.flowzati.archone.contracts.inventory.v2.StockOperationLifecycleIntegrationEvent
                                .CONTRACT_VERSION)
                .map(
                        ShipmentCancelledIntegrationEvent.class,
                        ShipmentCancelledIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION)
                .map(
                        com.flowzati.archone.contracts.fulfillment.v2.ShipmentCancelledIntegrationEvent.class,
                        com.flowzati.archone.contracts.fulfillment.v2.ShipmentCancelledIntegrationEvent.EVENT_TYPE,
                        com.flowzati.archone.contracts.fulfillment.v2.ShipmentCancelledIntegrationEvent
                                .CONTRACT_VERSION)
                .map(
                        com.flowzati.archone.contracts.fulfillment.v3.ShipmentCancelledIntegrationEvent.class,
                        com.flowzati.archone.contracts.fulfillment.v3.ShipmentCancelledIntegrationEvent.EVENT_TYPE,
                        com.flowzati.archone.contracts.fulfillment.v3.ShipmentCancelledIntegrationEvent
                                .CONTRACT_VERSION)
                .map(
                        ShipmentHandedOverIntegrationEvent.class,
                        ShipmentHandedOverIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION)
                .map(
                        com.flowzati.archone.contracts.fulfillment.v2.ShipmentHandedOverIntegrationEvent.class,
                        com.flowzati.archone.contracts.fulfillment.v2.ShipmentHandedOverIntegrationEvent.EVENT_TYPE,
                        com.flowzati.archone.contracts.fulfillment.v2.ShipmentHandedOverIntegrationEvent
                                .CONTRACT_VERSION)
                .map(
                        com.flowzati.archone.contracts.fulfillment.v3.ShipmentHandedOverIntegrationEvent.class,
                        com.flowzati.archone.contracts.fulfillment.v3.ShipmentHandedOverIntegrationEvent.EVENT_TYPE,
                        com.flowzati.archone.contracts.fulfillment.v3.ShipmentHandedOverIntegrationEvent
                                .CONTRACT_VERSION)
                .map(
                        OutboundMovementsCompletedIntegrationEvent.class,
                        OutboundMovementsCompletedIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION)
                .map(
                        com.flowzati.archone.contracts.fulfillment.v2.OutboundMovementsCompletedIntegrationEvent.class,
                        com.flowzati.archone.contracts.fulfillment.v2.OutboundMovementsCompletedIntegrationEvent
                                .EVENT_TYPE,
                        com.flowzati.archone.contracts.fulfillment.v2.OutboundMovementsCompletedIntegrationEvent
                                .CONTRACT_VERSION)
                .map(
                        com.flowzati.archone.contracts.fulfillment.v3.OutboundMovementsCompletedIntegrationEvent.class,
                        com.flowzati.archone.contracts.fulfillment.v3.OutboundMovementsCompletedIntegrationEvent
                                .EVENT_TYPE,
                        com.flowzati.archone.contracts.fulfillment.v3.OutboundMovementsCompletedIntegrationEvent
                                .CONTRACT_VERSION)
                .build();
    }
}
