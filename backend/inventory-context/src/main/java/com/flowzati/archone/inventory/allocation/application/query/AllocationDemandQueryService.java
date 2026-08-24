package com.flowzati.archone.inventory.allocation.application.query;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.entity.AllocationDemandLine;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.balance.domain.repository.StockQuantRepository;
import com.flowzati.archone.inventory.balance.domain.valueobject.AllocatableBatches;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockPicking;
import com.flowzati.archone.inventory.movement.domain.entity.StockMoveLine;
import com.flowzati.archone.inventory.movement.domain.repository.StockMoveRepository;
import com.flowzati.archone.inventory.movement.domain.repository.StockPickingRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 讀取 allocation demand 與 execution，並用當下 ATP 解釋 pending 原因；不做 reservation。 */
@Service
@Transactional(readOnly = true)
public class AllocationDemandQueryService {

    private final AllocationDemandRepository demandRepository;
    private final AllocationDemandQueryRepository demandQueryRepository;
    private final StockQuantRepository stockQuantRepository;
    private final StockMoveRepository stockMoveRepository;
    private final StockPickingRepository stockPickingRepository;
    private final BusinessClock appClock;

    public AllocationDemandQueryService(
            AllocationDemandRepository demandRepository,
            AllocationDemandQueryRepository demandQueryRepository,
            StockQuantRepository stockQuantRepository,
            StockMoveRepository stockMoveRepository,
            StockPickingRepository stockPickingRepository,
            BusinessClock appClock) {
        this.demandRepository = demandRepository;
        this.demandQueryRepository = demandQueryRepository;
        this.stockQuantRepository = stockQuantRepository;
        this.stockMoveRepository = stockMoveRepository;
        this.stockPickingRepository = stockPickingRepository;
        this.appClock = appClock;
    }

    public List<AllocationDemandView> listPending(int limit) {
        List<AllocationDemand> pending = demandQueryRepository.findPending(limit);
        List<AllocationDemandView> result = new ArrayList<>(pending.size());
        for (int index = 0; index < pending.size(); index++) {
            AllocationDemand demand = pending.get(index);
            result.add(toView(demand, findVisibleBlockerId(demand, pending.subList(0, index))));
        }
        return List.copyOf(result);
    }

