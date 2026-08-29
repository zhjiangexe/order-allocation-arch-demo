package com.flowzati.archone.inventory.movement.application;

import com.flowzati.archone.inventory.movement.domain.StockOperationSource;

/** Equal source identity was already registered with different immutable operation content. */
public class SourceMovementConflictException extends RuntimeException {

    public SourceMovementConflictException(StockOperationSource source) {
        super("Stock operation source was already registered with different content: " + source);
    }
}
