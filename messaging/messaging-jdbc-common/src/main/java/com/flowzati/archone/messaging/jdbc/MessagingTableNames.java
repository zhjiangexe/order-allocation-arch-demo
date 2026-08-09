package com.flowzati.archone.messaging.jdbc;

/** Validated application-owned Inbox and Outbox table names. */
public record MessagingTableNames(String inbox, String outbox) {

  public static final String DEFAULT_INBOX = "event_inbox";
  public static final String DEFAULT_OUTBOX = "event_outbox";

  public MessagingTableNames {
    inbox = SqlIdentifiers.requireValid("Inbox table", inbox);
    outbox = SqlIdentifiers.requireValid("Outbox table", outbox);
  }

  public static MessagingTableNames defaults() {
    return new MessagingTableNames(DEFAULT_INBOX, DEFAULT_OUTBOX);
  }
}
