package com.flowzati.archone.stock.allocation.application;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.stock.allocation.domain.event.AllocationCommitted;
import com.flowzati.archone.stock.allocation.domain.event.AllocationCommitted.CommittedAllocationMove;
import com.flowzati.archone.stock.allocation.domain.event.AllocationCommitted.CommittedBatchPick;
import com.flowzati.archone.stock.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.stock.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.stock.movement.domain.aggregate.StockMove;
import com.flowzati.archone.stock.movement.domain.entity.StockMoveLine;
import com.flowzati.archone.stock.movement.domain.aggregate.StockPicking;
import com.flowzati.archone.stock.inventory.domain.aggregate.StockPool;
import com.flowzati.archone.stock.inventory.domain.service.StockWriteOrder;
import com.flowzati.archone.stock.allocation.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.stock.movement.domain.repository.StockMoveRepository;
import com.flowzati.archone.stock.movement.domain.repository.StockPickingRepository;
import com.flowzati.archone.stock.inventory.domain.repository.StockPoolRepository;
import com.flowzati.archone.stock.allocation.domain.valueobject.AllocationBatchPick;
import com.flowzati.archone.stock.allocation.domain.valueobject.AllocationDemandPlan;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 在同一個 transaction 內套用 immutable allocation plan。
 *
 * <p>Planner 只回答「應該怎麼配」；本類別則依序完成：
 *
 * <ol>
 *   <li>載入 commit 所需資料。</li>
 *   <li>驗證 plan、execution 與庫存 scope。</li>
 *   <li>reserve stock pools。</li>
 *   <li>assign moves／pickings，再將 demand 標記為 ALLOCATED。</li>
 *   <li>建立 source-agnostic completion fact。</li>
 * </ol>
 *
 * <p>查詢結果只放進 {@link AllocationCommitData}；跨模型規則由純
 * {@link AllocationCommitValidator} 檢查。Stock pools 仍依全域順序寫入，避免多 SKU
 * 併發時形成反向鎖定。
 */
@Component
public class AllocationCommitter {

  private final AllocationDemandRepository demandRepository;
  private final StockPoolRepository stockPoolRepository;
  private final StockMoveRepository stockMoveRepository;
  private final StockPickingRepository stockPickingRepository;
  private final Supplier<UUID> idSupplier;

  @Autowired
  public AllocationCommitter(
      AllocationDemandRepository demandRepository,
      StockPoolRepository stockPoolRepository,
      StockMoveRepository stockMoveRepository,
      StockPickingRepository stockPickingRepository) {
    this(demandRepository, stockPoolRepository, stockMoveRepository, stockPickingRepository,
        IdGenerator::nextId);
  }

  AllocationCommitter(
      AllocationDemandRepository demandRepository,
      StockPoolRepository stockPoolRepository,
      StockMoveRepository stockMoveRepository,
      StockPickingRepository stockPickingRepository,
      Supplier<UUID> idSupplier) {
    this.demandRepository = demandRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.stockMoveRepository = stockMoveRepository;
    this.stockPickingRepository = stockPickingRepository;
    this.idSupplier = idSupplier;
  }

  /** 回傳 empty 表示 demand 已被其他 retry commit；呼叫端不得再次發布 completion。 */
  @Transactional
  public Optional<AllocationCommitted> commit(AllocationDemandPlan plan, Instant occurredAt) {
    requireCommitArguments(plan, occurredAt);

    AllocationDemand demand = loadDemand(plan.allocationDemandId());
    if (demand.status() == AllocationDemandStatus.ALLOCATED) {
      return Optional.empty();
    }
    requirePending(demand);

    // 先載齊資料，再交給純 validator 一次檢查；這兩步都不修改任何 aggregate。
    AllocationCommitData data = loadCommitData(plan, demand);
    AllocationCommitValidator.validate(data);

    // Mutation 從這裡才開始；任何一步失敗都由同一個 transaction 整體 rollback。
    reserveStock(data);
    assignMoves(data, occurredAt);
    assignPickings(data);
    markDemandAllocated(data);

    return Optional.of(createCompletionFact(data, occurredAt));
  }

  private static void requireCommitArguments(AllocationDemandPlan plan, Instant occurredAt) {
    if (plan == null || !plan.isReadyToCommit()) {
      throw new IllegalArgumentException("Only a plan ready to commit can be committed");
    }
    if (occurredAt == null) {
      throw new IllegalArgumentException("Allocation commit time is required");
    }
  }

  private AllocationDemand loadDemand(UUID demandId) {
    return demandRepository.findById(demandId)
        .orElseThrow(() -> new IllegalStateException("Allocation demand no longer exists: " + demandId));
  }

  private static void requirePending(AllocationDemand demand) {
    if (demand.status() != AllocationDemandStatus.PENDING) {
      throw new IllegalStateException("A cancelled allocation demand cannot be committed");
    }
  }

  /** 只負責 repository 查詢與排序；一致性規則全部留給 AllocationCommitValidator。 */
  private AllocationCommitData loadCommitData(AllocationDemandPlan plan, AllocationDemand demand) {
    List<StockMove> moves = stockMoveRepository.findByAllocationDemandId(demand.id());
    List<StockPool> stockPools = loadStockPoolsInWriteOrder(plan);
    List<StockPicking> pickings = loadPickingsInWriteOrder(moves);
    return new AllocationCommitData(plan, demand, moves, stockPools, pickings);
  }

