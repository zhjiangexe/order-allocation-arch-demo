package com.flowzati.archone.inventory.allocation.infrastructure.config;

import com.flowzati.archone.inventory.allocation.domain.StockAllocationPlanner;
import com.flowzati.archone.inventory.allocation.domain.service.MovementAssignmentPlanner;
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
