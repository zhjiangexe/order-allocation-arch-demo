package com.flowzati.archone.messaging.inbox;

import com.flowzati.archone.messaging.api.MessageMetadata;
import com.flowzati.archone.messaging.consumer.common.DuplicateMessageDetector;
import com.flowzati.archone.messaging.jdbc.MessagingTransactionTemplate;
import java.util.Objects;

/**
 * Temporary old-API bridge that delegates persistence to the new duplicate detector while the
 * application use cases still own their transactions.
 */
public final class DuplicateMessageDetectorInboxRepo implements InboxRepo {

  private final MessagingTransactionTemplate transactionTemplate;
  private final DuplicateMessageDetector duplicateMessageDetector;

  public DuplicateMessageDetectorInboxRepo(
      MessagingTransactionTemplate transactionTemplate,
      DuplicateMessageDetector duplicateMessageDetector
  ) {
    this.transactionTemplate = Objects.requireNonNull(
        transactionTemplate, "Messaging transaction template is required");
    this.duplicateMessageDetector = Objects.requireNonNull(
        duplicateMessageDetector, "Duplicate message detector is required");
  }

  @Override
  public boolean claimIfNew(MessageMetadata message) {
    Objects.requireNonNull(message, "Message metadata is required");
    if (!transactionTemplate.isActive()) {
      throw new IllegalStateException("No active caller transaction for Inbox claim");
    }
    return duplicateMessageDetector.claimIfNew(
        message.subscriberId(), message.eventId(), message.eventType());
  }
}
