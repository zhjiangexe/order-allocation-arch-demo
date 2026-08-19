package com.flowzati.archone.ordering.domain.exception;

/** 同一張已取消訂單收到不同取消請求或不同 immutable payload。 */
public class OrderCancellationRequestConflictException extends IllegalStateException {

    public OrderCancellationRequestConflictException(String message) {
        super(message);
    }
}
