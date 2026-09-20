package com.flowzati.archone.orderfulfillment.application;

public enum CancellationProcessState {
    WAITING_WMS,
    WAITING_ORDERING,
    COMPLETED,
    REJECTED,
    CONFLICT
}
