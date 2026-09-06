package com.flowzati.archone.orderfulfillment.orchestration.eventdriven;

import com.flowzati.archone.foundation.error.ErrorCode;

public enum EventDrivenCancellationErrorCode implements ErrorCode {
    SHIP_COMPLETE_EXECUTION_CONFLICT("FULFILLMENT_CANCELLATION_EXECUTION_CONFLICT");

    private final String value;

    EventDrivenCancellationErrorCode(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
