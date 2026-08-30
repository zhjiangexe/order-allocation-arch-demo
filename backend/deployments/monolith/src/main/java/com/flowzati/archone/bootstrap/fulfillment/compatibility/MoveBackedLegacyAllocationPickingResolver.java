package com.flowzati.archone.bootstrap.fulfillment.compatibility;

import com.flowzati.archone.inventory.movement.application.store.StockMoveStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.wms.outbound.application.service.LegacyAllocationPickingResolver;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Resolves retained V1 facts through their canonical moves; it does not revive allocation-demand persistence. */
@Component
public class MoveBackedLegacyAllocationPickingResolver implements LegacyAllocationPickingResolver {

    private final StockMoveStore stockMoveStore;

    public MoveBackedLegacyAllocationPickingResolver(StockMoveStore stockMoveStore) {
        this.stockMoveStore = stockMoveStore;
    }

    @Override
    public UUID resolve(UUID legacyAllocationId, List<UUID> moveIds) {
        if (legacyAllocationId == null
                || moveIds == null
                || moveIds.isEmpty()
                || moveIds.stream().anyMatch(id -> id == null)) {
            throw new IllegalArgumentException("Legacy allocation and movement identities are required");
        }
        Set<UUID> expectedMoveIds = new LinkedHashSet<>(moveIds);
        List<StockMove> moves = stockMoveStore.findByIds(expectedMoveIds);
        Set<UUID> actualMoveIds = moves.stream().map(StockMove::getId).collect(java.util.stream.Collectors.toSet());
        if (!actualMoveIds.equals(expectedMoveIds)) {
            throw new IllegalStateException(
                    "Legacy allocation fact references unknown movements: " + legacyAllocationId);
        }
        Set<UUID> stockOperationIds =
                moves.stream().map(StockMove::getStockOperationId).collect(java.util.stream.Collectors.toSet());
        if (stockOperationIds.size() != 1) {
            throw new IllegalStateException(
                    "Legacy allocation fact spans multiple canonical operations: " + legacyAllocationId);
        }
        return stockOperationIds.iterator().next();
    }
}
