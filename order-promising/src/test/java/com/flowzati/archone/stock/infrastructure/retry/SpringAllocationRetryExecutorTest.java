package com.flowzati.archone.stock.infrastructure.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.stock.application.retry.AllocationConcurrencyExhaustedException;
import com.flowzati.archone.stock.application.retry.AllocationRetryContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryOperations;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.dao.OptimisticLockingFailureException;

class SpringAllocationRetryExecutorTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final RetryOperations retryOperations = retryOperationsWithMetrics(meterRegistry);
  private final SpringAllocationRetryExecutor executor = new SpringAllocationRetryExecutor(
      retryOperations, meterRegistry);
  private final AllocationRetryContext context = new AllocationRetryContext(
      "allocate-order", UUID.randomUUID(), UUID.randomUUID().toString(), "SKU-1");

  private static RetryOperations retryOperationsWithMetrics(SimpleMeterRegistry meterRegistry) {
    RetryTemplate retryTemplate = new RetryTemplate(RetryPolicy.builder()
        .includes(OptimisticLockingFailureException.class)
        .maxRetries(2)
        .delay(Duration.ZERO)
        .build());
    retryTemplate.setRetryListener(new AllocationRetryMetricsListener(meterRegistry));
    return retryTemplate;
  }

  @Test
  @DisplayName("RetryTemplate 應在初始呼叫外最多重試兩次")
  void shouldRetryUntilTransactionalUsecaseSucceeds() {
    AtomicInteger attempts = new AtomicInteger();

    executor.execute(context, () -> {
      if (attempts.incrementAndGet() < 3) {
        throw new OptimisticLockingFailureException("conflict");
      }
    });

    assertThat(attempts).hasValue(3);
    assertThat(meterRegistry.find("order_allocation_retry_exhausted_total").counter()).isNull();
    assertThat(meterRegistry.get("order_allocation_retry_attempts_total")
        .tag("operation", "allocate-order").counter().count()).isEqualTo(2.0);
  }

  @Test
  @DisplayName("RetryTemplate 耗盡時應轉為專用例外並累加 metric")
  void shouldExposeExhaustedConcurrencyInsteadOfBusinessBackorder() {
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(() -> executor.execute(context, () -> {
      attempts.incrementAndGet();
      throw new OptimisticLockingFailureException("conflict");
    }))
        .isInstanceOf(AllocationConcurrencyExhaustedException.class)
        .satisfies(exception -> {
          AllocationConcurrencyExhaustedException exhausted =
              (AllocationConcurrencyExhaustedException) exception;
          assertThat(exhausted.attempts()).isEqualTo(3);
          assertThat(exhausted.context()).isEqualTo(context);
        });

    assertThat(attempts).hasValue(3);
    assertThat(meterRegistry.get("order_allocation_retry_exhausted_total")
        .tag("operation", "allocate-order").counter().count()).isEqualTo(1.0);
    assertThat(meterRegistry.get("order_allocation_retry_attempts_total")
        .tag("operation", "allocate-order").counter().count()).isEqualTo(2.0);
  }

  @Test
  @DisplayName("不在 retry policy 內的例外應保留原始語意")
  void shouldRethrowNonRetryableExceptionWithoutConvertingIt() {
    IllegalArgumentException expected = new IllegalArgumentException("invalid command");

    assertThatThrownBy(() -> executor.execute(context, () -> {
      throw expected;
    }))
        .isSameAs(expected);

    assertThat(meterRegistry.find("order_allocation_retry_exhausted_total").counter()).isNull();
    assertThat(meterRegistry.find("order_allocation_retry_attempts_total").counter()).isNull();
  }
}
