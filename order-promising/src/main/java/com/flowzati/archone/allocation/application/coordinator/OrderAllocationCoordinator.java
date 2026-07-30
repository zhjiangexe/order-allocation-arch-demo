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
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
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
 * 應用層服務：負責跨多個聚合根 (Order, StockPool, StockReservation) 的分配流程協調與持久化。
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
  private final OrderRepository orderRepository;
  private final StockReservationRepository stockReservationRepository;
  private final ApplicationEventPublisher eventPublisher;

  public OrderAllocationCoordinator(
      AllocationService allocationService,
      StockPoolRepository stockPoolRepository,
      OrderRepository orderRepository,
      StockReservationRepository stockReservationRepository,
      ApplicationEventPublisher eventPublisher) {
    this.allocationService = allocationService;
    this.stockPoolRepository = stockPoolRepository;
    this.orderRepository = orderRepository;
    this.stockReservationRepository = stockReservationRepository;
    this.eventPublisher = eventPublisher;
  }

  /** 配一張單，成功就連同預留一起寫入並發事件。 */
  public AllocationOutcome allocateOrder(
      Order order, List<StockPool> allocatableBatches, Instant now) {
    AllocationResult result = allocationService.allocate(order, allocatableBatches, now);
    if (!result.isAllocated()) {
      return result.outcome();
    }

    List<StockReservation> reservations = reservationsFor(result.picks(), now);
    persistAllocation(List.of(order), result.picks(), reservations);
    publishAllocationCompleted(order);
    return AllocationOutcome.ALLOCATED;
  }

  public void backorderOrder(Order order, Instant now) {
    order.markBackOrdered(now);
    orderRepository.save(order);
    publishDomainEvents(List.of(order));
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

  public List<Order> allocateBackorders(
      List<Order> backorders,
      List<StockPool> allocatableBatches,
      Instant now
  ) {
    List<OrderAllocation> allocations =
        allocationService.allocateBackorders(backorders, allocatableBatches, now);
    if (allocations.isEmpty()) {
      return List.of();
    }

    List<Order> allocatedOrders = allocations.stream().map(OrderAllocation::order).toList();
    List<BatchPick> picks = allocations.stream().flatMap(a -> a.picks().stream()).toList();

    // picks 與 reservations 之間**沒有位置關係**，兩者各自獨立使用：前者算出要寫哪些批，
    // 後者是要寫的預留。曾經有一段以 .get(i) 對齊兩者的程式，那是為了組事件的批次清單；
    // 清單移除之後那個對齊需求就消失了，連帶一個以 record 當 HashMap key 的脆弱處也不見了。
    List<StockReservation> reservations = reservationsFor(picks, now);

    persistAllocation(allocatedOrders, picks, reservations);
    allocatedOrders.forEach(this::publishAllocationCompleted);
    return allocatedOrders;
  }

  /**
   * 一個 pick 一筆預留——粒度是訂單行 × 批次。
   *
   * <p>{@link BatchPick} 已經是這個粒度，所以這裡是一對一的映射；把它摺成一張單一筆會在這裡
   * 就丟掉「哪一批是為哪一條行鎖的」，而出貨時要的正是那個資訊。
   */
  private List<StockReservation> reservationsFor(List<BatchPick> picks, Instant now) {
    return picks.stream()
        .map(pick -> StockReservation.create(
            IdGenerator.nextId(),
            pick.orderLineId(),
            pick.batch().getId(),
            pick.quantity(),
            now))
        .toList();
  }

  private void persistAllocation(
      List<Order> orders,
      List<BatchPick> picks,
      List<StockReservation> reservations
  ) {
    Map<UUID, StockPool> touched = new LinkedHashMap<>();
    picks.forEach(pick -> touched.put(pick.batch().getId(), pick.batch()));

    saveInWriteOrder(touched.values());
    orders.stream()
        .sorted(Comparator.comparing(Order::getId))
        .forEach(orderRepository::save);
    reservations.stream()
        .sorted(Comparator.comparing(StockReservation::getId))
        .forEach(stockReservationRepository::save);
    publishDomainEvents(orders);
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
   * 三個聚合根都寫入之後才發，這是它與 {@code OrderAllocated} 的差別。
   *
   * <p>不帶配到哪些批：對外事件不帶，這裡也就沒有東西要帶（理由見
   * {@code OrderAllocatedIntegrationEvent}）。
   */
  private void publishAllocationCompleted(Order order) {
    eventPublisher.publishEvent(
        new OrderAllocationCompleted(order.getId(), order.getAllocatedAt()));
  }

  private void publishDomainEvents(List<Order> orders) {
    orders.stream()
        .flatMap(order -> order.releaseDomainEvents().stream())
        .forEach(eventPublisher::publishEvent);
  }
}
