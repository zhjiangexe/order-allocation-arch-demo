package com.flowzati.archone.bootstrap.configuration;

import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 建立此 deployable 共用的 Temporal service connection 與 client。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.TEMPORAL)
public class TemporalClientConfiguration {

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs temporalWorkflowServiceStubs(
            @Value("${archone.temporal.target:localhost:7233}") String target) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
    }

    @Bean
    WorkflowClient temporalWorkflowClient(
            WorkflowServiceStubs serviceStubs, @Value("${archone.temporal.namespace:default}") String namespace) {
        return WorkflowClient.newInstance(
                serviceStubs,
                WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }
}
