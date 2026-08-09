package com.flowzati.archone.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
    ConsumerRecord<String, String> missingId = record(
        UUID.randomUUID(), "OrderPlaced.v1", Instant.now());
    missingId.headers().remove(KafkaMessageMapper.LEGACY_ID_HEADER);
    ConsumerRecord<String, String> missingType = record(
        UUID.randomUUID(), "OrderPlaced.v1", Instant.now());
    missingType.headers().remove(KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER);

    assertThatThrownBy(() -> mapper.map(missingId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Missing Kafka header: id");
    assertThatThrownBy(() -> mapper.map(missingType))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Missing Kafka header: eventType");
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
    record.headers().add(
        KafkaMessageMapper.LEGACY_ID_HEADER,
        id.toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER,
        eventType.getBytes(StandardCharsets.UTF_8));
    return record;
  }
}
