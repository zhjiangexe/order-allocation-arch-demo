package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.wms.outbound.application.command.SimulateWarehouseOperationsCommand;
import com.flowzati.archone.wms.outbound.application.store.ShipmentStore;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

/** 從持久化狀態找回等待到期的 Shipment，讓三個部署環境執行相同的模擬倉內流程。 */
public class ProcessDueShipmentsUsecase {

    private static final Logger log = LoggerFactory.getLogger(ProcessDueShipmentsUsecase.class);

    private final ShipmentStore shipmentStore;
    private final SimulateWarehouseOperationsUsecase simulateWarehouseOperations;
    private final BusinessClock appClock;
    private final Duration processingDelay;
    private final int batchLimit;

    public ProcessDueShipmentsUsecase(
            ShipmentStore shipmentStore,
            SimulateWarehouseOperationsUsecase simulateWarehouseOperations,
            BusinessClock appClock,
            Duration processingDelay,
            int batchLimit) {
        if (processingDelay == null || processingDelay.isNegative()) {
            throw new IllegalArgumentException("Shipment simulation delay must not be negative");
        }
        if (batchLimit <= 0) {
            throw new IllegalArgumentException("Shipment simulation batch limit must be positive");
        }
        this.shipmentStore = shipmentStore;
        this.simulateWarehouseOperations = simulateWarehouseOperations;
        this.appClock = appClock;
        this.processingDelay = processingDelay;
        this.batchLimit = batchLimit;
    }

    public void execute() {
        Instant processedAt = appClock.instant();
        Instant cutoff = processedAt.minus(processingDelay);
        for (UUID shipmentId : shipmentStore.findCreatedAtOrBefore(cutoff, batchLimit)) {
            processOne(shipmentId, processedAt);
        }
    }

    private void processOne(UUID shipmentId, Instant processedAt) {
        try {
            simulateWarehouseOperations.handle(new SimulateWarehouseOperationsCommand(shipmentId, processedAt));
        } catch (OptimisticLockingFailureException exception) {
            // 另一個 instance 或取消流程已先修改 Shipment；下次掃描會依最新狀態決定是否仍需處理。
            log.atDebug()
                    .addKeyValue("shipmentId", shipmentId)
                    .setCause(exception)
                    .log("Simulated warehouse processing conflicted; deferred until the next scan");
        } catch (RuntimeException exception) {
            // 隔離單張 Shipment，避免一筆異常阻止同一批其他到期 Shipment 被處理。
            log.atError()
                    .addKeyValue("shipmentId", shipmentId)
                    .setCause(exception)
                    .log("Simulated warehouse processing failed");
        }
    }
}
