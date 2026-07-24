package com.flowzati.archone.common.outbox.infrastructure.repository;

import com.flowzati.archone.common.outbox.Outbox;
import com.flowzati.archone.common.outbox.OutboxRepo;
import com.flowzati.archone.common.outbox.infrastructure.entity.OutboxEntity;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepoImpl implements OutboxRepo {

  private final JpaOutboxRepository repository;

  public OutboxRepoImpl(JpaOutboxRepository repository) {
    this.repository = repository;
  }

  @Override
  public void append(Outbox outbox) {
    repository.save(new OutboxEntity(
        outbox.eventId(),
        outbox.aggregateType(),
        outbox.aggregateId(),
        outbox.eventType(),
        outbox.payload(),
        outbox.occurredAt()
    ));
  }
}
