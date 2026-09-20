package com.flowzati.archone.inventory.movement.cancellation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperationCancellation;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationCancellationState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockOperationCancellationTest {

    private static final Instant STARTED = Instant.parse("2026-08-27T08:00:00Z");

    @Test
    void recordsWarehouseDecisionAndLocalCompletionAgainstPickingIdentity() {
        UUID stockOperationId = UUID.randomUUID();
        StockOperationCancellation operation =
                StockOperationCancellation.start(stockOperationId, UUID.randomUUID(), STARTED);

        operation.confirmExternally(STARTED.plusSeconds(1));
        operation.completeLocally(STARTED.plusSeconds(2));

        assertThat(operation.stockOperationId()).isEqualTo(stockOperationId);
        assertThat(operation.state()).isEqualTo(StockOperationCancellationState.COMPLETED);
    }

    @Test
    void cannotCompleteWithoutDurableWarehouseConfirmation() {
        StockOperationCancellation operation =
                StockOperationCancellation.start(UUID.randomUUID(), UUID.randomUUID(), STARTED);

        assertThatThrownBy(() -> operation.completeLocally(STARTED.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("warehouse confirmation");
    }
}
