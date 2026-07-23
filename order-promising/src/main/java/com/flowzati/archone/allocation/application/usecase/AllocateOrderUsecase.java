package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.application.event.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.inbox.Inbox;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import jakarta.transaction.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
public class AllocateOrderUsecase {
  private final Inbox inbox;
  private final OrderRepository orderRepository;
  private final StockPoolRepository stockPoolRepository;
  private final OrderAllocationCoordinator allocationCoordinator;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  public AllocateOrderUsecase(
      Inbox inbox,
      OrderRepository orderRepository,
      StockPoolRepository stockPoolRepository,
      OrderAllocationCoordinator allocationCoordinator,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.inbox = inbox;
    this.orderRepository = orderRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.allocationCoordinator = allocationCoordinator;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  @Transactional
  public void handle(OrderPlacedIntegrationEvent event) {
    if (!inbox.claimIfNew(event.getEventId())) {
      return;
    }

    Order order = orderRepository.findById(event.getOrderId())
        .orElseThrow(() -> new IllegalStateException("Order not found: " + event.getOrderId()));
    if (order.getStatus() != OrderStatus.PENDING) {
      return;
    }

    StockPool stockPool = stockPoolRepository.findBySku(order.getSku())
        .orElseThrow(() -> new IllegalStateException("StockPool not found for SKU: " + order.getSku()));

    Instant now = clock.instant();

    // 嘗試分配並儲存成功者
    Optional<Order> allocatedOrder = allocationCoordinator.allocateOrder(order, stockPool, now);

    if (allocatedOrder.isEmpty()) {
      // 如果分配失敗 (庫存不足)，則將此新訂單標記為欠單
      order.markBackOrdered(now);
      orderRepository.save(order);
      order.releaseDomainEvents().forEach(eventPublisher::publishEvent);
      eventPublisher.publishEvent(new BackorderCreatedIntegrationEvent(
          IdGenerator.nextId(), order.getId(), order.getSku(), order.getQuantity(), now));
    }
  }

}
