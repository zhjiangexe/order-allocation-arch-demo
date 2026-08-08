package com.flowzati.archone.messaging.outbox;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageProducer;

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
    outboxRepo.append(new Outbox(
        message.id(),
        message.aggregateType(),
        message.aggregateId(),
        message.type(),
        destination,
        message.partitionKey(),
        message.payload(),
        message.occurredAt()
    ));
  }
}
