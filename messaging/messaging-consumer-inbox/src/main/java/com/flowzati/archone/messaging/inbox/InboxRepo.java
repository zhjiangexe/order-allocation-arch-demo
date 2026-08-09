package com.flowzati.archone.messaging.inbox;

import com.flowzati.archone.messaging.api.MessageMetadata;

/**
 * Claims an inbound message once within a stable subscriber scope.
 *
 * <p>Migration status: application use cases move to boundary-owned transactional idempotency in
 * Gate E; this compatibility API is removed with the legacy Inbox adapter in Gate I.
 */
public interface InboxRepo {

  boolean claimIfNew(MessageMetadata message);
}
