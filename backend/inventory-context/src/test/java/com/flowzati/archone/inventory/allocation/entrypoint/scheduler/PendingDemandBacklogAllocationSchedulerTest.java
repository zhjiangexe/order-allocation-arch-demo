package com.flowzati.archone.inventory.allocation.entrypoint.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.inventory.allocation.application.usecase.PendingDemandBacklogAllocationUsecase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PendingDemandBacklogAllocationSchedulerTest {

    @Test
    @DisplayName("關閉 reconciliation 時即使 application 已啟用 scheduling 也不建立 scheduler bean")
    void shouldConditionTheSchedulerBeanInsteadOfTheSchedulingEngine() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(PendingDemandBacklogAllocationScheduler.class)
                .withBean(
                        PendingDemandBacklogAllocationUsecase.class,
                        () -> mock(PendingDemandBacklogAllocationUsecase.class));

        runner.withPropertyValues("archone.allocation.reconciliation-scheduler-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PendingDemandBacklogAllocationScheduler.class));
        runner.withPropertyValues("archone.allocation.reconciliation-scheduler-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(PendingDemandBacklogAllocationScheduler.class));
    }

    @Test
    @DisplayName("scheduler 只負責觸發 pending-demand backlog allocation use case")
    void shouldDelegateToThePendingDemandBacklogAllocationUsecase() {
        PendingDemandBacklogAllocationUsecase usecase = mock(PendingDemandBacklogAllocationUsecase.class);

        new PendingDemandBacklogAllocationScheduler(usecase).allocatePendingDemandBacklog();

        verify(usecase).execute();
    }
}
