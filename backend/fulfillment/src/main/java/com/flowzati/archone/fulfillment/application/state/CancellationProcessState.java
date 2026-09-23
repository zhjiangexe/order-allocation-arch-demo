package com.flowzati.archone.fulfillment.application.state;

public enum CancellationProcessState {
    WAITING_WMS,
    WAITING_ORDERING,
    COMPLETED,
    REJECTED,
    CONFLICT
}
