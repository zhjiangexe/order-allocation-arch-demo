package com.flowzati.archone.wms.outbound.entrypoint.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.wms.outbound.application.usecase.ProcessDueShipmentsUsecase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SimulatedWarehouseOperationsSchedulerTest {

    @Test
    void usesTheSameSchedulerByDefaultButStillAllowsTestsToDisableIt() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(SimulatedWarehouseOperationsScheduler.class)
                .withBean(ProcessDueShipmentsUsecase.class, () -> mock(ProcessDueShipmentsUsecase.class));

        runner.run(context -> assertThat(context).hasSingleBean(SimulatedWarehouseOperationsScheduler.class));
        runner.withPropertyValues("archone.wms.simulation.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SimulatedWarehouseOperationsScheduler.class));
    }

    @Test
    void delegatesEachSchedulerTickToTheDurableBatchUsecase() {
        ProcessDueShipmentsUsecase usecase = mock(ProcessDueShipmentsUsecase.class);

        new SimulatedWarehouseOperationsScheduler(usecase).processDueShipments();

        verify(usecase).execute();
    }
}
