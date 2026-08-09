package com.flowzati.archone.messaging.spring.producer.jdbc;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import com.flowzati.archone.messaging.producer.common.MessageProducerImplementation;
import java.util.Objects;

/** Enforces Tram-style caller-owned transaction semantics before an Outbox append. */
public final class CallerTransactionRequiredMessageProducerImplementation
    implements MessageProducerImplementation {

  private final MessagingTransactionTemplate transactionTemplate;
  private final MessageProducerImplementation delegate;

  public CallerTransactionRequiredMessageProducerImplementation(
      MessagingTransactionTemplate transactionTemplate,
      MessageProducerImplementation delegate
  ) {
    this.transactionTemplate = Objects.requireNonNull(
        transactionTemplate, "Messaging transaction template is required");
    this.delegate = Objects.requireNonNull(delegate, "Message producer delegate is required");
  }

  @Override
  public void send(String destination, Message message) {
    transactionTemplate.requireActive();
    delegate.send(destination, message);
  }
}
