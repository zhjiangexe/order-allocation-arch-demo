package com.flowzati.archone.common.inbox;

import java.util.UUID;

public class InboxImpl implements Inbox {
  private final InboxStore inbox;

  public InboxImpl(InboxStore inbox) {
    this.inbox = inbox;
  }

  public boolean claimIfNew(UUID eventId) {
    return inbox.claimIfNew(eventId) == 1;
  }
}
