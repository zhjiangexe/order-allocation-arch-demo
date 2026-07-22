package com.flowzati.archone.common.inbox;

import java.util.UUID;

public interface Inbox {
  boolean claimIfNew(UUID eventId);
}
