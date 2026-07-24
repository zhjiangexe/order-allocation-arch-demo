package com.flowzati.archone.common.inbox;

import org.springframework.stereotype.Repository;
@Repository
public class InboxRepoImpl implements InboxRepo {
  private final JpaEventInboxRepository repository;

  public InboxRepoImpl(JpaEventInboxRepository repository) {
    this.repository = repository;
  }

  public boolean claimIfNew(MessageMetadata message) {
    return repository.claimIfNew(message.eventId(), message.eventType()) == 1;
  }
}
