package com.flowzati.archone.messaging.kafka;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.api.MessageHeaders;
import com.flowzati.archone.messaging.api.MessageHeadersDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** Maps the legacy Debezium/Kafka record envelope to a transport-neutral generic message. */
public final class KafkaMessageMapper {

    public static final String LEGACY_ID_HEADER = "id";
    public static final String LEGACY_EVENT_TYPE_HEADER = "eventType";
    public static final String SERIALIZED_HEADERS = "messageHeaders";
    public static final String EVENT_TYPE_HEADER = "event-type";
    public static final String EVENT_CONTRACT_VERSION_HEADER = "event-contract-version";

    private final MessageHeadersDecoder headersDecoder;

    /** Legacy rolling-deployment behavior: safely ignores the newly added serialized header. */
    public KafkaMessageMapper() {
        this(encodedHeaders -> Map.of());
    }

    public KafkaMessageMapper(MessageHeadersDecoder headersDecoder) {
        this.headersDecoder = Objects.requireNonNull(headersDecoder, "Message headers decoder is required");
    }

    public Message map(ConsumerRecord<String, String> record) {
        if (record == null) {
            throw new IllegalArgumentException("Kafka ConsumerRecord is required");
        }
        String recordKey = record.key();
        if (recordKey == null || recordKey.isBlank()) {
            throw new IllegalArgumentException("Missing Kafka record key");
        }
        String eventType = requiredHeader(record, LEGACY_EVENT_TYPE_HEADER);
        UUID messageId = UUID.fromString(requiredHeader(record, LEGACY_ID_HEADER));
        Map<String, String> serializedHeaders = optionalHeader(record, SERIALIZED_HEADERS)
                .map(encoded -> requireDecodedHeaders(headersDecoder.decode(encoded)))
                .orElseGet(Map::of);
        validateNoPhysicalCollision(
                serializedHeaders, messageId, eventType, recordKey, record.topic(), record.timestamp());

        MessageBuilder builder = MessageBuilder.withPayload(record.value())
                .withHeaders(serializedHeaders)
                .withId(messageId)
                .withType(eventType)
                .withPartitionId(recordKey)
                .withHeader(MessageHeaders.DESTINATION, record.topic())
                .withHeader(EVENT_TYPE_HEADER, eventType);
        if (!serializedHeaders.containsKey(EVENT_CONTRACT_VERSION_HEADER)) {
            builder.withHeader(EVENT_CONTRACT_VERSION_HEADER, "1");
        }
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

    private Optional<String> optionalHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null || header.value().length == 0) {
            return Optional.empty();
        }
        return Optional.of(new String(header.value(), StandardCharsets.UTF_8));
    }

    private Map<String, String> requireDecodedHeaders(Map<String, String> decodedHeaders) {
        if (decodedHeaders == null) {
            throw new IllegalStateException("MessageHeadersDecoder returned null");
        }
        return decodedHeaders;
    }

    private void validateNoPhysicalCollision(
            Map<String, String> headers,
            UUID messageId,
            String eventType,
            String partitionId,
            String destination,
            long timestamp) {
        requireAbsentOrEqual(headers, MessageHeaders.MESSAGE_ID, messageId.toString());
        requireAbsentOrEqual(headers, MessageHeaders.MESSAGE_TYPE, eventType);
        requireAbsentOrEqual(headers, MessageHeaders.PARTITION_ID, partitionId);
        requireAbsentOrEqual(headers, MessageHeaders.DESTINATION, destination);
        requireAbsent(headers, MessageHeaders.LOGICAL_CHANNEL);
        if (timestamp >= 0) {
            requireAbsentOrEqual(
                    headers,
                    MessageHeaders.MESSAGE_DATE,
                    Instant.ofEpochMilli(timestamp).toString());
        } else {
            requireAbsent(headers, MessageHeaders.MESSAGE_DATE);
        }
        requireAbsentOrEqual(headers, EVENT_TYPE_HEADER, eventType);
    }

    private void requireAbsent(Map<String, String> headers, String name) {
        if (headers.containsKey(name)) {
            throw new IllegalArgumentException(
                    "Serialized message header conflicts with physical transport fact: " + name);
        }
    }

    private void requireAbsentOrEqual(Map<String, String> headers, String name, String physicalValue) {
        if (headers.containsKey(name) && !physicalValue.equals(headers.get(name))) {
            throw new IllegalArgumentException(
                    "Serialized message header conflicts with physical transport fact: " + name);
        }
    }
}
