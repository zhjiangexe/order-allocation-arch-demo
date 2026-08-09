package com.flowzati.archone.messaging.producer.common;

import com.flowzati.archone.messaging.api.MessageIdGenerator;
import java.util.UUID;

/** Default framework-neutral UUID message identity generator. */
public final class RandomUuidMessageIdGenerator implements MessageIdGenerator {

  @Override
  public UUID generate() {
    return UUID.randomUUID();
  }
}
