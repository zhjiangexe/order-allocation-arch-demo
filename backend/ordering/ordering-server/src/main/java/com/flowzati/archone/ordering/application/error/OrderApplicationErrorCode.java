package com.flowzati.archone.ordering.application.error;

import com.flowzati.archone.foundation.error.ErrorCode;

public enum OrderApplicationErrorCode implements ErrorCode {
    ORDER_NOT_FOUND("ORDER_NOT_FOUND");

    private final String value;

    OrderApplicationErrorCode(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
