package com.flowzati.archone.inventory.position.application.exception;

import java.util.UUID;

/** The caller reused one receipt ID for a different receipt request. */
public final class StockReceiptRequestConflictException extends RuntimeException {

    public StockReceiptRequestConflictException(UUID receiptId) {
        super("Receipt ID is already bound to a different request: " + receiptId);
    }
}
