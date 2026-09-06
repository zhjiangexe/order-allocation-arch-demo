package com.flowzati.archone.wms.shipment.domain.exception;

import com.flowzati.archone.foundation.error.ErrorCode;

public enum ShipmentErrorCode implements ErrorCode {
    CANCELLATION_REQUEST_CONFLICT("WMS_SHIPMENT_CANCELLATION_REQUEST_CONFLICT");

    private final String value;

    ShipmentErrorCode(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
