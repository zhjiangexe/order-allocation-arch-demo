package com.flowzati.archone.bootstrap.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingDecorator;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetryObserver;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetrySettings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BootstrapOptimisticLockingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BootstrapOptimisticLockingConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void optsIntoTheGenericDecoratorWithApplicationOwnedPolicyAndObserver() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OptimisticLockingDecorator.class);
            assertThat(context).hasSingleBean(OptimisticLockingRetryObserver.class);
            assertThat(context.getBean(OptimisticLockingRetrySettings.class))
                    .isEqualTo(new OptimisticLockingRetrySettings(2, Duration.ofMillis(100)));
        });
    }

    @Test
    void remainsInactiveWhenIntegrationEventConsumptionIsDisabled() {
        contextRunner.withPropertyValues("archone.messaging.core.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OptimisticLockingDecorator.class);
            assertThat(context).doesNotHaveBean(OptimisticLockingRetryObserver.class);
        });
    }
}
