package com.flowzati.archone.bootstrap.fulfillment.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.movement.application.repo.StockMoveStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MoveBackedLegacyAllocationPickingResolverTest {

    private final StockMoveStore stockMoveStore = mock(StockMoveStore.class);
    private final MoveBackedLegacyAllocationPickingResolver resolver =
            new MoveBackedLegacyAllocationPickingResolver(stockMoveStore);

    @Test
    void resolvesOneCanonicalPickingFromEveryLegacyMovement() {
        UUID legacyAllocationId = UUID.randomUUID();
        UUID stockOperationId = UUID.randomUUID();
        StockMove first = move(UUID.randomUUID(), stockOperationId);
        StockMove second = move(UUID.randomUUID(), stockOperationId);
        when(stockMoveStore.findByIds(Set.of(first.getId(), second.getId()))).thenReturn(List.of(first, second));

        assertThat(resolver.resolve(legacyAllocationId, List.of(first.getId(), second.getId())))
                .isEqualTo(stockOperationId);
    }

    @Test
    void rejectsUnknownLegacyMovementsInsteadOfInventingAPickingIdentity() {
        UUID legacyAllocationId = UUID.randomUUID();
        UUID moveId = UUID.randomUUID();
        when(stockMoveStore.findByIds(Set.of(moveId))).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolve(legacyAllocationId, List.of(moveId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(legacyAllocationId.toString());
    }

    @Test
    void rejectsAReplayThatSpansMultipleCanonicalPickings() {
        UUID legacyAllocationId = UUID.randomUUID();
        StockMove first = move(UUID.randomUUID(), UUID.randomUUID());
        StockMove second = move(UUID.randomUUID(), UUID.randomUUID());
        when(stockMoveStore.findByIds(Set.of(first.getId(), second.getId()))).thenReturn(List.of(first, second));

        assertThatThrownBy(() -> resolver.resolve(legacyAllocationId, List.of(first.getId(), second.getId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple canonical operations");
    }

    private static StockMove move(UUID moveId, UUID stockOperationId) {
        return StockMove.confirmed(
                moveId,
                stockOperationId,
                UUID.randomUUID(),
                "SKU-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                Instant.parse("2026-08-27T10:00:00Z"));
    }
}
