package com.flowzati.archone.bootstrap.microservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MicroserviceModeConfigurationTest {
    @Test
    void rejectsTemporalUntilThisDeploymentAssemblesWorkers() {
        new ApplicationContextRunner()
                .withUserConfiguration(MicroserviceModeConfiguration.class)
                .withPropertyValues("archone.fulfillment.orchestration-mode=temporal")
                .run(context -> assertThat(context).hasFailed());
    }
}
