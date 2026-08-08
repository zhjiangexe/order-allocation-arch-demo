package com.flowzati.archone.messaging.inbox.infrastructure.jpa;

import com.flowzati.archone.messaging.api.MessageMetadata;
import com.flowzati.archone.messaging.inbox.InboxRepo;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JPA Inbox adapter. Bean creation belongs to messaging auto-configuration. */
public class InboxRepoImpl implements InboxRepo {

  private final JpaEventInboxRepository repository;

  public InboxRepoImpl(JpaEventInboxRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean claimIfNew(MessageMetadata message) {
    return repository.claimIfNew(
        message.subscriberId(), message.eventId(), message.eventType()) == 1;
  }
}
