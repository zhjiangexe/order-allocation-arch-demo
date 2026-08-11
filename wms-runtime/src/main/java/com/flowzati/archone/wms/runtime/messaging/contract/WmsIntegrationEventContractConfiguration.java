package com.flowzati.archone.wms.runtime.messaging.contract;

import com.flowzati.archone.contracts.fulfillment.v1.AllocationCommittedForFulfillmentIntegrationEvent;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.MapBasedIntegrationEventNameMapping;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** WMS owns only the external contract types it consumes. */
@Configuration(proxyBeanMethods = false)
public class WmsIntegrationEventContractConfiguration {

  @Bean
  IntegrationEventNameMapping wmsIntegrationEventNameMapping() {
    return MapBasedIntegrationEventNameMapping.builder()
        .map(
            AllocationCommittedForFulfillmentIntegrationEvent.class,
            AllocationCommittedForFulfillmentIntegrationEvent.EVENT_TYPE,
            EventMessageHeaders.INITIAL_CONTRACT_VERSION)
        .build();
  }
}
