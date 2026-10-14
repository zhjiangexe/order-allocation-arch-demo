package com.flowzati.archone.bootstrap.configuration;

import com.flowzati.archone.inventory.bootstrap.observability.AllocationOptimisticLockRetryObserver;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Adds Inventory-specific observations to the deployment's consumer retry mechanism. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
public class AllocationRetryObservationConfiguration {

    @Bean
    AllocationOptimisticLockRetryObserver allocationOptimisticLockRetryObserver(MeterRegistry meterRegistry) {
        return new AllocationOptimisticLockRetryObserver(meterRegistry);
    }
}
