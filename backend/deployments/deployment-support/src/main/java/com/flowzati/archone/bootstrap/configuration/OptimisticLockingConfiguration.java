package com.flowzati.archone.bootstrap.configuration;

import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingDecoratorConfiguration;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetrySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Applies the deployment's local retry policy to all enabled Integration Event consumers. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@Import(OptimisticLockingDecoratorConfiguration.class)
public class OptimisticLockingConfiguration {

    @Bean
    OptimisticLockingRetrySettings bootstrapOptimisticLockingRetrySettings() {
        return ConsumerFailurePolicy.optimisticLockingRetrySettings();
    }
}
