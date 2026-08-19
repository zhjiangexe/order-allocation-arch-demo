package com.flowzati.archone.stock.allocation.application.configuration;

import com.flowzati.archone.stock.allocation.domain.service.AllocationDemandPlanner;
import com.flowzati.archone.stock.allocation.domain.service.AllocationFifoSelector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AllocationConfiguration {

  @Bean
  AllocationDemandPlanner allocationDemandPlanner() {
    return new AllocationDemandPlanner();
  }

  @Bean
  AllocationFifoSelector allocationFifoSelector() {
    return new AllocationFifoSelector();
  }
}
