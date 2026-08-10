package com.flowzati.archone.bootstrap.messaging.consumer;

import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingDecoratorConfiguration;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetryObserver;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetrySettings;
import com.flowzati.archone.stock.infrastructure.messaging.consumer.retry.AllocationOptimisticLockRetryObserver;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Opts order-promising into the generic Tram-style optimistic-locking decorator.
 *
 * <p>The shared module owns retry mechanics. This application composition root owns the retry
 * budget and its Allocation-specific observations.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@Import(OptimisticLockingDecoratorConfiguration.class)
public class OrderPromisingOptimisticLockingConfiguration {

  @Bean
  OptimisticLockingRetrySettings orderPromisingOptimisticLockingRetrySettings() {
    return OrderPromisingConsumerFailurePolicy.optimisticLockingRetrySettings();
  }

  @Bean
  OptimisticLockingRetryObserver allocationOptimisticLockRetryObserver(
      MeterRegistry meterRegistry
  ) {
    return new AllocationOptimisticLockRetryObserver(meterRegistry);
  }
}
