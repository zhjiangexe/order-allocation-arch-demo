package com.flowzati.archone.architecture.fixture.violation.interactorwrites.application.usecase;

import com.flowzati.archone.architecture.fixture.violation.interactorwrites.application.store.ReceiptRequestStore;

/** 自己登記收貨編號（寫入），再轉交 Usecase：寫入應該在 Usecase 裡。 */
public class ReceiveStockInteractor {
    private final ReceiptRequestStore receiptRequestStore;
    private final ReceiveStockUsecase receiveStockUsecase;

    public ReceiveStockInteractor(ReceiptRequestStore receiptRequestStore, ReceiveStockUsecase receiveStockUsecase) {
        this.receiptRequestStore = receiptRequestStore;
        this.receiveStockUsecase = receiveStockUsecase;
    }

    public void execute() {
        if (receiptRequestStore.claimIfNew("R-1")) {
            receiveStockUsecase.execute();
        }
    }
}
