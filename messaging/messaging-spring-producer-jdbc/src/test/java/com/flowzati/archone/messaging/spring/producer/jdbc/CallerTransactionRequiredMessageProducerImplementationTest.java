package com.flowzati.archone.messaging.spring.producer.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionCallback;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import com.flowzati.archone.messaging.producer.common.MessageProducerImplementation;
import org.junit.jupiter.api.Test;

class CallerTransactionRequiredMessageProducerImplementationTest {

  @Test
  void delegatesOnlyWhenTheCallerTransactionIsActive() {
    TransactionProbe transaction = new TransactionProbe(true);
    CapturingProducer delegate = new CapturingProducer();
    CallerTransactionRequiredMessageProducerImplementation producer =
        new CallerTransactionRequiredMessageProducerImplementation(transaction, delegate);
    Message message = MessageBuilder.withPayload("{}").build();

    producer.send("orders", message);

    assertThat(transaction.checked).isTrue();
    assertThat(delegate.message).isSameAs(message);
  }

  @Test
  void failsBeforeTheOutboxDelegateWhenThereIsNoCallerTransaction() {
    TransactionProbe transaction = new TransactionProbe(false);
    CapturingProducer delegate = new CapturingProducer();
    CallerTransactionRequiredMessageProducerImplementation producer =
        new CallerTransactionRequiredMessageProducerImplementation(transaction, delegate);

    assertThatThrownBy(() -> producer.send(
        "orders", MessageBuilder.withPayload("{}").build()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("transaction required");
    assertThat(delegate.message).isNull();
  }

  private static final class TransactionProbe implements MessagingTransactionTemplate {
    private final boolean active;
    private boolean checked;

    private TransactionProbe(boolean active) {
      this.active = active;
    }

    @Override
    public <T> T execute(MessagingTransactionCallback<T> callback) {
      return callback.execute();
    }

    @Override
    public void requireActive() {
      checked = true;
      if (!active) {
        throw new IllegalStateException("transaction required");
      }
    }

    @Override
    public boolean isActive() {
      return active;
    }
  }

  private static final class CapturingProducer implements MessageProducerImplementation {
    private Message message;

    @Override
    public void send(String destination, Message message) {
      this.message = message;
    }
  }
}
