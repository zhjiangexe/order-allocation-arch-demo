package com.flowzati.archone.common.inbox;

public record InboundCommand<C>(
    C command,
    MessageMetadata message
) {
  public InboundCommand {
    if (command == null || message == null) {
      throw new IllegalArgumentException("Inbound command and message are required");
    }
  }
}
