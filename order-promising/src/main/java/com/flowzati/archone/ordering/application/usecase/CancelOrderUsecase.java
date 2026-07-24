package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class CancelOrderUsecase {

  private final OrderRepository orderRepository;
  private final ApplicationEventPublisher eventPublisher;

  public CancelOrderUsecase(
      OrderRepository orderRepository,
      ApplicationEventPublisher eventPublisher) {
    this.orderRepository = orderRepository;
    this.eventPublisher = eventPublisher;
  }

  @Transactional
  public void cancel(UUID orderId, Instant cancelledAt) {
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
    if (!order.cancel(cancelledAt)) {
      return;
    }

    orderRepository.save(order);
    order.releaseDomainEvents().forEach(eventPublisher::publishEvent);
  }
}
