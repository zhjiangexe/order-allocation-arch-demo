package com.flowzati.archone.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

class KafkaMessageMapperTest {

    private final KafkaMessageMapper mapper = new KafkaMessageMapper();

    @Test
    void mapsLegacyDebeziumHeadersWithoutDependingOnTheEventsLayer() {
        UUID id = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-08-09T11:00:00Z");
        ConsumerRecord<String, String> record = record(id, "OrderPlaced.v1", timestamp);

        Message message = mapper.map(record);

        assertThat(message.id()).isEqualTo(id);
        assertThat(message.type()).isEqualTo("OrderPlaced.v1");
        assertThat(message.partitionId()).isEqualTo("order-1");
        assertThat(message.messageDate()).isEqualTo(timestamp);
        assertThat(message.payload()).isEqualTo("{\"eventId\":\"" + id + "\"}");
        assertThat(message.headers())
                .containsEntry(MessageHeaders.DESTINATION, "ordering.order-events")
                .containsEntry(KafkaMessageMapper.EVENT_TYPE_HEADER, "OrderPlaced.v1")
                .containsEntry(KafkaMessageMapper.EVENT_CONTRACT_VERSION_HEADER, "1");
    }

    @Test
    void rejectsMissingPhysicalIdentityHeaders() {
        ConsumerRecord<String, String> missingId = record(UUID.randomUUID(), "OrderPlaced.v1", Instant.now());
        missingId.headers().remove(KafkaMessageMapper.LEGACY_ID_HEADER);
        ConsumerRecord<String, String> missingType = record(UUID.randomUUID(), "OrderPlaced.v1", Instant.now());
        missingType.headers().remove(KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER);

        assertThatThrownBy(() -> mapper.map(missingId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing Kafka header: id");
        assertThatThrownBy(() -> mapper.map(missingType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing Kafka header: eventType");
    }

    @Test
    void restoresSerializedLogicalHeadersWhenADecoderIsConfigured() {
        KafkaMessageMapper restoringMapper = new KafkaMessageMapper(encoded -> Map.of(
                MessageHeaders.CORRELATION_ID,
                "checkout-1",
                MessageHeaders.CAUSATION_ID,
                "command-1",
                MessageHeaders.TRACEPARENT,
                "00-abc-def-01",
                "event-aggregate-type",
                "Order",
                "event-aggregate-id",
                "order-1",
                KafkaMessageMapper.EVENT_CONTRACT_VERSION_HEADER,
                "2"));
        ConsumerRecord<String, String> record =
                record(UUID.randomUUID(), "OrderPlaced.v1", Instant.parse("2026-08-09T12:00:00Z"));
        record.headers().add(KafkaMessageMapper.SERIALIZED_HEADERS, "{serialized}".getBytes(StandardCharsets.UTF_8));

        Message message = restoringMapper.map(record);

        assertThat(message.headers())
                .containsEntry(MessageHeaders.CORRELATION_ID, "checkout-1")
                .containsEntry(MessageHeaders.CAUSATION_ID, "command-1")
                .containsEntry(MessageHeaders.TRACEPARENT, "00-abc-def-01")
                .containsEntry("event-aggregate-type", "Order")
                .containsEntry("event-aggregate-id", "order-1")
                .containsEntry(KafkaMessageMapper.EVENT_CONTRACT_VERSION_HEADER, "2");
    }

    @Test
    void remainsCompatibleWithAnOldConnectorAndAnOldConsumer() {
        ConsumerRecord<String, String> oldConnectorRecord = record(UUID.randomUUID(), "OrderPlaced.v1", Instant.now());
        assertThat(new KafkaMessageMapper(encoded -> Map.of())
                        .map(oldConnectorRecord)
                        .headers())
                .containsEntry(KafkaMessageMapper.EVENT_CONTRACT_VERSION_HEADER, "1");

        oldConnectorRecord
                .headers()
                .add(
                        KafkaMessageMapper.SERIALIZED_HEADERS,
                        "{\"correlation-id\":\"ignored-by-old-consumer\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(new KafkaMessageMapper().map(oldConnectorRecord).headers())
                .doesNotContainKey(MessageHeaders.CORRELATION_ID);
    }

    @Test
    void rejectsSerializedHeadersThatForgePhysicalTransportFacts() {
        KafkaMessageMapper collisionMapper = new KafkaMessageMapper(
                encoded -> Map.of(MessageHeaders.MESSAGE_ID, UUID.randomUUID().toString()));
        ConsumerRecord<String, String> record = record(UUID.randomUUID(), "OrderPlaced.v1", Instant.now());
        record.headers().add(KafkaMessageMapper.SERIALIZED_HEADERS, "{collision}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> collisionMapper.map(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Serialized message header conflicts with physical transport fact: message-id");
    }

    @Test
    void rejectsANullDecoderResultInsteadOfSilentlyDroppingHeaders() {
        KafkaMessageMapper invalidMapper = new KafkaMessageMapper(encoded -> null);
        ConsumerRecord<String, String> record = record(UUID.randomUUID(), "OrderPlaced.v1", Instant.now());
        record.headers().add(KafkaMessageMapper.SERIALIZED_HEADERS, "{}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> invalidMapper.map(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MessageHeadersDecoder returned null");
    }

    private ConsumerRecord<String, String> record(UUID id, String eventType, Instant timestamp) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "ordering.order-events",
                0,
                10L,
                timestamp.toEpochMilli(),
                TimestampType.CREATE_TIME,
                0,
                0,
                "order-1",
                "{\"eventId\":\"" + id + "\"}",
                new RecordHeaders(),
                Optional.empty());
        record.headers().add(KafkaMessageMapper.LEGACY_ID_HEADER, id.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
