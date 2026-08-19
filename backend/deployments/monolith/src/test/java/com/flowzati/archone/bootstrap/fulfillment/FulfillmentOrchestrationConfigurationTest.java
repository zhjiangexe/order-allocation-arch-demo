package com.flowzati.archone.bootstrap.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FulfillmentOrchestrationConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(FulfillmentOrchestrationConfiguration.class);

    @Test
    void defaultsToEventDrivenChoreography() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(FulfillmentOrchestrationMode.class);
            assertThat(context.getBean(FulfillmentOrchestrationMode.class))
                    .isEqualTo(FulfillmentOrchestrationMode.EVENTS);
        });
    }

    @Test
    void acceptsTemporalAsTheAlternativeDriver() {
        contextRunner
                .withPropertyValues("archone.fulfillment.orchestration-mode=temporal")
                .run(context -> assertThat(context.getBean(FulfillmentOrchestrationMode.class))
                        .isEqualTo(FulfillmentOrchestrationMode.TEMPORAL));
    }

    @Test
    void rejectsAnUnknownModeInsteadOfSilentlyDisablingBothDrivers() {
        contextRunner
                .withPropertyValues("archone.fulfillment.orchestration-mode=typo")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("expected events or temporal"));
    }
}
