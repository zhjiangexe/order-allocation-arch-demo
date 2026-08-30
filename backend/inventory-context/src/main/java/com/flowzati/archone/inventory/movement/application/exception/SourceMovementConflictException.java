package com.flowzati.archone.inventory.movement.application.exception;

import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;

/** Equal source identity was already registered with different immutable operation content. */
public class SourceMovementConflictException extends RuntimeException {

    public SourceMovementConflictException(StockOperationSource source) {
        super("Stock operation source was already registered with different content: " + source);
    }
}
