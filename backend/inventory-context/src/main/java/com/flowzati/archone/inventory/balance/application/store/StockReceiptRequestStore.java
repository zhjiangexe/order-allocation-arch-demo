package com.flowzati.archone.inventory.balance.application.store;

import com.flowzati.archone.inventory.balance.application.StockReceiptRequest;
import com.flowzati.archone.inventory.balance.application.exception.StockReceiptRequestConflictException;

/** Application port for atomically claiming a synchronous stock receipt request. */
@FunctionalInterface
public interface StockReceiptRequestStore {

    /**
     * @return {@code true} for the first identical request, {@code false} for an exact replay
     * @throws StockReceiptRequestConflictException when the ID was already bound to other content
     */
    boolean claimIfNew(StockReceiptRequest request);
}
