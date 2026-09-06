package com.flowzati.archone.wms.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.wms.dispatch.application.usecase.HandOverShipmentUsecase;
import com.flowzati.archone.wms.dispatch.application.usecase.PackShipmentUsecase;
import com.flowzati.archone.wms.dispatch.application.usecase.StageShipmentUsecase;
import com.flowzati.archone.wms.dispatch.infrastructure.persistence.jpa.repository.JpaShipmentDispatchRepository;
import com.flowzati.archone.wms.picking.application.usecase.ConfirmPickUsecase;
import com.flowzati.archone.wms.picking.infrastructure.persistence.jpa.repository.JpaPickingWorkRepository;
import com.flowzati.archone.wms.process.application.usecase.ProcessCancellingShipmentsUsecase;
import com.flowzati.archone.wms.process.application.usecase.ProcessDueShipmentsUsecase;
import com.flowzati.archone.wms.process.application.usecase.SimulateWarehouseOperationsUsecase;
import com.flowzati.archone.wms.receiving.application.usecase.ConfirmArrivalUsecase;
import com.flowzati.archone.wms.receiving.application.usecase.ConfirmPutawayUsecase;
import com.flowzati.archone.wms.receiving.application.usecase.RecordInspectionUsecase;
import com.flowzati.archone.wms.receiving.application.usecase.RegisterInboundOperationUsecase;
import com.flowzati.archone.wms.receiving.infrastructure.persistence.jpa.repository.JpaInboundOperationRepository;
import com.flowzati.archone.wms.shipment.application.service.LegacyAllocationPickingResolver;
import com.flowzati.archone.wms.shipment.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.shipment.application.usecase.CompleteShipmentCancellationUsecase;
import com.flowzati.archone.wms.shipment.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.shipment.application.usecase.GetOrderShipmentsUsecase;
import com.flowzati.archone.wms.shipment.infrastructure.persistence.jpa.repository.JpaShipmentRepository;
import com.flowzati.archone.wms.wave.application.usecase.CompleteWaveUsecase;
import com.flowzati.archone.wms.wave.application.usecase.PlanWaveUsecase;
import com.flowzati.archone.wms.wave.application.usecase.ReleaseWaveUsecase;
import com.flowzati.archone.wms.wave.infrastructure.persistence.jpa.repository.JpaWaveRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WmsApplicationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context ->
                    context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(WmsApplicationConfiguration.class)
            .withBean(LegacyAllocationPickingResolver.class, () -> mock(LegacyAllocationPickingResolver.class))
            .withBean(JpaInboundOperationRepository.class, () -> mock(JpaInboundOperationRepository.class))
            .withBean(JpaShipmentRepository.class, () -> mock(JpaShipmentRepository.class))
            .withBean(JpaWaveRepository.class, () -> mock(JpaWaveRepository.class))
            .withBean(JpaPickingWorkRepository.class, () -> mock(JpaPickingWorkRepository.class))
            .withBean(JpaShipmentDispatchRepository.class, () -> mock(JpaShipmentDispatchRepository.class))
            .withBean(IntegrationEventPublisher.class, () -> mock(IntegrationEventPublisher.class))
            .withBean(IntegrationEventDispatcherFactory.class, () -> mock(IntegrationEventDispatcherFactory.class))
            .withBean(BusinessClock.class, () -> mock(BusinessClock.class));

    @Test
    void exposesEveryWmsUsecaseAsASpringBean() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RegisterInboundOperationUsecase.class);
            assertThat(context).hasSingleBean(ConfirmArrivalUsecase.class);
            assertThat(context).hasSingleBean(RecordInspectionUsecase.class);
            assertThat(context).hasSingleBean(ConfirmPutawayUsecase.class);
            assertThat(context).hasSingleBean(CreateShipmentUsecase.class);
            assertThat(context).hasSingleBean(CancelShipmentUsecase.class);
            assertThat(context).hasSingleBean(CompleteShipmentCancellationUsecase.class);
            assertThat(context).hasSingleBean(GetOrderShipmentsUsecase.class);
            assertThat(context).hasSingleBean(PlanWaveUsecase.class);
            assertThat(context).hasSingleBean(ReleaseWaveUsecase.class);
            assertThat(context).hasSingleBean(CompleteWaveUsecase.class);
            assertThat(context).hasSingleBean(ConfirmPickUsecase.class);
            assertThat(context).hasSingleBean(PackShipmentUsecase.class);
            assertThat(context).hasSingleBean(StageShipmentUsecase.class);
            assertThat(context).hasSingleBean(HandOverShipmentUsecase.class);
            assertThat(context).hasSingleBean(SimulateWarehouseOperationsUsecase.class);
            assertThat(context).hasSingleBean(ProcessDueShipmentsUsecase.class);
            assertThat(context).hasSingleBean(ProcessCancellingShipmentsUsecase.class);
        });
    }
}
