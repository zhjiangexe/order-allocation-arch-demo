package com.flowzati.archone.wms.outbound.domain.exception;

/** 已有取消結果的 Shipment 收到另一筆 request ID。 */
public class ShipmentCancellationRequestConflictException extends IllegalStateException {

    public ShipmentCancellationRequestConflictException(String message) {
        super(message);
    }
}
