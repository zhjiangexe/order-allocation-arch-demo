package com.flowzati.archone.allocation.application.coordinator;

import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.allocation.domain.model.StockMove;
import com.flowzati.archone.allocation.domain.model.StockMoveLine;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockMoveRepository;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.service.AllocationOutcome;
import com.flowzati.archone.allocation.domain.service.AllocationResult;
import com.flowzati.archone.allocation.domain.service.AllocationService;
import com.flowzati.archone.allocation.domain.service.BatchPick;
import com.flowzati.archone.allocation.domain.service.OrderAllocation;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.allocation.domain.event.OrderBackorderRecorded;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 應用層服務：負責庫存與搬運的配貨流程協調與持久化。
 *
 * <p><b>不碰訂單聚合根。</b>配貨結果以領域事件陳述，由 ordering 收到對外事件後自己推進狀態。
 */
@Component
public class OrderAllocationCoordinator {

  /**
   * 所有寫入一律照這個順序排。
   *
   * <p>兩個交易若以相反順序去鎖同一組列，就會互相等待成死鎖。避免的方式是全系統以**同一個
   * 全序**寫入，而這個排序鍵**現在就寫成跨 SKU 的形式**，即使收單目前限定單行、一次配貨只
   * 碰一個 SKU——R8 放寬多行之後一次配貨會碰多個 SKU 的多個批，屆時才補上 {@code skuCode}
   * 是死鎖裡最難重現的一類問題：它只在特定的交錯下發生，壓測跑不出來，正式環境才偶爾出現。
   *
   * <p>不可依賴集合的自然順序——FEFO 查詢**碰巧**已經是這個順序，但那是查詢的實作細節，
   * 補貨與釋放兩條路徑的集合來源完全不同。
   */
  private static final Comparator<StockPool> WRITE_ORDER =
      Comparator.comparing(StockPool::getSkuCode)
          .thenComparing(StockPool::getExpiryDate)
          .thenComparing(StockPool::getInDate)
          .thenComparing(StockPool::getId);

  private final AllocationService allocationService;
  private final StockPoolRepository stockPoolRepository;
  private final StockMoveRepository stockMoveRepository;
  private final ApplicationEventPublisher eventPublisher;

  public OrderAllocationCoordinator(
      AllocationService allocationService,
      StockPoolRepository stockPoolRepository,
      StockMoveRepository stockMoveRepository,
      ApplicationEventPublisher eventPublisher) {
    this.allocationService = allocationService;
    this.stockPoolRepository = stockPoolRepository;
    this.stockMoveRepository = stockMoveRepository;
    this.eventPublisher = eventPublisher;
  }

  /** 配一筆需求，成功就連同預留一起寫入並發事件。 */
  public AllocationOutcome allocateOrder(
      Demand demand, Map<String, List<StockPool>> batchesBySku, Instant now) {
    AllocationResult result = allocationService.allocate(demand, batchesBySku, now);
    if (!result.isAllocated()) {
      return result.outcome();
    }

    AssignedMoves assignMoves = assign(result.picks(), now);
    persistAllocation(result.picks(), assignMoves, now);
    publishAllocationCompleted(demand.orderId(), now);
    return AllocationOutcome.ALLOCATED;
  }

  /**
   * 記錄這筆需求現在滿足不了。
   *
   * <p><b>不碰訂單。</b>它只發一個事實，由 ordering 收到之後自己把訂單推進到缺貨——一個交易
   * 只修改一個 aggregate，而 {@code orders} 只有 ordering 寫。
   */
  public void backorderOrder(Demand demand, Instant now) {
    eventPublisher.publishEvent(new OrderBackorderRecorded(demand.orderId(), now));
  }

  /**
   * 取消一張單的搬運，把鎖住的量還給庫存。
   *
   * <p>參數是清單而不是單筆：一條行跨三批就有三條明細，只放其中一條會讓其餘批的量永遠鎖在
   * 那裡，而且不會有任何錯誤浮現——庫存看起來只是「莫名其妙少了一些」。
   *
   * <p><b>明細是刪除，不是標記為已釋放。</b>一條被釋放的明細不表達任何事實：貨沒有動，也
   * 沒有被鎖住。留著它等於讓每個讀取端都要記得過濾。釋放的歷史留在搬運的狀態上。
   */
  public boolean releaseMoves(
      List<StockMove> moves,
      List<StockMoveLine> lines,
      Map<UUID, StockPool> batchesById,
      Instant releasedAt
  ) {
    Map<UUID, StockPool> touched = new LinkedHashMap<>();
    for (StockMoveLine line : lines) {
      StockPool batch = batchesById.get(line.stockPoolId());
      if (batch == null) {
        throw new IllegalArgumentException(
            "Stock pool not supplied for move line " + line.id());
      }
      if (line.quantity() > batch.getReservedQuantity()) {
        throw new IllegalArgumentException("Quantity to release cannot exceed reserved quantity");
      }
      batch.release(line.quantity());
      touched.put(batch.getId(), batch);
    }

    List<StockMove> cancelled = moves.stream().filter(StockMove::cancel).toList();
    if (cancelled.isEmpty() && touched.isEmpty()) {
      return false;
    }

    saveInWriteOrder(touched.values());
    stockMoveRepository.deleteLinesOf(cancelled.stream().map(StockMove::getId).toList());
    stockMoveRepository.saveAll(cancelled);
    return true;
  }