  private List<StockPool> loadStockPoolsInWriteOrder(AllocationDemandPlan plan) {
    Set<UUID> poolIds = plan.picks().stream()
        .map(AllocationBatchPick::stockPoolId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return stockPoolRepository.findByIds(poolIds).stream()
        .sorted(StockWriteOrder.BY_GLOBAL_ORDER)
        .toList();
  }

  private List<StockPicking> loadPickingsInWriteOrder(List<StockMove> moves) {
    Set<UUID> pickingIds = moves.stream()
        .map(StockMove::getPickingId)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return stockPickingRepository.findByIds(pickingIds).stream()
        .sorted(Comparator.comparing(StockPicking::id))
        .toList();
  }

  /** 按全域鎖定順序 reserve 每個 pool；同一 pool 的多筆 picks 會先加總。 */
  private void reserveStock(AllocationCommitData data) {
    Map<UUID, Integer> plannedQuantityByPool = aggregatePlannedQuantityByPool(data.plan());
    for (StockPool pool : data.stockPoolsInWriteOrder()) {
      pool.reserve(plannedQuantityByPool.get(pool.getId()));
      stockPoolRepository.save(pool);
    }
  }

  private static Map<UUID, Integer> aggregatePlannedQuantityByPool(
      AllocationDemandPlan plan) {
    Map<UUID, Integer> quantityByPool = new LinkedHashMap<>();
    for (AllocationBatchPick pick : plan.picks()) {
      quantityByPool.merge(pick.stockPoolId(), pick.quantity(), Math::addExact);
    }
    return quantityByPool;
  }

  /** 將 outbound moves 標成 ASSIGNED，並把每筆 batch pick 落成 StockMoveLine。 */
  private void assignMoves(AllocationCommitData data, Instant occurredAt) {
    for (StockMove move : data.moves()) {
      move.assign(occurredAt);
    }

    List<StockMoveLine> moveLines = createMoveLines(data);
    stockMoveRepository.saveAll(data.moves());
    stockMoveRepository.saveLines(moveLines);
  }

  private List<StockMoveLine> createMoveLines(AllocationCommitData data) {
    Map<UUID, StockMove> moveByDemandLine = data.moves().stream()
        .collect(Collectors.toMap(StockMove::getAllocationDemandLineId, move -> move));
    List<StockMoveLine> moveLines = new ArrayList<>(data.plan().picks().size());
    for (AllocationBatchPick pick : data.plan().picks()) {
      StockMove move = moveByDemandLine.get(pick.allocationDemandLineId());
      moveLines.add(new StockMoveLine(
          idSupplier.get(), move.getId(), pick.stockPoolId(), pick.quantity()));
    }
    return List.copyOf(moveLines);
  }

  /** Picking 是 execution summary；只有存在 picking 的 allocation 才需要更新。 */
  private void assignPickings(AllocationCommitData data) {
    for (StockPicking picking : data.pickings()) {
      picking.assign();
      stockPickingRepository.save(picking);
    }
  }

  private void markDemandAllocated(AllocationCommitData data) {
    data.demand().markAllocated();
    demandRepository.save(data.demand());
  }

  /** 把已成功套用的 plan 轉成 completion fact；事件 routing 仍由呼叫端負責。 */
  private static AllocationCommitted createCompletionFact(
      AllocationCommitData data, Instant occurredAt) {
    Map<UUID, List<CommittedBatchPick>> committedPicksByLine = groupCommittedPicksByLine(
        data.plan());
    AllocationDemand demand = data.demand();

    List<CommittedAllocationMove> committedMoves = data.moves().stream()
        .map(move -> toCommittedMove(demand, move, committedPicksByLine))
        .toList();

    return new AllocationCommitted(
        demand.id(),
        demand.source(),
        demand.ownerId(),
        demand.facilityId(),
        demand.locationId(),
        demand.requiredBy(),
        demand.releasePriority(),
        occurredAt,
        committedMoves);
  }

  private static Map<UUID, List<CommittedBatchPick>> groupCommittedPicksByLine(
      AllocationDemandPlan plan) {
    return plan.picks().stream()
        .collect(Collectors.groupingBy(
            AllocationBatchPick::allocationDemandLineId,
            LinkedHashMap::new,
            Collectors.mapping(
                pick -> new CommittedBatchPick(pick.stockPoolId(), pick.quantity()),
                Collectors.toList())));
  }

  private static CommittedAllocationMove toCommittedMove(
      AllocationDemand demand,
      StockMove move,
      Map<UUID, List<CommittedBatchPick>> committedPicksByLine) {
    return new CommittedAllocationMove(
        demand.id(),
        move.getAllocationDemandLineId(),
        move.getSourceLineId(),
        move.getId(),
        move.getPickingId(),
        move.getSkuCode(),
        move.getFromLocationId(),
        move.getDemandQuantity(),
        committedPicksByLine.get(move.getAllocationDemandLineId()));
  }
}
