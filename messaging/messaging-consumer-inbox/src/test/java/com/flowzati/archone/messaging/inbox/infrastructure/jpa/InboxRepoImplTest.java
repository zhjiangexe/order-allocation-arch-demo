package com.flowzati.archone.messaging.inbox.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.messaging.api.MessageMetadata;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboxRepoImplTest {

  private final JpaEventInboxRepository repository = mock(JpaEventInboxRepository.class);
  private final InboxRepoImpl inboxRepo = new InboxRepoImpl(repository);

  @Test
  void reportsWhetherTheSubscriberClaimWasInserted() {
    UUID eventId = UUID.randomUUID();
    MessageMetadata message = new MessageMetadata(eventId, "OrderPlaced.v1", "allocation");
    when(repository.claimIfNew("allocation", eventId, "OrderPlaced.v1"))
        .thenReturn(1, 0);

    assertThat(inboxRepo.claimIfNew(message)).isTrue();
    assertThat(inboxRepo.claimIfNew(message)).isFalse();
    verify(repository, org.mockito.Mockito.times(2))
        .claimIfNew("allocation", eventId, "OrderPlaced.v1");
  }
}
