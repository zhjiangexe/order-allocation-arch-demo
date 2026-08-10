package com.flowzati.archone.stock.entrypoint.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageContext;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorOrders;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.ProcessingOutcome;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.stock.application.retry.AllocationRetryContext;
import com.flowzati.archone.stock.application.retry.AllocationRetryExecutor;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class AllocationRetryMessageHandlerDecoratorTest {

  @Test
  void retriesTheRemainingChainOutsideTransactionalIdempotency() {
    AtomicInteger attempts = new AtomicInteger();
    AtomicReference<AllocationRetryContext> capturedContext = new AtomicReference<>();
    AllocationRetryExecutor retryExecutor = (context, attempt) -> {
      capturedContext.set(context);
      try {
        attempt.run();
      } catch (OptimisticLockingFailureException exception) {
        attempt.run();
      }
    };
    AllocationRetryMessageHandlerDecorator retry =
        new AllocationRetryMessageHandlerDecorator(retryExecutor);
    MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(
        List.of(retry),
        invocation -> {
          if (attempts.incrementAndGet() == 1) {
            throw new OptimisticLockingFailureException("forced conflict");
          }
          return ProcessingOutcome.PROCESSED;
        });

    ProcessingOutcome outcome = chain.invokeNext(invocation(
        AllocationEventSubscriptions.ORDER_LIFECYCLE));

    assertThat(outcome).isEqualTo(ProcessingOutcome.PROCESSED);
    assertThat(attempts).hasValue(2);
    assertThat(capturedContext.get().operation()).isEqualTo("allocate-order");
    assertThat(retry.order())
        .isBetween(
            MessageHandlerDecoratorOrders.APPLICATION_ATTEMPT_MIN,
            MessageHandlerDecoratorOrders.APPLICATION_ATTEMPT_MAX)
        .isLessThan(MessageHandlerDecoratorOrders.TRANSACTIONAL_IDEMPOTENCY);
  }

  @Test
  void doesNotApplyAllocationRetryToAnotherSubscriber() {
    AtomicInteger retryInvocations = new AtomicInteger();
    AllocationRetryMessageHandlerDecorator retry = new AllocationRetryMessageHandlerDecorator(
        (context, attempt) -> retryInvocations.incrementAndGet());
    MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(
        List.of(retry),
        invocation -> ProcessingOutcome.PROCESSED);

    ProcessingOutcome outcome = chain.invokeNext(invocation(
        OrderingEventSubscriptions.ALLOCATION_RESULTS));

    assertThat(outcome).isEqualTo(ProcessingOutcome.PROCESSED);
    assertThat(retryInvocations).hasValue(0);
  }

  @Test
  void classifiesInventoryAvailabilityAsWaitingDemandAllocation() {
    AtomicReference<AllocationRetryContext> capturedContext = new AtomicReference<>();
    AllocationRetryMessageHandlerDecorator retry = new AllocationRetryMessageHandlerDecorator(
        (context, attempt) -> {
          capturedContext.set(context);
          attempt.run();
        });
    MessageHandlerDecoratorChain chain = MessageHandlerDecoratorChain.create(
        List.of(retry),
        invocation -> ProcessingOutcome.PROCESSED);

    ProcessingOutcome outcome = chain.invokeNext(invocation(
        AllocationEventSubscriptions.INVENTORY_AVAILABILITY,
        StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE,
        "inventory.stock-events"));

    assertThat(outcome).isEqualTo(ProcessingOutcome.PROCESSED);
    assertThat(capturedContext.get().operation())
        .isEqualTo("allocate-waiting-demand-after-availability-increase");
  }

  private MessageHandlerInvocation invocation(String subscriberId) {
    return invocation(
        subscriberId,
        OrderPlacedIntegrationEvent.EVENT_TYPE,
        "ordering.order-events");
  }

  private MessageHandlerInvocation invocation(
      String subscriberId,
      String messageType,
      String logicalChannel
  ) {
    return new MessageHandlerInvocation(
        MessageBuilder.withPayload("{}")
            .withId(UUID.randomUUID())
            .withType(messageType)
            .withPartitionId("order-1")
            .build(),
        new MessageContext(subscriberId, logicalChannel, 1));
  }
}
