package com.flowzati.archone.wms.process.entrypoint.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.wms.process.application.usecase.ProcessCancellingShipmentsInteractor;
import com.flowzati.archone.wms.process.application.usecase.ProcessDueShipmentsInteractor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SimulatedWarehouseOperationsSchedulerTest {

    @Test
    void usesTheSameSchedulerByDefaultButStillAllowsTestsToDisableIt() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(SimulatedWarehouseOperationsScheduler.class)
                .withBean(ProcessDueShipmentsInteractor.class, () -> mock(ProcessDueShipmentsInteractor.class))
                .withBean(
                        ProcessCancellingShipmentsInteractor.class,
                        () -> mock(ProcessCancellingShipmentsInteractor.class));

        runner.run(context -> assertThat(context).hasSingleBean(SimulatedWarehouseOperationsScheduler.class));
        runner.withPropertyValues("archone.wms.simulation.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SimulatedWarehouseOperationsScheduler.class));
    }

    @Test
    void delegatesEachSchedulerTickToTheDurableBatchUsecase() {
        ProcessDueShipmentsInteractor usecase = mock(ProcessDueShipmentsInteractor.class);
        ProcessCancellingShipmentsInteractor cancellationUsecase = mock(ProcessCancellingShipmentsInteractor.class);

        new SimulatedWarehouseOperationsScheduler(usecase, cancellationUsecase).processDueShipments();

        verify(cancellationUsecase).execute();
        verify(usecase).execute();
    }
}
