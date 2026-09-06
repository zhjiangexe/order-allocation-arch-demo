package com.flowzati.archone.orderfulfillment.orchestration.temporal;

import com.flowzati.archone.foundation.error.ErrorCode;

public enum TemporalCancellationErrorCode implements ErrorCode {
    WORKFLOW_UNAVAILABLE("FULFILLMENT_CANCELLATION_WORKFLOW_UNAVAILABLE");

    private final String value;

    TemporalCancellationErrorCode(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
