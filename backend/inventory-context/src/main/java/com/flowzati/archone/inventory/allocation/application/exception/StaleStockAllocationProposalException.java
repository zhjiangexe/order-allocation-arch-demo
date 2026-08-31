package com.flowzati.archone.inventory.allocation.application.exception;

/** The mutable operation or supply facts changed after pure stock allocation planning. */
public final class StaleStockAllocationProposalException extends IllegalStateException {

    public StaleStockAllocationProposalException(String message) {
        super(message);
    }

    public StaleStockAllocationProposalException(String message, Throwable cause) {
        super(message, cause);
    }
}
