package com.flowzati.archone.bootstrap.messaging.consumer;

import com.flowzati.archone.messaging.consumer.common.MessageFailureCategory;
import com.flowzati.archone.messaging.consumer.common.MessageFailureClassification;
import com.flowzati.archone.messaging.consumer.common.MessageMappingException;
import com.flowzati.archone.messaging.consumer.common.TypeBasedMessageFailureClassifier;
import com.flowzati.archone.messaging.events.IntegrationEventContractException;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerFailurePolicy;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetryExhaustedException;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetrySettings;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Duration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionTimedOutException;

/** One application-owned definition of local retry, broker redelivery, and failure taxonomy. */
final class BootstrapConsumerFailurePolicy {

    static final int LOCAL_OPTIMISTIC_RETRIES = 2;
    static final Duration LOCAL_OPTIMISTIC_RETRY_DELAY = Duration.ofMillis(100);
    static final int KAFKA_REDELIVERY_RETRIES = 4;

    static final int MAX_OPTIMISTIC_TRANSACTION_ATTEMPTS =
            (LOCAL_OPTIMISTIC_RETRIES + 1) * (KAFKA_REDELIVERY_RETRIES + 1);
    static final int MAX_TRANSIENT_INFRASTRUCTURE_PROCESSING_ATTEMPTS = KAFKA_REDELIVERY_RETRIES + 1;

    private BootstrapConsumerFailurePolicy() {}

    static OptimisticLockingRetrySettings optimisticLockingRetrySettings() {
        return new OptimisticLockingRetrySettings(LOCAL_OPTIMISTIC_RETRIES, LOCAL_OPTIMISTIC_RETRY_DELAY);
    }

    static KafkaConsumerFailurePolicy kafkaConsumerFailurePolicy() {
        ExponentialBackOffWithMaxRetries retryBackOff = new ExponentialBackOffWithMaxRetries(KAFKA_REDELIVERY_RETRIES);
        retryBackOff.setInitialInterval(1_000);
        retryBackOff.setMultiplier(2.0);
        retryBackOff.setMaxInterval(10_000);

        return new KafkaConsumerFailurePolicy(retryBackOff, failureClassifier());
    }

    private static TypeBasedMessageFailureClassifier failureClassifier() {
        return TypeBasedMessageFailureClassifier.builder()
                // Fast local retries have already rolled back; delayed broker redelivery gets one more
                // bounded chance to escape sustained contention.
                .retryable(OptimisticLockingRetryExhaustedException.class, MessageFailureCategory.HANDLER)
                .retryable(OptimisticLockingFailureException.class, MessageFailureCategory.HANDLER)
                // Only transaction-safe Spring/JDBC failure families are admitted. DataAccessException as
                // a whole is intentionally not retryable because it also contains permanent SQL errors.
                .retryable(TransientDataAccessException.class, MessageFailureCategory.INFRASTRUCTURE)
                .retryable(RecoverableDataAccessException.class, MessageFailureCategory.INFRASTRUCTURE)
                .retryable(DataAccessResourceFailureException.class, MessageFailureCategory.INFRASTRUCTURE)
                .retryable(CannotCreateTransactionException.class, MessageFailureCategory.INFRASTRUCTURE)
                .retryable(TransactionTimedOutException.class, MessageFailureCategory.INFRASTRUCTURE)
                .retryable(SQLTransientException.class, MessageFailureCategory.INFRASTRUCTURE)
                .retryable(SQLRecoverableException.class, MessageFailureCategory.INFRASTRUCTURE)
                .nonRetryable(DataAccessException.class, MessageFailureCategory.INFRASTRUCTURE)
                .nonRetryable(TransactionException.class, MessageFailureCategory.INFRASTRUCTURE)
                .nonRetryable(SQLException.class, MessageFailureCategory.INFRASTRUCTURE)
                .nonRetryable(MessageMappingException.class, MessageFailureCategory.MAPPING)
                .nonRetryable(IntegrationEventContractException.class, MessageFailureCategory.CONTRACT)
                // Business rejection and unknown programming failures are both terminal by default. They
                // stay HANDLER failures unless an application introduces a more specific registered type.
                .fallback(MessageFailureClassification.nonRetryable(MessageFailureCategory.HANDLER))
                .build();
    }
}
