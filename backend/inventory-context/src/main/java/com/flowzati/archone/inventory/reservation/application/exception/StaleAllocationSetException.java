package com.flowzati.archone.inventory.reservation.application.exception;

/** Supply changed between optimistic planning and the globally ordered quant lock. */
public final class StaleAllocationSetException extends IllegalStateException {

    public StaleAllocationSetException(String message) {
        super(message);
    }
}
