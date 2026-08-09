package com.flowzati.archone.messaging.consumer.common;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageContext;

/** Immutable input passed through one ordered semantic handler chain. */
public record MessageHandlerInvocation(Message message, MessageContext context) {

  public MessageHandlerInvocation {
    if (message == null || context == null) {
      throw new IllegalArgumentException("Message handler invocation fields are required");
    }
  }
}
