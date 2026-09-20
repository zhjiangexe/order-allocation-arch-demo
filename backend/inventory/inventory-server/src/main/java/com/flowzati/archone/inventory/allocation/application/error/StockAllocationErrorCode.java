package com.flowzati.archone.inventory.allocation.application.error;

import com.flowzati.archone.foundation.error.ErrorCode;

public enum StockAllocationErrorCode implements ErrorCode {
    ALLOCATION_SET_STALE("INVENTORY_ALLOCATION_SET_STALE"),
    STOCK_ALLOCATION_PROPOSAL_STALE("INVENTORY_STOCK_ALLOCATION_PROPOSAL_STALE");

    private final String value;

    StockAllocationErrorCode(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
