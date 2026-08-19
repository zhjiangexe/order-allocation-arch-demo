package com.flowzati.archone.messaging.spring.optimisticlocking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorOrders;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.ProcessingOutcome;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.OptimisticLockingFailureException;

class OptimisticLockingDecoratorTest {

    @Test
    void retriesTheRemainingChainForAnySubscriber() {
        AtomicInteger attempts = new AtomicInteger();
        RecordingObserver observer = new RecordingObserver();
        OptimisticLockingDecorator decorator = decorator(2, observer);
        MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(List.of(decorator), invocation -> {
            if (attempts.incrementAndGet() < 3) {
                throw new OptimisticLockingFailureException("forced conflict");
            }
            return ProcessingOutcome.PROCESSED;
        });

        ProcessingOutcome outcome = chain.invokeNext(invocation("any-subscriber"));

        assertThat(outcome).isEqualTo(ProcessingOutcome.PROCESSED);
        assertThat(attempts).hasValue(3);
        assertThat(observer.retries).hasValue(2);
        assertThat(decorator.order())
                .isBetween(
                        MessageHandlerDecoratorOrders.APPLICATION_ATTEMPT_MIN,
                        MessageHandlerDecoratorOrders.APPLICATION_ATTEMPT_MAX)
                .isLessThan(MessageHandlerDecoratorOrders.TRANSACTIONAL_IDEMPOTENCY);
    }

    @Test
    void exposesGenericMessageIdentityWhenLocalRetriesAreExhausted() {
        UUID messageId = UUID.randomUUID();
        RecordingObserver observer = new RecordingObserver();
        MessageHandlerDecoratorChain chain =
                MessageHandlerDecoratorChain.create(List.of(decorator(2, observer)), invocation -> {
                    throw new OptimisticLockingFailureException("forced conflict");
                });

        assertThatThrownBy(() -> chain.invokeNext(invocation("stock-allocation", messageId)))
                .isInstanceOf(OptimisticLockingRetryExhaustedException.class)
                .satisfies(exception -> {
                    OptimisticLockingRetryExhaustedException exhausted =
                            (OptimisticLockingRetryExhaustedException) exception;
                    assertThat(exhausted.subscriberId()).isEqualTo("stock-allocation");
                    assertThat(exhausted.messageId()).isEqualTo(messageId);
                    assertThat(exhausted.attempts()).isEqualTo(3);
                    assertThat(exhausted.getCause()).isInstanceOf(OptimisticLockingFailureException.class);
                });

        assertThat(observer.retries).hasValue(2);
        assertThat(observer.exhausted).hasValue(1);
    }

    @Test
    void propagatesANonRetryableFailureWithoutConversion() {
        IllegalArgumentException expected = new IllegalArgumentException("invalid contract");
        RecordingObserver observer = new RecordingObserver();
        MessageHandlerDecoratorChain chain =
                MessageHandlerDecoratorChain.create(List.of(decorator(2, observer)), invocation -> {
                    throw expected;
                });

        assertThatThrownBy(() -> chain.invokeNext(invocation("any-subscriber"))).isSameAs(expected);
        assertThat(observer.retries).hasValue(0);
        assertThat(observer.exhausted).hasValue(0);
    }

    private OptimisticLockingDecorator decorator(int maxRetries, OptimisticLockingRetryObserver observer) {
        RetryTemplate retryTemplate = new RetryTemplate(RetryPolicy.builder()
                .includes(OptimisticLockingFailureException.class)
                .maxRetries(maxRetries)
                .delay(Duration.ZERO)
                .build());
        return new OptimisticLockingDecorator(retryTemplate, List.of(observer));
    }

    private MessageHandlerInvocation invocation(String subscriberId) {
        return invocation(subscriberId, UUID.randomUUID());
    }

    private MessageHandlerInvocation invocation(String subscriberId, UUID messageId) {
        return new MessageHandlerInvocation(
                MessageBuilder.withPayload("{}")
                        .withId(messageId)
                        .withType("TestEvent.v1")
                        .withPartitionId("aggregate-1")
                        .build(),
                new MessageContext(subscriberId, "test.events", 1));
    }

    private static final class RecordingObserver implements OptimisticLockingRetryObserver {

        private final AtomicInteger retries = new AtomicInteger();
        private final AtomicInteger exhausted = new AtomicInteger();

        @Override
        public void onRetry(MessageHandlerInvocation invocation, int attempt) {
            retries.incrementAndGet();
        }

        @Override
        public void onExhausted(
                MessageHandlerInvocation invocation, int attempts, OptimisticLockingFailureException failure) {
            exhausted.incrementAndGet();
        }
    }
}
