package com.flowzati.archone.inventory.allocation.infrastructure.config;

import com.flowzati.archone.inventory.allocation.domain.service.MovementAssignmentPlanner;
import com.flowzati.archone.inventory.allocation.domain.service.StockAllocationPlanner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Outer-layer composition for pure allocation domain services. */
@Configuration(proxyBeanMethods = false)
public class StockAllocationDomainConfiguration {

    @Bean
    StockAllocationPlanner stockAllocationPlanner() {
        return new MovementAssignmentPlanner();
    }
}
