package com.flowzati.archone.bootstrap.configuration;

import com.flowzati.archone.contracts.fulfillment.v1.OutboundMovementsCompletedIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.inventory.v1.StockOperationLifecycleIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.MapBasedIntegrationEventNameMapping;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared producer/consumer allow-list for stable Integration Event wire types. */
@Configuration(proxyBeanMethods = false)
public class IntegrationEventContractConfiguration {

    @Bean
    IntegrationEventNameMapping bootstrapIntegrationEventNameMapping() {
        return MapBasedIntegrationEventNameMapping.builder()
                .map(
                        OrderAllocationCommittedIntegrationEvent.class,
                        OrderAllocationCommittedIntegrationEvent.EVENT_TYPE,
                        OrderAllocationCommittedIntegrationEvent.CONTRACT_VERSION)
                .map(
                        OrderPlacedIntegrationEvent.class,
                        OrderPlacedIntegrationEvent.EVENT_TYPE,
                        OrderPlacedIntegrationEvent.CONTRACT_VERSION)
                .map(
                        OrderCancelledIntegrationEvent.class,
                        OrderCancelledIntegrationEvent.EVENT_TYPE,
                        OrderCancelledIntegrationEvent.CONTRACT_VERSION)
                .map(
                        StockAvailabilityIncreasedIntegrationEvent.class,
                        StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE,
                        StockAvailabilityIncreasedIntegrationEvent.CONTRACT_VERSION)
                .map(
                        StockOperationLifecycleIntegrationEvent.class,
                        StockOperationLifecycleIntegrationEvent.EVENT_TYPE,
                        StockOperationLifecycleIntegrationEvent.CONTRACT_VERSION)
                .map(
                        ShipmentCancelledIntegrationEvent.class,
                        ShipmentCancelledIntegrationEvent.EVENT_TYPE,
                        ShipmentCancelledIntegrationEvent.CONTRACT_VERSION)
                .map(
                        ShipmentHandedOverIntegrationEvent.class,
                        ShipmentHandedOverIntegrationEvent.EVENT_TYPE,
                        ShipmentHandedOverIntegrationEvent.CONTRACT_VERSION)
                .map(
                        OutboundMovementsCompletedIntegrationEvent.class,
                        OutboundMovementsCompletedIntegrationEvent.EVENT_TYPE,
                        OutboundMovementsCompletedIntegrationEvent.CONTRACT_VERSION)
                .build();
    }
}
