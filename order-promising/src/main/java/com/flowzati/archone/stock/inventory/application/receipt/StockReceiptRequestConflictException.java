package com.flowzati.archone.stock.inventory.application.receipt;

import java.util.UUID;

/** The caller reused one receipt ID for a different receipt request. */
public final class StockReceiptRequestConflictException extends RuntimeException {

  public StockReceiptRequestConflictException(UUID receiptId) {
    super("Receipt ID is already bound to a different request: " + receiptId);
  }
}
