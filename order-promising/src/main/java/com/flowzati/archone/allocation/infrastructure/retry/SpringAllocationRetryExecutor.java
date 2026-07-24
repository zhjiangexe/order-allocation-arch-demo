package com.flowzati.archone.allocation.infrastructure.retry;

import com.flowzati.archone.allocation.application.retry.AllocationConcurrencyExhaustedException;
import com.flowzati.archone.allocation.application.retry.AllocationRetryContext;
import com.flowzati.archone.allocation.application.retry.AllocationRetryExecutor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryOperations;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/** Spring Retry adapter for the application retry port. */
@Component
public class SpringAllocationRetryExecutor implements AllocationRetryExecutor {

  private static final String RETRY_EXHAUSTED_METRIC = "order_allocation_retry_exhausted_total";
  private static final Logger log = LoggerFactory.getLogger(SpringAllocationRetryExecutor.class);

  private final RetryOperations retryOperations;
  private final MeterRegistry meterRegistry;

  public SpringAllocationRetryExecutor(
      @Qualifier("allocationRetryOperations") RetryOperations retryOperations,
      MeterRegistry meterRegistry
  ) {
    this.retryOperations = retryOperations;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public void execute(AllocationRetryContext context, Runnable transactionalUsecase) {
    try {
      retryOperations.execute(() -> {
        transactionalUsecase.run();
        return null;
      });
    } catch (RetryException exception) {
      if (!(exception.getCause() instanceof OptimisticLockingFailureException optimisticLockException)) {
        throw rethrowOriginalCause(exception);
      }
      int attempts = exception.getRetryCount() + 1;
      recordExhausted(context, optimisticLockException, attempts);
      throw new AllocationConcurrencyExhaustedException(context, attempts, optimisticLockException);
    }
  }

  private RuntimeException rethrowOriginalCause(RetryException exception) {
    if (exception.getCause() instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    return new IllegalStateException("Allocation retry operation failed unexpectedly", exception);
  }

  private void recordExhausted(
      AllocationRetryContext context,
      OptimisticLockingFailureException exception,
      int attempts
  ) {
    Counter.builder(RETRY_EXHAUSTED_METRIC)
        .tag("operation", context.operation())
        .register(meterRegistry)
        .increment();
    log.atError()
        .addKeyValue("operation", context.operation())
        .addKeyValue("eventId", context.eventId())
        .addKeyValue("orderId", context.orderId())
        .addKeyValue("sku", context.sku())
        .addKeyValue("attempts", attempts)
        .addKeyValue("exceptionType", exception.getClass().getSimpleName())
        .setCause(exception)
        .log("Allocation optimistic-lock retry exhausted");
  }
}
