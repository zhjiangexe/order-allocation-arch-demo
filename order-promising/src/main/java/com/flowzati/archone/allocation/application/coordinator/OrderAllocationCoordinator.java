package com.flowzati.archone.allocation.application.coordinator;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.service.AllocationService;
import com.flowzati.archone.allocation.domain.service.AllocationOutcome;
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
  private final ApplicationEventPublisher eventPublisher;

  public OrderAllocationCoordinator(
      AllocationService allocationService,
      StockPoolRepository stockPoolRepository,
      OrderRepository orderRepository,
      ApplicationEventPublisher eventPublisher) {
    this.allocationService = allocationService;
    this.stockPoolRepository = stockPoolRepository;
    this.orderRepository = orderRepository;
    this.eventPublisher = eventPublisher;
  }

  public Optional<Order> allocateOrder(Order order, StockPool stockPool, Instant now) {
    AllocationOutcome outcome = allocationService.allocate(order, stockPool, now);
    if (outcome == AllocationOutcome.INSUFFICIENT_ATP) {
      return Optional.empty();
    }

    persistAllocation(List.of(order), stockPool);
    return Optional.of(order);
  }

  public List<Order> replenishAndAllocateBackorders(List<Order> backorders, StockPool stockPool, int replenishedQuantity, Instant now) {
    stockPool.replenish(replenishedQuantity);

    List<Order> allocatedOrders =
        allocationService.allocateBackorders(backorders, stockPool, now);

    return persistAllocation(allocatedOrders, stockPool);
  }

  private List<Order> persistAllocation(List<Order> allocatedOrders, StockPool stockPool) {
    stockPoolRepository.save(stockPool);
    allocatedOrders.forEach(orderRepository::save);
    allocatedOrders.stream()
        .flatMap(order -> order.releaseDomainEvents().stream())
        .forEach(eventPublisher::publishEvent);
    return allocatedOrders;
  }
}
