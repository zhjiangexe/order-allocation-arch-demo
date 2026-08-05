package com.flowzati.archone.stock.application.movement;

import com.flowzati.archone.common.time.AppClock;
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
import com.flowzati.archone.common.IdGenerator;

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

/**
 * 搬運的第二個動作：**鎖定**（Odoo 的 {@code stock.move._action_assign}）。
 *
 * <p>一個動作的五個步驟：取批 → 決策 → 轉狀態 → 寫明細 → 寫入。它們住在一起是刻意
 * 的——拆開會讓「鎖定」這件事沒有一個完整的落點。若日後長到讀不完，該拆的是「規劃」與「套用」，
 * 而不是把步驟散回各個 usecase。
 *
 * <p><b>接收搬運，不接收需求的識別碼。</b>兩個呼叫端手上本來就有那些搬運：收單剛建完，補貨剛
 * 從佇列讀出來。回頭用 {@code order_line_id} 再讀一次是分層的副作用，不是必要的往返。
 *
 * <p><b>只套用並回傳結果，不決定事件。</b>初次配貨與缺貨喚醒對失敗結果的政策不同，
 * 所以由各自的 flow owner 在同一交易裡發布事件。這個元件也不碰訂單聚合根。
 */
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

  /**
   * 一張單：試著把它的搬運全部鎖定。
   *
   * <p>需求由呼叫端給定而不是從搬運反推——收單路徑手上已經有它，反推只會多一次單據查詢。
   */
  public AllocationOutcome assign(Demand demand, List<StockMove> moves, Instant now) {
    if (hasEarlierWaitingDemand(demand, moves)) {
      return AllocationOutcome.WAITING_FOR_EARLIER_DEMAND;
    }
    AllocatableBatches batches = allocatableBatchesFor(List.of(demand));
    AllocationResult result = allocationService.allocate(demand, batches);
    if (!result.isAllocated()) {
      return result.outcome();
    }

    applyAssignment(result.picks(), moves, now);
    return AllocationOutcome.ALLOCATED;
  }

  /** Prevents a newly arrived order from consuming ATP ahead of an existing waiting picking. */
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

  /**
   * 一批還在等貨的搬運：由挑單政策決定這一輪餵飽哪幾張，再逐張攤到批上。
   *
   * <p>回傳真正配到的那些需求，讓喚醒元件記錄每張成功訂單的完成事實與本輪結果。
   */
  public List<Demand> assignWaitingBatch(List<StockMove> moves, Instant now) {
    List<Demand> candidates = toDemands(moves);
    if (candidates.isEmpty()) {
      return List.of();
    }

    AllocatableBatches batches = allocatableBatchesFor(candidates);
    List<OrderAllocation> allocations =
        allocationService.allocateWaitingBatch(candidates, batches, now);
    if (allocations.isEmpty()) {
      return List.of();
    }

    applyAssignment(
        allocations.stream().flatMap(a -> a.picks().stream()).toList(), moves, now);

    List<Demand> allocated = allocations.stream().map(OrderAllocation::demand).toList();
    return allocated;
  }

  /**
   * 這些需求要用到的每一個 SKU 的可配批。
   *
   * <p><b>一次取完，不逐張查。</b>查詢次數固定，不隨候選單數成長；逐張各自查會是 N+1。更重要
   * 的是死鎖：本輪要碰哪些庫存列必須在進入交易前全部已知，寫入的全序才算得出來。
   *
   * <p>篩選與排序都在資料庫做——批數只會隨時間成長，把配不到的載進記憶體只為了丟掉是錯的
   * 方向，而 {@code (expiry_date, in_date, id)} 正是索引的順序。
   */
  private AllocatableBatches allocatableBatchesFor(List<Demand> demands) {
    Demand first = demands.getFirst();
    UUID ownerId = first.ownerId();
    UUID locationId = first.locationId();
    Set<String> skuCodes = demands.stream()
        .flatMap(demand -> demand.totalsBySku().keySet().stream())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return stockPoolRepository.findAllocatableBatchesBySku(ownerId, locationId, skuCodes, appClock.today());
  }

  /**
   * 把配到貨的那些搬運轉為已鎖定，並寫出「從哪一批取用」的明細。
   *
   * <p>粒度是**訂單行 × 批**——{@link BatchPick} 已經是這個粒度，所以明細與它一對一。摺成
   * 一條行一列會丟掉「哪一批是為哪一條行鎖的」，而出貨時要的正是那個資訊。
   *
   * <p>搬運在收單時就已經建立（狀態為「還在等貨」），這裡只是轉狀態——**不是新建**。
   *
   * <p>庫存先寫、搬運後寫：庫存是會被搶的那一組列，先把它們鎖起來能縮短其他交易等待的窗口。
   */
  private void applyAssignment(List<BatchPick> picks, List<StockMove> moves, Instant now) {
    Map<UUID, StockMove> movesByLine = moves.stream()
        .filter(move -> move.getOrderLineId() != null)
        .collect(Collectors.toMap(StockMove::getOrderLineId, move -> move, (a, b) -> a));

    Map<UUID, StockPool> touchedBatches = new LinkedHashMap<>();
    Map<UUID, StockMove> touchedMoves = new LinkedHashMap<>();
    List<StockMoveLine> lines = new ArrayList<>();
    for (BatchPick pick : picks) {
      StockMove move = movesByLine.get(pick.orderLineId());
      if (move == null) {
        throw new IllegalStateException(
            "No movement was supplied for order line " + pick.orderLineId());
      }
      move.assign(now);
      touchedMoves.put(move.getId(), move);
      touchedBatches.put(pick.batch().getId(), pick.batch());
      lines.add(new StockMoveLine(
          IdGenerator.nextId(), move.getId(), pick.batch().getId(), pick.quantity()));
    }

    touchedBatches.values().stream().sorted(StockWriteOrder.BY_GLOBAL_ORDER).forEach(stockPoolRepository::save);
    stockMoveRepository.saveAll(touchedMoves.values());
    stockMoveRepository.saveLines(lines);
    markPickingsAssigned(touchedMoves.values());
  }

  /** Picking state 是 moves 的物化摘要，必須與本次 ASSIGNED moves 在同一 transaction 更新。 */
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

  /**
   * 把還在等貨的搬運投影成配貨演算法看得懂的 {@link Demand}。
   *
   * <p><b>這一段存在的理由是讓演算法不必改。</b>佇列的來源從「訂單與預留 join 出來的檢視」換成
   * 了「搬運的狀態」，但 {@code AllocationService} 的整籃判斷、FEFO 取批、head-of-line
   * blocking、批次上限全部依賴 {@code Demand} 的形狀——投影回同一個形狀，那些一個字都不用動。
   *
   * <p>分組用 {@code pickingId}：一張單據就是一張單的全部搬運，而 ship-complete 判斷的單位正是
   * 一張單。訂單 id 只在這裡查一次（回傳給 flow owner 建立結果事件），不參與分組。
   */
  private List<Demand> toDemands(List<StockMove> moves) {
    if (moves.isEmpty()) {
      return List.of();
    }
    // LinkedHashMap 保住 FIFO：查詢已經依到達順序回來，分組不得打亂它。
    Map<UUID, List<StockMove>> byPicking = new LinkedHashMap<>();
    for (StockMove move : moves) {
      byPicking.computeIfAbsent(move.getPickingId(), key -> new ArrayList<>()).add(move);
    }
    Map<UUID, UUID> orderIds = stockPickingRepository.findByIds(byPicking.keySet()).stream()
        .filter(picking -> picking.orderId() != null)
        .collect(Collectors.toMap(StockPicking::id, StockPicking::orderId));

    List<Demand> demands = new ArrayList<>();
    byPicking.forEach((pickingId, pickingMoves) -> {
      UUID orderId = orderIds.get(pickingId);
      if (orderId == null) {
        // 沒有訂單的單據是入庫，它不是待配需求。
        return;
      }
      StockMove first = pickingMoves.getFirst();
      UUID facilityId = stockLocationRepository.findById(first.getFromLocationId())
          .map(StockLocation::getFacilityId)
          .orElseThrow(() -> new IllegalStateException(
              "Location " + first.getFromLocationId() + " no longer exists"));
      List<DemandLine> lines = pickingMoves.stream()
          .filter(move -> move.getOrderLineId() != null)
          .map(move -> new DemandLine(
              move.getOrderLineId(), move.getSkuCode(), move.getDemandQuantity()))
          .toList();
      if (!lines.isEmpty()) {
        demands.add(new Demand(
            orderId, first.getOwnerId(), facilityId, first.getFromLocationId(), lines));
      }
    });
    return List.copyOf(demands);
  }
}
