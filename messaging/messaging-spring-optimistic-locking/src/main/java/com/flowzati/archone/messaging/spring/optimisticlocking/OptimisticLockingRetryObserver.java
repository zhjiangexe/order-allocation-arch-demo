package com.flowzati.archone.messaging.spring.optimisticlocking;

import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import org.springframework.dao.OptimisticLockingFailureException;

/** Optional application observation around generic optimistic-lock retry attempts. */
public interface OptimisticLockingRetryObserver {

  default void onRetry(MessageHandlerInvocation invocation, int attempt) {
  }

  default void onExhausted(
      MessageHandlerInvocation invocation,
      int attempts,
      OptimisticLockingFailureException failure
  ) {
  }
}
