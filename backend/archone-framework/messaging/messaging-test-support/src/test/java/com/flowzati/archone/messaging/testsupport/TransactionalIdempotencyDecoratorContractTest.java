package com.flowzati.archone.messaging.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorOrders;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.MessageProcessingStatus;
import com.flowzati.archone.messaging.consumer.jdbc.TransactionalIdempotencyMessageHandlerDecorator;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionCallback;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TransactionalIdempotencyDecoratorContractTest {

    @Test
    void fixesTheTransactionClaimAndHandlerOrder() {
        List<String> calls = new ArrayList<>();
        TransactionProbe transaction = new TransactionProbe(calls);
        CapturingDetector detector = new CapturingDetector(transaction, calls, true);
        var decorator = new TransactionalIdempotencyMessageHandlerDecorator(transaction, detector);
        MessageHandlerInvocation invocation = invocation();
        MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(List.of(decorator), handled -> {
            assertThat(transaction.isActive()).isTrue();
            calls.add("handler");
            return MessageProcessingStatus.PROCESSED;
        });

        assertThat(chain.invokeNext(invocation)).isEqualTo(MessageProcessingStatus.PROCESSED);
        assertThat(decorator.order()).isEqualTo(MessageHandlerDecoratorOrders.TRANSACTIONAL_IDEMPOTENCY);
        assertThat(calls).containsExactly("transaction-begin", "claim", "handler", "transaction-commit");
        assertThat(detector.subscriberId).isEqualTo("stock-allocation");
        assertThat(detector.messageId).isEqualTo(MessageFixtures.MESSAGE_ID);
        assertThat(detector.messageType).isEqualTo("test-message.v1");
    }

    @Test
    void returnsDuplicateWithoutInvokingTheRemainingChain() {
        List<String> calls = new ArrayList<>();
        TransactionProbe transaction = new TransactionProbe(calls);
        CapturingDetector detector = new CapturingDetector(transaction, calls, false);
        AtomicBoolean handlerCalled = new AtomicBoolean();
        MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(
                List.of(new TransactionalIdempotencyMessageHandlerDecorator(transaction, detector)), handled -> {
                    handlerCalled.set(true);
                    return MessageProcessingStatus.PROCESSED;
                });

        assertThat(chain.invokeNext(invocation())).isEqualTo(MessageProcessingStatus.DUPLICATE);
        assertThat(handlerCalled).isFalse();
        assertThat(calls).containsExactly("transaction-begin", "claim", "transaction-commit");
    }

    @Test
    void propagatesTheOriginalHandlerExceptionThroughTheTransactionBoundary() {
        List<String> calls = new ArrayList<>();
        TransactionProbe transaction = new TransactionProbe(calls);
        CapturingDetector detector = new CapturingDetector(transaction, calls, true);
        IllegalStateException failure = new IllegalStateException("handler failed");
        MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(
                List.of(new TransactionalIdempotencyMessageHandlerDecorator(transaction, detector)), handled -> {
                    calls.add("handler");
                    throw failure;
                });

        assertThatThrownBy(() -> chain.invokeNext(invocation())).isSameAs(failure);
        assertThat(calls).containsExactly("transaction-begin", "claim", "handler", "transaction-rollback");
    }

    private MessageHandlerInvocation invocation() {
        return new MessageHandlerInvocation(
                MessageFixtures.message(), new MessageContext("stock-allocation", "orders", 1));
    }

    private static final class CapturingDetector implements DuplicateMessageDetector {
        private final TransactionProbe transaction;
        private final List<String> calls;
        private final boolean claimed;
        private String subscriberId;
        private UUID messageId;
        private String messageType;

        private CapturingDetector(TransactionProbe transaction, List<String> calls, boolean claimed) {
            this.transaction = transaction;
            this.calls = calls;
            this.claimed = claimed;
        }

        @Override
        public boolean claimIfNew(String subscriberId, UUID messageId, String messageType) {
            assertThat(transaction.isActive()).isTrue();
            calls.add("claim");
            this.subscriberId = subscriberId;
            this.messageId = messageId;
            this.messageType = messageType;
            return claimed;
        }
    }

    private static final class TransactionProbe implements MessagingTransactionTemplate {
        private final List<String> calls;
        private boolean active;

        private TransactionProbe(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public <T> T execute(MessagingTransactionCallback<T> callback) {
            calls.add("transaction-begin");
            active = true;
            try {
                T result = callback.execute();
                calls.add("transaction-commit");
                return result;
            } catch (RuntimeException | Error failure) {
                calls.add("transaction-rollback");
                throw failure;
            } finally {
                active = false;
            }
        }

        @Override
        public void requireActive() {
            if (!active) {
                throw new IllegalStateException("transaction required");
            }
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }
}
