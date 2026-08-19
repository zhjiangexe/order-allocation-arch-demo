package com.flowzati.archone.wms.outbound.application.command;

import java.time.Instant;
import java.util.UUID;

/**
 * 取消單一 WMS Shipment 的直接 command。
 *
 * <p>TODO(order-promising)：目前 {@code OrderCancelledIntegrationEvent} 只有 {@code orderId}
 * 與時間，無法直接填入 {@code shipmentId}。接通整單取消前，上游 allocation snapshot 必須保留
 * order-to-shipment correlation，或另定義以 {@code orderId} 為輸入、可取消多張 Shipment 的 WMS
 * use case；不要讓 WMS 回查 order-promising repository。
 */
public record CancelShipmentCommand(
        /** 消費 integration event 時可使用 event ID，吸收重送。 */
        String requestId, UUID shipmentId, Instant requestedAt) {

    public CancelShipmentCommand {
        if (requestId == null || requestId.isBlank() || shipmentId == null || requestedAt == null) {
            throw new IllegalArgumentException("Cancellation request ID, shipment ID and time are required");
        }
    }
}
