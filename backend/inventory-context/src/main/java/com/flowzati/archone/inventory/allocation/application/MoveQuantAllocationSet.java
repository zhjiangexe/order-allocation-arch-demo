package com.flowzati.archone.inventory.allocation.application;

import com.flowzati.archone.inventory.allocation.application.exception.StaleAllocationSetException;
import com.flowzati.archone.inventory.allocation.domain.entity.StockMoveLine;
import com.flowzati.archone.inventory.allocation.domain.valueobject.StockAllocationProposal;
import com.flowzati.archone.inventory.balance.application.policy.StockWriteOrder;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.movement.application.StockOperationComposite;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Ephemeral move-to-quant quantities shared by allocation commit, release and completion. */
public final class MoveQuantAllocationSet {

    private final List<MoveQuantAllocation> allocations;
    private final Map<UUID, Integer> quantitiesByStockQuant;
    private final Set<UUID> stockQuantIds;

    private MoveQuantAllocationSet(List<MoveQuantAllocation> allocations) {
        if (allocations == null || allocations.isEmpty()) {
            throw new IllegalStateException("Move-to-quant allocation set requires allocation detail");
        }
        LinkedHashMap<UUID, Integer> quantities = new LinkedHashMap<>();
        HashSet<MoveQuantKey> uniqueDetails = new HashSet<>();
        for (MoveQuantAllocation allocation : allocations) {
            if (allocation == null
                    || allocation.moveId() == null
                    || allocation.stockQuantId() == null
                    || allocation.quantity() <= 0
                    || !uniqueDetails.add(new MoveQuantKey(allocation.moveId(), allocation.stockQuantId()))) {
                throw new IllegalStateException("Move-to-quant allocation set contains invalid or duplicate detail");
            }
            quantities.merge(allocation.stockQuantId(), allocation.quantity(), Math::addExact);
        }
        this.allocations = List.copyOf(allocations);
        this.quantitiesByStockQuant = Map.copyOf(quantities);
        this.stockQuantIds = Set.copyOf(new LinkedHashSet<>(quantities.keySet()));
    }

    public static MoveQuantAllocationSet fromProposal(StockAllocationProposal proposal) {
        return new MoveQuantAllocationSet(proposal.proposedMoveLines().stream()
                .map(line -> new MoveQuantAllocation(line.moveId(), line.stockQuantId(), line.quantity()))
                .toList());
    }

    public static MoveQuantAllocationSet fromMoveLines(Collection<StockMoveLine> moveLines) {
        return new MoveQuantAllocationSet(moveLines.stream()
                .map(line -> new MoveQuantAllocation(line.moveId(), line.stockQuantId(), line.quantity()))
                .toList());
    }

    public Set<UUID> stockQuantIds() {
        return stockQuantIds;
    }

    public int quantityForStockQuant(UUID stockQuantId) {
        Integer quantity = quantitiesByStockQuant.get(stockQuantId);
        if (quantity == null) {
            throw new IllegalStateException("Stock quant is outside this move-to-quant allocation set");
        }
        return quantity;
    }

    /** Validates loaded quant identity and scope, then returns the one global mutation order. */
    public List<StockQuant> validateAndOrder(
            Collection<StockQuant> loadedStockQuants,
            StockOperationComposite operationComposite,
            LocalDate today,
            boolean requireAvailableToPromise) {
        Map<UUID, StockQuant> stockQuantsById =
                loadedStockQuants.stream().collect(Collectors.toMap(StockQuant::getId, Function.identity()));
        if (!stockQuantsById.keySet().equals(stockQuantIds)) {
            throw new StaleAllocationSetException("One or more allocated stock quants no longer exist");
        }

        for (MoveQuantAllocation allocation : allocations) {
            StockMove move = operationComposite.move(allocation.moveId());
            StockQuant stockQuant = stockQuantsById.get(allocation.stockQuantId());
            if (!operationComposite.operation().ownerId().equals(stockQuant.getOwnerId())
                    || !operationComposite.operation().fromLocationId().equals(stockQuant.getLocationId())
                    || !move.getSkuCode().equals(stockQuant.getSkuCode())) {
                throw new IllegalStateException("Move-to-quant allocation scopes do not match");
            }
        }
        if (today != null && loadedStockQuants.stream().anyMatch(stockQuant -> stockQuant.isExpired(today))) {
            throw new StaleAllocationSetException("Move-to-quant allocation contains an expired stock quant");
        }
        if (requireAvailableToPromise
                && quantitiesByStockQuant.entrySet().stream()
                        .anyMatch(entry -> !stockQuantsById.get(entry.getKey()).canReserve(entry.getValue()))) {
            throw new StaleAllocationSetException(
                    "Move-to-quant allocation exceeds current available-to-promise quantity");
        }
        return loadedStockQuants.stream()
                .sorted(StockWriteOrder.BY_GLOBAL_ORDER)
                .toList();
    }

    public List<MoveQuantAllocation> allocations() {
        return allocations.stream()
                .sorted(Comparator.comparing(MoveQuantAllocation::moveId)
                        .thenComparing(MoveQuantAllocation::stockQuantId))
                .toList();
    }

    public record MoveQuantAllocation(UUID moveId, UUID stockQuantId, int quantity) {}

    private record MoveQuantKey(UUID moveId, UUID stockQuantId) {}
}
