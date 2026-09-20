package com.flowzati.archone.inventory.allocation.domain.valueobject;

import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable, non-authoritative move-to-quant result of pure stock allocation planning. */
public record StockAllocationProposal(
        UUID stockOperationId,
        Long stockOperationVersion,
        Map<UUID, Long> expectedMoveVersions,
        MovementAssignmentPolicy policy,
        List<ProposedMoveLine> proposedMoveLines,
        SkuQuantities missingQuantities) {

    public StockAllocationProposal {
        if (stockOperationId == null
                || stockOperationVersion == null
                || expectedMoveVersions == null
                || expectedMoveVersions.isEmpty()
                || policy == null
                || proposedMoveLines == null
                || missingQuantities == null) {
            throw new IllegalArgumentException("Stock allocation proposal is incomplete");
        }
        expectedMoveVersions = Map.copyOf(expectedMoveVersions);
        proposedMoveLines = List.copyOf(proposedMoveLines);
        if (!missingQuantities.isEmpty() && !proposedMoveLines.isEmpty()) {
            throw new IllegalArgumentException("Insufficient proposal must not contain partial move lines");
        }
    }

    public static StockAllocationProposal ready(StockOperationDemand demand, List<ProposedMoveLine> proposedMoveLines) {
        if (demand == null || proposedMoveLines == null || proposedMoveLines.isEmpty()) {
            throw new IllegalArgumentException("Ready proposal requires demand and proposed move lines");
        }
        requireExactCoverage(demand, proposedMoveLines);
        return new StockAllocationProposal(
                demand.stockOperationId(),
                demand.stockOperationVersion(),
                versions(demand),
                demand.policy(),
                proposedMoveLines,
                SkuQuantities.empty());
    }

    public static StockAllocationProposal insufficient(StockOperationDemand demand, SkuQuantities missingQuantities) {
        if (demand == null || missingQuantities == null || missingQuantities.isEmpty()) {
            throw new IllegalArgumentException("Insufficient proposal requires demand and complete shortfalls");
        }
        return new StockAllocationProposal(
                demand.stockOperationId(),
                demand.stockOperationVersion(),
                versions(demand),
                demand.policy(),
                List.of(),
                missingQuantities);
    }

    public boolean isReady() {
        return missingQuantities.isEmpty();
    }

    private static Map<UUID, Long> versions(StockOperationDemand demand) {
        Map<UUID, Long> versions = new LinkedHashMap<>();
        demand.moves().forEach(move -> versions.put(move.moveId(), move.moveVersion()));
        return versions;
    }

    private static void requireExactCoverage(StockOperationDemand demand, List<ProposedMoveLine> proposedMoveLines) {
        Map<UUID, Integer> required = new HashMap<>();
        demand.moves().forEach(move -> required.put(move.moveId(), move.quantity()));
        Map<UUID, Integer> proposed = new HashMap<>();
        for (ProposedMoveLine proposedMoveLine : proposedMoveLines) {
            if (!required.containsKey(proposedMoveLine.moveId())) {
                throw new IllegalArgumentException("Proposal contains a foreign move");
            }
            proposed.merge(proposedMoveLine.moveId(), proposedMoveLine.quantity(), Math::addExact);
        }
        if (!required.equals(proposed)) {
            throw new IllegalArgumentException("SHIP_COMPLETE proposal must exactly cover every move");
        }
    }
}
