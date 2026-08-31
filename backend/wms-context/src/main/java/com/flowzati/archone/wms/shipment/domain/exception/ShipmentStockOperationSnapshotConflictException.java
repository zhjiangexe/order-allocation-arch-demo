package com.flowzati.archone.wms.shipment.domain.exception;

/** The same Inventory picking was handed off with structurally different execution facts. */
public class ShipmentStockOperationSnapshotConflictException extends RuntimeException {

    public ShipmentStockOperationSnapshotConflictException(String message) {
        super(message);
    }
}
