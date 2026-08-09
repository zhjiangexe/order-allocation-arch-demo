package com.flowzati.archone.messaging.producer.jdbc;

import com.flowzati.archone.messaging.api.MessageHeaders;
import java.util.Set;

/** Headers represented by dedicated Outbox/Kafka fields rather than serialized header JSON. */
public final class OutboxPhysicalHeaders {

  public static final Set<String> ALL = Set.of(
      MessageHeaders.MESSAGE_ID,
      MessageHeaders.MESSAGE_TYPE,
      MessageHeaders.LOGICAL_CHANNEL,
      MessageHeaders.DESTINATION,
      MessageHeaders.PARTITION_ID,
      MessageHeaders.MESSAGE_DATE);

  private OutboxPhysicalHeaders() {
  }
}
