package com.flowzati.archone.inventory.movement.application.exception;

import com.flowzati.archone.foundation.error.ErrorCode;

public enum StockMovementErrorCode implements ErrorCode {
    SOURCE_MOVEMENT_CONFLICT("INVENTORY_SOURCE_MOVEMENT_CONFLICT");

    private final String value;

    StockMovementErrorCode(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
