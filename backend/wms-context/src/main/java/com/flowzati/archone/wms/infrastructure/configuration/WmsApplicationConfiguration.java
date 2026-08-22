package com.flowzati.archone.wms.infrastructure.configuration;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.wms.inbound.application.usecase.ConfirmArrivalUsecase;
import com.flowzati.archone.wms.inbound.application.usecase.ConfirmPutawayUsecase;
import com.flowzati.archone.wms.inbound.application.usecase.RecordInspectionUsecase;
import com.flowzati.archone.wms.inbound.application.usecase.RegisterInboundOperationUsecase;
import com.flowzati.archone.wms.inbound.domain.repository.InboundOperationRepository;
import com.flowzati.archone.wms.outbound.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.ConfirmPickUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.GetOrderShipmentsUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.HandOverShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.PackShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.ProcessDueShipmentsUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.SimulateWarehouseOperationsUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.StageShipmentUsecase;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.outbound.wave.application.usecase.CompleteWaveUsecase;
import com.flowzati.archone.wms.outbound.wave.application.usecase.PlanWaveUsecase;
import com.flowzati.archone.wms.outbound.wave.application.usecase.ReleaseWaveUsecase;
import com.flowzati.archone.wms.outbound.wave.domain.repository.WaveRepository;
import com.flowzati.archone.wms.outbound.wave.domain.service.PriorityCapacityWavePlanner;
import com.flowzati.archone.wms.outbound.wave.domain.service.WavePlanner;
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
    RegisterInboundOperationUsecase registerInboundOperationUsecase(InboundOperationRepository repository) {
        return new RegisterInboundOperationUsecase(repository);
    }

    @Bean
    ConfirmArrivalUsecase confirmArrivalUsecase(InboundOperationRepository repository) {
        return new ConfirmArrivalUsecase(repository);
    }

    @Bean
    RecordInspectionUsecase recordInspectionUsecase(InboundOperationRepository repository) {
        return new RecordInspectionUsecase(repository);
    }

    @Bean
    ConfirmPutawayUsecase confirmPutawayUsecase(InboundOperationRepository repository) {
        return new ConfirmPutawayUsecase(repository);
    }

    @Bean
    CreateShipmentUsecase createShipmentUsecase(ShipmentRepository shipmentRepository) {
        return new CreateShipmentUsecase(shipmentRepository);
    }

    @Bean
    CancelShipmentUsecase cancelShipmentUsecase(ShipmentRepository shipmentRepository) {
        return new CancelShipmentUsecase(shipmentRepository);
    }

    @Bean
    HandOverShipmentUsecase handOverShipmentUsecase(
            ShipmentRepository shipmentRepository, IntegrationEventPublisher integrationEventPublisher) {
        return new HandOverShipmentUsecase(shipmentRepository, integrationEventPublisher);
    }

    @Bean
    GetOrderShipmentsUsecase getOrderShipmentsUsecase(ShipmentRepository shipmentRepository) {
        return new GetOrderShipmentsUsecase(shipmentRepository);
    }

    @Bean
    WavePlanner wavePlanner() {
        return new PriorityCapacityWavePlanner();
    }

    @Bean
    PlanWaveUsecase planWaveUsecase(
            WaveRepository waveRepository, ShipmentRepository shipmentRepository, WavePlanner wavePlanner) {
        return new PlanWaveUsecase(waveRepository, shipmentRepository, wavePlanner);
    }

    @Bean
    ReleaseWaveUsecase releaseWaveUsecase(
            WaveRepository waveRepository,
            ShipmentRepository shipmentRepository,
            com.flowzati.archone.wms.shared.application.IdGenerator wmsIdGenerator) {
        return new ReleaseWaveUsecase(waveRepository, shipmentRepository, wmsIdGenerator);
    }

    @Bean
    CompleteWaveUsecase completeWaveUsecase(WaveRepository waveRepository, ShipmentRepository shipmentRepository) {
        return new CompleteWaveUsecase(waveRepository, shipmentRepository);
    }

    @Bean
    ConfirmPickUsecase confirmPickUsecase(ShipmentRepository shipmentRepository) {
        return new ConfirmPickUsecase(shipmentRepository);
    }

    @Bean
    PackShipmentUsecase packShipmentUsecase(ShipmentRepository shipmentRepository) {
        return new PackShipmentUsecase(shipmentRepository);
    }

    @Bean
    StageShipmentUsecase stageShipmentUsecase(ShipmentRepository shipmentRepository) {
        return new StageShipmentUsecase(shipmentRepository);
    }

    @Bean
    SimulateWarehouseOperationsUsecase simulateWarehouseOperationsUsecase(
            ShipmentRepository shipmentRepository,
            com.flowzati.archone.wms.shared.application.IdGenerator wmsIdGenerator,
            PlanWaveUsecase planWaveUsecase,
            ReleaseWaveUsecase releaseWaveUsecase,
            ConfirmPickUsecase confirmPickUsecase,
            CompleteWaveUsecase completeWaveUsecase,
            PackShipmentUsecase packShipmentUsecase,
            StageShipmentUsecase stageShipmentUsecase,
            HandOverShipmentUsecase handOverShipmentUsecase) {
        return new SimulateWarehouseOperationsUsecase(
                shipmentRepository,
                wmsIdGenerator,
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
            ShipmentRepository shipmentRepository,
            SimulateWarehouseOperationsUsecase simulateWarehouseOperationsUsecase,
            BusinessClock appClock,
            @Value("${archone.wms.simulation.processing-delay:10s}") Duration processingDelay,
            @Value("${archone.wms.simulation.batch-limit:100}") int batchLimit) {
        return new ProcessDueShipmentsUsecase(
                shipmentRepository, simulateWarehouseOperationsUsecase, appClock, processingDelay, batchLimit);
    }

    @Bean
    com.flowzati.archone.wms.shared.application.IdGenerator wmsIdGenerator() {
        return IdGenerator::nextId;
    }
}
