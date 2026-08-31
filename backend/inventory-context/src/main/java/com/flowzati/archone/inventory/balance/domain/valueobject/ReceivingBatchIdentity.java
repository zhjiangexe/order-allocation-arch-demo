package com.flowzati.archone.inventory.balance.domain.valueobject;

import java.time.LocalDate;

/** 入庫日與效期共同參與 StockQuant 的批次身分。 */
public record ReceivingBatchIdentity(LocalDate inDate, LocalDate expiryDate) {

    public ReceivingBatchIdentity {
        if (inDate == null || expiryDate == null) {
            throw new IllegalArgumentException("A batch is identified by its arrival and expiry");
        }
    }
}
