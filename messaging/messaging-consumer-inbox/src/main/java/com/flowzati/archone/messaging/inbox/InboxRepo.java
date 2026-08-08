package com.flowzati.archone.messaging.inbox;

import com.flowzati.archone.messaging.api.MessageMetadata;

/** Claims an inbound message once within a stable subscriber scope. */
public interface InboxRepo {

  boolean claimIfNew(MessageMetadata message);
}
