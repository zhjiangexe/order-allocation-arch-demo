package com.flowzati.archone.wms.shipment.domain.type;

/** WMS 對單次 Shipment cancellation command 的受理結果。 */
public enum CancelShipmentStatus {
    ACCEPTED,
    ALREADY_ACCEPTED,
    REJECTED
}
