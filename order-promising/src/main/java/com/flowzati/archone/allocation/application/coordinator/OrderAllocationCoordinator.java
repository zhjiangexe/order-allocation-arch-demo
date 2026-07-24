package com.flowzati.archone.allocation.application.coordinator;

import com.flowzati.archone.allocation.domain.event.OrderAllocationCompleted;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.allocation.domain.service.AllocationService;
import com.flowzati.archone.allocation.domain.service.AllocationOutcome;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 應用層服務：負責跨多個聚合根 (Order, StockPool) 的分配流程協調與持久化。
 */
@Component
public class OrderAllocationCoordinator {

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

  public Optional<StockReservation> allocateOrder(Order order, StockPool stockPool, Instant now) {
    AllocationOutcome outcome = allocationService.allocate(order, stockPool, now);
    if (outcome == AllocationOutcome.INSUFFICIENT_ATP) {
      return Optional.empty();
    }

    StockReservation reservation = StockReservation.create(
        IdGenerator.nextId(),
        order.getId(),
        stockPool.getId(),
        order.getQuantity(),
        now
    );
    persistAllocation(order, stockPool, reservation);
    return Optional.of(reservation);
  }

  public void backorderOrder(Order order, Instant now) {
    order.markBackOrdered(now);
    orderRepository.save(order);
    publishDomainEvents(List.of(order));
  }

  public boolean releaseReservation(
      StockReservation reservation,
      StockPool stockPool,
      Instant releasedAt
  ) {
    if (!release(reservation, stockPool, releasedAt)) {
      return false;
    }

    stockPoolRepository.save(stockPool);
    stockReservationRepository.save(reservation);
    return true;
  }

  public List<Order> replenishAndAllocateBackorders(List<Order> backorders, StockPool stockPool, int replenishedQuantity, Instant now) {
    stockPool.replenish(replenishedQuantity);

    List<Order> allocatedOrders =
        allocationService.allocateBackorders(backorders, stockPool, now);
    List<StockReservation> reservations = allocatedOrders.stream()
        .map(order -> StockReservation.create(
            IdGenerator.nextId(),
            order.getId(),
            stockPool.getId(),
            order.getQuantity(),
            now))
        .toList();

    return persistReplenishmentAllocation(allocatedOrders, reservations, stockPool);
  }

  private List<Order> persistReplenishmentAllocation(
      List<Order> allocatedOrders,
      List<StockReservation> reservations,
      StockPool stockPool
  ) {
    stockPoolRepository.save(stockPool);
    allocatedOrders.forEach(orderRepository::save);
    reservations.forEach(stockReservationRepository::save);
    publishDomainEvents(allocatedOrders);
    for (int i = 0; i < allocatedOrders.size(); i++) {
      publishAllocationCompleted(allocatedOrders.get(i), reservations.get(i));
    }
    return allocatedOrders;
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

  private void persistAllocation(
      Order order,
      StockPool stockPool,
      StockReservation reservation
  ) {
    stockPoolRepository.save(stockPool);
    orderRepository.save(order);
    stockReservationRepository.save(reservation);
    publishDomainEvents(List.of(order));
    publishAllocationCompleted(order, reservation);
  }

  private void publishAllocationCompleted(Order order, StockReservation reservation) {
    eventPublisher.publishEvent(new OrderAllocationCompleted(
        order.getId(),
        reservation.getId(),
        order.getSku(),
        order.getQuantity(),
        order.getAllocatedAt()
    ));
  }

  private void publishDomainEvents(List<Order> orders) {
    orders.stream()
        .flatMap(order -> order.releaseDomainEvents().stream())
        .forEach(eventPublisher::publishEvent);
  }
}
