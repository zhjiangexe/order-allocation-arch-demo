package com.flowzati.archone.ordering.domain.enums;

/** 一次訂單取消請求的處理結果（不是訂單的狀態；訂單狀態見 OrderStatus）。 */
public enum OrderCancellationOutcome {
    CANCELLED,
    ALREADY_CANCELLED,
    REJECTED
}
