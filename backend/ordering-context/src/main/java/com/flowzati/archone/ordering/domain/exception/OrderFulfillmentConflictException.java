package com.flowzati.archone.ordering.domain.exception;

/** 同一張已完成訂單收到不同 Shipment 或不同完成事實。 */
public class OrderFulfillmentConflictException extends IllegalStateException {

    public OrderFulfillmentConflictException(String message) {
        super(message);
    }
}
