package com.flowzati.archone.messaging.producer.common;

import com.flowzati.archone.messaging.api.Message;

/**
 * Single generic delivery SPI used by producer common.
 *
 * <p>The destination has already passed through {@code ChannelMapping}. A JDBC Outbox adapter is
 * one implementation; this contract does not imply a direct broker producer.
 */
@FunctionalInterface
public interface MessageProducerImplementation {

    void send(String destination, Message message);
}
