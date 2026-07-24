package com.flowzati.archone.allocation.infrastructure.configuration;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryOperations;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.OptimisticLockingFailureException;

@Configuration
public class AllocationRetryConfiguration {

  @Bean
  RetryOperations allocationRetryOperations() {
    RetryPolicy retryPolicy = RetryPolicy.builder()
        .includes(OptimisticLockingFailureException.class)
        .maxRetries(2)
        .delay(Duration.ofMillis(100))
        .build();
    return new RetryTemplate(retryPolicy);
  }
}
