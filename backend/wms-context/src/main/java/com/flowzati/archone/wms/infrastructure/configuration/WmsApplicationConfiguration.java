package com.flowzati.archone.wms.infrastructure.configuration;

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
import com.flowzati.archone.wms.outbound.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.CompleteShipmentCancellationUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.CompleteWaveUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.ConfirmPickUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.GetOrderShipmentsUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.HandOverShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.PackShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.PlanWaveUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.ProcessCancellingShipmentsUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.ProcessDueShipmentsUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.ReleaseWaveUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.SimulateWarehouseOperationsUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.StageShipmentUsecase;
import com.flowzati.archone.wms.outbound.domain.service.WavePlanner;
import com.flowzati.archone.wms.outbound.domain.service.impl.PriorityCapacityWavePlanner;
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
        log.info("WMS use cases enabled: inbound registration/arrival/inspection/putaway and "
                + "outbound wave/pick/pack/stage/handover checkpoints");
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
            BusinessClock appClock) {
        return new CancelShipmentUsecase(shipmentStore, shipmentCancelledPublisher, appClock);
    }

    @Bean
    CompleteShipmentCancellationUsecase completeShipmentCancellationUsecase(
            ShipmentStore shipmentStore, ShipmentCancelledPublisher shipmentCancelledPublisher) {
        return new CompleteShipmentCancellationUsecase(shipmentStore, shipmentCancelledPublisher);
    }

    @Bean
    HandOverShipmentUsecase handOverShipmentUsecase(
            ShipmentStore shipmentStore, ShipmentHandedOverPublisher shipmentHandedOverPublisher) {
        return new HandOverShipmentUsecase(shipmentStore, shipmentHandedOverPublisher);
    }

    @Bean
    GetOrderShipmentsUsecase getOrderShipmentsUsecase(ShipmentStore shipmentStore) {
        return new GetOrderShipmentsUsecase(shipmentStore);
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
    ReleaseWaveUsecase releaseWaveUsecase(WaveStore waveStore, ShipmentStore shipmentStore) {
        return new ReleaseWaveUsecase(waveStore, shipmentStore);
    }

    @Bean
    CompleteWaveUsecase completeWaveUsecase(WaveStore waveStore, ShipmentStore shipmentStore) {
        return new CompleteWaveUsecase(waveStore, shipmentStore);
    }

    @Bean
    ConfirmPickUsecase confirmPickUsecase(ShipmentStore shipmentStore) {
        return new ConfirmPickUsecase(shipmentStore);
    }

    @Bean
    PackShipmentUsecase packShipmentUsecase(ShipmentStore shipmentStore) {
        return new PackShipmentUsecase(shipmentStore);
    }

    @Bean
    StageShipmentUsecase stageShipmentUsecase(ShipmentStore shipmentStore) {
        return new StageShipmentUsecase(shipmentStore);
    }

    @Bean
    SimulateWarehouseOperationsUsecase simulateWarehouseOperationsUsecase(
            ShipmentStore shipmentStore,
            PlanWaveUsecase planWaveUsecase,
            ReleaseWaveUsecase releaseWaveUsecase,
            ConfirmPickUsecase confirmPickUsecase,
            CompleteWaveUsecase completeWaveUsecase,
            PackShipmentUsecase packShipmentUsecase,
            StageShipmentUsecase stageShipmentUsecase,
            HandOverShipmentUsecase handOverShipmentUsecase) {
        return new SimulateWarehouseOperationsUsecase(
                shipmentStore,
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
