package com.flowzati.archone.messaging.spring.jdbc;

import com.flowzati.archone.messaging.jdbc.MessagingTransactionCallback;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bridges Spring transaction ownership without leaking Spring types into pure messaging code. */
public final class SpringMessagingTransactionTemplate implements MessagingTransactionTemplate {

    private final TransactionOperations transactionOperations;
    private final BooleanSupplier activeTransaction;

    public SpringMessagingTransactionTemplate(PlatformTransactionManager transactionManager) {
        this(
                new TransactionTemplate(
                        Objects.requireNonNull(transactionManager, "PlatformTransactionManager is required")),
                TransactionSynchronizationManager::isActualTransactionActive);
    }

    SpringMessagingTransactionTemplate(TransactionOperations transactionOperations, BooleanSupplier activeTransaction) {
        this.transactionOperations = Objects.requireNonNull(transactionOperations, "TransactionOperations is required");
        this.activeTransaction = Objects.requireNonNull(activeTransaction, "Active transaction probe is required");
    }

    @Override
    public <T> T execute(MessagingTransactionCallback<T> callback) {
        Objects.requireNonNull(callback, "Messaging transaction callback is required");
        return transactionOperations.execute(status -> callback.execute());
    }

    @Override
    public void requireActive() {
        if (!isActive()) {
            throw new IllegalStateException("No active caller transaction for transactional message production");
        }
    }

    @Override
    public boolean isActive() {
        return activeTransaction.getAsBoolean();
    }
}
