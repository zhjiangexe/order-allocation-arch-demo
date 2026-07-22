package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import jakarta.transaction.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class PlaceOrderUsecase {

  private final OrderRepository orderRepository;
  private final ApplicationEventPublisher publisher;

  public PlaceOrderUsecase(OrderRepository orderRepository, ApplicationEventPublisher publisher) {
    this.orderRepository = orderRepository;
    this.publisher = publisher;
  }

  @Transactional
  public Long placeOrder(String sku, Integer quantity) {
    Order placedOrder = Order.place(IdGenerator.nextId(), sku, quantity);
    orderRepository.save(placedOrder);
    publisher.publishEvent(placedOrder.releaseDomainEvents());
    return null; // 暫時返回 null 以符合語法，實際業務邏輯應根據需求修改
  }
}
