package com.flowzati.archone.common.outbox;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxTest {

  private static final Instant OCCURRED_AT = Instant.parse("2026-07-26T10:00:00Z");

  @Test
  @DisplayName("partitionKey 為 null 時不得建立 Outbox row")
  void shouldRejectNullPartitionKey() {
    assertThatThrownBy(() -> outboxWithPartitionKey(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("partitionKey 為空白時不得建立 Outbox row")
  void shouldRejectBlankPartitionKey() {
    assertThatThrownBy(() -> outboxWithPartitionKey("  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Outbox outboxWithPartitionKey(String partitionKey) {
    return new Outbox(
        UUID.randomUUID(),
        OutboxAggregateTypes.ORDER,
        UUID.randomUUID().toString(),
        "OrderPlacedIntegrationEvent",
        "ordering.order-events",
        partitionKey,
        "{}",
        OCCURRED_AT);
  }
}
