package com.flowzati.archone.bootstrap.messaging.contract;

import com.flowzati.archone.contracts.fulfillment.v1.AllocationCommittedForFulfillmentIntegrationEvent;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.MapBasedIntegrationEventNameMapping;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared producer/consumer allow-list for stable Integration Event wire types. */
@Configuration(proxyBeanMethods = false)
public class OrderPromisingIntegrationEventContractConfiguration {

    @Bean
    IntegrationEventNameMapping orderPromisingIntegrationEventNameMapping() {
        return MapBasedIntegrationEventNameMapping.builder()
                .map(
                        OrderAllocatedIntegrationEvent.class,
                        OrderAllocatedIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION)
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
                        AllocationCommittedForFulfillmentIntegrationEvent.class,
                        AllocationCommittedForFulfillmentIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION)
                .build();
    }
}
