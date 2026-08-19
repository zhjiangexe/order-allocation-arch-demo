package com.flowzati.archone.wms.outbound.domain.exception;

/** 同一 allocation ID 已建立 Shipment，但重播內容與第一次接受的 snapshot 不同。 */
public class ShipmentAllocationSnapshotConflictException extends IllegalStateException {

    public ShipmentAllocationSnapshotConflictException(String message) {
        super(message);
    }
}
