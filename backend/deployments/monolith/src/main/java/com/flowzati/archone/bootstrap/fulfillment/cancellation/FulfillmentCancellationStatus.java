package com.flowzati.archone.bootstrap.fulfillment.cancellation;

/** 對 HTTP caller 穩定的取消受理結果，不暴露 Temporal 或 WMS 的技術型別。 */
public enum FulfillmentCancellationStatus {
    ACCEPTED,
    ALREADY_REQUESTED,
    ALREADY_CANCELLED,
    REJECTED
}
