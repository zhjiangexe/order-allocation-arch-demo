package com.flowzati.archone.inventory.allocation.infrastructure.persistence.jpa.store;

import com.flowzati.archone.inventory.allocation.application.store.OrderStockMovementStore;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jpa.model.OrderAllocationSourceLineEntity;
import com.flowzati.archone.inventory.allocation.infrastructure.persistence.jpa.repository.JpaOrderAllocationSourceRepository;
import com.flowzati.archone.inventory.movement.application.invocation.MovementLine;
import com.flowzati.archone.inventory.movement.application.invocation.RegisterStockOperationCommand;
import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Order-specific normalization ends here; shared Inventory code receives only movement facts. */
@Component
public class OrderStockMovementStoreAdapter implements OrderStockMovementStore {

    private final JpaOrderAllocationSourceRepository jpaOrderAllocationSourceRepository;

    public OrderStockMovementStoreAdapter(JpaOrderAllocationSourceRepository jpaOrderAllocationSourceRepository) {
        this.jpaOrderAllocationSourceRepository = jpaOrderAllocationSourceRepository;
    }

    @Override
    public Optional<RegisterStockOperationCommand> find(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("Order ID is required");
        }
        // 只讀 Order 發布給 Inventory 的 source projection。
        List<OrderAllocationSourceLineEntity> rows =
                jpaOrderAllocationSourceRepository.findBySourceIdOrderBySourceLineIdAsc(orderId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        OrderAllocationSourceLineEntity first = rows.getFirst();
        // 一個 allocation unit 必須落在同一貨主、作業類型與起訖庫位。
        requireOneResolvedScope(orderId, first, rows);

        // Order 專屬語彙到此為止，後續只處理 source-neutral movement facts。
        return Optional.of(new RegisterStockOperationCommand(
                first.getStockOperationTypeId(),
                StockOperationDirection.OUTBOUND,
                StockOperationSource.primaryOrder(orderId.toString()),
                first.getOwnerId(),
                first.getSourceLocationId(),
                first.getDestinationLocationId(),
                MovementAssignmentPolicy.SHIP_COMPLETE,
                first.getEnqueuedAt(),
                first.getRequiredBy(),
                first.getReleasePriority(),
                rows.stream()
                        .map(row -> new MovementLine(
                                row.getSourceLineReferenceId().toString(), row.getSkuCode(), row.getQuantity()))
                        .toList()));
    }

    private static void requireOneResolvedScope(
            UUID orderId, OrderAllocationSourceLineEntity first, List<OrderAllocationSourceLineEntity> rows) {
        if (first.getStockOperationTypeId() == null
                || first.getSourceLocationId() == null
                || first.getDestinationLocationId() == null) {
            throw new IllegalStateException("Facility for order " + orderId + " has no outbound operation type");
        }
        if (rows.stream()
                .anyMatch(row -> !first.getOwnerId().equals(row.getOwnerId())
                        || !first.getFacilityId().equals(row.getFacilityId())
                        || !first.getStockOperationTypeId().equals(row.getStockOperationTypeId())
                        || !first.getSourceLocationId().equals(row.getSourceLocationId())
                        || !first.getDestinationLocationId().equals(row.getDestinationLocationId()))) {
            throw new IllegalStateException("One source movement unit spans multiple inventory scopes");
        }
    }
}
