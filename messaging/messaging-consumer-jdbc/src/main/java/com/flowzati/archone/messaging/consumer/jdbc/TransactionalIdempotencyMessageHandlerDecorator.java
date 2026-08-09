package com.flowzati.archone.messaging.consumer.jdbc;

import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecorator;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorChain;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerDecoratorOrders;
import com.flowzati.archone.messaging.consumer.common.MessageHandlerInvocation;
import com.flowzati.archone.messaging.consumer.common.ProcessingOutcome;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import java.util.Objects;

/**
 * Opens or joins one local transaction, claims Inbox first, and invokes the remaining handler
 * chain only for a new message.
 */
public final class TransactionalIdempotencyMessageHandlerDecorator
    implements MessageHandlerDecorator {

  private final MessagingTransactionTemplate transactionTemplate;
  private final DuplicateMessageDetector duplicateMessageDetector;

  public TransactionalIdempotencyMessageHandlerDecorator(
      MessagingTransactionTemplate transactionTemplate,
      DuplicateMessageDetector duplicateMessageDetector
  ) {
    this.transactionTemplate = Objects.requireNonNull(
        transactionTemplate, "Messaging transaction template is required");
    this.duplicateMessageDetector = Objects.requireNonNull(
        duplicateMessageDetector, "Duplicate message detector is required");
  }

  @Override
  public int order() {
    return MessageHandlerDecoratorOrders.TRANSACTIONAL_IDEMPOTENCY;
  }

  @Override
  public ProcessingOutcome handle(
      MessageHandlerInvocation invocation,
      MessageHandlerDecoratorChain chain
  ) {
    Objects.requireNonNull(invocation, "Message handler invocation is required");
    Objects.requireNonNull(chain, "Message handler decorator chain is required");

    return transactionTemplate.execute(() -> {
      boolean claimed = duplicateMessageDetector.claimIfNew(
          invocation.context().subscriberId(),
          invocation.message().id(),
          invocation.message().type());
      return claimed
          ? chain.invokeNext(invocation)
          : ProcessingOutcome.DUPLICATE;
    });
  }
}
