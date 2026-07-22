package com.flowzati.archone.allocation.application.coordinator;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.service.AllocateService;
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

  private final AllocateService allocateService;
  private final StockPoolRepository stockPoolRepository;
  private final OrderRepository orderRepository;
  private final ApplicationEventPublisher eventPublisher;

  public OrderAllocationCoordinator(
      AllocateService allocateService,
      StockPoolRepository stockPoolRepository,
      OrderRepository orderRepository,
      ApplicationEventPublisher eventPublisher) {
    this.allocateService = allocateService;
    this.stockPoolRepository = stockPoolRepository;
    this.orderRepository = orderRepository;
    this.eventPublisher = eventPublisher;
  }

  /**
   * 處理單筆訂單分配的便利方法。
   */
  public Optional<Order> allocateAndSaveSuccess(Order order, StockPool stockPool, Instant now) {
    List<Order> orders = this.allocateAndSaveSuccesses(List.of(order), stockPool, now);
    return orders.isEmpty() ? Optional.empty() : Optional.of(orders.get(0));
  }

  /**
   * 執行分配行為並持久化成功的結果。
   */
  public List<Order> allocateAndSaveSuccesses(List<Order> ordersToProcess, StockPool stockPool, Instant now) {
    // 調用領域服務執行計算
    List<Order> allocatedOrders = allocateService.drain(ordersToProcess, stockPool, now);

    // 持久化成功的訂單
    allocatedOrders.forEach(order -> {
      orderRepository.save(order);
      order.releaseDomainEvents().forEach(eventPublisher::publishEvent);
    });

    // 持久化庫存池變更
    stockPoolRepository.save(stockPool);

    return allocatedOrders;
  }
}
