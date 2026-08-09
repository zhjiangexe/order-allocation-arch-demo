package com.flowzati.archone.messaging.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.MessageMetadata;
import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionCallback;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings("removal")
class DuplicateMessageDetectorInboxRepoTest {

  @Test
  void requiresTheUseCaseTransactionBeforeDelegatingToTheNewDetector() {
    TransactionProbe transaction = new TransactionProbe(false);
    CapturingDetector detector = new CapturingDetector();
    InboxRepo inboxRepo = new DuplicateMessageDetectorInboxRepo(transaction, detector);

    assertThatThrownBy(() -> inboxRepo.claimIfNew(metadata()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No active caller transaction for Inbox claim");
    assertThat(detector.messageId).isNull();
  }

  @Test
  void mapsLegacyMetadataToTheGenericDuplicateClaimContract() {
    TransactionProbe transaction = new TransactionProbe(true);
    CapturingDetector detector = new CapturingDetector();
    InboxRepo inboxRepo = new DuplicateMessageDetectorInboxRepo(transaction, detector);
    MessageMetadata metadata = metadata();

    assertThat(inboxRepo.claimIfNew(metadata)).isTrue();
    assertThat(detector.subscriberId).isEqualTo(metadata.subscriberId());
    assertThat(detector.messageId).isEqualTo(metadata.eventId());
    assertThat(detector.messageType).isEqualTo(metadata.eventType());
  }

  private MessageMetadata metadata() {
    return new MessageMetadata(
        UUID.fromString("00000000-0000-0000-0000-000000000031"),
        "OrderPlaced.v1",
        "stock-allocation");
  }

  private static final class CapturingDetector implements DuplicateMessageDetector {
    private String subscriberId;
    private UUID messageId;
    private String messageType;

    @Override
    public boolean claimIfNew(String subscriberId, UUID messageId, String messageType) {
      this.subscriberId = subscriberId;
      this.messageId = messageId;
      this.messageType = messageType;
      return true;
    }
  }

  private static final class TransactionProbe implements MessagingTransactionTemplate {
    private final boolean active;

    private TransactionProbe(boolean active) {
      this.active = active;
    }

    @Override
    public <T> T execute(MessagingTransactionCallback<T> callback) {
      return callback.execute();
    }

    @Override
    public void requireActive() {
      if (!active) {
        throw new IllegalStateException("transaction required");
      }
    }

    @Override
    public boolean isActive() {
      return active;
    }
  }
}