  public List<Demand> allocateBackorders(
      List<Demand> backorders,
      Map<String, List<StockPool>> batchesBySku,
      Instant now
  ) {
    List<OrderAllocation> allocations =
        allocationService.allocateBackorders(backorders, batchesBySku, now);
    if (allocations.isEmpty()) {
      return List.of();
    }

    List<Demand> allocated = allocations.stream().map(OrderAllocation::demand).toList();
    List<BatchPick> picks = allocations.stream().flatMap(a -> a.picks().stream()).toList();

    persistAllocation(picks, assign(picks, now), now);
    allocated.forEach(demand -> publishAllocationCompleted(demand.orderId(), now));
    return allocated;
  }

  /**
   * 把配到貨的那些搬運轉為已鎖定，並寫出「從哪一批取用」的明細。
   *
   * <p>粒度是**訂單行 × 批**——{@link BatchPick} 已經是這個粒度，所以明細與它一對一。摺成
   * 一條行一列會丟掉「哪一批是為哪一條行鎖的」，而出貨時要的正是那個資訊。
   *
   * <p>搬運在收單時就已經建立（狀態為「還在等貨」），這裡只是轉狀態——**不是新建**。找不到
   * 對應的搬運代表配貨演算法收到了一筆沒有搬運的需求，而那不該發生：需求本來就是從搬運投影
   * 出來的。
   */
  private AssignedMoves assign(List<BatchPick> picks, Instant now) {
    Map<UUID, StockMove> movesByLine = stockMoveRepository
        .findByOrderLineIds(picks.stream().map(BatchPick::orderLineId).distinct().toList())
        .stream()
        .collect(Collectors.toMap(StockMove::getOrderLineId, move -> move, (a, b) -> a));

    List<StockMoveLine> lines = new ArrayList<>();
    Map<UUID, StockMove> touched = new LinkedHashMap<>();
    for (BatchPick pick : picks) {
      StockMove move = movesByLine.get(pick.orderLineId());
      if (move == null) {
        throw new IllegalStateException(
            "No movement exists for order line " + pick.orderLineId());
      }
      move.assign(now);
      touched.put(move.getId(), move);
      lines.add(new StockMoveLine(
          IdGenerator.nextId(), move.getId(), pick.batch().getId(), pick.quantity()));
    }
    return new AssignedMoves(List.copyOf(touched.values()), List.copyOf(lines));
  }

  private record AssignedMoves(List<StockMove> moves, List<StockMoveLine> lines) {
  }

  /**
   * 只寫 allocation 自己的表。
   *
   * <p>訂單不在這裡寫，也不在別處寫：配貨結果經事件送回 ordering。因此這個交易只碰
   * {@code stock_pools}、{@code stock_moves} 與 {@code stock_move_lines}。
   *
   * <p>庫存先寫、搬運後寫：庫存是會被搶的那一組列，先把它們鎖起來能縮短其他交易等待的窗口。
   */
  private void persistAllocation(List<BatchPick> picks, AssignedMoves assigned, Instant now) {
    Map<UUID, StockPool> touched = new LinkedHashMap<>();
    picks.forEach(pick -> touched.put(pick.batch().getId(), pick.batch()));

    saveInWriteOrder(touched.values());
    stockMoveRepository.saveAll(assigned.moves());
    stockMoveRepository.saveLines(assigned.lines());
  }

  private void saveInWriteOrder(Collection<StockPool> batches) {
    batches.stream().sorted(WRITE_ORDER).forEach(stockPoolRepository::save);
  }

  /**
   * 庫存與預留都寫入之後才發，這是它與 ordering 的 {@code OrderAllocated} 的差別。
   *
   * <p>時間戳由呼叫端傳入而不是從訂單讀——訂單不在這裡，而配貨的時刻本來就是這一次交易的
   * {@code now}。
   *
   * <p>不帶配到哪些批：對外事件不帶，這裡也就沒有東西要帶（理由見
   * {@code OrderAllocatedIntegrationEvent}）。
   */
  private void publishAllocationCompleted(UUID orderId, Instant allocatedAt) {
    eventPublisher.publishEvent(new OrderAllocationCompleted(orderId, allocatedAt));
  }
}
