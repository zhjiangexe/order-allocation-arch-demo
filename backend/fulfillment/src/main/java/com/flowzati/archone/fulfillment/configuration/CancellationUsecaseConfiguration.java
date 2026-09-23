package com.flowzati.archone.fulfillment.configuration;

import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.port.CancellationRequestAcceptedPublisher;
import com.flowzati.archone.fulfillment.application.port.CancellationWorkflowPort;
import com.flowzati.archone.fulfillment.application.store.CancellationProcessStore;
import com.flowzati.archone.fulfillment.application.usecase.AcceptCancellationRequestUsecase;
import com.flowzati.archone.fulfillment.application.usecase.RequestWorkflowCancellationUsecase;
import com.flowzati.archone.fulfillment.infrastructure.temporal.TemporalCancellationRequestAdapter;
import io.temporal.client.WorkflowClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CancellationUsecaseConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
            havingValue = FulfillmentOrchestrationMode.EVENTS,
            matchIfMissing = true)
    AcceptCancellationRequestUsecase acceptCancellationRequestUsecase(
            CancellationRequestAcceptedPublisher publisher, CancellationProcessStore store) {
        return new AcceptCancellationRequestUsecase(publisher, store);
    }

    @Bean
    @ConditionalOnProperty(
            name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
            havingValue = FulfillmentOrchestrationMode.TEMPORAL)
    CancellationWorkflowPort cancellationWorkflowPort(WorkflowClient workflowClient) {
        return new TemporalCancellationRequestAdapter(workflowClient);
    }

    @Bean
    @ConditionalOnProperty(
            name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
            havingValue = FulfillmentOrchestrationMode.TEMPORAL)
    RequestWorkflowCancellationUsecase requestWorkflowCancellationUsecase(
            CancellationWorkflowPort cancellationWorkflowPort) {
        return new RequestWorkflowCancellationUsecase(cancellationWorkflowPort);
    }
}
