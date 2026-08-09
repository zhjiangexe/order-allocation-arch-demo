package com.flowzati.archone.messaging.consumer.common;

import java.util.UUID;

/** Atomic idempotency claim contract; persistence and transaction ownership belong to later adapters. */
@FunctionalInterface
public interface DuplicateMessageDetector {

  boolean claimIfNew(String subscriberId, UUID messageId);
}
