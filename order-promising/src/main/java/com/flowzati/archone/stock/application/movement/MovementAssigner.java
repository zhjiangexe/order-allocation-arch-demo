package com.flowzati.archone.stock.application.movement;

import com.flowzati.archone.promising.time.AppClock;
import com.flowzati.archone.catalog.domain.model.StockLocation;
import com.flowzati.archone.catalog.domain.repository.StockLocationRepository;
import com.flowzati.archone.stock.domain.model.AllocatableBatches;
import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.model.DemandLine;
import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.model.StockMoveLine;
import com.flowzati.archone.stock.domain.model.StockPicking;
import com.flowzati.archone.stock.domain.model.StockPool;
import com.flowzati.archone.stock.domain.model.StockWriteOrder;
import com.flowzati.archone.stock.domain.repository.StockMoveRepository;
import com.flowzati.archone.stock.domain.repository.StockPickingRepository;
import com.flowzati.archone.stock.domain.repository.StockPoolRepository;
import com.flowzati.archone.stock.domain.service.AllocationOutcome;
import com.flowzati.archone.stock.domain.service.AllocationResult;
import com.flowzati.archone.stock.domain.service.AllocationService;
import com.flowzati.archone.stock.domain.service.BatchPick;
import com.flowzati.archone.stock.domain.service.OrderAllocation;
import com.flowzati.archone.foundation.identity.IdGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 將需求配到庫存批次，並把配貨結果套用到 {@code StockMove}。 */
@Component
public class MovementAssigner {

  private final AllocationService allocationService;
  private final StockPoolRepository stockPoolRepository;
  private final StockMoveRepository stockMoveRepository;
  private final StockPickingRepository stockPickingRepository;
  private final StockLocationRepository stockLocationRepository;
  private final AppClock appClock;

  public MovementAssigner(
      AllocationService allocationService,
      StockPoolRepository stockPoolRepository,
      StockMoveRepository stockMoveRepository,
      StockPickingRepository stockPickingRepository,
      StockLocationRepository stockLocationRepository,
      AppClock appClock) {
    this.allocationService = allocationService;
    this.stockPoolRepository = stockPoolRepository;
    this.stockMoveRepository = stockMoveRepository;
    this.stockPickingRepository = stockPickingRepository;
    this.stockLocationRepository = stockLocationRepository;
    this.appClock = appClock;
  }

  /** 嘗試將一張訂單的全部搬運整批配貨。 */
  public AllocationOutcome assign(Demand demand, List<StockMove> moves, Instant now) {
    // 先確認 FIFO 順序，避免新訂單越過更早的等待需求。
    if (hasEarlierWaitingDemand(demand, moves)) {
      return AllocationOutcome.WAITING_FOR_EARLIER_DEMAND;
    }

    // 載入這張訂單所需 SKU 的可配庫存，交給領域服務計算配貨方案。
    AllocatableBatches batches = allocatableBatchesFor(List.of(demand));
    AllocationResult result = allocationService.allocate(demand, batches);
    if (!result.isAllocated()) {
      return result.outcome();
    }

    // 決策成功後，才更新 move、庫存批次與配貨明細。
    List<BatchPick> picks = result.picks();
    applyAssignment(picks, moves, now);
    return AllocationOutcome.ALLOCATED;
  }

