package com.flowzati.archone.messaging.kafka;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** Maps the legacy Debezium/Kafka record envelope to a transport-neutral generic message. */
public final class KafkaMessageMapper {

  public static final String LEGACY_ID_HEADER = "id";
  public static final String LEGACY_EVENT_TYPE_HEADER = "eventType";
  public static final String EVENT_TYPE_HEADER = "event-type";
  public static final String EVENT_CONTRACT_VERSION_HEADER = "event-contract-version";

  public Message map(ConsumerRecord<String, String> record) {
    if (record == null) {
      throw new IllegalArgumentException("Kafka ConsumerRecord is required");
    }
    String recordKey = record.key();
    if (recordKey == null || recordKey.isBlank()) {
      throw new IllegalArgumentException("Missing Kafka record key");
    }
    String eventType = requiredHeader(record, LEGACY_EVENT_TYPE_HEADER);
    MessageBuilder builder = MessageBuilder.withPayload(record.value())
        .withId(UUID.fromString(requiredHeader(record, LEGACY_ID_HEADER)))
        .withType(eventType)
        .withPartitionId(recordKey)
        .withHeader(MessageHeaders.DESTINATION, record.topic())
        .withHeader(EVENT_TYPE_HEADER, eventType)
        .withHeader(EVENT_CONTRACT_VERSION_HEADER, "1");
    if (record.timestamp() >= 0) {
      builder.withMessageDate(Instant.ofEpochMilli(record.timestamp()));
    }
    return builder.build();
  }

  private String requiredHeader(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    if (header == null || header.value() == null || header.value().length == 0) {
      throw new IllegalArgumentException("Missing Kafka header: " + name);
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}
