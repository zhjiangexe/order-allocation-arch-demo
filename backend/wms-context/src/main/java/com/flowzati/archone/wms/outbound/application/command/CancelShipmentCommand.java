package com.flowzati.archone.wms.outbound.application.command;

import java.time.Instant;
import java.util.UUID;

/**
 * 取消單一 WMS Shipment 的直接 command。
 *
 * <p>Fulfillment Workflow 已保存 order-to-shipment correlation，取消時會直接提供 Shipment ID 與
 * 穩定的 request ID。WMS 以 request ID 區分同一請求重播與另一筆衝突請求，不回查 Ordering。
 */
public record CancelShipmentCommand(
        /** 由外部命令入口產生並在所有重試中保持不變。 */
        UUID requestId, UUID shipmentId, Instant requestedAt, String reason) {

    public CancelShipmentCommand {
        if (requestId == null || shipmentId == null || requestedAt == null) {
            throw new IllegalArgumentException("Cancellation request ID, shipment ID and time are required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason is required");
        }
        if (reason.length() > 512) {
            throw new IllegalArgumentException("Cancellation reason must not exceed 512 characters");
        }
    }
}
