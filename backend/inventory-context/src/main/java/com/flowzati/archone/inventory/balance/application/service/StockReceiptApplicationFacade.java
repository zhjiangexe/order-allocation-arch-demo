package com.flowzati.archone.inventory.balance.application.service;

import com.flowzati.archone.inventory.balance.application.StockReceiptRequest;
import com.flowzati.archone.inventory.balance.application.store.StockReceiptRequestStore;
import com.flowzati.archone.inventory.balance.application.usecase.ConfirmStockReceiptUsecase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transaction owner for synchronous receipt idempotency and the resulting stock mutation. */
@Service
public class StockReceiptApplicationFacade {

    private final StockReceiptRequestStore stockReceiptRequestStore;
    private final ConfirmStockReceiptUsecase confirmStockReceiptUsecase;

    public StockReceiptApplicationFacade(
            StockReceiptRequestStore stockReceiptRequestStore, ConfirmStockReceiptUsecase confirmStockReceiptUsecase) {
        this.stockReceiptRequestStore = stockReceiptRequestStore;
        this.confirmStockReceiptUsecase = confirmStockReceiptUsecase;
    }

    @Transactional
    public void confirm(StockReceiptRequest request) {
        if (!stockReceiptRequestStore.claimIfNew(request)) {
            return;
        }
        confirmStockReceiptUsecase.execute(request.command());
    }
}
