package com.flowzati.archone.inventory.movement.operation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import com.flowzati.archone.inventory.movement.infrastructure.persistence.jpa.mapper.StockMoveMapper;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Stock move persistence mapping")
class StockMoveMapperTest {

    @Test
    @DisplayName("source-agnostic inbound move 以 operation identity 完整往返持久化邊界")
    void shouldRoundTripAnInboundMoveWithoutSourceLineIdentity() {
        StockMove inbound = new StockMove(
                IdGenerator.nextId(),
                IdGenerator.nextId(),
                IdGenerator.nextId(),
                "SKU-1",
                IdGenerator.nextId(),
                IdGenerator.nextId(),
                null,
                null,
                7,
                MoveState.CONFIRMED,
                Instant.parse("2026-08-03T01:00:00Z"),
                null,
                0L);

        StockMove restored = StockMoveMapper.toDomain(StockMoveMapper.toEntity(inbound));

        assertThat(restored.getStockOperationId()).isEqualTo(inbound.getStockOperationId());
        assertThat(restored.getSourceLineId()).isNull();
        assertThat(restored.getLineSequence()).isNull();
        assertThat(restored.getId()).isEqualTo(inbound.getId());
        assertThat(restored.getOwnerId()).isEqualTo(inbound.getOwnerId());
        assertThat(restored.getSkuCode()).isEqualTo("SKU-1");
        assertThat(restored.getDemandQuantity()).isEqualTo(7);
        assertThat(restored.getCreatedAt()).isEqualTo(inbound.getCreatedAt());
    }
}
