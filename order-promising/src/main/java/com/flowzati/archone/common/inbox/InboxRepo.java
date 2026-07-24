package com.flowzati.archone.common.inbox;

public interface InboxRepo {
  boolean claimIfNew(MessageMetadata message);
}
