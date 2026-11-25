package com.flowzati.archone.wms.process.application.invocation;

import java.util.UUID;

/** 一張出貨單已完成取消：收掉它的揀貨工作與出庫作業。 */
public record CloseCancelledShipmentWorkCommand(UUID shipmentId) {

    public CloseCancelledShipmentWorkCommand {
        if (shipmentId == null) {
            throw new IllegalArgumentException("Shipment ID is required");
        }
    }
}
