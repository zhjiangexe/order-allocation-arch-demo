package com.flowzati.archone.wms.infrastructure.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.wms.dispatch.application.port.ShipmentDispatchStatusChangedPublisher;
import com.flowzati.archone.wms.dispatch.application.port.ShipmentHandedOverPublisher;
import com.flowzati.archone.wms.dispatch.application.store.ShipmentDispatchStore;
import com.flowzati.archone.wms.dispatch.application.usecase.PackShipmentUsecase;
import com.flowzati.archone.wms.dispatch.application.usecase.StageShipmentUsecase;
import com.flowzati.archone.wms.picking.application.port.PickingWorkStatusChangedPublisher;
import com.flowzati.archone.wms.picking.application.store.PickingWorkStore;
import com.flowzati.archone.wms.picking.application.usecase.ConfirmPickUsecase;
import com.flowzati.archone.wms.process.application.usecase.ProcessCancellingShipmentsUsecase;
import com.flowzati.archone.wms.process.application.usecase.SimulateWarehouseOperationsUsecase;
import com.flowzati.archone.wms.receiving.application.store.InboundOperationStore;
import com.flowzati.archone.wms.receiving.application.usecase.ConfirmArrivalUsecase;
import com.flowzati.archone.wms.receiving.application.usecase.ConfirmPutawayUsecase;
import com.flowzati.archone.wms.receiving.application.usecase.RecordInspectionUsecase;
import com.flowzati.archone.wms.receiving.application.usecase.RegisterInboundOperationUsecase;
import com.flowzati.archone.wms.shipment.application.port.ShipmentCancellationCompletedPublisher;
import com.flowzati.archone.wms.shipment.application.port.ShipmentCancelledPublisher;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.application.usecase.CompleteShipmentCancellationUsecase;
import com.flowzati.archone.wms.wave.application.port.WaveReleasedPublisher;
import com.flowzati.archone.wms.wave.application.store.WaveStore;
import com.flowzati.archone.wms.wave.application.usecase.CompleteWaveUsecase;
import com.flowzati.archone.wms.wave.application.usecase.PlanWaveUsecase;
import com.flowzati.archone.wms.wave.application.usecase.ReleaseWaveUsecase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WmsApplicationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context ->
                    context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(WmsApplicationConfiguration.class)
            .withBean(InboundOperationStore.class, () -> mock(InboundOperationStore.class))
            .withBean(ShipmentStore.class, () -> mock(ShipmentStore.class))
            .withBean(WaveStore.class, () -> mock(WaveStore.class))
            .withBean(PickingWorkStore.class, () -> mock(PickingWorkStore.class))
            .withBean(ShipmentDispatchStore.class, () -> mock(ShipmentDispatchStore.class))
            .withBean(ShipmentCancelledPublisher.class, () -> mock(ShipmentCancelledPublisher.class))
            .withBean(
                    ShipmentCancellationCompletedPublisher.class,
                    () -> mock(ShipmentCancellationCompletedPublisher.class))
            .withBean(ShipmentHandedOverPublisher.class, () -> mock(ShipmentHandedOverPublisher.class))
            .withBean(WaveReleasedPublisher.class, () -> mock(WaveReleasedPublisher.class))
            .withBean(PickingWorkStatusChangedPublisher.class, () -> mock(PickingWorkStatusChangedPublisher.class))
            .withBean(
                    ShipmentDispatchStatusChangedPublisher.class,
                    () -> mock(ShipmentDispatchStatusChangedPublisher.class))
            .withBean(BusinessClock.class, () -> mock(BusinessClock.class));

    @Test
    void exposesEveryInboundAndWarehouseCheckpointAsASpringBean() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RegisterInboundOperationUsecase.class);
            assertThat(context).hasSingleBean(ConfirmArrivalUsecase.class);
            assertThat(context).hasSingleBean(RecordInspectionUsecase.class);
            assertThat(context).hasSingleBean(ConfirmPutawayUsecase.class);
            assertThat(context).hasSingleBean(PlanWaveUsecase.class);
            assertThat(context).hasSingleBean(ReleaseWaveUsecase.class);
            assertThat(context).hasSingleBean(CompleteWaveUsecase.class);
            assertThat(context).hasSingleBean(ConfirmPickUsecase.class);
            assertThat(context).hasSingleBean(CompleteShipmentCancellationUsecase.class);
            assertThat(context).hasSingleBean(ProcessCancellingShipmentsUsecase.class);
            assertThat(context).hasSingleBean(PackShipmentUsecase.class);
            assertThat(context).hasSingleBean(StageShipmentUsecase.class);
            assertThat(context).hasSingleBean(SimulateWarehouseOperationsUsecase.class);
        });
    }
}
