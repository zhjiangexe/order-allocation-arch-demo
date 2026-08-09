package com.flowzati.archone.messaging.consumer.common;

import java.util.UUID;

/**
 * Atomic idempotency claim contract scoped by a stable local subscriber identity.
 *
 * <p>{@code messageType} is diagnostic persistence metadata; uniqueness is defined only by
 * subscriber ID and message ID.
 */
@FunctionalInterface
public interface DuplicateMessageDetector {

  boolean claimIfNew(String subscriberId, UUID messageId, String messageType);
}