    public Optional<AllocationDemandView> findPrimaryOrder(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("Order ID is required");
        }
        return demandRepository
                .findBySource(SourceAllocationUnit.primaryOrder(orderId.toString()))
                .map(demand -> {
                    UUID blockerId = demand.status() == AllocationDemandStatus.PENDING
                            ? demandQueryRepository.findBlockingDemandId(demand).orElse(null)
                            : null;
                    return toView(demand, blockerId);
                });
    }

    private AllocationDemandView toView(AllocationDemand demand, UUID blockerId) {
        AllocationSupplyExplanation supply = demand.status() == AllocationDemandStatus.PENDING
                ? explainPending(demand, blockerId)
                : AllocationSupplyExplanation.notPending();
        List<StockMove> moves = stockMoveRepository.findByAllocationDemandId(demand.id());
        List<StockMoveLine> moveLines = stockMoveRepository.findLinesOf(
                moves.stream().map(StockMove::getId).toList());
        Map<UUID, StockQuant> stockQuantById =
                stockQuantRepository
                        .findByIds(moveLines.stream()
                                .map(StockMoveLine::stockQuantId)
                                .distinct()
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(StockQuant::getId, Function.identity()));
        Map<UUID, List<AllocationReservationView>> reservationsByMove = moveLines.stream()
                .sorted(Comparator.comparing(StockMoveLine::stockQuantId))
                .collect(Collectors.groupingBy(
                        StockMoveLine::moveId,
                        LinkedHashMap::new,
                        Collectors.mapping(
                                line -> toReservationView(line, requireStockQuant(stockQuantById, line.stockQuantId())),
                                Collectors.toList())));
        List<UUID> pickingIds = moves.stream()
                .map(StockMove::getPickingId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        List<StockPicking> pickings = stockPickingRepository.findByIds(pickingIds);

        return new AllocationDemandView(
                demand.id(),
                demand.source().sourceType(),
                demand.source().sourceId(),
                demand.source().allocationUnitKey(),
                demand.ownerId(),
                demand.facilityId(),
                demand.locationId(),
                demand.requiredBy(),
                demand.releasePriority(),
                demand.enqueuedAt(),
                demand.status(),
                supply.reason(),
                supply.blockedByDemandId(),
                demand.totalsBySku(),
                supply.availableQuantities(),
                supply.missingQuantities(),
                demand.lines().stream()
                        .sorted(Comparator.comparingInt(AllocationDemandLine::lineSequence))
                        .map(line -> new AllocationDemandLineView(
                                line.id(), line.sourceLineId(), line.lineSequence(), line.skuCode(), line.quantity()))
                        .toList(),
                pickings.stream()
                        .map(AllocationDemandQueryService::toPickingView)
                        .toList(),
                moves.stream()
                        .map(move -> toMoveView(move, reservationsByMove.getOrDefault(move.getId(), List.of())))
                        .toList());
    }

    private static UUID findVisibleBlockerId(AllocationDemand demand, List<AllocationDemand> earlierDemands) {
        return earlierDemands.stream()
                .filter(other -> sameScope(other, demand))
                .filter(other -> sharesRequiredSku(other, demand))
                .map(AllocationDemand::id)
                .findFirst()
                .orElse(null);
    }

    private AllocationSupplyExplanation explainPending(AllocationDemand demand, UUID blockerId) {

        Map<String, Integer> required = demand.totalsBySku();
        AllocatableBatches batches = stockQuantRepository.findAllocatableBatchesBySku(
                demand.ownerId(), demand.locationId(), required.keySet(), appClock.today());
        Map<String, Integer> available = new LinkedHashMap<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            int availableForDemand = availableUpTo(entry.getValue(), batches.forSku(entry.getKey()));
            available.put(entry.getKey(), availableForDemand);
            if (availableForDemand < entry.getValue()) {
                missing.put(entry.getKey(), entry.getValue() - availableForDemand);
            }
        }

        AllocationWaitingReason reason;
        if (blockerId != null) {
            reason = AllocationWaitingReason.WAITING_FOR_EARLIER_DEMAND;
        } else if (missing.isEmpty()) {
            reason = AllocationWaitingReason.READY_TO_ALLOCATE;
        } else if (available.values().stream().allMatch(quantity -> quantity == 0)) {
            reason = AllocationWaitingReason.NO_ALLOCATABLE_STOCK;
        } else {
            reason = AllocationWaitingReason.INSUFFICIENT_ATP;
        }
        return new AllocationSupplyExplanation(reason, blockerId, Map.copyOf(available), Map.copyOf(missing));
    }

    private static int availableUpTo(int required, List<StockQuant> batches) {
        int available = 0;
        for (StockQuant batch : batches) {
            int stillRequired = required - available;
            if (stillRequired == 0) {
                break;
            }
            available += Math.min(stillRequired, batch.availableToPromise());
        }
        return available;
    }

    private static boolean sameScope(AllocationDemand left, AllocationDemand right) {
        return left.ownerId().equals(right.ownerId())
                && left.facilityId().equals(right.facilityId())
                && left.locationId().equals(right.locationId());
    }

    private static boolean sharesRequiredSku(AllocationDemand left, AllocationDemand right) {
        Set<String> rightSkus = right.totalsBySku().keySet();
        return left.totalsBySku().keySet().stream().anyMatch(rightSkus::contains);
    }

    private static AllocationPickingView toPickingView(StockPicking picking) {
        return new AllocationPickingView(
                picking.id(),
                picking.orderId(),
                picking.direction().name(),
                picking.state().name(),
                picking.fromLocationId(),
                picking.toLocationId(),
                picking.dispatchBy(),
                picking.releasePriority());
    }

    private static AllocationMoveView toMoveView(StockMove move, List<AllocationReservationView> reservations) {
        return new AllocationMoveView(
                move.getId(),
                move.getPickingId(),
                move.getAllocationDemandLineId(),
                move.getSourceLineId(),
                move.getOrderLineId(),
                move.getSkuCode(),
                move.getDemandQuantity(),
                move.getState().name(),
                move.getFromLocationId(),
                move.getToLocationId(),
                move.getCreatedAt(),
                move.getAssignedAt(),
                reservations);
    }

    private static StockQuant requireStockQuant(Map<UUID, StockQuant> stockQuantById, UUID stockQuantId) {
        StockQuant stockQuant = stockQuantById.get(stockQuantId);
        if (stockQuant == null) {
            throw new IllegalStateException("Reserved StockQuant no longer exists: " + stockQuantId);
        }
        return stockQuant;
    }

    private static AllocationReservationView toReservationView(StockMoveLine line, StockQuant stockQuant) {
        return new AllocationReservationView(
                stockQuant.getId(),
                stockQuant.getOwnerId(),
                stockQuant.getLocationId(),
                stockQuant.getSkuCode(),
                stockQuant.getInDate(),
                stockQuant.getExpiryDate(),
                line.quantity());
    }
}
