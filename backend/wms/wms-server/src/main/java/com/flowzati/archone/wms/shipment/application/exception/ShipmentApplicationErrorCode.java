package com.flowzati.archone.wms.shipment.application.exception;

import com.flowzati.archone.foundation.error.ErrorCode;

public enum ShipmentApplicationErrorCode implements ErrorCode {
    STOCK_OPERATION_SNAPSHOT_CONFLICT("WMS_SHIPMENT_STOCK_OPERATION_SNAPSHOT_CONFLICT");

    private final String value;

    ShipmentApplicationErrorCode(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
