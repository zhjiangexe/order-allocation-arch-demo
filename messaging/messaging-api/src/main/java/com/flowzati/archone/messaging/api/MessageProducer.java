package com.flowzati.archone.messaging.api;

/**
 * Sends a message to a logical destination.
 *
 * <p>This port does not promise a direct broker call. The current implementation writes an Outbox
 * row in the caller's database transaction; Debezium performs the later Kafka delivery.
 */
@FunctionalInterface
public interface MessageProducer {

  void send(String destination, Message message);
}
