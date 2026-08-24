package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 從持久化 backlog 完成已受理的 Shipment cancellation；每張 Shipment 的 transaction 彼此隔離。 */
public class ProcessCancellingShipmentsUsecase {

    private static final Logger log = LoggerFactory.getLogger(ProcessCancellingShipmentsUsecase.class);

    private final ShipmentRepository shipmentRepository;
    private final CompleteShipmentCancellationUsecase completeShipmentCancellation;
    private final BusinessClock appClock;
    private final int batchLimit;

    public ProcessCancellingShipmentsUsecase(
            ShipmentRepository shipmentRepository,
            CompleteShipmentCancellationUsecase completeShipmentCancellation,
            BusinessClock appClock,
            int batchLimit) {
        if (batchLimit <= 0) {
            throw new IllegalArgumentException("Cancellation recovery batch limit must be positive");
        }
        this.shipmentRepository = shipmentRepository;
        this.completeShipmentCancellation = completeShipmentCancellation;
        this.appClock = appClock;
        this.batchLimit = batchLimit;
    }

    public void execute() {
        Instant completedAt = appClock.instant();
        for (UUID shipmentId : shipmentRepository.findCancelling(batchLimit)) {
            completeOne(shipmentId, completedAt);
        }
    }

    private void completeOne(UUID shipmentId, Instant completedAt) {
        try {
            completeShipmentCancellation.execute(shipmentId, completedAt);
        } catch (RuntimeException exception) {
            // 單筆衝突或異常都留待下一輪依最新持久化狀態重試，不阻斷同批其他 Shipment。
            log.atError()
                    .addKeyValue("shipmentId", shipmentId)
                    .setCause(exception)
                    .log("Shipment cancellation recovery failed; deferred until the next scan");
        }
    }
}
