package com.flowzati.archone.inventory.balance.application.usecase;

import com.flowzati.archone.inventory.balance.application.command.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.balance.application.event.OutboundMovementEventPublisher;
import com.flowzati.archone.inventory.balance.application.result.CompleteOutboundMovementsResult;
import com.flowzati.archone.inventory.balance.application.result.CompleteOutboundMovementsResult.Status;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.balance.domain.event.OutboundMovementsCompleted;
import com.flowzati.archone.inventory.balance.domain.repository.StockQuantRepository;
import com.flowzati.archone.inventory.balance.domain.service.StockWriteOrder;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockPicking;
import com.flowzati.archone.inventory.movement.domain.entity.StockMoveLine;
import com.flowzati.archone.inventory.movement.domain.repository.StockMoveRepository;
import com.flowzati.archone.inventory.movement.domain.repository.StockPickingRepository;
import com.flowzati.archone.inventory.movement.domain.type.MoveState;
import com.flowzati.archone.inventory.movement.domain.type.PickingState;
import com.flowzati.archone.inventory.warehouse.domain.type.PickingDirection;
import jakarta.transaction.Transactional;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 將 WMS 的 custody handover 過帳成 Inventory 的實體出庫。
 *
 * <p>這個 use case 是 Kafka consumer 與 Temporal Activity 共用的唯一 transaction boundary。它不相信
 * 呼叫端傳來的 movement 清單：先以 fulfillment v1 的 allocation ID（即該次出庫的 picking ID）載入
 * 完整 execution，再要求兩邊集合完全相同，避免少扣一條或夾帶另一張需求。所有驗證都在 mutation
 * 前完成。
 */
@Service
public class CompleteOutboundMovementsUsecase {

    private final StockMoveRepository stockMoveRepository;
    private final StockPickingRepository stockPickingRepository;
    private final StockQuantRepository stockQuantRepository;
    private final OutboundMovementEventPublisher eventPublisher;