  /** 判斷是否有更早的等待 picking，避免新訂單先取得庫存。 */
  private boolean hasEarlierWaitingDemand(Demand demand, List<StockMove> currentMoves) {
    Set<UUID> currentPickingIds = currentMoves.stream()
        .map(StockMove::getPickingId)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toSet());
    return demand.totalsBySku().keySet().stream().anyMatch(skuCode ->
        stockMoveRepository.findWaitingInFifoOrder(
                demand.ownerId(), demand.locationId(), skuCode, 1).stream()
            .map(StockMove::getPickingId)
            .filter(java.util.Objects::nonNull)
            .anyMatch(pickingId -> !currentPickingIds.contains(pickingId)));
  }

  /** 批次處理等待中的搬運，回傳本輪實際完成配貨的需求。 */
  public List<AssignedDemand> assignWaitingBatch(List<StockMove> moves, Instant now) {
    // ① 將 picking 的 moves 還原成配貨演算法使用的 Demand。
    List<Demand> candidates = toDemands(moves);
    if (candidates.isEmpty()) {
      return List.of();
    }

    // ② 一次載入所有候選需求需要的可配批次，並依 FEFO 順序提供給領域服務。
    AllocatableBatches batches = allocatableBatchesFor(candidates);

    // ③ 由領域服務依 FIFO/ship-complete 政策決定哪些訂單可以整張配成。
    List<OrderAllocation> allocations = allocationService.allocateWaitingBatch(candidates, batches, now);
    if (allocations.isEmpty()) {
      return List.of();
    }

    // ④ 將成功的 BatchPick 套用到原始 move，寫入庫存分配明細並更新 picking。
    List<BatchPick> batchPicks = allocations.stream().flatMap(a -> a.picks().stream()).toList();
    applyAssignment(batchPicks, moves, now);

    // 回傳每張成功訂單及其 moves，讓上層發布配貨完成事件。
    List<AssignedDemand> assignedDemands = allocations.stream()
        .map(allocation -> assignedDemand(allocation.demand(), moves)).toList();
    return assignedDemands;
  }

  /** 將成功配貨的需求與其原始 moves 對回，供完成事件使用。 */
  private AssignedDemand assignedDemand(Demand demand, List<StockMove> suppliedMoves) {
    Set<UUID> lineIds = demand.lines().stream()
        .map(DemandLine::orderLineId)
        .collect(Collectors.toSet());
    List<StockMove> demandMoves = suppliedMoves.stream()
        .filter(move -> lineIds.contains(move.getOrderLineId()))
        .toList();
    if (demandMoves.size() != demand.lines().size()) {
      throw new IllegalStateException(
          "Allocated demand does not have one movement per demand line: " + demand.orderId());
    }
    return new AssignedDemand(demand, demandMoves);
  }

  /** 一次載入候選需求所需的可配批次；篩選與 FEFO 排序由 repository 負責。 */
  private AllocatableBatches allocatableBatchesFor(List<Demand> demands) {
    Demand first = demands.getFirst();
    UUID ownerId = first.ownerId();
    UUID locationId = first.locationId();
    Set<String> skuCodes = demands.stream()
        .flatMap(demand -> demand.totalsBySku().keySet().stream())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return stockPoolRepository.findAllocatableBatchesBySku(ownerId, locationId, skuCodes, appClock.today());
  }

  /** 將配貨決策寫回庫存批次、moves、明細與 picking。 */
  private void applyAssignment(List<BatchPick> picks, List<StockMove> moves, Instant now) {
    // 以 orderLineId 對回原始 move，確保配貨結果不會寫到錯誤的搬運。
    Map<UUID, StockMove> movesByLine = moves.stream()
        .filter(move -> move.getOrderLineId() != null)
        .collect(Collectors.toMap(StockMove::getOrderLineId, move -> move, (a, b) -> a));

    Map<UUID, StockPool> touchedBatches = new LinkedHashMap<>();
    Map<UUID, StockMove> touchedMoves = new LinkedHashMap<>();
    List<StockMoveLine> lines = new ArrayList<>();
    for (BatchPick pick : picks) {
      // 一個 BatchPick 對應一筆「order line × stock batch」分配明細。
      StockMove move = movesByLine.get(pick.orderLineId());
      if (move == null) {
        throw new IllegalStateException("No movement was supplied for order line " + pick.orderLineId());
      }
      move.assign(now);
      touchedMoves.put(move.getId(), move);
      touchedBatches.put(pick.batch().getId(), pick.batch());
      lines.add(new StockMoveLine(
          IdGenerator.nextId(), move.getId(), pick.batch().getId(), pick.quantity()));
    }

    // 依固定順序寫入庫存，降低多個配貨交易互相鎖定造成死結的機率。
    touchedBatches.values().stream().sorted(StockWriteOrder.BY_GLOBAL_ORDER).forEach(stockPoolRepository::save);
    // move 狀態、分配明細與 picking 狀態由同一個 transaction 一起提交。
    stockMoveRepository.saveAll(touchedMoves.values());
    stockMoveRepository.saveLines(lines);
    markPickingsAssigned(touchedMoves.values());
  }

  /** 將受影響的 picking 更新為已配貨；它是 moves 狀態的物化摘要。 */
  private void markPickingsAssigned(java.util.Collection<StockMove> assignedMoves) {
    Set<UUID> pickingIds = assignedMoves.stream()
        .map(StockMove::getPickingId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (pickingIds.contains(null)) {
      throw new IllegalStateException("An allocated outbound movement must belong to a picking");
    }

    List<StockPicking> pickings = stockPickingRepository.findByIds(pickingIds);
    if (pickings.size() != pickingIds.size()) {
      Set<UUID> found = pickings.stream().map(StockPicking::id).collect(Collectors.toSet());
      Set<UUID> missing = new LinkedHashSet<>(pickingIds);
      missing.removeAll(found);
      throw new IllegalStateException("Stock pickings no longer exist: " + missing);
    }
    for (StockPicking picking : pickings) {
      picking.assign();
      stockPickingRepository.save(picking);
    }
  }

  /** 將 waiting moves 依 picking 分組，轉成配貨演算法使用的 {@link Demand}。 */
  private List<Demand> toDemands(List<StockMove> moves) {
    if (moves.isEmpty()) {
      return List.of();
    }
    Set<UUID> locationIds = moves.stream()
        .map(StockMove::getFromLocationId)
        .collect(Collectors.toSet());
    if (locationIds.size() != 1) {
      throw new IllegalArgumentException("A waiting allocation batch must belong to exactly one source location");
    }
    UUID locationId = locationIds.iterator().next();
    UUID facilityId = stockLocationRepository.findById(locationId)
        .map(StockLocation::getFacilityId)
        .orElseThrow(() -> new IllegalStateException("Location " + locationId + " no longer exists"));

    // 保留查詢回傳的 FIFO 順序，避免分組後改變候選訂單順序。
    Map<UUID, List<StockMove>> byPicking = moves.stream()
        .collect(Collectors.groupingBy(StockMove::getPickingId, LinkedHashMap::new, Collectors.toList()));
    Map<UUID, StockPicking> pickings = stockPickingRepository.findByIds(byPicking.keySet()).stream()
        .filter(picking -> picking.orderId() != null)
        .collect(Collectors.toMap(StockPicking::id, picking -> picking));

    List<Demand> demands = new ArrayList<>();
    byPicking.forEach((pickingId, pickingMoves) -> {
      StockPicking picking = pickings.get(pickingId);
      if (picking == null) {
        // 沒有 orderId 的 picking 不是訂單待配需求，跳過。
        return;
      }
      StockMove first = pickingMoves.getFirst();
      List<DemandLine> lines = pickingMoves.stream()
          .filter(move -> move.getOrderLineId() != null)
          .map(move -> new DemandLine(move.getOrderLineId(), move.getSkuCode(), move.getDemandQuantity()))
          .toList();
      if (!lines.isEmpty()) {
        demands.add(new Demand(
            picking.orderId(), first.getOwnerId(), facilityId, locationId,
            picking.dispatchBy(), picking.releasePriority(), lines));
      }
    });
    return List.copyOf(demands);
  }
}
