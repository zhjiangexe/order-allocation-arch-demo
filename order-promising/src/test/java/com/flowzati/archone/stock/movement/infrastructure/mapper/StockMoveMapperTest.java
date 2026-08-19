package com.flowzati.archone.stock.movement.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.stock.movement.domain.aggregate.StockMove;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Stock move persistence mapping")
class StockMoveMapperTest {

  @Test
  @DisplayName("沒有 picking 的通用搬運可完整往返持久化邊界")
  void shouldRoundTripAStandaloneMoveWithoutAPicking() {
    StockMove standalone = StockMove.confirmed(
        IdGenerator.nextId(),
        null,
        IdGenerator.nextId(),
        "SKU-1",
        IdGenerator.nextId(),
        IdGenerator.nextId(),
        null,
        7,
        Instant.parse("2026-08-03T01:00:00Z"));

    StockMove restored = StockMoveMapper.toDomain(StockMoveMapper.toEntity(standalone));

    assertThat(restored.getPickingId()).isNull();
    assertThat(restored.getId()).isEqualTo(standalone.getId());
    assertThat(restored.getOwnerId()).isEqualTo(standalone.getOwnerId());
    assertThat(restored.getSkuCode()).isEqualTo("SKU-1");
    assertThat(restored.getDemandQuantity()).isEqualTo(7);
    assertThat(restored.getCreatedAt()).isEqualTo(standalone.getCreatedAt());
  }
}
