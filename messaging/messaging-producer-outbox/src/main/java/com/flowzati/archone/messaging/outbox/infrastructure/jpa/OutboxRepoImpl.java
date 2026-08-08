package com.flowzati.archone.messaging.outbox.infrastructure.jpa;

import com.flowzati.archone.messaging.outbox.Outbox;
import com.flowzati.archone.messaging.outbox.OutboxRepo;
import com.flowzati.archone.messaging.outbox.infrastructure.jpa.entity.OutboxEntity;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JPA Outbox adapter. Bean creation belongs to messaging auto-configuration. */
public class OutboxRepoImpl implements OutboxRepo {

  private final JpaOutboxRepository repository;

  public OutboxRepoImpl(JpaOutboxRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(Outbox outbox) {
    repository.save(new OutboxEntity(
        outbox.eventId(),
        outbox.aggregateType(),
        outbox.aggregateId(),
        outbox.eventType(),
        outbox.route(),
        outbox.partitionKey(),
        outbox.payload(),
        outbox.occurredAt()
    ));
  }
}
