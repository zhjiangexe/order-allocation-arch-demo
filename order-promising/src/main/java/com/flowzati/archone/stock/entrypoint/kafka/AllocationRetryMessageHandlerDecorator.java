package com.flowzati.archone.stock.entrypoint.kafka;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecorator;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorOrders;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.ProcessingOutcome;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.stock.application.retry.AllocationRetryContext;
import com.flowzati.archone.stock.application.retry.AllocationRetryExecutor;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Retries one complete allocation message attempt outside transactional Inbox handling.
 *
 * <p>This is an order-promising policy, not a generic messaging retry implementation. The
 * decorator is inert for non-allocation subscribers. Retried chain continuations are immutable,
 * so each invocation re-enters the transactional idempotency decorator and opens a new
 * transaction.
 */
@Component
final class AllocationRetryMessageHandlerDecorator implements MessageHandlerDecorator {

  private static final Set<String> ALLOCATION_SUBSCRIBERS = Set.of(
      AllocationEventSubscriptions.ORDER_LIFECYCLE,
      AllocationEventSubscriptions.INVENTORY_AVAILABILITY);

  private final AllocationRetryExecutor retryExecutor;

  AllocationRetryMessageHandlerDecorator(AllocationRetryExecutor retryExecutor) {
    this.retryExecutor = retryExecutor;
  }

  @Override
  public int order() {
    return MessageHandlerDecoratorOrders.APPLICATION_ATTEMPT_MIN;
  }

  @Override
  public ProcessingOutcome handle(
      MessageHandlerInvocation invocation,
      MessageHandlerDecoratorChain chain
  ) {
    if (!ALLOCATION_SUBSCRIBERS.contains(invocation.context().subscriberId())) {
      return chain.invokeNext(invocation);
    }

    AtomicReference<ProcessingOutcome> outcome = new AtomicReference<>();
    retryExecutor.execute(
        retryContext(invocation),
        () -> outcome.set(chain.invokeNext(invocation)));
    ProcessingOutcome completed = outcome.get();
    if (completed == null) {
      throw new IllegalStateException("Allocation retry completed without a processing outcome");
    }
    return completed;
  }

  private AllocationRetryContext retryContext(MessageHandlerInvocation invocation) {
    return new AllocationRetryContext(
        operation(invocation.message().type()),
        invocation.message().id(),
        null,
        null);
  }

  private String operation(String messageType) {
    return switch (messageType) {
      case OrderPlacedIntegrationEvent.EVENT_TYPE -> "allocate-order";
      case OrderCancelledIntegrationEvent.EVENT_TYPE -> "release-reservation";
      case StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE ->
          "allocate-waiting-demand-after-availability-increase";
      default -> "handle-allocation-message";
    };
  }
}
