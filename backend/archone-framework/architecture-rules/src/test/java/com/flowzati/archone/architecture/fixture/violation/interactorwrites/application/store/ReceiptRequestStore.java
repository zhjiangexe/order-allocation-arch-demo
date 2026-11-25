package com.flowzati.archone.architecture.fixture.violation.interactorwrites.application.store;

public interface ReceiptRequestStore {
    boolean claimIfNew(String receiptId);
}
