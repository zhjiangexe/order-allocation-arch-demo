package com.flowzati.archone.orderfulfillment.contract.activity.ordering;

/** Ordering 對取消命令的最終業務狀態。 */
public enum CancelOrderActivityStatus {
    CANCELLED,
    ALREADY_CANCELLED,
    REJECTED
}
