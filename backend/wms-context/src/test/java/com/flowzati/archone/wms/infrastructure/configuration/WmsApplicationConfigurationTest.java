package com.flowzati.archone.wms.infrastructure.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.wms.inbound.application.store.InboundOperationStore;
import com.flowzati.archone.wms.inbound.application.usecase.ConfirmArrivalUsecase;
import com.flowzati.archone.wms.inbound.application.usecase.ConfirmPutawayUsecase;
import com.flowzati.archone.wms.inbound.application.usecase.RecordInspectionUsecase;
import com.flowzati.archone.wms.inbound.application.usecase.RegisterInboundOperationUsecase;
import com.flowzati.archone.wms.outbound.application.port.ShipmentCancelledPublisher;
import com.flowzati.archone.wms.outbound.application.port.ShipmentHandedOverPublisher;
import com.flowzati.archone.wms.outbound.application.store.ShipmentStore;
import com.flowzati.archone.wms.outbound.application.store.WaveStore;
import com.flowzati.archone.wms.outbound.application.usecase.CompleteShipmentCancellationUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.CompleteWaveUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.ConfirmPickUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.PackShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.PlanWaveUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.ProcessCancellingShipmentsUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.ReleaseWaveUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.SimulateWarehouseOperationsUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.StageShipmentUsecase;
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
            .withBean(ShipmentCancelledPublisher.class, () -> mock(ShipmentCancelledPublisher.class))
            .withBean(ShipmentHandedOverPublisher.class, () -> mock(ShipmentHandedOverPublisher.class))
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
