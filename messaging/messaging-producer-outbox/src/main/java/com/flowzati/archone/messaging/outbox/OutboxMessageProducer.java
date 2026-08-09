package com.flowzati.archone.messaging.outbox;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageProducer;
import com.flowzati.archone.messaging.events.EventMessageHeaders;

/**
 * MessageProducer that appends to the transactional Outbox instead of calling a broker.
 *
 * <p>The caller and this repository operation must share the same database transaction. Debezium
 * owns delivery from the committed row to Kafka.
 */
public final class OutboxMessageProducer implements MessageProducer {

  private final OutboxRepo outboxRepo;

  public OutboxMessageProducer(OutboxRepo outboxRepo) {
    this.outboxRepo = outboxRepo;
  }

  @Override
  public void send(String destination, Message message) {
    EventMessageHeaders.validateForPublication(message);
    outboxRepo.append(new Outbox(
        message.id(),
        message.requiredHeader(EventMessageHeaders.EVENT_AGGREGATE_TYPE),
        message.requiredHeader(EventMessageHeaders.EVENT_AGGREGATE_ID),
        message.type(),
        destination,
        message.partitionId(),
        message.payload(),
        message.messageDate()
    ));
  }
}
