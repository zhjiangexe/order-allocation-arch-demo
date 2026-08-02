package com.flowzati.archone.stock.application.configuration;

import com.flowzati.archone.stock.domain.service.AllocationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AllocationConfiguration {

  @Bean
  AllocationService allocationService() {
    return new AllocationService();
  }
}
