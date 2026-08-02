package com.flowzati.archone.allocation.application.movement;

import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.allocation.domain.model.DemandLine;
import com.flowzati.archone.allocation.domain.model.StockMove;
import com.flowzati.archone.allocation.domain.model.StockMoveLine;
import com.flowzati.archone.allocation.domain.model.StockPicking;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockWriteOrder;
import com.flowzati.archone.allocation.domain.repository.StockMoveRepository;
import com.flowzati.archone.allocation.domain.repository.StockPickingRepository;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.service.AllocationOutcome;
import com.flowzati.archone.allocation.domain.service.AllocationResult;
import com.flowzati.archone.allocation.domain.service.AllocationService;
import com.flowzati.archone.allocation.domain.service.BatchPick;
import com.flowzati.archone.allocation.domain.service.OrderAllocation;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.time.BusinessCalendar;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 搬運的第二個動作：**鎖定**（Odoo 的 {@code stock.move._action_assign}）。
 *
 * <p>一個動作的六個步驟：取批 → 決策 → 轉狀態 → 寫明細 → 寫入 → 發事件。它們住在一起是刻意
 * 的——拆開會讓「鎖定」這件事沒有一個完整的落點。若日後長到讀不完，該拆的是「規劃」與「套用」，
 * 而不是把步驟散回各個 usecase。
 *
 * <p><b>接收搬運，不接收需求的識別碼。</b>兩個呼叫端手上本來就有那些搬運：收單剛建完，補貨剛
 * 從佇列讀出來。回頭用 {@code order_line_id} 再讀一次是分層的副作用，不是必要的往返。
 *
 * <p><b>不碰訂單聚合根。</b>配貨結果以領域事件陳述，由 ordering 收到對外事件後自己推進狀態。
 */
@Component
public class MovementAssigner {

  private final AllocationService allocationService;
  private final StockPoolRepository stockPoolRepository;
  private final StockMoveRepository stockMoveRepository;
  private final StockPickingRepository stockPickingRepository;
  private final BusinessCalendar businessCalendar;
  private final ApplicationEventPublisher eventPublisher;

  public MovementAssigner(
      AllocationService allocationService,
      StockPoolRepository stockPoolRepository,
      StockMoveRepository stockMoveRepository,
      StockPickingRepository stockPickingRepository,
      BusinessCalendar businessCalendar,
      ApplicationEventPublisher eventPublisher) {
    this.allocationService = allocationService;
    this.stockPoolRepository = stockPoolRepository;
    this.stockMoveRepository = stockMoveRepository;
    this.stockPickingRepository = stockPickingRepository;
    this.businessCalendar = businessCalendar;
    this.eventPublisher = eventPublisher;
  }

  /**
   * 一張單：試著把它的搬運全部鎖定。
   *
   * <p>需求由呼叫端給定而不是從搬運反推——收單路徑手上已經有它，反推只會多一次單據查詢。
   */
  public AllocationOutcome assign(Demand demand, List<StockMove> moves, Instant now) {
    AllocationResult result =
        allocationService.allocate(demand, allocatableBatchesFor(List.of(demand)), now);
    if (!result.isAllocated()) {
      return result.outcome();
    }

    applyAssignment(result.picks(), moves, now);
    publishAllocationCompleted(demand.orderId(), now);
    return AllocationOutcome.ALLOCATED;
  }

  /**
   * 一批還在等貨的搬運：由挑單政策決定這一輪餵飽哪幾張，再逐張攤到批上。
   *
   * <p>回傳真正配到的那些需求——呼叫端數的是張數（續做的判準）。
   */
  public List<Demand> assignAll(List<StockMove> moves, Instant now) {
    List<Demand> candidates = toDemands(moves);
    if (candidates.isEmpty()) {
      return List.of();
    }

    List<OrderAllocation> allocations = allocationService.allocateBackorders(
        candidates, allocatableBatchesFor(candidates), now);
    if (allocations.isEmpty()) {
      return List.of();
    }

    applyAssignment(
        allocations.stream().flatMap(a -> a.picks().stream()).toList(), moves, now);

    List<Demand> allocated = allocations.stream().map(OrderAllocation::demand).toList();
    allocated.forEach(demand -> publishAllocationCompleted(demand.orderId(), now));
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
  private Map<String, List<StockPool>> allocatableBatchesFor(List<Demand> demands) {
    Demand first = demands.getFirst();
    Set<String> skuCodes = demands.stream()
        .flatMap(demand -> demand.totalsBySku().keySet().stream())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return stockPoolRepository.findAllocatableBatchesBySku(
        first.ownerId(), first.locationId(), skuCodes, businessCalendar.today());
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
  }

  /**
   * 把還在等貨的搬運投影成配貨演算法看得懂的 {@link Demand}。
   *
   * <p><b>這一段存在的理由是讓演算法不必改。</b>佇列的來源從「訂單與預留 join 出來的檢視」換成
   * 了「搬運的狀態」，但 {@code AllocationService} 的整籃判斷、FEFO 取批、head-of-line
   * blocking、批次上限全部依賴 {@code Demand} 的形狀——投影回同一個形狀，那些一個字都不用動。
   *
   * <p>分組用 {@code pickingId}：一張單據就是一張單的全部搬運，而 ship-complete 判斷的單位正是
   * 一張單。訂單 id 只在這裡查一次（發事件要用），不參與分組。
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
      List<DemandLine> lines = pickingMoves.stream()
          .filter(move -> move.getOrderLineId() != null)
          .map(move -> new DemandLine(
              move.getOrderLineId(), move.getSkuCode(), move.getDemandQuantity()))
          .toList();
      if (!lines.isEmpty()) {
        demands.add(new Demand(orderId, first.getOwnerId(), first.getFromLocationId(), lines));
      }
    });
    return List.copyOf(demands);
  }

  /**
   * 庫存與搬運都寫入之後才發，這是它與 ordering 的 {@code OrderAllocated} 的差別。
   *
   * <p>不帶配到哪些批：對外事件不帶，這裡也就沒有東西要帶。
   */
  private void publishAllocationCompleted(UUID orderId, Instant allocatedAt) {
    eventPublisher.publishEvent(new OrderAllocationCompleted(orderId, allocatedAt));
  }
}
