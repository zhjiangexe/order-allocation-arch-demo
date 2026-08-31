package com.flowzati.archone.wms.infrastructure.configuration;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.wms.dispatch.application.port.ShipmentDispatchStatusChangedPublisher;
import com.flowzati.archone.wms.dispatch.application.store.ShipmentDispatchStore;
import com.flowzati.archone.wms.dispatch.application.usecase.HandOverShipmentUsecase;
import com.flowzati.archone.wms.dispatch.application.usecase.PackShipmentUsecase;
import com.flowzati.archone.wms.dispatch.application.usecase.StageShipmentUsecase;
import com.flowzati.archone.wms.picking.application.port.PickingWorkStatusChangedPublisher;
import com.flowzati.archone.wms.picking.application.store.PickingWorkStore;
import com.flowzati.archone.wms.picking.application.usecase.ConfirmPickUsecase;
import com.flowzati.archone.wms.process.application.usecase.ProcessCancellingShipmentsUsecase;
import com.flowzati.archone.wms.process.application.usecase.ProcessDueShipmentsUsecase;
import com.flowzati.archone.wms.process.application.usecase.SimulateWarehouseOperationsUsecase;
import com.flowzati.archone.wms.receiving.application.store.InboundOperationStore;
import com.flowzati.archone.wms.receiving.application.usecase.ConfirmArrivalUsecase;
import com.flowzati.archone.wms.receiving.application.usecase.ConfirmPutawayUsecase;
import com.flowzati.archone.wms.receiving.application.usecase.RecordInspectionUsecase;
import com.flowzati.archone.wms.receiving.application.usecase.RegisterInboundOperationUsecase;
import com.flowzati.archone.wms.shipment.application.port.ShipmentCancellationCompletedPublisher;
import com.flowzati.archone.wms.shipment.application.port.ShipmentCancelledPublisher;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.shipment.application.usecase.CompleteShipmentCancellationUsecase;
import com.flowzati.archone.wms.shipment.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.shipment.application.usecase.GetOrderShipmentsUsecase;
import com.flowzati.archone.wms.wave.application.port.WaveReleasedPublisher;
import com.flowzati.archone.wms.wave.application.store.WaveStore;
import com.flowzati.archone.wms.wave.application.usecase.CompleteWaveUsecase;
import com.flowzati.archone.wms.wave.application.usecase.PlanWaveUsecase;
import com.flowzati.archone.wms.wave.application.usecase.ReleaseWaveUsecase;
import com.flowzati.archone.wms.wave.domain.service.WavePlanner;
import com.flowzati.archone.wms.wave.domain.service.impl.PriorityCapacityWavePlanner;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires pure WMS application services to runtime adapters. */
@Configuration(proxyBeanMethods = false)
public class WmsApplicationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WmsApplicationConfiguration.class);

    public WmsApplicationConfiguration() {
        log.info("WMS capabilities enabled: receiving, shipment, wave, picking and dispatch");
    }

    @Bean
    RegisterInboundOperationUsecase registerInboundOperationUsecase(InboundOperationStore repository) {
        return new RegisterInboundOperationUsecase(repository);
    }

    @Bean
    ConfirmArrivalUsecase confirmArrivalUsecase(InboundOperationStore repository) {
        return new ConfirmArrivalUsecase(repository);
    }

    @Bean
    RecordInspectionUsecase recordInspectionUsecase(InboundOperationStore repository) {
        return new RecordInspectionUsecase(repository);
    }

    @Bean
    ConfirmPutawayUsecase confirmPutawayUsecase(InboundOperationStore repository) {
        return new ConfirmPutawayUsecase(repository);
    }

    @Bean
    CreateShipmentUsecase createShipmentUsecase(ShipmentStore shipmentStore) {
        return new CreateShipmentUsecase(shipmentStore);
    }

    @Bean
    CancelShipmentUsecase cancelShipmentUsecase(
            ShipmentStore shipmentStore,
            ShipmentCancelledPublisher shipmentCancelledPublisher,
            ShipmentCancellationCompletedPublisher shipmentCancellationCompletedPublisher,
            BusinessClock appClock) {
        return new CancelShipmentUsecase(
                shipmentStore, shipmentCancelledPublisher, shipmentCancellationCompletedPublisher, appClock);
    }

    @Bean
    CompleteShipmentCancellationUsecase completeShipmentCancellationUsecase(
            ShipmentStore shipmentStore,
            ShipmentCancelledPublisher shipmentCancelledPublisher,
            ShipmentCancellationCompletedPublisher shipmentCancellationCompletedPublisher) {
        return new CompleteShipmentCancellationUsecase(
                shipmentStore, shipmentCancelledPublisher, shipmentCancellationCompletedPublisher);
    }

    @Bean
    HandOverShipmentUsecase handOverShipmentUsecase(
            ShipmentDispatchStore shipmentDispatchStore,
            ShipmentDispatchStatusChangedPublisher shipmentDispatchStatusChangedPublisher) {
        return new HandOverShipmentUsecase(shipmentDispatchStore, shipmentDispatchStatusChangedPublisher);
    }

    @Bean
    GetOrderShipmentsUsecase getOrderShipmentsUsecase(ShipmentStore shipmentStore, PickingWorkStore pickingWorkStore) {
        return new GetOrderShipmentsUsecase(shipmentStore, pickingWorkStore);
    }

    @Bean
    WavePlanner wavePlanner() {
        return new PriorityCapacityWavePlanner();
    }

    @Bean
    PlanWaveUsecase planWaveUsecase(WaveStore waveStore, ShipmentStore shipmentStore, WavePlanner wavePlanner) {
        return new PlanWaveUsecase(waveStore, shipmentStore, wavePlanner);
    }

    @Bean
    ReleaseWaveUsecase releaseWaveUsecase(
            WaveStore waveStore, ShipmentStore shipmentStore, WaveReleasedPublisher waveReleasedPublisher) {
        return new ReleaseWaveUsecase(waveStore, shipmentStore, waveReleasedPublisher);
    }

    @Bean
    CompleteWaveUsecase completeWaveUsecase(
            WaveStore waveStore, ShipmentStore shipmentStore, PickingWorkStore pickingWorkStore) {
        return new CompleteWaveUsecase(waveStore, shipmentStore, pickingWorkStore);
    }

    @Bean
    ConfirmPickUsecase confirmPickUsecase(
            PickingWorkStore pickingWorkStore, PickingWorkStatusChangedPublisher pickingStatusChangedPublisher) {
        return new ConfirmPickUsecase(pickingWorkStore, pickingStatusChangedPublisher);
    }

    @Bean
    PackShipmentUsecase packShipmentUsecase(
            ShipmentStore shipmentStore,
            ShipmentDispatchStore shipmentDispatchStore,
            ShipmentDispatchStatusChangedPublisher shipmentDispatchStatusChangedPublisher) {
        return new PackShipmentUsecase(shipmentStore, shipmentDispatchStore, shipmentDispatchStatusChangedPublisher);
    }

    @Bean
    StageShipmentUsecase stageShipmentUsecase(
            ShipmentDispatchStore shipmentDispatchStore,
            ShipmentDispatchStatusChangedPublisher shipmentDispatchStatusChangedPublisher) {
        return new StageShipmentUsecase(shipmentDispatchStore, shipmentDispatchStatusChangedPublisher);
    }

    @Bean
    SimulateWarehouseOperationsUsecase simulateWarehouseOperationsUsecase(
            ShipmentStore shipmentStore,
            PickingWorkStore pickingWorkStore,
            PlanWaveUsecase planWaveUsecase,
            ReleaseWaveUsecase releaseWaveUsecase,
            ConfirmPickUsecase confirmPickUsecase,
            CompleteWaveUsecase completeWaveUsecase,
            PackShipmentUsecase packShipmentUsecase,
            StageShipmentUsecase stageShipmentUsecase,
            HandOverShipmentUsecase handOverShipmentUsecase) {
        return new SimulateWarehouseOperationsUsecase(
                shipmentStore,
                pickingWorkStore,
                planWaveUsecase,
                releaseWaveUsecase,
                confirmPickUsecase,
                completeWaveUsecase,
                packShipmentUsecase,
                stageShipmentUsecase,
                handOverShipmentUsecase);
    }

    @Bean
    ProcessDueShipmentsUsecase processDueShipmentsUsecase(
            ShipmentStore shipmentStore,
            SimulateWarehouseOperationsUsecase simulateWarehouseOperationsUsecase,
            BusinessClock appClock,
            @Value("${archone.wms.simulation.processing-delay:10s}") Duration processingDelay,
            @Value("${archone.wms.simulation.batch-limit:100}") int batchLimit) {
        return new ProcessDueShipmentsUsecase(
                shipmentStore, simulateWarehouseOperationsUsecase, appClock, processingDelay, batchLimit);
    }

    @Bean
    ProcessCancellingShipmentsUsecase processCancellingShipmentsUsecase(
            ShipmentStore shipmentStore,
            CompleteShipmentCancellationUsecase completeShipmentCancellationUsecase,
            BusinessClock appClock,
            @Value("${archone.wms.simulation.cancellation-batch-limit:100}") int batchLimit) {
        return new ProcessCancellingShipmentsUsecase(
                shipmentStore, completeShipmentCancellationUsecase, appClock, batchLimit);
    }
}
