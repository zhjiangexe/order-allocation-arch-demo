package com.flowzati.archone.inventory.reservation.assignment.entrypoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.inventory.allocation.application.usecase.StockOperationBacklogInteractor;
import com.flowzati.archone.inventory.allocation.entrypoint.schedule.StockOperationBacklogReconciliationScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StockOperationBacklogReconciliationSchedulerTest {

    @Test
    @DisplayName("disabled reconciliation does not create the operation scheduler bean")
    void conditionsTheSchedulerBeanInsteadOfTheSchedulingEngine() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(StockOperationBacklogReconciliationScheduler.class)
                .withBean(StockOperationBacklogInteractor.class, () -> mock(StockOperationBacklogInteractor.class));

        runner.withPropertyValues("archone.allocation.reconciliation-scheduler-enabled=false")
                .run(context ->
                        assertThat(context).doesNotHaveBean(StockOperationBacklogReconciliationScheduler.class));
        runner.withPropertyValues("archone.allocation.reconciliation-scheduler-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(StockOperationBacklogReconciliationScheduler.class));
    }

    @Test
    @DisplayName("scheduler delegates only to the confirmed-operation backlog usecase")
    void delegatesToPickingBacklogAssignment() {
        StockOperationBacklogInteractor usecase = mock(StockOperationBacklogInteractor.class);

        new StockOperationBacklogReconciliationScheduler(usecase).reconcileAssignmentBacklog();

        verify(usecase).execute();
    }
}
