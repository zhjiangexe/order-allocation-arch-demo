package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import jakarta.transaction.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class PlaceOrderUsecase {

  private final OrderRepository orderRepository;
  private final ApplicationEventPublisher publisher;

  public PlaceOrderUsecase(OrderRepository orderRepository, ApplicationEventPublisher publisher) {
    this.orderRepository = orderRepository;
    this.publisher = publisher;
  }

  @Transactional
  public UUID placeOrder(String sku, Integer quantity) {
    UUID orderId = IdGenerator.nextId();
    Instant placedAt = Instant.now();
    Order placedOrder = Order.place(orderId, sku, quantity, placedAt);
    orderRepository.save(placedOrder);
    placedOrder.releaseDomainEvents().forEach(publisher::publishEvent);
    publisher.publishEvent(new OrderPlacedIntegrationEvent(
        IdGenerator.nextId(), orderId, sku, quantity, placedAt));
    return orderId;
  }
}
