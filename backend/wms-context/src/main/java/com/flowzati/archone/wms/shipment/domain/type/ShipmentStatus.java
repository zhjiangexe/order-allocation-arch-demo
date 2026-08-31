package com.flowzati.archone.wms.shipment.domain.type;

/** WMS 內部的 Shipment 作業狀態。 */
public enum ShipmentStatus {
    CREATED,
    WAVE_PLANNED,
    RELEASED,
    PICKING,
    PICKED,
    PACKED,
    READY_FOR_DISPATCH,
    HANDED_OVER_TO_CARRIER,
    CANCELLING,
    CANCELLED
}
