package com.flowzati.archone.stock.infrastructure.retry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.Retryable;
import org.springframework.stereotype.Component;

/**
 * 記錄每一次因樂觀鎖衝突觸發的重試嘗試，依 operation 分類。跟只計「重試用盡」的
 * {@code order_allocation_retry_exhausted_total} 不同，這裡連最後成功收斂的重試也算。
 */
@Component
public class AllocationRetryMetricsListener implements RetryListener {

  private static final String RETRY_ATTEMPT_METRIC = "order_allocation_retry_attempts_total";

  private final MeterRegistry meterRegistry;

  public AllocationRetryMetricsListener(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  @Override
  public void beforeRetry(RetryPolicy retryPolicy, Retryable<?> retryable, RetryState retryState) {
    Counter.builder(RETRY_ATTEMPT_METRIC)
        .tag("operation", retryable.getName())
        .register(meterRegistry)
        .increment();
  }
}
