package com.flowzati.archone.ordering.domain.error;

import com.flowzati.archone.foundation.error.ErrorCode;

public enum OrderErrorCode implements ErrorCode {
    FULFILLMENT_CONFLICT("ORDER_FULFILLMENT_CONFLICT"),
    CANCELLATION_REQUEST_CONFLICT("ORDER_CANCELLATION_REQUEST_CONFLICT");

    private final String value;

    OrderErrorCode(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
