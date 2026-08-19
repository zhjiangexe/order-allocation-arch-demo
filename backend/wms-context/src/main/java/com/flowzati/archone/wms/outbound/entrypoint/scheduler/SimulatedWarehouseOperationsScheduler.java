package com.flowzati.archone.wms.outbound.entrypoint.scheduler;

import com.flowzati.archone.wms.outbound.application.usecase.ProcessDueShipmentsUsecase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 現階段用來取代真實 WMS 操作入口的 durable polling driver。
 *
 * <p>它不在 JVM 記憶體保存每張 Shipment 的 timer，而是定期從資料庫找出已等待到期的 CREATED
 * Shipment。多個 application instances 可以掃到同一筆資料；transaction 與 optimistic version
 * 保護唯一提交，衝突者留到下一輪重新判斷。
 */
@Component
@ConditionalOnProperty(name = "archone.wms.simulation.enabled", havingValue = "true", matchIfMissing = true)
public class SimulatedWarehouseOperationsScheduler {

    private final ProcessDueShipmentsUsecase processDueShipmentsUsecase;

    public SimulatedWarehouseOperationsScheduler(ProcessDueShipmentsUsecase processDueShipmentsUsecase) {
        this.processDueShipmentsUsecase = processDueShipmentsUsecase;
    }

    @Scheduled(
            initialDelayString = "${archone.wms.simulation.scheduler-initial-delay-ms:1000}",
            fixedDelayString = "${archone.wms.simulation.scheduler-delay-ms:1000}")
    public void processDueShipments() {
        processDueShipmentsUsecase.execute();
    }
}
