package com.flowzati.archone.messaging.api;

/** Temporary application boundary carrying a mapped command and its idempotency metadata. */
public record InboundCommand<C>(C command, MessageMetadata message) {
  public InboundCommand {
    if (command == null || message == null) {
      throw new IllegalArgumentException("Inbound command and message are required");
    }
  }
}
