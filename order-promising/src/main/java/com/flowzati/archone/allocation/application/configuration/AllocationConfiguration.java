package com.flowzati.archone.allocation.application.configuration;

import com.flowzati.archone.allocation.domain.service.AllocationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AllocationConfiguration {

  @Bean
  AllocationService allocationService() {
    return new AllocationService();
  }
}
