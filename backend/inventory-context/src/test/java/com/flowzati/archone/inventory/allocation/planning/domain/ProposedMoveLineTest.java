package com.flowzati.archone.inventory.allocation.planning.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.inventory.allocation.domain.ProposedMoveLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProposedMoveLineTest {

    private static final UUID MOVE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STOCK_QUANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void requiresMoveQuantAndPositiveQuantity() {
        assertThatThrownBy(() -> new ProposedMoveLine(null, STOCK_QUANT_ID, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProposedMoveLine(MOVE_ID, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProposedMoveLine(MOVE_ID, STOCK_QUANT_ID, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
