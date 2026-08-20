package com.flowzati.archone.wms.infrastructure.configuration;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.wms.outbound.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.GetOrderShipmentsUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.HandOverShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.ProcessDueShipmentsUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.SimulateWarehouseOperationsUsecase;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires pure WMS application services to runtime adapters. */
@Configuration(proxyBeanMethods = false)
public class WmsApplicationConfiguration {

    @Bean
    CreateShipmentUsecase createShipmentUsecase(
            ShipmentRepository shipmentRepository, DomainEventPublisher wmsDomainEventPublisher) {
        return new CreateShipmentUsecase(shipmentRepository, wmsDomainEventPublisher);
    }

    @Bean
    CancelShipmentUsecase cancelShipmentUsecase(
            ShipmentRepository shipmentRepository, DomainEventPublisher wmsDomainEventPublisher) {
        return new CancelShipmentUsecase(shipmentRepository, wmsDomainEventPublisher);
    }

    @Bean
    HandOverShipmentUsecase handOverShipmentUsecase(
            ShipmentRepository shipmentRepository, DomainEventPublisher wmsDomainEventPublisher) {
        return new HandOverShipmentUsecase(shipmentRepository, wmsDomainEventPublisher);
    }

    @Bean
    GetOrderShipmentsUsecase getOrderShipmentsUsecase(ShipmentRepository shipmentRepository) {
        return new GetOrderShipmentsUsecase(shipmentRepository);
    }

    @Bean
    SimulateWarehouseOperationsUsecase simulateWarehouseOperationsUsecase(
            ShipmentRepository shipmentRepository,
            com.flowzati.archone.wms.shared.application.IdGenerator wmsIdGenerator,
            DomainEventPublisher wmsDomainEventPublisher) {
        return new SimulateWarehouseOperationsUsecase(shipmentRepository, wmsIdGenerator, wmsDomainEventPublisher);
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
