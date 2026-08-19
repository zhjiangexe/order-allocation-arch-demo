package com.flowzati.archone.bootstrap.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.messaging.consumer.common.MessageFailureCategory;
import com.flowzati.archone.messaging.consumer.common.MessageFailureClassification;
import com.flowzati.archone.messaging.consumer.common.MessageMappingException;
import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import com.flowzati.archone.messaging.events.IntegrationEventContractException;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaConsumerFailurePolicy;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetryExhaustedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransientConnectionException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.util.backoff.BackOffExecution;

class BootstrapKafkaConsumerConfigurationTest {

    @Test
    void classifiesTheApplicationFailureMatrix() {
        KafkaConsumerFailurePolicy policy = policy();

        assertClassification(
                policy, exhausted(), MessageFailureClassification.retryable(MessageFailureCategory.HANDLER));
        assertClassification(
                policy,
                new OptimisticLockingFailureException("unwrapped optimistic conflict"),
                MessageFailureClassification.retryable(MessageFailureCategory.HANDLER));
        assertClassification(
                policy,
                new QueryTimeoutException("query timed out"),
                MessageFailureClassification.retryable(MessageFailureCategory.INFRASTRUCTURE));
        assertClassification(
                policy,
                new RecoverableDataAccessException("connection must be replaced"),
                MessageFailureClassification.retryable(MessageFailureCategory.INFRASTRUCTURE));
        assertClassification(
                policy,
                new DataAccessResourceFailureException("database unavailable"),
                MessageFailureClassification.retryable(MessageFailureCategory.INFRASTRUCTURE));
        assertClassification(
                policy,
                new CannotGetJdbcConnectionException("connection unavailable"),
                MessageFailureClassification.retryable(MessageFailureCategory.INFRASTRUCTURE));
        assertClassification(
                policy,
                new CannotCreateTransactionException("transaction cannot start"),
                MessageFailureClassification.retryable(MessageFailureCategory.INFRASTRUCTURE));
        assertClassification(
                policy,
                new TransactionTimedOutException("transaction timed out"),
                MessageFailureClassification.retryable(MessageFailureCategory.INFRASTRUCTURE));
        assertClassification(
                policy,
                new RuntimeException(new SQLTransientConnectionException("connection interrupted")),
                MessageFailureClassification.retryable(MessageFailureCategory.INFRASTRUCTURE));
        assertClassification(
                policy,
                new DataIntegrityViolationException("permanent constraint violation"),
                MessageFailureClassification.nonRetryable(MessageFailureCategory.INFRASTRUCTURE));
        assertClassification(
                policy,
                new RuntimeException(new SQLIntegrityConstraintViolationException("invalid row")),
                MessageFailureClassification.nonRetryable(MessageFailureCategory.INFRASTRUCTURE));
        assertClassification(
                policy,
                new MessageMappingException("malformed transport envelope", new RuntimeException()),
                MessageFailureClassification.nonRetryable(MessageFailureCategory.MAPPING));
        assertClassification(
                policy,
                new IntegrationEventContractException("invalid contract"),
                MessageFailureClassification.nonRetryable(MessageFailureCategory.CONTRACT));
        assertClassification(
                policy,
                new BusinessRejection("allocation rejected"),
                MessageFailureClassification.nonRetryable(MessageFailureCategory.HANDLER));
        assertClassification(
                policy,
                new NullPointerException("programming defect"),
                MessageFailureClassification.nonRetryable(MessageFailureCategory.HANDLER));
    }

    @Test
    void retainsFourExponentialContainerRetries() {
        BackOffExecution execution = policy().retryBackOff().start();

        assertThat(execution.nextBackOff()).isEqualTo(1_000);
        assertThat(execution.nextBackOff()).isEqualTo(2_000);
        assertThat(execution.nextBackOff()).isEqualTo(4_000);
        assertThat(execution.nextBackOff()).isEqualTo(8_000);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void fixesTheCombinedTransactionAttemptBudget() {
        assertThat(BootstrapConsumerFailurePolicy.LOCAL_OPTIMISTIC_RETRIES).isEqualTo(2);
        assertThat(BootstrapConsumerFailurePolicy.KAFKA_REDELIVERY_RETRIES).isEqualTo(4);
        assertThat(BootstrapConsumerFailurePolicy.MAX_OPTIMISTIC_TRANSACTION_ATTEMPTS)
                .isEqualTo(15);
        assertThat(BootstrapConsumerFailurePolicy.MAX_TRANSIENT_INFRASTRUCTURE_PROCESSING_ATTEMPTS)
                .isEqualTo(5);
    }

    private KafkaConsumerFailurePolicy policy() {
        return new BootstrapKafkaConsumerConfiguration()
                .bootstrapKafkaConsumerFailurePolicyResolver()
                .resolve(new ResolvedMessageSubscription(
                        "any-order-promising-subscriber",
                        "any-order-promising-group",
                        Map.of("any.topic", "any-channel")));
    }

    private OptimisticLockingRetryExhaustedException exhausted() {
        return new OptimisticLockingRetryExhaustedException(
                "allocation-ordering-events", UUID.randomUUID(), 3, new RuntimeException("optimistic lock conflict"));
    }

    private void assertClassification(
            KafkaConsumerFailurePolicy policy, Throwable failure, MessageFailureClassification expected) {
        assertThat(policy.failureClassifier().classify(failure)).isEqualTo(expected);
    }

    private static final class BusinessRejection extends RuntimeException {

        private BusinessRejection(String message) {
            super(message);
        }
    }
}
