package com.flowzati.archone.inventory.balance.application.error;

import com.flowzati.archone.foundation.error.ErrorCode;

public enum StockBalanceErrorCode implements ErrorCode {
    STOCK_RECEIPT_REQUEST_CONFLICT("INVENTORY_STOCK_RECEIPT_REQUEST_CONFLICT");

    private final String value;

    StockBalanceErrorCode(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
