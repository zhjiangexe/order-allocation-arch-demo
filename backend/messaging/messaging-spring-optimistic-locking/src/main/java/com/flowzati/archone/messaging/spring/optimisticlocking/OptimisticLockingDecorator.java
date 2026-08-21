package com.flowzati.archone.messaging.spring.optimisticlocking;

import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecorator;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorOrders;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.MessageProcessingStatus;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryOperations;
import org.springframework.core.retry.Retryable;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Tram-style generic optimistic-lock retry around the remaining message handler chain.
 *
 * <p>The decorator is ordered before transactional idempotency, so each execution re-enters Inbox
 * claiming and opens a new transaction. Broker retry and DLT remain outside this local retry.
 */
public final class OptimisticLockingDecorator implements MessageHandlerDecorator {

    private final RetryOperations retryOperations;
    private final List<OptimisticLockingRetryObserver> observers;

    public OptimisticLockingDecorator(
            RetryOperations retryOperations, Collection<OptimisticLockingRetryObserver> observers) {
        this.retryOperations = Objects.requireNonNull(retryOperations, "Optimistic-lock retry operations are required");
        if (observers == null || observers.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Optimistic-lock retry observers are required");
        }
        this.observers = List.copyOf(observers);
    }

    @Override
    public int order() {
        return MessageHandlerDecoratorOrders.APPLICATION_ATTEMPT_MIN;
    }

    @Override
    public MessageProcessingStatus handle(MessageHandlerInvocation invocation, MessageHandlerDecoratorChain chain) {
        AtomicInteger attempts = new AtomicInteger();
        try {
            return retryOperations.execute(new Retryable<>() {
                @Override
                public MessageProcessingStatus execute() {
                    int attempt = attempts.incrementAndGet();
                    if (attempt > 1) {
                        observers.forEach(observer -> observer.onRetry(invocation, attempt));
                    }
                    return chain.invokeNext(invocation);
                }

                @Override
                public String getName() {
                    return invocation.context().subscriberId();
                }
            });
        } catch (RetryException exception) {
            if (!(exception.getCause() instanceof OptimisticLockingFailureException optimisticLockFailure)) {
                throw rethrowOriginalCause(exception);
            }
            int executionCount = Math.max(attempts.get(), exception.getRetryCount() + 1);
            observers.forEach(observer -> observer.onExhausted(invocation, executionCount, optimisticLockFailure));
            throw new OptimisticLockingRetryExhaustedException(
                    invocation.context().subscriberId(),
                    invocation.message().id(),
                    executionCount,
                    optimisticLockFailure);
        }
    }

    private RuntimeException rethrowOriginalCause(RetryException exception) {
        if (exception.getCause() instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("Optimistic-lock retry operation failed unexpectedly", exception);
    }
}
