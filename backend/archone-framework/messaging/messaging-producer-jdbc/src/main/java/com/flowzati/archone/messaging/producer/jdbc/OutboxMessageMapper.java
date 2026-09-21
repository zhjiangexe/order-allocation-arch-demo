package com.flowzati.archone.messaging.producer.jdbc;

import com.flowzati.archone.messaging.api.Message;

/** Maps the normalized generic message into the application-owned Outbox table shape. */
@FunctionalInterface
public interface OutboxMessageMapper {

    OutboxMessage map(String destination, Message message);
}
