package com.flowzati.archone.bootstrap.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.orderfulfillment.configuration.OrderFulfillmentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OrderFulfillmentConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(OrderFulfillmentConfiguration.class);

    @Test
    void defaultsToEventDrivenChoreography() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OrderFulfillmentProperties.class);
            assertThat(context.getBean(OrderFulfillmentProperties.class).orchestrationMode())
                    .isEqualTo(OrderFulfillmentProperties.Driver.EVENTS);
        });
    }

    @Test
    void acceptsTemporalAsTheAlternativeDriver() {
        contextRunner
                .withPropertyValues("archone.fulfillment.orchestration-mode=temporal")
                .run(context -> assertThat(context.getBean(OrderFulfillmentProperties.class)
                                .orchestrationMode())
                        .isEqualTo(OrderFulfillmentProperties.Driver.TEMPORAL));
    }

    @Test
    void rejectsAnUnknownModeInsteadOfSilentlyDisablingBothDrivers() {
        contextRunner
                .withPropertyValues("archone.fulfillment.orchestration-mode=typo")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("OrderFulfillmentProperties.Driver.typo"));
    }
}
