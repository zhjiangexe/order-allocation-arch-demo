package com.flowzati.archone.messaging.spring.optimisticlocking;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.retry.RetryOperations;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.OptimisticLockingFailureException;

/** Import-only Spring configuration for the optional optimistic-locking consumer capability. */
@Configuration(proxyBeanMethods = false)
public class OptimisticLockingDecoratorConfiguration {

    @Bean
    @Fallback
    public OptimisticLockingRetrySettings optimisticLockingRetrySettings() {
        return OptimisticLockingRetrySettings.defaults();
    }

    @Bean
    public OptimisticLockingDecorator optimisticLockingDecorator(
            OptimisticLockingRetrySettings settings, List<OptimisticLockingRetryObserver> observers) {
        return new OptimisticLockingDecorator(retryOperations(settings), observers);
    }

    private RetryOperations retryOperations(OptimisticLockingRetrySettings settings) {
        RetryPolicy retryPolicy = RetryPolicy.builder()
                .includes(OptimisticLockingFailureException.class)
                .maxRetries(settings.maxRetries())
                .delay(settings.delay())
                .build();
        return new RetryTemplate(retryPolicy);
    }
}