    public CompleteOutboundMovementsUsecase(
            StockMoveRepository stockMoveRepository,
            StockPickingRepository stockPickingRepository,
            StockQuantRepository stockQuantRepository,
            OutboundMovementEventPublisher eventPublisher) {
        this.stockMoveRepository = stockMoveRepository;
        this.stockPickingRepository = stockPickingRepository;
        this.stockQuantRepository = stockQuantRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public CompleteOutboundMovementsResult execute(CompleteOutboundMovementsCommand command) {
        List<StockMove> movements = loadAndValidateMovements(command);
        List<StockPicking> pickings = loadAndValidatePickings(command, movements);

        if (movements.stream().allMatch(move -> move.getState() == MoveState.DONE)) {
            requireAllPickingsDone(pickings);
            return result(command, Status.ALREADY_COMPLETED);
        }
        requireAllAssigned(command, movements, pickings);

        List<StockMoveLine> movementLines = loadAndValidateMovementLines(movements);
        Map<UUID, StockQuant> stockQuants = loadRequiredStockQuants(movements, movementLines);

        // move line 是「從哪一批取了多少」的既有事實；依它扣 reserved 與 on-hand，不能重新分批。
        for (StockMoveLine line : movementLines) {
            stockQuants.get(line.stockQuantId()).consume(line.quantity());
        }
        movements.forEach(move -> move.complete(command.completedAt()));
        pickings.forEach(StockPicking::complete);

        persistCompletion(stockQuants, movements, pickings);
        eventPublisher.publish(new OutboundMovementsCompleted(
                command.allocationId(),
                command.orderId(),
                command.shipmentId(),
                command.movementIds(),
                command.completedAt()));
        return result(command, Status.COMPLETED);
    }

    private List<StockMove> loadAndValidateMovements(CompleteOutboundMovementsCommand command) {
        // OrderAllocationCompletionAdapter 為相容 fulfillment v1，會把唯一的 picking ID 對外稱為
        // allocationId；這裡不可拿它當 AllocationDemand.id 查詢，兩者是不同的業務身分。
        List<StockMove> movements = stockMoveRepository.findByPickingIds(List.of(command.allocationId()));
        if (movements.isEmpty()) {
            throw new IllegalStateException("Allocation has no outbound movements: " + command.allocationId());
        }

        Set<UUID> persistedIds =
                movements.stream().map(StockMove::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> requestedIds = new LinkedHashSet<>(command.movementIds());
        if (!persistedIds.equals(requestedIds)) {
            throw new IllegalArgumentException("Movement IDs do not match allocation " + command.allocationId());
        }
        if (movements.stream().anyMatch(move -> move.getPickingId() == null)) {
            throw new IllegalStateException("Outbound movements must belong to a picking");
        }
        return movements;
    }

    private List<StockPicking> loadAndValidatePickings(
            CompleteOutboundMovementsCommand command, List<StockMove> movements) {
        Set<UUID> pickingIds =
                movements.stream().map(StockMove::getPickingId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<StockPicking> pickings = stockPickingRepository.findByIds(pickingIds);
        if (pickings.size() != pickingIds.size()) {
            throw new IllegalStateException("One or more outbound pickings no longer exist: " + pickingIds);
        }
        for (StockPicking picking : pickings) {
            if (picking.direction() == PickingDirection.INBOUND) {
                throw new IllegalStateException("Inbound picking cannot be completed as outbound: " + picking.id());
            }
            if (!command.orderId().equals(picking.orderId())) {
                throw new IllegalArgumentException("Picking belongs to another order: " + picking.id());
            }
        }

        // movements 已在 loadAndValidateMovements 以同一個 picking ID 完整載入並核對 movementIds，
        // 不需要再查一次相同 picking。
        return pickings.stream().sorted(Comparator.comparing(StockPicking::id)).toList();
    }

    private static void requireAllPickingsDone(List<StockPicking> pickings) {
        if (pickings.stream().anyMatch(picking -> picking.state() != PickingState.DONE)) {
            throw new IllegalStateException("Completed movements require completed pickings");
        }
    }

    private static void requireAllAssigned(
            CompleteOutboundMovementsCommand command, List<StockMove> movements, List<StockPicking> pickings) {
        if (movements.stream().anyMatch(move -> move.getState() != MoveState.ASSIGNED)) {
            throw new IllegalStateException("All outbound movements must be assigned before completion");
        }
        if (movements.stream().anyMatch(move -> command.completedAt().isBefore(move.getAssignedAt()))) {
            throw new IllegalArgumentException("Outbound completion cannot be before allocation assignment");
        }
        if (pickings.stream().anyMatch(picking -> picking.state() != PickingState.ASSIGNED)) {
            throw new IllegalStateException("All outbound pickings must be assigned before completion");
        }
    }

    private List<StockMoveLine> loadAndValidateMovementLines(List<StockMove> movements) {
        List<UUID> movementIds = movements.stream().map(StockMove::getId).toList();
        List<StockMoveLine> lines = stockMoveRepository.findLinesOf(movementIds);
        Map<UUID, Integer> quantityByMovement = new LinkedHashMap<>();
        for (StockMoveLine line : lines) {
            if (!movementIds.contains(line.moveId())) {
                throw new IllegalStateException("Movement line belongs to an unexpected movement: " + line.id());
            }
            quantityByMovement.merge(line.moveId(), line.quantity(), Math::addExact);
        }
        for (StockMove movement : movements) {
            if (quantityByMovement.getOrDefault(movement.getId(), 0) != movement.getDemandQuantity()) {
                throw new IllegalStateException("Reserved movement lines do not cover movement " + movement.getId());
            }
        }
        return lines;
    }

    private Map<UUID, StockQuant> loadRequiredStockQuants(List<StockMove> movements, List<StockMoveLine> lines) {
        Set<UUID> requiredIds =
                lines.stream().map(StockMoveLine::stockQuantId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, StockQuant> stockQuants = stockQuantRepository.findByIds(requiredIds).stream()
                .collect(Collectors.toMap(StockQuant::getId, Function.identity()));
        if (!stockQuants.keySet().equals(requiredIds)) {
            Set<UUID> missing = new LinkedHashSet<>(requiredIds);
            missing.removeAll(stockQuants.keySet());
            throw new IllegalStateException("Stock quants no longer exist: " + missing);
        }
        Map<UUID, StockMove> movementsById =
                movements.stream().collect(Collectors.toMap(StockMove::getId, Function.identity()));
        for (StockMoveLine line : lines) {
            StockMove movement = movementsById.get(line.moveId());
            StockQuant stockQuant = stockQuants.get(line.stockQuantId());
            if (!movement.getOwnerId().equals(stockQuant.getOwnerId())
                    || !movement.getFromLocationId().equals(stockQuant.getLocationId())
                    || !movement.getSkuCode().equals(stockQuant.getSkuCode())) {
                throw new IllegalStateException("Stock quant does not match outbound movement " + movement.getId());
            }
        }
        return stockQuants;
    }

    private void persistCompletion(
            Map<UUID, StockQuant> stockQuants, List<StockMove> movements, List<StockPicking> pickings) {
        stockQuants.values().stream().sorted(StockWriteOrder.BY_GLOBAL_ORDER).forEach(stockQuantRepository::save);
        stockMoveRepository.saveAll(movements);
        pickings.forEach(stockPickingRepository::save);
    }

    private static CompleteOutboundMovementsResult result(CompleteOutboundMovementsCommand command, Status status) {
        return new CompleteOutboundMovementsResult(
                command.allocationId(), command.orderId(), command.shipmentId(), status, command.completedAt());
    }
}
