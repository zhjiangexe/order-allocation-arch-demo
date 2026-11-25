package com.flowzati.archone.fulfillment.application.invocation;

import java.time.Instant;
import java.util.UUID;

/** 倉庫已依取消請求停止並取消出貨單。 */
public record RecordShipmentCancelledCommand(
        UUID orderId, UUID shipmentId, UUID cancellationRequestId, Instant cancelledAt) {}
