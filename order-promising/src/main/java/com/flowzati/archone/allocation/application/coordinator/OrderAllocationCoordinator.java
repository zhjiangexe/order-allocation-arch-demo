package com.flowzati.archone.allocation.application.coordinator;

import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
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

/**
 * 應用層服務：負責 StockPool 與 StockReservation 的分配流程協調與持久化。
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
  private final StockReservationRepository stockReservationRepository;
  private final ApplicationEventPublisher eventPublisher;

  public OrderAllocationCoordinator(
      AllocationService allocationService,
      StockPoolRepository stockPoolRepository,
      StockReservationRepository stockReservationRepository,
      ApplicationEventPublisher eventPublisher) {
    this.allocationService = allocationService;
    this.stockPoolRepository = stockPoolRepository;
    this.stockReservationRepository = stockReservationRepository;
    this.eventPublisher = eventPublisher;
  }

  /** 配一筆需求，成功就連同預留一起寫入並發事件。 */
  public AllocationOutcome allocateOrder(
      Demand demand, List<StockPool> allocatableBatches, Instant now) {
    AllocationResult result = allocationService.allocate(demand, allocatableBatches, now);
    if (!result.isAllocated()) {
      return result.outcome();
    }

    OrderAllocation allocation = new OrderAllocation(demand, result.picks());
    List<StockReservation> reservations = reservationsFor(allocation, now);
    persistAllocation(result.picks(), reservations);
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
   * 釋放一張單的所有有效預留。
   *
   * <p>參數是清單而不是單筆：一條行跨三批就有三筆預留，只釋放其中一筆會讓其餘批的量永遠鎖
   * 在那裡，而且不會有任何錯誤浮現——庫存看起來只是「莫名其妙少了一些」。
   */
  public boolean releaseReservations(
      List<StockReservation> reservations,
      Map<UUID, StockPool> batchesById,
      Instant releasedAt
  ) {
    List<StockReservation> released = new ArrayList<>();
    Map<UUID, StockPool> touched = new LinkedHashMap<>();
    for (StockReservation reservation : reservations) {
      StockPool batch = batchesById.get(reservation.getStockPoolId());
      if (batch == null) {
        throw new IllegalArgumentException(
            "Stock pool not supplied for reservation " + reservation.getId());
      }
      if (release(reservation, batch, releasedAt)) {
        released.add(reservation);
        touched.put(batch.getId(), batch);
      }
    }
    if (released.isEmpty()) {
      return false;
    }

    saveInWriteOrder(touched.values());
    released.stream()
        .sorted(Comparator.comparing(StockReservation::getId))
        .forEach(stockReservationRepository::save);
    return true;
  }

  public List<Demand> allocateBackorders(
      List<Demand> backorders,
      List<StockPool> allocatableBatches,
      Instant now
  ) {
    List<OrderAllocation> allocations =
        allocationService.allocateBackorders(backorders, allocatableBatches, now);
    if (allocations.isEmpty()) {
      return List.of();
    }

    List<Demand> allocated = allocations.stream().map(OrderAllocation::demand).toList();
    List<BatchPick> picks = allocations.stream().flatMap(a -> a.picks().stream()).toList();

    // 逐 allocation 建預留，因為每一筆預留要記下它屬於哪一張單——而那個對應只有在還沒把
    // picks 攤平之前看得到。攤平後的 picks 只用來決定要寫哪些批。
    List<StockReservation> reservations = allocations.stream()
        .flatMap(allocation -> reservationsFor(allocation, now).stream())
        .toList();

    persistAllocation(picks, reservations);
    allocated.forEach(demand -> publishAllocationCompleted(demand.orderId(), now));
    return allocated;
  }

  /**
   * 一個 pick 一筆預留——粒度是訂單行 × 批次。
   *
   * <p>{@link BatchPick} 已經是這個粒度，所以這裡是一對一的映射；把它摺成一張單一筆會在這裡
   * 就丟掉「哪一批是為哪一條行鎖的」，而出貨時要的正是那個資訊。
   */
  private List<StockReservation> reservationsFor(OrderAllocation allocation, Instant now) {
    return allocation.picks().stream()
        .map(pick -> StockReservation.create(
            IdGenerator.nextId(),
            allocation.demand().orderId(),
            pick.orderLineId(),
            pick.batch().getId(),
            pick.quantity(),
            now))
        .toList();
  }

  /**
   * 只寫 allocation 自己的兩張表。
   *
   * <p>訂單不在這裡寫，也不在別處寫：配貨結果經事件送回 ordering。因此這個交易只碰
   * {@code stock_pools} 與 {@code stock_reservations}。
   */
  private void persistAllocation(List<BatchPick> picks, List<StockReservation> reservations) {
    Map<UUID, StockPool> touched = new LinkedHashMap<>();
    picks.forEach(pick -> touched.put(pick.batch().getId(), pick.batch()));

    saveInWriteOrder(touched.values());
    reservations.stream()
        .sorted(Comparator.comparing(StockReservation::getId))
        .forEach(stockReservationRepository::save);
  }

  private void saveInWriteOrder(Collection<StockPool> batches) {
    batches.stream().sorted(WRITE_ORDER).forEach(stockPoolRepository::save);
  }

  private boolean release(
      StockReservation reservation,
      StockPool stockPool,
      Instant releasedAt
  ) {
    if (reservation == null) {
      throw new IllegalArgumentException("Reservation is required");
    }
    if (stockPool == null) {
      throw new IllegalArgumentException("Stock pool is required");
    }
    if (!reservation.getStockPoolId().equals(stockPool.getId())) {
      throw new IllegalArgumentException("Reservation does not belong to stock pool");
    }
    if (reservation.getStatus() == ReservationStatus.RELEASED) {
      return false;
    }
    if (releasedAt == null) {
      throw new IllegalArgumentException("Released time is required");
    }
    if (releasedAt.isBefore(reservation.getReservedAt())) {
      throw new IllegalArgumentException("Released time cannot be before reserved time");
    }
    if (reservation.getQuantity() > stockPool.getReservedQuantity()) {
      throw new IllegalArgumentException("Quantity to release cannot exceed reserved quantity");
    }

    reservation.release(releasedAt);
    stockPool.release(reservation.getQuantity());
    return true;
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
