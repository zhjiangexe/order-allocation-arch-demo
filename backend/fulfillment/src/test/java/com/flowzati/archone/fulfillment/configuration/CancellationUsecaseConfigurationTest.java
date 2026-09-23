package com.flowzati.archone.fulfillment.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowzati.archone.fulfillment.application.port.CancellationRequestAcceptedPublisher;
import com.flowzati.archone.fulfillment.application.port.CancellationWorkflowPort;
import com.flowzati.archone.fulfillment.application.store.CancellationProcessStore;
import com.flowzati.archone.fulfillment.application.usecase.AcceptCancellationRequestUsecase;
import com.flowzati.archone.fulfillment.application.usecase.RequestWorkflowCancellationUsecase;
import com.flowzati.archone.fulfillment.infrastructure.temporal.TemporalCancellationRequestAdapter;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CancellationUsecaseConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CancellationUsecaseConfiguration.class)
            .withBean(
                    CancellationRequestAcceptedPublisher.class, () -> mock(CancellationRequestAcceptedPublisher.class))
            .withBean(CancellationProcessStore.class, () -> mock(CancellationProcessStore.class))
            .withBean(WorkflowClient.class, () -> mock(WorkflowClient.class));

    @Test
    void defaultsToTheEventDrivenUsecase() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AcceptCancellationRequestUsecase.class);
            assertThat(context).doesNotHaveBean(RequestWorkflowCancellationUsecase.class);
            assertThat(context).doesNotHaveBean(CancellationWorkflowPort.class);
        });
    }

    @Test
    void selectsTheTemporalAdapter() {
        contextRunner
                .withPropertyValues("archone.fulfillment.orchestration-mode=temporal")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AcceptCancellationRequestUsecase.class);
                    assertThat(context).hasSingleBean(RequestWorkflowCancellationUsecase.class);
                    assertThat(context).hasSingleBean(CancellationWorkflowPort.class);
                    assertThat(context.getBean(CancellationWorkflowPort.class))
                            .isInstanceOf(TemporalCancellationRequestAdapter.class);
                });
    }
}
