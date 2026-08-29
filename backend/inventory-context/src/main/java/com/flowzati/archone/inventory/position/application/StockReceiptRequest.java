package com.flowzati.archone.inventory.position.application;

import com.flowzati.archone.inventory.position.application.command.ConfirmStockReceiptCommand;
import java.util.UUID;

/** One caller-owned receipt identity and the exact command bound to that identity. */
public record StockReceiptRequest(UUID receiptId, ConfirmStockReceiptCommand command) {

    public StockReceiptRequest {
        if (receiptId == null || command == null) {
            throw new IllegalArgumentException("Receipt request ID and command are required");
        }
    }
}
