package com.flowzati.archone.bootstrap.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingDecorator;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetryObserver;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetrySettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OptimisticLockingConfigurationTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(OptimisticLockingConfiguration.class);

    @Test
    void enablesLocalRetryWithoutInventoryOrAMetricsObserver() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OptimisticLockingDecorator.class);
            assertThat(context).doesNotHaveBean(OptimisticLockingRetryObserver.class);
            assertThat(context.getBean(OptimisticLockingRetrySettings.class))
                    .isEqualTo(new OptimisticLockingRetrySettings(2, Duration.ofMillis(100)));
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "archone.messaging.core.enabled=false",
                "archone.messaging.consumer.kafka.enabled=false",
                "archone.messaging.events.dispatcher.enabled=false"
            })
    void doesNotEnableRetryWhenIntegrationEventConsumptionIsDisabled(String property) {
        contextRunner
                .withPropertyValues(property)
                .run(context -> assertThat(context).doesNotHaveBean(OptimisticLockingDecorator.class));
    }
}
