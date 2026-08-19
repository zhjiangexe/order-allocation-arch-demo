package com.flowzati.archone.messaging.spring.consumer.kafka;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * Reconstructs the original Kafka record from a DLT record without publishing it.
 *
 * <p>A replay operator may explicitly send the returned record. Normal application publication
 * remains on the transactional Outbox path. The original message ID and application headers are
 * deliberately preserved so the same subscriber's Inbox can still detect duplicates.
 */
public final class KafkaDeadLetterReplayRecordFactory {

    private static final Set<String> ARCHONE_DLT_HEADERS = Set.of(
            KafkaDeadLetterHeaders.ORIGINAL_LOGICAL_CHANNEL,
            KafkaDeadLetterHeaders.ORIGINAL_PHYSICAL_DESTINATION,
            KafkaDeadLetterHeaders.SUBSCRIBER_ID,
            KafkaDeadLetterHeaders.CONSUMER_GROUP_ID);

    public <K, V> ProducerRecord<K, V> create(ConsumerRecord<K, V> deadLetterRecord) {
        if (deadLetterRecord == null) {
            throw new IllegalArgumentException("Kafka dead-letter record is required");
        }

        String originalTopic = requiredTextHeader(deadLetterRecord.headers(), KafkaHeaders.DLT_ORIGINAL_TOPIC);
        String physicalDestination =
                requiredTextHeader(deadLetterRecord.headers(), KafkaDeadLetterHeaders.ORIGINAL_PHYSICAL_DESTINATION);
        requiredTextHeader(deadLetterRecord.headers(), KafkaDeadLetterHeaders.ORIGINAL_LOGICAL_CHANNEL);
        requiredTextHeader(deadLetterRecord.headers(), KafkaDeadLetterHeaders.SUBSCRIBER_ID);
        requiredTextHeader(deadLetterRecord.headers(), KafkaDeadLetterHeaders.CONSUMER_GROUP_ID);
        if (!originalTopic.equals(physicalDestination)) {
            throw new IllegalArgumentException("DLT original topic does not match physical destination");
        }

        int originalPartition = requiredIntHeader(deadLetterRecord.headers(), KafkaHeaders.DLT_ORIGINAL_PARTITION);
        requiredLongHeader(deadLetterRecord.headers(), KafkaHeaders.DLT_ORIGINAL_OFFSET);
        long originalTimestamp = requiredLongHeader(deadLetterRecord.headers(), KafkaHeaders.DLT_ORIGINAL_TIMESTAMP);

        return new ProducerRecord<>(
                originalTopic,
                originalPartition,
                originalTimestamp < 0 ? null : originalTimestamp,
                deadLetterRecord.key(),
                deadLetterRecord.value(),
                replayHeaders(deadLetterRecord.headers()));
    }

    private Headers replayHeaders(Headers deadLetterHeaders) {
        RecordHeaders replayHeaders = new RecordHeaders();
        for (Header header : deadLetterHeaders) {
            if (!isDeadLetterMetadata(header.key())) {
                replayHeaders.add(header.key(), header.value());
            }
        }
        return replayHeaders;
    }

    private boolean isDeadLetterMetadata(String name) {
        return name.startsWith("kafka_dlt-")
                || KafkaHeaders.DELIVERY_ATTEMPT.equals(name)
                || ARCHONE_DLT_HEADERS.contains(name);
    }

    private String requiredTextHeader(Headers headers, String name) {
        byte[] value = requiredHeader(headers, name);
        String decoded = new String(value, StandardCharsets.UTF_8);
        if (decoded.isBlank()) {
            throw new IllegalArgumentException("Blank Kafka DLT header: " + name);
        }
        return decoded;
    }

    private int requiredIntHeader(Headers headers, String name) {
        byte[] value = requiredHeader(headers, name);
        if (value.length != Integer.BYTES) {
            throw new IllegalArgumentException("Invalid Kafka DLT integer header: " + name);
        }
        return ByteBuffer.wrap(value).getInt();
    }

    private long requiredLongHeader(Headers headers, String name) {
        byte[] value = requiredHeader(headers, name);
        if (value.length != Long.BYTES) {
            throw new IllegalArgumentException("Invalid Kafka DLT long header: " + name);
        }
        return ByteBuffer.wrap(value).getLong();
    }

    private byte[] requiredHeader(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        if (header == null || header.value() == null) {
            throw new IllegalArgumentException("Missing Kafka DLT header: " + name);
        }
        return header.value();
    }
}
