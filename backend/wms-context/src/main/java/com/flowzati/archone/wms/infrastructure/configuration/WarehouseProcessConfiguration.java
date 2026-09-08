package com.flowzati.archone.wms.infrastructure.configuration;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.wms.process.application.usecase.ProcessCancellingShipmentsUsecase;
import com.flowzati.archone.wms.process.application.usecase.ProcessDueShipmentsUsecase;
import com.flowzati.archone.wms.process.application.usecase.SimulateWarehouseOperationsUsecase;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.application.usecase.CompleteShipmentCancellationUsecase;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires warehouse background processes whose construction depends on runtime settings. */
@Configuration(proxyBeanMethods = false)
public class WarehouseProcessConfiguration {

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
